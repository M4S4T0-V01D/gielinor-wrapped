package com.gielinorwrapped;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.DrawManager;

/**
 * Keeps a rolling buffer of scaled-down game frames and saves a clip around each highlight moment, plus a
 * still every few seconds for the timelapse. Frames are copied on the client thread into one of two
 * reusable buffers, then scaled, blurred and compressed on the frame thread; if it falls behind, frames
 * are skipped. Files are written on the media thread.
 */
@Slf4j
class Capture
{
	private static final int BUFFERS = 2;
	private static final float CLIP_QUALITY = 0.92f;
	/** Interface parts that cover the game in the resizable layouts; fixed mode keeps them outside the view. */
	private static final int[] INTERFACE_PANELS = {
		InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER, InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER,
		InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, InterfaceID.ToplevelPreEoc.MAP_CONTAINER, InterfaceID.ToplevelPreEoc.CHAT_CONTAINER,
	};
	/** A clip that keeps being extended by new moments is cut off at this length. */
	private static final long MAX_CLIP_MS = 30_000;
	/** Size of the blocks chat is pixelated into, in clip pixels. */
	private static final int BLUR_BLOCK = 10;
	/** Largest share of the frame one hidden chat area may cover. */
	static final double MAX_HIDDEN_SHARE = 0.25;

	private final Client client;
	private final DrawManager drawManager;
	private final GielinorWrappedConfig config;
	private final ClipLibrary library;
	private final Runnable frameListener = this::onFrame;
	private final Deque<ClipFile.Frame> frames = new ArrayDeque<>();
	private final Queue<BufferedImage> spare = new ConcurrentLinkedQueue<>();
	private final AtomicInteger buffers = new AtomicInteger();
	/** The frame scaled to the clip width, reused for each frame. Frame thread only. */
	private BufferedImage scaled;

	private ScheduledExecutorService worker;
	private ExecutorService writer;
	private long lastRequest;
	private boolean requestPending;
	private long lastTimelapse;
	/** The clip being recorded; touched on the client thread only. */
	private Pending pending;
	/** The footage recording in progress, or null. */
	private volatile Footage footage;
	/** Waiting for the player to start an emote: the time it was armed, or 0. Client thread. */
	private long emoteArmed;
	private long emoteAccount;
	/** The emote being captured; client thread. */
	private EmoteTake emoteTake;
	/** Gap between cutouts of the same NPC; a later, bigger one replaces the stored picture. */
	static final long NPC_RETRY_MS = 45_000;
	/** NPCs cut out this session, by safe name, with when the next try is due. */
	private final Map<String, Long> npcsCut = new ConcurrentHashMap<>();
	private Listener listener = new Listener()
	{
	};

	/** Hears about recordings starting and stopping, and captures finishing. Called on any thread. */
	interface Listener
	{
		default void footage(boolean recording, long start)
		{
		}

		default void message(String text)
		{
		}

		/** The account's list of recordings changed. Media thread. */
		default void recordings(long account)
		{
		}
	}

	/** A recording the player started; frames are written as they come. */
	private static final class Footage
	{
		final long account;
		final long start;
		final long stopAt;
		/** Opened and used on the media thread. */
		ClipLibrary.FootageWriter out;
		boolean failed;

		Footage(long account, long start, long stopAt)
		{
			this.account = account;
			this.start = start;
			this.stopAt = stopAt;
		}
	}

	/** The player's emote being cut out of the game view, frame by frame. */
	private static final class EmoteTake
	{
		static final int FPS = 15;
		static final long MAX_MS = 8000;
		final long account;
		final long start;
		final List<ClipFile.Frame> frames = new ArrayList<>();
		/** Crop box size in canvas pixels, fixed by the first frame so the player stays put. */
		int boxW;
		int boxH;
		long lastFrame;
		long idleSince;

		EmoteTake(long account, long start)
		{
			this.account = account;
			this.start = start;
		}
	}

