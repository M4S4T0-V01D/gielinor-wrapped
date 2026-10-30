package com.gielinorwrapped;

import com.google.gson.JsonParseException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Builds recaps on a background thread: the share card first (quick), then the video (minutes). Finished
 * recaps live in {@code accounts/<hash>/recaps/<period id>/}. Also runs the daily/weekly/... schedule.
 */
@Slf4j
final class Recaps
{
	static final String CARD = "card.png";
	private static final String META = "recap.json";
	private static final String STATE = "schedule.json";

	enum Status
	{
		QUEUED("Waiting"),
		CARD("Drawing card"),
		VIDEO("Rendering video"),
		DONE("Ready"),
		FAILED("Failed"),
		CANCELLED("Cancelled");

		final String label;

		Status(String label)
		{
			this.label = label;
		}

		boolean finished()
		{
			return this == DONE || this == FAILED || this == CANCELLED;
		}
	}

	/** A recap being made. Fields are written by the render thread and read by the panel. */
	static final class Job
	{
		final long account;
		final Period period;
		final boolean video;
		volatile Status status = Status.QUEUED;
		volatile double progress;
		volatile String message;
		volatile boolean cancelled;
		/** The friend file to put side by side with this recap, or null. */
		final String friend;

		Job(long account, Period period, boolean video, String friend)
		{
			this.account = account;
			this.period = period;
			this.video = video;
			this.friend = friend;
		}

		void cancel()
		{
			cancelled = true;
		}
	}

	/** What is stored next to a finished recap. */
	static final class Meta
	{
		String kind;
		String from;
		String to;
		String player;
		long created;
		String video;
		/** The friend file it was made with, for rendering its video later; null for none. */
		String friend;

		Period period()
		{
			return new Period(Period.Kind.valueOf(kind), LocalDate.parse(from), LocalDate.parse(to));
		}
	}

	/** A finished recap on disk. */
	static final class Saved
	{
		final Meta meta;
		final Filepath dir;

		Saved(Meta meta, Filepath dir)
		{
			this.meta = meta;
			this.dir = dir;
		}

		Filepath card()
		{
			return dir.join(CARD);
		}

		Filepath video()
		{
			return meta.video == null ? null : dir.join(meta.video);
		}
	}

	private static final class State
	{
		Set<String> done = new HashSet<>();
	}

	private final StatsStore store;
	private final ClipLibrary library;
	private final GielinorWrappedConfig config;
	private final ExecutorService render;
	private final List<Job> jobs = new CopyOnWriteArrayList<>();
	/** Set by {@link #close()}; queued tasks then skip their work. */
	private volatile boolean closed;
	private final Consumer<Job> listener;
	private final Art art;
	private final Shots shots;
	private final DeathLog deathLog;

	/** Game sprites for cards and videos. Called on the render thread; may block briefly while a sprite loads. */
	interface Art
	{
		BufferedImage item(int id);

		BufferedImage skill(String name);

		/** The chat badge of an account type, or null. */
		default BufferedImage badge(AccountType type)
		{
			return null;
		}

		/** A music track as MIDI file bytes: a name from the music player, "Random" or "Current area"; null if unavailable. */
		default byte[] song(String track)
		{
			return null;
		}
	}

	/**
	 * @param art game sprites, or null for none
	 * @param listener told about every job change, on the render thread
	 */
	Recaps(StatsStore store, ClipLibrary library, Shots shots, DeathLog deathLog, Art art, GielinorWrappedConfig config, Consumer<Job> listener)
	{
		this.store = store;
		this.library = library;
		this.shots = shots;
		this.deathLog = deathLog;
		this.art = art;
		this.config = config;
		this.listener = listener;
		render = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "gielinor-wrapped-render");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	/** Current and recent jobs, oldest first. */
	List<Job> jobs()
	{
		return new ArrayList<>(jobs);
	}

	/** Queues a recap; an identical job that is still waiting or running is reused. */
	Job request(long account, String player, Period period, boolean video)
	{
		return request(account, player, period, video, null);
	}

	/** Queues a recap with a friend's file for the "you vs them" scene; {@code friend} may be null. */
	Job request(long account, String player, Period period, boolean video, String friend)
	{
		for (Job j : jobs)
		{
			if (j.account == account && j.period.equals(period) && (j.video || !video) && !j.status.finished()
				&& java.util.Objects.equals(j.friend, friend))
			{
				return j;
			}
		}
		jobs.removeIf(j -> j.status.finished() && j.period.equals(period) && j.account == account);
		Job job = new Job(account, period, video, friend);
		jobs.add(job);
		listener.accept(job);
		render.execute(() -> run(job, player));
		return job;
	}

	/**
	 * Queues the scheduled recaps that are due: the last full day/week/month/year, once each, if anything
	 * was tracked in it. I/O thread.
	 *
	 * @param ready called with a short message when a scheduled recap finishes
	 */
	void checkSchedule(long account, String player, LocalDate today, Consumer<String> ready)
	{
		State state = readState(account);
		DayOfWeek weekStart = config.weekStart();
		List<Period> due = new ArrayList<>();
		if (config.autoDaily())
		{
			due.add(Period.previous(Period.Kind.DAY, today, weekStart));
		}
		if (config.autoWeekly())
		{
			due.add(Period.previous(Period.Kind.WEEK, today, weekStart));
		}
		if (config.autoMonthly())
		{
			due.add(Period.previous(Period.Kind.MONTH, today, weekStart));
		}
		if (config.autoYearly())
		{
			due.add(Period.previous(Period.Kind.YEAR, today, weekStart));
		}
		boolean changed = false;
		for (Period p : due)
		{
			if (state.done.contains(p.id()))
			{
				continue;
			}
			state.done.add(p.id());
			changed = true;
			if (!hasData(account, p))
			{
				continue;
			}
			Job job = request(account, player, p, config.autoVideo());
			if (config.notifyReady())
			{
				render.execute(() ->
				{
					if (!closed && job.status == Status.DONE)
					{
						ready.accept("Your " + p.label().toLowerCase() + " Gielinor Wrapped is ready: " + p.describe());
					}
				});
			}
		}
		if (changed)
		{
			writeState(account, state);
		}
	}

	private boolean hasData(long account, Period p)
	{
		for (LocalDate d : store.listDays(account))
		{
			if (!d.isBefore(p.from) && !d.isAfter(p.to))
			{
				return true;
			}
		}
		return false;
	}