	/** A clip waiting for its after-moment footage; more moments can join it. */
	private final class Pending
	{
		final long account;
		final long start;
		long from;
		long to;
		int importance;
		final List<Moment> moments = new ArrayList<>();
		ScheduledFuture<?> due;

		Pending(long account, long start)
		{
			this.account = account;
			this.start = start;
		}
	}

	Capture(Client client, DrawManager drawManager, GielinorWrappedConfig config, ClipLibrary library)
	{
		this.client = client;
		this.drawManager = drawManager;
		this.config = config;
		this.library = library;
	}

	void start()
	{
		worker = Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "gielinor-wrapped-frames");
			t.setDaemon(true);
			return t;
		});
		writer = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "gielinor-wrapped-media");
			t.setDaemon(true);
			return t;
		});
		drawManager.registerEveryFrameListener(frameListener);
	}

	void setListener(Listener listener)
	{
		this.listener = listener;
	}

	/** Stops recording; clips already being written are finished. */
	void stop() throws InterruptedException
	{
		drawManager.unregisterEveryFrameListener(frameListener);
		pending = null;
		stopFootage();
		emoteArmed = 0;
		emoteTake = null;
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
		if (writer != null)
		{
			writer.shutdown();
			if (!writer.awaitTermination(5, TimeUnit.SECONDS))
			{
				writer.shutdownNow();
			}
			writer = null;
		}
		clear();
	}

	private void clear()
	{
		synchronized (frames)
		{
			frames.clear();
		}
		while (spare.poll() != null)
		{
			buffers.decrementAndGet();
		}
	}

	/** Whether a moment of this kind gets a clip under the current settings. */
	static boolean wanted(Moment m, GielinorWrappedConfig config)
	{
		switch (m.type)
		{
			case LEVEL_UP:
			case MAX_LEVEL:
				return config.clipLevels();
			case DROP:
				return config.clipDrops();
			case CLUE:
				return config.clipDrops() && m.importance >= 50;
			case PET:
			case COLLECTION_LOG:
				return config.clipPets();
			case PERSONAL_BEST:
			case KILL_COUNT:
			case QUEST:
			case COMBAT_TASK:
			case DIARY:
				return config.clipBosses();
			case DEATH:
				return config.clipDeaths();
			case MANUAL:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Called on the client thread for each new moment. {@code saved} is run on the media thread with each
	 * moment and the clip's file name once it is on disk.
	 */
	void onMoment(long account, Moment m, BiConsumer<Moment, String> saved)
	{
		ScheduledExecutorService w = worker;
		if (w == null || account == -1 || !config.recordClips() || !wanted(m, config))
		{
			return;
		}
		long before = config.clipBefore() * 1000L;
		long after = config.clipAfter() * 1000L;
		Pending p = pending;
		if (p != null && p.account == account && m.time <= p.to && m.time + after - p.from <= MAX_CLIP_MS && p.due != null && p.due.cancel(false))
		{
			p.to = Math.max(p.to, m.time + after);
		}
		else
		{
			p = new Pending(account, m.time);
			p.from = m.time - before;
			p.to = m.time + after;
			pending = p;
		}
		p.importance = Math.max(p.importance, m.importance);
		p.moments.add(m);
		Pending clip = p;
		long delay = Math.max(0, clip.to - System.currentTimeMillis()) + 200;
		try
		{
			clip.due = w.schedule(() -> take(clip, saved), delay, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e)
		{
			// Shutting down.
		}
	}

	/** Runs on the frame thread once the clip's last frame is due. */
	private void take(Pending p, BiConsumer<Moment, String> saved)
	{
		List<ClipFile.Frame> clip = new ArrayList<>();
		synchronized (frames)
		{
			for (ClipFile.Frame f : frames)
			{
				if (f.time >= p.from && f.time <= p.to)
				{
					clip.add(f);
				}
			}
		}
		log.debug("Clip: {} frames for {} moments", clip.size(), p.moments.size());
		if (clip.size() < 2)
		{
			return;
		}
		List<Moment> moments = new ArrayList<>(p.moments);
		execute(writer, () ->
		{
			try
			{
				String name = library.writeClip(p.account, p.start, p.importance, clip);
				for (Moment m : moments)
				{
					saved.accept(m, name);
				}
				library.prune(p.account, config.diskBudget() * 1024L * 1024L, LocalDate.now());
			}
			catch (Exception e)
			{
				log.warn("Unable to save clip", e);
			}
		});
	}

	/** Called on the client thread each game tick to take timelapse stills. */
	void onTick(long account)
	{
		if (worker != null && account != -1 && config.bossStickers())
		{
			npcCutout(account);
		}
		if (worker == null || account == -1 || !config.timelapse())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastTimelapse < config.timelapseInterval() * 1000L)
		{
			return;
		}
		lastTimelapse = now;
		LocalDate date = LocalDate.now();
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage copy = copy(image, null);
			List<Rectangle> hidden = hiddenAreas(image);
			execute(worker, () ->
			{
				try
				{
					ClipFile.Frame frame = new ClipFile.Frame(now, ClipFile.encode(prepare(copy, hidden), CLIP_QUALITY));
					execute(writer, () ->
					{
						try
						{
							library.appendTimelapse(account, date, frame);
						}
						catch (Exception e)
						{
							log.warn("Unable to save timelapse still", e);
						}
					});
				}
				catch (Exception e)
				{
					log.debug("Unable to compress timelapse still", e);
				}
			});
		});
	}

	boolean isRecording()
	{
		return footage != null;
	}

	/** When the recording in progress started, or 0. */
	long recordingStart()
	{
		Footage f = footage;
		return f == null ? 0 : f.start;
	}

	/** Starts recording footage for the video maker; it stops by itself after the longest recording setting. */
	void startFootage(long account)
	{
		ExecutorService wr = writer;
		if (wr == null || account == -1 || footage != null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		Footage f = new Footage(account, now, now + config.footageMax() * 1000L);
		footage = f;
		execute(wr, () ->
		{
			try
			{
				f.out = library.startFootage(f.account, f.start);
			}
			catch (IOException e)
			{
				f.failed = true;
				log.warn("Unable to start a recording", e);
				listener.message("Unable to start recording: " + e.getMessage());
			}
		});
		listener.footage(true, now);
	}

	/** Stops the recording in progress and saves it; does nothing when there is none. */
	void stopFootage()
	{
		Footage f = footage;
		if (f == null)
		{
			return;
		}
		footage = null;
		listener.footage(false, f.start);
		ExecutorService wr = writer;
		if (wr == null)
		{
			return;
		}
		execute(wr, () ->
		{
			if (f.out == null)
			{
				return;
			}
			try
			{
				ClipLibrary.Recording rec = f.out.finish();
				if (rec != null)
				{
					library.pruneFootage(f.account, config.footageBudget() * 1024L * 1024L, rec.name);
					listener.message(String.format("Recording saved (%d:%02d).", rec.length / 60000, rec.length / 1000 % 60));
					listener.recordings(f.account);
				}
			}
			catch (IOException e)
			{
				log.warn("Unable to save the recording", e);
				listener.message("Unable to save the recording: " + e.getMessage());
			}
		});
	}

	private static void writeFootage(Footage f, ClipFile.Frame frame)
	{
		if (f.out == null || f.failed)
		{
			return;
		}
		try
		{
			f.out.add(frame);
		}
		catch (IOException e)
		{
			f.failed = true;
			log.warn("Unable to write a recording frame", e);
		}
	}

	/** Turns leftovers from a crash into recordings. Media thread. */
	void recover(long account)
	{
		execute(writer, () ->
		{
			library.recoverFootage(account);
			listener.recordings(account);
		});
	}

	/** Gets ready to capture the next emote the player does. Client thread. */
	void armEmote(long account)
	{
		if (account == -1 || client.getGameState() != GameState.LOGGED_IN)
		{
			listener.message("Log in to capture your emote.");
			return;
		}
		emoteArmed = System.currentTimeMillis();
		emoteAccount = account;
		emoteTake = null;
		listener.message("Ready: do an emote in game now. Best with the camera facing you and nothing behind.");
	}

	boolean isEmoteArmed()
	{
		return emoteArmed != 0 || emoteTake != null;
	}

	/** Client thread. Starts the capture when the armed player begins an animation. */
	void onAnimation(Actor actor)
	{
		if (emoteArmed == 0 || actor != client.getLocalPlayer() || actor.getAnimation() == -1)
		{
			return;
		}
		emoteArmed = 0;
		emoteTake = new EmoteTake(emoteAccount, System.currentTimeMillis());
	}

	/** Client thread, every frame: grabs the cut-out player while an emote is being captured. */
	private void emoteFrame(long now)
	{
		if (emoteArmed != 0 && now - emoteArmed > 30_000)
		{
			emoteArmed = 0;
			listener.message("Emote capture timed out. Press capture, then do an emote.");
		}
		EmoteTake take = emoteTake;
		if (take == null || now - take.lastFrame < 1000 / EmoteTake.FPS)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		boolean idle = me == null || me.getAnimation() == -1;
		if (idle && take.idleSince == 0)
		{
			take.idleSince = now;
		}
		else if (!idle)
		{
			take.idleSince = 0;
		}
		if (me == null || now - take.start > EmoteTake.MAX_MS || (take.idleSince != 0 && now - take.idleSince > 300))
		{
			emoteTake = null;
			finishEmote(take);
			return;
		}
		take.lastFrame = now;
		drawManager.requestNextFrameListener(image ->
		{
			Player p = client.getLocalPlayer();
			if (p == null || emoteTake != take)
			{
				return;
			}
			Shape hull = p.getConvexHull();
			LocalPoint lp = p.getLocalLocation();
			Point foot = lp == null ? null : Perspective.localToCanvas(client, lp, p.getWorldView().getPlane());
			if (hull == null || foot == null)
			{
				return;
			}
			Rectangle hb = hull.getBounds();
			if (take.boxH == 0)
			{
				take.boxH = Math.max(40, (int) (hb.height * 1.5));
				take.boxW = take.boxH;
			}
			Rectangle box = new Rectangle(foot.getX() - take.boxW / 2, foot.getY() - take.boxH * 9 / 10, take.boxW, take.boxH);
			BufferedImage cut = cutout(image, hull, box, scaleOf(image));
			if (cut == null)
			{
				return;
			}
			long time = System.currentTimeMillis();
			execute(worker, () ->
			{
				try
				{
					take.frames.add(new ClipFile.Frame(time, png(cut)));
				}
				catch (IOException e)
				{
					log.debug("Unable to compress an emote frame", e);
				}
			});
		});
	}

	private void finishEmote(EmoteTake take)
	{
		// Queued behind the last frames' compression.
		execute(worker, () ->
		{
			List<ClipFile.Frame> got = new ArrayList<>(take.frames);
			if (got.size() < 3 || Emote.of(got) == null)
			{
				listener.message("Couldn't see your character for the emote. Face the camera and try again.");
				return;
			}
			execute(writer, () ->
			{
				try
				{
					library.writeEmote(take.account, got);
					listener.message("Emote captured (" + got.size() + " frames). It will dance in your next video.");
				}
				catch (IOException e)
				{
					log.warn("Unable to save the emote", e);
					listener.message("Unable to save the emote: " + e.getMessage());
				}
			});
		});
	}

	/**
	 * Client thread, each tick: cuts out a monster the player is fighting or being attacked by, to use as a
	 * sticker in recaps. Each one is tried again every {@link #NPC_RETRY_MS} so a closer view can replace a
	 * small first picture.
	 */
	private void npcCutout(long account)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		NPC npc = null;
		String name = null;
		Actor target = me.getInteracting();
		if (target instanceof NPC)
		{
			name = cutoutName((NPC) target, now);
			npc = name == null ? null : (NPC) target;
		}
		if (npc == null)
		{
			for (NPC other : client.getTopLevelWorldView().npcs())
			{
				if (other != null && other.getInteracting() == me)
				{
					name = cutoutName(other, now);
					if (name != null)
					{
						npc = other;
						break;
					}
				}
			}
		}
		if (npc == null)
		{
			return;
		}
		cutNpc(account, npc, name, now);
	}

	/** The safe name of a monster due a cutout, or null when it isn't one or was cut out recently. */
	private String cutoutName(NPC npc, long now)
	{
		if (npc.getName() == null || npc.getCombatLevel() <= 0)
		{
			return null;
		}
		String name = ClipLibrary.safeName(net.runelite.client.util.Text.removeTags(npc.getName()));
		return name.isEmpty() || npcsCut.getOrDefault(name, 0L) > now ? null : name;
	}

	private void cutNpc(long account, NPC npc, String name, long now)
	{
		npcsCut.put(name, now + NPC_RETRY_MS);
		drawManager.requestNextFrameListener(image ->
		{
			Shape hull = npc.getConvexHull();
			if (hull == null)
			{
				npcsCut.remove(name, now + NPC_RETRY_MS);
				return;
			}
			Rectangle b = hull.getBounds();
			if (b.width < 24 || b.height < 24 || !unobstructed(b))
			{
				// Too far away, or partly off screen or behind the interface; try again later.
				npcsCut.remove(name, now + NPC_RETRY_MS);
				return;
			}
			BufferedImage cut = cutout(image, hull, b, scaleOf(image));
			if (cut == null)
			{
				npcsCut.remove(name, now + NPC_RETRY_MS);
				return;
			}
			long area = (long) cut.getWidth() * cut.getHeight();
			execute(worker, () ->
			{
				try
				{
					byte[] png = png(cut);
					execute(writer, () ->
					{
						try
						{
							// Keep the first picture unless this one is clearly bigger, so a far-away cutout gets replaced.
							if (area * 2 > library.npcArea(account, name) * 3)
							{
								library.writeNpc(account, name, png);
							}
						}
						catch (IOException e)
						{
							log.debug("Unable to save NPC cutout", e);
						}
					});
				}
				catch (IOException e)
				{
					log.debug("Unable to compress NPC cutout", e);
				}
			});
		});
	}

	/**
	 * True when {@code box}, in canvas pixels, lies inside the game view and clear of the side panel, minimap
	 * and chatbox, which draw over the game in resizable mode. Client thread.
	 */
	private boolean unobstructed(Rectangle box)
	{
		Rectangle view = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
		if (!view.contains(box))
		{
			return false;
		}
		List<Rectangle> panels = new ArrayList<>();
		for (int id : INTERFACE_PANELS)
		{
			addBounds(client.getWidget(id), panels);
		}
		return clearOf(box, panels, view);
	}

	/**
	 * True when {@code box} touches none of {@code panels}. A panel covering more than half the view is a
	 * layer spanning the screen rather than something drawn over the game, so it is ignored.
	 */
	static boolean clearOf(Rectangle box, List<Rectangle> panels, Rectangle view)
	{
		for (Rectangle p : panels)
		{
			if ((double) p.width * p.height <= 0.5 * view.width * view.height && p.intersects(box))
			{
				return false;
			}
		}
		return true;
	}

	/** Frame pixels per canvas pixel. Client thread. */
	private double[] scaleOf(Image image)
	{
		if (client.isStretchedEnabled() && client.getRealDimensions() != null)
		{
			return new double[]{image.getWidth(null) / (double) client.getRealDimensions().width, image.getHeight(null) / (double) client.getRealDimensions().height};
		}
		return new double[]{1, 1};
	}

	/**
	 * The part of {@code frame} inside {@code shape}, on transparency, cropped to {@code box}; shape and box are
	 * in canvas pixels. Null when the box is off screen.
	 */
	static BufferedImage cutout(Image frame, Shape shape, Rectangle box, double[] scale)
	{
		int fw = frame.getWidth(null);
		int fh = frame.getHeight(null);
		Rectangle fb = new Rectangle((int) (box.x * scale[0]), (int) (box.y * scale[1]), (int) Math.ceil(box.width * scale[0]), (int) Math.ceil(box.height * scale[1]));
		if (fb.width < 8 || fb.height < 8 || !fb.intersects(new Rectangle(0, 0, fw, fh)))
		{
			return null;
		}
		// Cutouts are small in a recap; keep them under 320 px.
		double down = Math.min(1, 320.0 / Math.max(fb.width, fb.height));
		int w = Math.max(1, (int) (fb.width * down));
		int h = Math.max(1, (int) (fb.height * down));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.scale(down, down);
		g.translate(-fb.x, -fb.y);
		AffineTransform toFrame = AffineTransform.getScaleInstance(scale[0], scale[1]);
		g.clip(toFrame.createTransformedShape(shape));
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		return out;
	}

	private static byte[] png(BufferedImage img) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	private void onFrame()
	{
		long now = System.currentTimeMillis();
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		Footage rec = footage;
		if (rec != null && (!loggedIn || now >= rec.stopAt))
		{
			stopFootage();
			rec = null;
		}
		if (loggedIn)
		{
			emoteFrame(now);
		}
		boolean clips = config.recordClips();
		if ((!clips && rec == null) || !loggedIn)
		{
			if (!frames.isEmpty())
			{
				clear();
			}
			return;
		}
		int fps = rec == null ? config.clipFps() : Math.max(config.footageFps(), clips ? config.clipFps() : 0);
		long interval = 1000L / Math.max(1, fps);
		// If a requested frame never arrived (e.g. the window was minimised), ask again.
		if (now - lastRequest < interval || (requestPending && now - lastRequest < 1000))
		{
			return;
		}
		lastRequest = now;
		requestPending = true;
		drawManager.requestNextFrameListener(image ->
		{
			requestPending = false;
			ScheduledExecutorService w = worker;
			BufferedImage buffer = w == null ? null : buffer(image.getWidth(null), image.getHeight(null));
			if (buffer == null)
			{
				return;
			}
			copy(image, buffer);
			List<Rectangle> hidden = hiddenAreas(image);
			long time = System.currentTimeMillis();
			long keep = (config.clipBefore() + config.clipAfter()) * 1000L + MAX_CLIP_MS;
			try
			{
				w.execute(() ->
				{
					try
					{
						ClipFile.Frame frame = new ClipFile.Frame(time, ClipFile.encode(prepare(buffer, hidden), CLIP_QUALITY));
						synchronized (frames)
						{
							frames.addLast(frame);
							while (!frames.isEmpty() && frames.peekFirst().time < time - keep)
							{
								frames.pollFirst();
							}
						}
					}
					catch (Exception e)
					{
						log.debug("Unable to compress clip frame", e);
					}
					finally
					{
						spare.offer(buffer);
					}
				});
			}
			catch (RejectedExecutionException e)
			{
				spare.offer(buffer);
			}
		});
	}

	/**
	 * Grabs the next frame at full size for a recap shot, cropped to the game view if asked, with chat hidden
	 * per the settings. {@code done} runs on {@code executor}. Client thread.
	 */
	void shot(boolean gameView, ExecutorService executor, Consumer<BufferedImage> done)
	{
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage copy = copy(image, null);
			List<Rectangle> hidden = hiddenAreas(image);
			Rectangle crop = gameView ? viewport(image) : null;
			execute(executor, () -> done.accept(shotImage(copy, crop, hidden)));
		});
	}

	/** The 3D game view in frame pixels, or null when it isn't known. Client thread only. */
	private Rectangle viewport(Image image)
	{
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		if (vw <= 0 || vh <= 0)
		{
			return null;
		}
		double sx = 1;
		double sy = 1;
		if (client.isStretchedEnabled() && client.getRealDimensions() != null)
		{
			sx = image.getWidth(null) / (double) client.getRealDimensions().width;
			sy = image.getHeight(null) / (double) client.getRealDimensions().height;
		}
		return new Rectangle((int) (client.getViewportXOffset() * sx), (int) (client.getViewportYOffset() * sy), (int) (vw * sx), (int) (vh * sy));
	}

	/** Pixelates the hidden areas of a full-size frame, then crops it. */
	static BufferedImage shotImage(BufferedImage full, Rectangle crop, List<Rectangle> hidden)
	{
		// Blocks as coarse as in a clip of the usual width, so chat stays unreadable at full size.
		int block = Math.max(BLUR_BLOCK, BLUR_BLOCK * full.getWidth() / 640);
		for (Rectangle r : hidden)
		{
			pixelate(full, r, block);
		}
		Rectangle c = crop == null ? null : crop.intersection(new Rectangle(0, 0, full.getWidth(), full.getHeight()));
		if (c == null || c.width < 16 || c.height < 16 || c.getSize().equals(new java.awt.Dimension(full.getWidth(), full.getHeight())))
		{
			return full;
		}
		BufferedImage out = new BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(full, -c.x, -c.y, null);
		g.dispose();
		return out;
	}

	/** Chat areas to blur, in frame pixels. Client thread only. */
	private List<Rectangle> hiddenAreas(Image image)
	{
		List<Rectangle> areas = new ArrayList<>();
		if (!config.hideChat())
		{
			return areas;
		}
		double sx = 1;
		double sy = 1;
		if (client.isStretchedEnabled() && client.getRealDimensions() != null)
		{
			sx = image.getWidth(null) / (double) client.getRealDimensions().width;
			sy = image.getHeight(null) / (double) client.getRealDimensions().height;
		}
		// Only the widgets holding chat text: the chatbox root and the PM container span far
		// more of the screen than the text, up to the whole viewport in resizable mode.
		for (int id : new int[]{InterfaceID.Chatbox.SCROLLAREA, InterfaceID.Chatbox.INPUT})
		{
			addBounds(client.getWidget(id), areas);
		}
		Widget pm = client.getWidget(InterfaceID.PmChat.CONTAINER);
		if (pm != null && !pm.isHidden())
		{
			addTextLines(pm, areas, 0);
		}
		return fitAreas(areas, image.getWidth(null), image.getHeight(null), sx, sy);
	}

	private static void addBounds(Widget w, List<Rectangle> areas)
	{
		if (w != null && !w.isHidden())
		{
			Rectangle b = w.getBounds();
			if (b != null && b.width > 0 && b.height > 0)
			{
				areas.add(b);
			}
		}
	}

	/** Adds every visible descendant of {@code parent} that shows text, such as the private message lines. */
	private static void addTextLines(Widget parent, List<Rectangle> areas, int depth)
	{
		if (depth > 4)
		{
			return;
		}
		for (Widget[] children : new Widget[][]{parent.getStaticChildren(), parent.getDynamicChildren(), parent.getNestedChildren()})
		{
			if (children == null)
			{
				continue;
			}
			for (Widget c : children)
			{
				if (c == null || c.isHidden())
				{
					continue;
				}
				String text = c.getText();
				if (text != null && !text.isEmpty())
				{
					addBounds(c, areas);
				}
				addTextLines(c, areas, depth + 1);
			}
		}
	}

	/**
	 * Scales widget bounds to frame pixels and clips them to the frame. An area covering more than
	 * {@link #MAX_HIDDEN_SHARE} of the frame can't be chat text, so it is dropped rather than blurring the game.
	 */
	static List<Rectangle> fitAreas(List<Rectangle> bounds, int width, int height, double sx, double sy)
	{
		Rectangle frame = new Rectangle(0, 0, width, height);
		List<Rectangle> out = new ArrayList<>();
		for (Rectangle b : bounds)
		{
			Rectangle r = new Rectangle((int) (b.x * sx), (int) (b.y * sy), (int) Math.ceil(b.width * sx), (int) Math.ceil(b.height * sy))
				.intersection(frame);
			if (!r.isEmpty() && (double) r.width * r.height <= MAX_HIDDEN_SHARE * width * height)
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Scales the frame to the clip width and pixelates hidden areas. Frame thread; the result is only good until the
	 * next call.
	 */
	private BufferedImage prepare(BufferedImage full, List<Rectangle> hidden)
	{
		int width = Math.min(full.getWidth(), config.clipWidth());
		double scale = width / (double) full.getWidth();
		BufferedImage out = full;
		if (width != full.getWidth())
		{
			int height = Math.max(2, (int) Math.round(full.getHeight() * scale));
			if (scaled == null || scaled.getWidth() != width || scaled.getHeight() != height)
			{
				scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			}
			out = scaled;
			Graphics2D g = out.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(full, 0, 0, width, height, null);
			g.dispose();
		}
		for (Rectangle r : hidden)
		{
			pixelate(out, new Rectangle((int) (r.x * scale), (int) (r.y * scale), (int) Math.ceil(r.width * scale), (int) Math.ceil(r.height * scale)));
		}
		return out;
	}

	/** Replaces each block in the area with its average colour, so text can't be read back. */
	static void pixelate(BufferedImage image, Rectangle area)
	{
		pixelate(image, area, BLUR_BLOCK);
	}

	static void pixelate(BufferedImage image, Rectangle area, int block)
	{
		Rectangle r = area.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
		if (r.isEmpty())
		{
			return;
		}
		int[] px = image.getRGB(r.x, r.y, r.width, r.height, null, 0, r.width);
		for (int by = 0; by < r.height; by += block)
		{
			for (int bx = 0; bx < r.width; bx += block)
			{
				int ex = Math.min(bx + block, r.width);
				int ey = Math.min(by + block, r.height);
				long red = 0;
				long green = 0;
				long blue = 0;
				for (int y = by; y < ey; y++)
				{
					for (int x = bx; x < ex; x++)
					{
						int c = px[y * r.width + x];
						red += (c >> 16) & 0xFF;
						green += (c >> 8) & 0xFF;
						blue += c & 0xFF;
					}
				}
				int n = (ex - bx) * (ey - by);
				int avg = (int) (red / n) << 16 | (int) (green / n) << 8 | (int) (blue / n);
				for (int y = by; y < ey; y++)
				{
					for (int x = bx; x < ex; x++)
					{
						px[y * r.width + x] = avg;
					}
				}
			}
		}
		image.setRGB(r.x, r.y, r.width, r.height, px, 0, r.width);
	}

	private BufferedImage buffer(int width, int height)
	{
		BufferedImage b;
		while ((b = spare.poll()) != null)
		{
			if (b.getWidth() == width && b.getHeight() == height)
			{
				return b;
			}
			buffers.decrementAndGet();
		}
		if (buffers.incrementAndGet() > BUFFERS)
		{
			buffers.decrementAndGet();
			return null;
		}
		return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
	}

	private static BufferedImage copy(Image image, BufferedImage into)
	{
		BufferedImage out = into != null ? into : new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return out;
	}

	private static void execute(ExecutorService executor, Runnable task)
	{
		if (executor == null)
		{
			return;
		}
		try
		{
			executor.execute(task);
		}
		catch (RejectedExecutionException e)
		{
			// Shutting down.
		}
	}
}