	/** Finished recaps for the account, newest period first. I/O thread. */
	List<Saved> saved(long account)
	{
		Filepath dir = recapsDir(account);
		List<Saved> out = new ArrayList<>();
		if (!dir.exists())
		{
			return out;
		}
		List<Filepath> subdirs;
		try (Stream<Filepath> s = dir.walk(1))
		{
			subdirs = s.filter(f -> !f.equals(dir) && f.isDirectory()).collect(Collectors.toList());
		}
		catch (IOException e)
		{
			log.warn("Unable to list {}", dir, e);
			return out;
		}
		for (Filepath d : subdirs)
		{
			Filepath meta = d.join(META);
			if (!meta.exists() || !d.join(CARD).exists())
			{
				continue;
			}
			try (Reader r = meta.openReader())
			{
				Meta m = store.gson().fromJson(r, Meta.class);
				if (m != null && m.kind != null)
				{
					m.period();
					out.add(new Saved(m, d));
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("Skipping recap {}", d, e);
			}
		}
		out.sort((a, b) -> b.meta.to.compareTo(a.meta.to) != 0 ? b.meta.to.compareTo(a.meta.to) : a.meta.kind.compareTo(b.meta.kind));
		return out;
	}

	/** I/O thread. */
	void delete(Saved saved) throws IOException
	{
		saved.dir.deleteRecursively();
	}

	private void run(Job job, String player)
	{
		if (closed)
		{
			return;
		}
		if (job.cancelled)
		{
			update(job, Status.CANCELLED, null);
			return;
		}
		Filepath dir = recapsDir(job.account).join(job.period.id());
		Filepath partial = null;
		try
		{
			update(job, Status.CARD, null);
			List<DayRecord> days = store.readRange(job.account, job.period.from, job.period.to);
			if (days.isEmpty())
			{
				update(job, Status.FAILED, "Nothing was tracked in that period yet. Try This week or Today.");
				return;
			}
			Period before = job.period.before();
			Recap recap = Recap.build(job.period, player, days, store.readRange(job.account, before.from, before.to));
			FriendFile friend = job.friend == null ? null : readFriend(job.account, job.friend);
			Composer.Media media = media(job.account, job.period, friend);

			dir.createDirectories();
			BufferedImage card = ShareCard.render(recap, config, media);
			Filepath cardTmp = dir.join(CARD + ".tmp");
			try (OutputStream out = cardTmp.openOutputStream())
			{
				ImageIO.write(card, "png", out);
			}
			cardTmp.moveTo(dir.join(CARD), StandardCopyOption.REPLACE_EXISTING);

			Meta meta = new Meta();
			meta.kind = job.period.kind.name();
			meta.from = job.period.from.toString();
			meta.to = job.period.to.toString();
			meta.player = player;
			meta.friend = job.friend;
			meta.created = System.currentTimeMillis();
			Saved old = readMeta(dir);
			meta.video = old != null && old.video() != null && old.video().exists() ? old.meta.video : null;
			writeMeta(dir, meta);
			writeUpload(dir, UploadText.text(recap, config.hideName(), null, 1));

			if (job.video)
			{
				update(job, Status.VIDEO, null);
				Composer composer = new Composer(recap, media, config);
				String name = composer.format == GielinorWrappedConfig.VideoFormat.GIF ? "recap.gif" : "recap.mp4";
				Filepath result = dir.join(name + ".part");
				Filepath scratch = dir.join("frames.part");
				partial = result;
				try
				{
					composer.render(new Composer.Output()
					{
						@Override
						public OutputStream result() throws IOException
						{
							return result.openOutputStream();
						}

						@Override
						public OutputStream scratch() throws IOException
						{
							return scratch.openOutputStream();
						}

						@Override
						public InputStream readScratch() throws IOException
						{
							return scratch.openInputStream();
						}
					}, p ->
					{
						job.progress = p;
						listener.accept(job);
					}, () -> job.cancelled);
				}
				finally
				{
					scratch.deleteIfExists();
				}
				if (meta.video != null && !meta.video.equals(name))
				{
					dir.join(meta.video).deleteIfExists();
				}
				result.moveTo(dir.join(name), StandardCopyOption.REPLACE_EXISTING);
				partial = null;
				meta.video = name;
				writeMeta(dir, meta);
				writeUpload(dir, UploadText.text(recap, config.hideName(), composer.scenes, composer.fps));
			}
			job.progress = 1;
			update(job, Status.DONE, null);
		}
		catch (InterruptedIOException e)
		{
			update(job, Status.CANCELLED, null);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Unable to make the {} recap", job.period.id(), e);
			update(job, Status.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
		finally
		{
			if (partial != null)
			{
				try
				{
					partial.deleteIfExists();
				}
				catch (IOException e)
				{
					log.debug("Unable to delete {}", partial, e);
				}
			}
		}
	}

	private void writeUpload(Filepath dir, String text) throws IOException
	{
		Filepath tmp = dir.join(UploadText.FILE + ".tmp");
		tmp.write(text);
		tmp.moveTo(dir.join(UploadText.FILE), StandardCopyOption.REPLACE_EXISTING);
	}

	/** The upload title, description and chapters saved with a recap, or null. I/O thread. */
	String upload(Saved saved)
	{
		Filepath f = saved.dir.join(UploadText.FILE);
		try
		{
			return f.exists() ? readText(f) : null;
		}
		catch (IOException e)
		{
			log.debug("Unable to read {}", f, e);
			return null;
		}
	}

	static String readText(Filepath f) throws IOException
	{
		try (InputStream in = f.openInputStream())
		{
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private Filepath friendsDir(long account)
	{
		return store.accountDir(account).join("friends");
	}

	/** A friend file for the saved recap, as JSON. I/O thread. */
	String exportFriend(long account, String player, Period period)
	{
		List<DayRecord> days = store.readRange(account, period.from, period.to);
		if (days.isEmpty())
		{
			return null;
		}
		Period before = period.before();
		Recap recap = Recap.build(period, player, days, store.readRange(account, before.from, before.to));
		return FriendFile.of(recap, config.hideName()).toJson(store.gson());
	}

	/**
	 * Checks a friend file and keeps a copy for the friend dropdown. I/O thread.
	 *
	 * @return the friend, or null if the text isn't a friend file
	 */
	FriendFile importFriend(long account, String json) throws IOException
	{
		FriendFile f = FriendFile.parse(store.gson(), json);
		if (f == null)
		{
			return null;
		}
		Filepath dir = friendsDir(account);
		dir.createDirectories();
		String name = friendName(f);
		Filepath tmp = dir.join(name + ".tmp");
		tmp.write(f.toJson(store.gson()));
		tmp.moveTo(dir.join(name), StandardCopyOption.REPLACE_EXISTING);
		return f;
	}

	/** The file name an imported friend file is kept under. */
	static String friendName(FriendFile f)
	{
		return ClipLibrary.safeName(f.player) + "-" + ClipLibrary.safeName(f.from == null ? "recap" : f.from) + "." + FriendFile.EXTENSION;
	}

	/** Imported friend files by file name, newest name order. I/O thread. */
	List<String> friends(long account)
	{
		Filepath dir = friendsDir(account);
		List<String> out = new ArrayList<>();
		if (!dir.exists())
		{
			return out;
		}
		try (Stream<Filepath> s = dir.walk(1))
		{
			s.filter(f -> !f.equals(dir) && f.getFileName().endsWith("." + FriendFile.EXTENSION))
				.forEach(f -> out.add(f.getFileName()));
		}
		catch (IOException e)
		{
			log.debug("Unable to list {}", dir, e);
		}
		out.sort(null);
		return out;
	}

	/** Reads an imported friend file, or null. I/O or render thread. */
	FriendFile readFriend(long account, String name)
	{
		if (!safeFriendName(name))
		{
			return null;
		}
		Filepath f = friendsDir(account).join(name);
		try
		{
			return f.exists() && f.size() <= FriendFile.MAX_BYTES ? FriendFile.parse(store.gson(), readText(f)) : null;
		}
		catch (IOException e)
		{
			log.debug("Unable to read {}", f, e);
			return null;
		}
	}

	private static boolean safeFriendName(String name)
	{
		return name != null && !name.contains("/") && !name.contains("\\") && !name.startsWith(".") && name.endsWith("." + FriendFile.EXTENSION);
	}

	/** Removes an imported friend file. I/O thread. */
	void deleteFriend(long account, String name) throws IOException
	{
		if (safeFriendName(name))
		{
			friendsDir(account).join(name).deleteIfExists();
		}
	}

	private Composer.Media media(long account, Period period, FriendFile friend)
	{
		boolean icons = art != null && config.icons();
		Map<Integer, Optional<BufferedImage>> items = new HashMap<>();
		Map<String, Optional<BufferedImage>> skills = new HashMap<>();
		boolean badges = art != null && config.accountBadge();
		Map<AccountType, Optional<BufferedImage>> badgeImages = new HashMap<>();
		Shots.Pick pick;
		try
		{
			// Shots from the start of the period up to now, so a cover set up after the period still counts.
			long from = period.from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			pick = Shots.pick(shots.list(account), from, System.currentTimeMillis());
		}
		catch (IOException e)
		{
			log.debug("Unable to list shots", e);
			pick = new Shots.Pick();
		}
		Shots.Pick picked = pick;
		long periodStart = period.from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		long periodEnd = period.to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
		List<DeathLog.Entry> deaths = deathLog == null ? new java.util.ArrayList<>() : deathLog.range(account, periodStart, periodEnd);
		return new Composer.Media()
		{
			@Override
			public FriendFile friend()
			{
				return friend;
			}

			@Override
			public List<DeathLog.Entry> deaths()
			{
				return new java.util.ArrayList<>(deaths);
			}

			@Override
			public BufferedImage deathImage(String name)
			{
				byte[] data = deathLog == null ? null : deathLog.read(account, name);
				if (data == null)
				{
					return null;
				}
				try
				{
					return ImageIO.read(new java.io.ByteArrayInputStream(data));
				}
				catch (IOException e)
				{
					log.debug("Unable to read death picture {}", name, e);
					return null;
				}
			}

			@Override
			public List<ClipFile.Frame> deathReplay(long time)
			{
				DeathLog.Entry best = null;
				for (DeathLog.Entry e : deaths)
				{
					if (e.replay != null && e.replayTimes != null && Math.abs(e.time - time) <= DeathLog.MERGE_MS
						&& (best == null || Math.abs(e.time - time) < Math.abs(best.time - time)))
					{
						best = e;
					}
				}
				if (best == null)
				{
					return new java.util.ArrayList<>();
				}
				byte[] gif = deathLog.read(account, best.replay);
				return ReplayGif.frames(gif, best.recapTime, best.replayTimes, config.clipWidth());
			}

			@Override
			public BufferedImage item(int id)
			{
				return icons && id > 0 ? items.computeIfAbsent(id, k -> Optional.ofNullable(art.item(k))).orElse(null) : null;
			}

			@Override
			public BufferedImage skill(String name)
			{
				return icons && name != null ? skills.computeIfAbsent(name, k -> Optional.ofNullable(art.skill(k))).orElse(null) : null;
			}

			@Override
			public BufferedImage badge(AccountType type)
			{
				return badges && type != null && type.hasBadge() ? badgeImages.computeIfAbsent(type, k -> Optional.ofNullable(art.badge(k))).orElse(null) : null;
			}

			@Override
			public Shots.Pick shots()
			{
				return picked;
			}

			@Override
			public List<ClipFile.Frame> clip(String name)
			{
				return library.readClip(account, name);
			}

			@Override
			public List<ClipFile.Frame> timelapse(int max)
			{
				return library.readTimelapse(account, period.from, period.to, max);
			}

			@Override
			public Emote emote()
			{
				return config.emoteScene() ? Emote.of(library.readEmote(account)) : null;
			}

			@Override
			public List<ClipLibrary.Recording> recordings()
			{
				// Recorded during the period, or since: like shots, footage taken to make this recap counts.
				long from = period.from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
				List<ClipLibrary.Recording> out = new java.util.ArrayList<>();
				for (ClipLibrary.Recording r : library.listRecordings(account))
				{
					if (r.end() >= from)
					{
						out.add(r);
					}
				}
				return out;
			}

			@Override
			public List<ClipFile.Frame> recording(String name, long from, long to)
			{
				return library.readRecording(account, name, from, to);
			}

			@Override
			public List<Sticker> stickers()
			{
				return library.stickers(account, true, true);
			}

			@Override
			public StickerBoard stickerBoard()
			{
				return library.readBoard(account);
			}

			@Override
			public byte[] song()
			{
				return art != null && config.musicEnabled() ? art.song(config.musicTrack()) : null;
			}
		};
	}

	private void update(Job job, Status status, String message)
	{
		job.status = status;
		job.message = message;
		listener.accept(job);
	}

	private Filepath recapsDir(long account)
	{
		return store.accountDir(account).join("recaps");
	}

	private Saved readMeta(Filepath dir)
	{
		Filepath f = dir.join(META);
		if (!f.exists())
		{
			return null;
		}
		try (Reader r = f.openReader())
		{
			Meta m = store.gson().fromJson(r, Meta.class);
			return m == null ? null : new Saved(m, dir);
		}
		catch (IOException | JsonParseException e)
		{
			return null;
		}
	}

	private void writeMeta(Filepath dir, Meta meta) throws IOException
	{
		Filepath tmp = dir.join(META + ".tmp");
		tmp.write(store.gson().toJson(meta));
		tmp.moveTo(dir.join(META), StandardCopyOption.REPLACE_EXISTING);
	}

	private State readState(long account)
	{
		Filepath f = recapsDir(account).join(STATE);
		if (f.exists())
		{
			try (Reader r = f.openReader())
			{
				State s = store.gson().fromJson(r, State.class);
				if (s != null && s.done != null)
				{
					return s;
				}
			}
			catch (IOException | JsonParseException e)
			{
				log.warn("Unable to read {}", f, e);
			}
		}
		return new State();
	}

	private void writeState(long account, State state)
	{
		Filepath dir = recapsDir(account);
		try
		{
			dir.createDirectories();
			Filepath tmp = dir.join(STATE + ".tmp");
			tmp.write(store.gson().toJson(state));
			tmp.moveTo(dir.join(STATE), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			log.warn("Unable to save the recap schedule", e);
		}
	}

	/** Stops rendering; a video in progress is abandoned and its partial file removed. */
	void close() throws InterruptedException
	{
		closed = true;
		for (Job j : jobs)
		{
			j.cancel();
		}
		render.shutdown();
		if (!render.awaitTermination(10, TimeUnit.SECONDS))
		{
			log.warn("Gielinor Wrapped rendering did not stop in time");
		}
	}
}
