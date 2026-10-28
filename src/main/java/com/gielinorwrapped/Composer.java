package com.gielinorwrapped;

import com.gielinorwrapped.music.Midi;
import com.gielinorwrapped.music.Synth;
import com.gielinorwrapped.video.AacEncoder;
import com.gielinorwrapped.video.GifWriter;
import com.gielinorwrapped.video.H264Encoder;
import com.gielinorwrapped.video.Mp4Writer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/** Turns a {@link Recap} and its clips into a video. Runs off the client thread; rendering takes a while. */
final class Composer
{
	/** Where clip footage comes from. */
	interface Media
	{
		List<ClipFile.Frame> clip(String name);

		/** At most {@code max} stills spread over the period. */
		List<ClipFile.Frame> timelapse(int max);

		/** The item's inventory sprite, or null when unknown or icons are off. */
		default BufferedImage item(int id)
		{
			return null;
		}

		/** The skill's icon by name, e.g. "Woodcutting", or null. */
		default BufferedImage skill(String name)
		{
			return null;
		}

		/** The chat badge of an account type, e.g. the Hardcore Ironman helmet, or null when there is none or badges are off. */
		default BufferedImage badge(AccountType type)
		{
			return null;
		}

		/** The recap shots taken during the period. */
		default Shots.Pick shots()
		{
			return new Shots.Pick();
		}

		/** The player's recorded emote, or null. */
		default Emote emote()
		{
			return null;
		}

		/** Footage recordings, oldest first. */
		default List<ClipLibrary.Recording> recordings()
		{
			return new ArrayList<>();
		}

		/** Frames of a recording between two capture times. */
		default List<ClipFile.Frame> recording(String name, long from, long to)
		{
			return new ArrayList<>();
		}

		/** Every NPC cutout and the player's own stickers; the settings only switch off the automatic picks. */
		default List<Sticker> stickers()
		{
			return new ArrayList<>();
		}

		/** Where the player placed stickers by hand. */
		default StickerBoard stickerBoard()
		{
			return new StickerBoard();
		}

		/** The chosen music track as MIDI file bytes, or null for none. */
		default byte[] song()
		{
			return null;
		}

		/** Deaths in the period from the death log, oldest first. */
		default List<DeathLog.Entry> deaths()
		{
			return new ArrayList<>();
		}

		/** One of a death's pictures (its card or screenshot file name), or null. */
		default BufferedImage deathImage(String name)
		{
			return null;
		}

		/** Death Recap's replay of the death nearest {@code time}, as clip frames, or empty when there is none. */
		default List<ClipFile.Frame> deathReplay(long time)
		{
			return new ArrayList<>();
		}

		/** The friend picked for a "you vs them" scene, or null. */
		default FriendFile friend()
		{
			return null;
		}
	}

	/** Where the video goes. MP4s are written to scratch first, then indexed into the result. */
	interface Output
	{
		OutputStream result() throws IOException;

		OutputStream scratch() throws IOException;

		InputStream readScratch() throws IOException;
	}

	static final double TRANSITION = 0.5;
	static final double MAX_CLIP = 8;
	static final double MIN_CLIP = 3;
	static final int MIN_QP = 18;
	static final int MAX_QP = 44;
	/** The first pass's qp, and the only pass's when there is no size limit or the video fits. */
	static final int FIRST_QP = 23;
	static final int GIF_MAX_SIDE = 640;
	static final int AUDIO_BITRATE = 128000;
	private static final DateTimeFormatter MOMENT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter MOMENT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

	final List<Scenes.Scene> scenes;
	final int width;
	final int height;
	final int fps;
	final GielinorWrappedConfig.VideoFormat format;
	private final GielinorWrappedConfig.Transition transition;
	/** Transition length in seconds. */
	private final double fade;
	private final boolean letterbox;
	/** Height of each cinema bar in pixels; 0 without them. Scenes are laid out in the band between. */
	final int bar;
	private final boolean progressBar;
	private final boolean wobble;
	private final String watermark;
	private final Theme theme;
	private final long targetBytes;
	private final byte[] song;
	private final double musicVolume;
	private final boolean musicFade;

	Composer(Recap recap, Media media, GielinorWrappedConfig config)
	{
		format = config.videoFormat();
		Dimension d = size(config);
		width = d.width;
		height = d.height;
		fps = format == GielinorWrappedConfig.VideoFormat.GIF ? Math.min(15, config.videoFps()) : config.videoFps();
		transition = config.transition();
		fade = Math.max(0.1, config.transitionLength() / 1000.0);
		letterbox = config.letterbox();
		bar = letterbox ? barHeight(height) : 0;
		progressBar = config.progressBar();
		wobble = config.stickerWobble();
		watermark = config.watermark();
		theme = Theme.of(config);
		targetBytes = config.targetSize() * 1_000_000L * 94 / 100;
		song = format != GielinorWrappedConfig.VideoFormat.GIF && config.musicEnabled() ? media.song() : null;
		musicVolume = config.musicVolume() / 100.0;
		musicFade = config.musicFade();
		scenes = plan(recap, media, config);
	}

	/** Reads a shot, or null if there is none or it can't be read. */
	static BufferedImage read(Shots.Shot shot)
	{
		if (shot == null)
		{
			return null;
		}
		try
		{
			return shot.read();
		}
		catch (IOException e)
		{
			return null;
		}
	}

	static Dimension size(GielinorWrappedConfig config)
	{
		GielinorWrappedConfig.Aspect a = config.aspect();
		double scale = config.quality().scale;
		if (config.videoFormat() == GielinorWrappedConfig.VideoFormat.GIF)
		{
			scale = Math.min(scale, GIF_MAX_SIDE / (double) Math.max(a.width, a.height));
		}
		return new Dimension(even(a.width * scale), even(a.height * scale));
	}

	private static int even(double v)
	{
		return Math.max(2, (int) Math.round(v / 2) * 2);
	}

	/** Picks the scenes and fits them into the length limit. */
	static List<Scenes.Scene> plan(Recap r, Media media, GielinorWrappedConfig config)
	{
		Scenes.Look look = Scenes.Look.of(config);
		Map<String, String> tok = r.tokens(config.hideName());
		DayRecord total = r.total;
		double pace = config.pace().scale;
		CustomCaptions custom = CustomCaptions.parse(config.customCaptions());

		List<Scenes.Scene> stats = new ArrayList<>();
		if (config.statScenes())
		{
			double d = 3.5 * pace;
			if (total.totalXp() > 0)
			{
				stats.add(new Scenes.Stat("xp", look, d, "Experience", Recap.fill(config.xpText(), tok), total.totalXp(), n -> Recap.number(n) + " XP",
					r.skills, Recap::number, null));
			}
			if (total.lootValue > 0)
			{
				stats.add(new Scenes.Stat("loot", look, d, "Loot", Recap.fill(config.lootText(), tok), total.lootValue, Recap::gp,
					r.lootSources, Recap::gp, null));
			}
			if (r.totalKills > 0)
			{
				stats.add(new Scenes.Stat("kills", look, d, "Kills", Recap.fill(config.killsText(), tok), r.totalKills, Recap::number,
					r.kills, Recap::number, null));
			}
			if (total.playSeconds > 0)
			{
				stats.add(new Scenes.Stat("time", look, d, "Time played", Recap.fill(config.timeText(), tok), total.playSeconds, Recap::duration,
					null, null, total.hours.clone()));
			}
		}

		List<Scenes.Clip> clips = new ArrayList<>();
		List<Scenes.Reel> clipReels = new ArrayList<>();
		if (config.maxClips() > 0)
		{
			for (Moment m : r.highlights)
			{
				if (clips.size() >= config.maxClips())
				{
					break;
				}
				// A death that wasn't clipped can use Death Recap's replay instead.
				boolean replay = m.clip == null && m.type == Moment.Type.DEATH && config.deathReplays();
				if (m.clip == null && !replay)
				{
					continue;
				}
				String name = m.clip;
				Supplier<List<ClipFile.Frame>> load = replay ? () -> media.deathReplay(m.time) : () -> media.clip(name);
				List<ClipFile.Frame> frames = load.get();
				if (frames.size() < 2)
				{
					continue;
				}
				long first = frames.get(0).time;
				long length = frames.get(frames.size() - 1).time - first;
				Scenes.Clip c = new Scenes.Clip(look, load, length, Math.max(0, Math.min(length, m.time - first)), m.importance,
					Scenes.badge(m.type), Recap.fill(config.clipText(), momentTokens(m)));
				c.momentOrder = m.time;
				c.icon = momentIcon(m, media);
				c.moment = m;
				c.trim(Math.min(MAX_CLIP, length / 1000.0));
				clips.add(c);
				clipReels.add(new Scenes.Reel((from, to) -> load.get(), first, first + length, null));
			}
		}

		Scenes.Timelapse timelapse = null;
		if (config.timelapseScene())
		{
			int max = config.videoFps() * 5;
			List<ClipFile.Frame> stills = media.timelapse(max);
			if (stills.size() >= 8)
			{
				double secs = Math.max(2.5, Math.min(5, stills.size() / 12.0));
				timelapse = new Scenes.Timelapse(look, () -> media.timelapse(max), stills.size(), secs, Recap.fill(config.timelapseText(), tok));
			}
		}

		Emote emote = config.emoteScene() ? media.emote() : null;
		Scenes.Scene persona = config.personaScene()
			? new Scenes.Card("persona", look, 3 * pace, "Your " + r.period.noun() + " persona", Recap.fill(config.personaText(), tok), null)
			: null;
		Scenes.Scene title = null;
		if (config.introScene())
		{
			String[] own = custom.title == null ? null : CustomCaptions.split(Recap.fill(custom.title, tok));
			if (config.titleStyle() == GielinorWrappedConfig.TitleStyle.SPLASH)
			{
				Splash splash = Splash.of(r, media, tok, config, own == null ? null : own[0]);
				if (splash.hasArt())
				{
					Scenes.SplashTitle st = new Scenes.SplashTitle(look, 3 * pace, splash);
					st.player = emote;
					title = st;
				}
			}
			if (title == null)
			{
				Scenes.Title t = new Scenes.Title(look, 2.5 * pace, r.period.describe(), own != null ? own[0] : Recap.fill(config.titleText(), tok),
					own != null && own[1] != null ? own[1] : Recap.fill("{days} · {hours} played", tok));
				t.player = emote;
				title = t;
			}
		}
		Shots.Pick shots = media.shots();
		Scenes.Card outro = null;
		if (config.outroScene())
		{
			String[] own = custom.outro == null ? null : CustomCaptions.split(Recap.fill(custom.outro, tok));
			outro = new Scenes.Card("outro", look, 2.5 * pace, null, own != null ? own[0] : Recap.fill(config.outroText(), tok),
				own != null && own[1] != null ? own[1] : Recap.fill("{xp} XP · {loot} · {hours}", tok));
			if (shots.outro != null)
			{
				Shots.Shot shot = shots.outro;
				outro.backdrop = () -> read(shot);
			}
			else if (title instanceof Scenes.SplashTitle || title == null)
			{
				// The player waves goodbye when the intro didn't show them standing under the text.
				outro.player = emote;
			}
		}

		Scenes.Gallery gallery = null;
		if (config.galleryScene() && !shots.gallery.isEmpty())
		{
			List<String> captions = new ArrayList<>();
			for (Shots.Shot shot : shots.gallery)
			{
				Map<String, String> t = new HashMap<>();
				Instant at = Instant.ofEpochMilli(shot.time);
				t.put("date", MOMENT_DATE.format(at.atZone(ZoneId.systemDefault())));
				t.put("time", MOMENT_TIME.format(at.atZone(ZoneId.systemDefault())));
				t.put("player", tok.get("player"));
				captions.add(Recap.fill(config.galleryText(), t));
			}
			gallery = new Scenes.Gallery(look, new ArrayList<>(shots.gallery), captions);
		}

		Scenes.Deaths deaths = config.deathScene() ? deathsScene(media, config, look, tok.get("player")) : null;

		List<Scenes.Scene> cards = new ArrayList<>();
		for (String line : custom.cards)
		{
			String[] parts = CustomCaptions.split(Recap.fill(line, tok));
			cards.add(new Scenes.Card("custom", look, config.customCardSeconds() * pace, null, parts[0], parts[1]));
		}

		// Extra scenes, most important first; the length fit drops them from the end.
		List<Scenes.Scene> extras = extras(r, media, config, look, pace, tok);
		MoreScenes.Montage montage = null;
		MoreScenes.Graphs graphs = null;
		for (Scenes.Scene s : extras)
		{
			if (s instanceof MoreScenes.Montage)
			{
				montage = (MoreScenes.Montage) s;
			}
			else if (s instanceof MoreScenes.Graphs)
			{
				graphs = (MoreScenes.Graphs) s;
			}
		}

		// Recorded footage: plain recordings and, with chroma key on, green-screen ones told apart by a sample frame.
		GielinorWrappedConfig.FootageMode mode = config.footageMode();
		Chroma chroma = Chroma.of(config);
		List<ClipLibrary.Recording> plain = new ArrayList<>();
		List<ClipLibrary.Recording> green = new ArrayList<>();
		if (mode != GielinorWrappedConfig.FootageMode.OFF)
		{
			for (ClipLibrary.Recording rec : media.recordings())
			{
				(chroma != null && isGreen(media, rec, chroma) ? green : plain).add(rec);
			}
		}
		boolean between = mode == GielinorWrappedConfig.FootageMode.BETWEEN || mode == GielinorWrappedConfig.FootageMode.BOTH;
		boolean backdrop = mode == GielinorWrappedConfig.FootageMode.BACKDROP || mode == GielinorWrappedConfig.FootageMode.BOTH;
		GielinorWrappedConfig.ChromaPlacement placement = config.chromaPlacement();
		boolean greenCuts = !green.isEmpty() && placement != GielinorWrappedConfig.ChromaPlacement.OVER_STATS;
		long segment = config.footageSegment() * 1000L;
		int maxCuts = config.footageCuts();
		int plainCuts = between ? (greenCuts ? (maxCuts + 1) / 2 : maxCuts) : 0;
		int greenCount = greenCuts ? (between && !plain.isEmpty() ? Math.max(1, maxCuts / 2) : maxCuts) : 0;
		List<Scenes.Footage> footage = new ArrayList<>();
		for (Cut cut : cuts(plain, count(plain, plainCuts, segment), 0.5))
		{
			footage.add(new Scenes.Footage(look, reel(media, cut, null), null));
		}
		List<Cut> bases = placement == GielinorWrappedConfig.ChromaPlacement.OVER_FOOTAGE ? cuts(plain, count(plain, greenCount, segment), 0.25) : new ArrayList<>();
		List<Cut> keyedCuts = cuts(green, count(green, greenCount, segment), 0.5);
		for (int i = 0; i < keyedCuts.size(); i++)
		{
			Scenes.Footage f = new Scenes.Footage(look, reel(media, keyedCuts.get(i), chroma), null);
			if (placement == GielinorWrappedConfig.ChromaPlacement.OVER_FOOTAGE)
			{
				if (!bases.isEmpty())
				{
					f.base = reel(media, bases.get(i % bases.size()), null);
				}
				else if (!clipReels.isEmpty())
				{
					Scenes.Reel c = clipReels.get(i % clipReels.size());
					f.base = c;
				}
			}
			footage.add(f);
		}
		footage.sort(Comparator.comparingLong(f -> f.reel.from));
		for (int i = 0; i < footage.size() && i < custom.footage.size(); i++)
		{
			footage.get(i).caption = Recap.fill(custom.footage.get(i), tok);
		}

		// Fit the length limit: shorten clips and footage, then drop the least important, then the extras.
		double max = config.maxLength();
		while (true)
		{
			double length = sum(stats) + sum(clips) + sum(footage) + sum(cards) + sum(extras) + sum(title, outro, persona, timelapse, gallery, deaths);
			double over = length - max;
			if (over <= 1e-6)
			{
				break;
			}
			double slack = 0;
			for (Scenes.Clip c : clips)
			{
				slack += Math.max(0, c.duration - MIN_CLIP);
			}
			double footageSlack = 0;
			for (Scenes.Footage f : footage)
			{
				footageSlack += Math.max(0, f.duration - f.minDuration());
			}
			if (slack > 0.05)
			{
				double cut = Math.min(1, over / slack);
				for (Scenes.Clip c : clips)
				{
					c.trim(c.duration - Math.max(0, c.duration - MIN_CLIP) * cut);
				}
			}
			else if (footageSlack > 0.05)
			{
				double cut = Math.min(1, over / footageSlack);
				for (Scenes.Footage f : footage)
				{
					f.duration -= Math.max(0, f.duration - f.minDuration()) * cut;
				}
			}
			else if (timelapse != null && timelapse.duration > 2.5 + 1e-6)
			{
				timelapse.duration = Math.max(2.5, timelapse.duration - over);
			}
			else if (gallery != null && gallery.duration > gallery.minDuration() + 1e-6)
			{
				gallery.duration = Math.max(gallery.minDuration(), gallery.duration - over);
			}
			else if (deaths != null && deaths.duration > deaths.minDuration() + 1e-6)
			{
				deaths.duration = Math.max(deaths.minDuration(), deaths.duration - over);
			}
			else if (montage != null && extras.contains(montage) && montage.duration > montage.minDuration() + 1e-6)
			{
				montage.duration = Math.max(montage.minDuration(), montage.duration - over);
			}
			else if (clips.size() > 1)
			{
				clips.remove(clips.stream().min(Comparator.comparingInt(c -> c.importance)).get());
			}
			else if (graphs != null && extras.remove(graphs))
			{
				// The player's own footage matters more than the charts.
				graphs = null;
			}
			else if (!footage.isEmpty())
			{
				// Drop every other cut so the rest stay spread over the period.
				footage.remove(footage.size() > 2 ? footage.size() / 2 : footage.size() - 1);
			}
			else if (timelapse != null)
			{
				timelapse = null;
			}
			else if (!extras.isEmpty())
			{
				extras.remove(extras.size() - 1);
			}
			else if (stats.size() > 1)
			{
				stats.remove(stats.size() - 1);
			}
			else if (gallery != null && gallery.size() > 2)
			{
				gallery.dropOne();
			}
			else if (deaths != null && deaths.size() > 2)
			{
				deaths.dropOne();
			}
			else if (!cards.isEmpty())
			{
				cards.remove(cards.size() - 1);
			}
			else if (persona != null)
			{
				persona = null;
			}
			else if (!clips.isEmpty())
			{
				clips.clear();
			}
			else if (!stats.isEmpty())
			{
				stats.clear();
			}
			else if (deaths != null)
			{
				deaths = null;
			}
			else if (gallery != null)
			{
				gallery = null;
			}
			else
			{
				double each = Math.max(1.5, max / 2);
				if (title != null)
				{
					title.duration = Math.min(title.duration, each);
				}
				if (outro != null)
				{
					outro.duration = Math.min(outro.duration, each);
				}
				break;
			}
		}

		// Stats interleaved with clips in the order they happened; the timelapse follows the time scene.
		List<Scenes.Clip> ordered = new ArrayList<>(clips);
		ordered.sort(Comparator.comparingLong(c -> c.momentOrder));
		List<Scenes.Scene> middle = new ArrayList<>();
		int ci = 0;
		boolean placedTimelapse = false;
		for (Scenes.Scene s : stats)
		{
			middle.add(s);
			if ("time".equals(s.name) && timelapse != null)
			{
				middle.add(timelapse);
				placedTimelapse = true;
			}
			else if (ci < ordered.size())
			{
				middle.add(ordered.get(ci++));
			}
		}
		while (ci < ordered.size())
		{
			middle.add(ordered.get(ci++));
		}
		if (timelapse != null && !placedTimelapse)
		{
			middle.add(timelapse);
		}
		middle = spread(middle, footage);
		middle = spread(middle, cards);

		List<Scenes.Scene> out = new ArrayList<>();
		if (title != null)
		{
			out.add(title);
		}
		out.addAll(middle);
		// Where and what: map, bosses, collection, GE; then the montage; then how it compares.
		for (Scenes.Scene s : extras)
		{
			if (!(s instanceof MoreScenes.Montage) && !(s instanceof MoreScenes.Duel) && !(s instanceof MoreScenes.Pace))
			{
				out.add(s);
			}
		}
		if (montage != null && extras.contains(montage))
		{
			out.add(montage);
		}
		if (deaths != null)
		{
			out.add(deaths);
		}
		if (gallery != null)
		{
			out.add(gallery);
		}
		for (Scenes.Scene s : extras)
		{
			if (s instanceof MoreScenes.Duel || s instanceof MoreScenes.Pace)
			{
				out.add(s);
			}
		}
		if (persona != null)
		{
			out.add(persona);
		}
		if (outro != null)
		{
			out.add(outro);
		}
		if (out.isEmpty())
		{
			out.add(new Scenes.Card("outro", look, Math.min(2.5, Math.max(1.5, max)), null, Recap.fill(config.outroText(), tok), null));
		}

		// Footage behind the stat, title and text scenes.
		if (backdrop || (!green.isEmpty() && placement == GielinorWrappedConfig.ChromaPlacement.OVER_STATS))
		{
			List<Scenes.Scene> behind = new ArrayList<>();
			List<Scenes.Scene> statScenes = new ArrayList<>();
			for (Scenes.Scene s : out)
			{
				if (s instanceof Scenes.Stat)
				{
					statScenes.add(s);
				}
				if (s instanceof Scenes.Stat || s instanceof Scenes.Title || (s instanceof Scenes.Card && ((Scenes.Card) s).backdrop == null))
				{
					behind.add(s);
				}
			}
			if (backdrop)
			{
				attach(media, plain, behind, null, false);
			}
			if (placement == GielinorWrappedConfig.ChromaPlacement.OVER_STATS)
			{
				attach(media, green, statScenes, chroma, true);
			}
		}

		placeStickers(out, r, media, config, look);
		return out;
	}

	/** Gives each scene a stretch of footage as long as the scene, spread over the recordings. */
	private static void attach(Media media, List<ClipLibrary.Recording> recs, List<Scenes.Scene> targets, Chroma chroma, boolean over)
	{
		if (recs.isEmpty() || targets.isEmpty())
		{
			return;
		}
		long[] lengths = new long[targets.size()];
		for (int i = 0; i < lengths.length; i++)
		{
			lengths[i] = Math.round(targets.get(i).duration * 1000) + 500;
		}
		List<Cut> list = cuts(recs, lengths, 0.0);
		for (int i = 0; i < list.size() && i < targets.size(); i++)
		{
			Scenes.Reel reel = reel(media, list.get(i), chroma);
			if (over)
			{
				targets.get(i).over = reel;
			}
			else
			{
				targets.get(i).under = reel;
			}
		}
	}

	private static Scenes.Reel reel(Media media, Cut cut, Chroma chroma)
	{
		String name = cut.name;
		return new Scenes.Reel((from, to) -> media.recording(name, from, to), cut.from, cut.to, chroma);
	}

	/** Whether a recording was shot on a green screen: most of a frame from its middle keys out. */
	static boolean isGreen(Media media, ClipLibrary.Recording rec, Chroma chroma)
	{
		long mid = rec.start + rec.length / 2;
		List<ClipFile.Frame> frames = media.recording(rec.name, mid, mid);
		if (frames.isEmpty())
		{
			return false;
		}
		try
		{
			BufferedImage img = frames.get(0).image();
			return img != null && chroma.coverage(img) > 0.25;
		}
		catch (IOException e)
		{
			return false;
		}
	}

	/** A stretch of one recording, in capture times. */
	static final class Cut
	{
		final String name;
		final long from;
		final long to;

		Cut(String name, long from, long to)
		{
			this.name = name;
			this.from = from;
			this.to = to;
		}
	}

	/** How many cuts of {@code segment} ms fit, up to {@code max}. */
	static long[] count(List<ClipLibrary.Recording> recs, int max, long segment)
	{
		long total = 0;
		for (ClipLibrary.Recording rec : recs)
		{
			total += rec.length;
		}
		int n = total < 1000 || max <= 0 ? 0 :(int) Math.max(1, Math.min(max, total / Math.max(1, segment)));
		long[] out = new long[n];
		java.util.Arrays.fill(out, segment);
		return out;
	}

	/**
	 * Picks stretches of the given lengths spread evenly over all the recordings laid end to end. Cut
	 * {@code i} centres on {@code (i + phase) / n} of the way through; each stays inside one recording.
	 */
	static List<Cut> cuts(List<ClipLibrary.Recording> recs, long[] lengths, double phase)
	{
		List<Cut> out = new ArrayList<>();
		long total = 0;
		for (ClipLibrary.Recording rec : recs)
		{
			total += rec.length;
		}
		int n = lengths.length;
		if (total <= 0 || n == 0)
		{
			return out;
		}
		for (int i = 0; i < n; i++)
		{
			long pos = (long) ((i + phase) * total / n);
			long cum = 0;
			for (ClipLibrary.Recording rec : recs)
			{
				if (pos < cum + rec.length || rec == recs.get(recs.size() - 1))
				{
					long seg = Math.min(lengths[i], rec.length);
					long local = Math.max(0, Math.min(rec.length - seg, pos - cum - seg / 2));
					out.add(new Cut(rec.name, rec.start + local, rec.start + local + seg));
					break;
				}
				cum += rec.length;
			}
		}
		return out;
	}

	/** Spreads {@code extra} evenly between the scenes of {@code base}, never first. */
	static List<Scenes.Scene> spread(List<Scenes.Scene> base, List<? extends Scenes.Scene> extra)
	{
		if (extra.isEmpty())
		{
			return base;
		}
		List<Scenes.Scene> out = new ArrayList<>();
		int m = base.size();
		int n = extra.size();
		int e = 0;
		for (int i = 0; i < m; i++)
		{
			out.add(base.get(i));
			while (e < n && (long) (e + 1) * m / (n + 1) <= i)
			{
				out.add(extra.get(e++));
			}
		}
		while (e < n)
		{
			out.add(extra.get(e++));
		}
		return out;
	}

	private static final double[][] SLOTS = {{0.88, 0.2}, {0.12, 0.8}, {0.88, 0.8}, {0.12, 0.2}};

	/**
	 * Slaps boss cutouts, game sprites and the player's own stickers onto the scenes: the ones placed on the
	 * sticker board, and picked automatically for the scenes the player didn't arrange.
	 */
	static void placeStickers(List<Scenes.Scene> scenes, Recap r, Media media, GielinorWrappedConfig config, Scenes.Look look)
	{
		List<Sticker> all = media.stickers();
		StickerBoard board = media.stickerBoard();
		Map<String, Sticker> byKey = new HashMap<>();
		for (Sticker s : all)
		{
			String key = StickerBoard.key(s);
			if (key != null)
			{
				byKey.putIfAbsent(key, s);
			}
		}
		boolean icons = config.iconStickers();
		BufferedImage helmet = media.badge(r.accountType);
		BufferedImage badgeArt = Sprites.splash(helmet, 160, look.theme.accent);
		Sticker badge = badgeArt == null ? null : Sticker.still("badge", Sticker.Kind.GAME, badgeArt);
		if (all.isEmpty() && !icons && badge == null)
		{
			return;
		}
		Map<String, Sticker> npcs = new HashMap<>();
		List<Sticker> mine = new ArrayList<>();
		for (Sticker s : all)
		{
			if (s.kind == Sticker.Kind.NPC && config.bossStickers())
			{
				npcs.putIfAbsent(ClipLibrary.safeName(s.name), s);
			}
			else if (s.kind == Sticker.Kind.MINE && config.myStickers())
			{
				mine.add(s);
			}
		}
		double size = 0.2 * config.stickerSize() / 100.0;
		int mineNext = 0;
		int index = 0;
		for (Scenes.Scene scene : scenes)
		{
			List<StickerBoard.Spot> spots = board.on(scene.name);
			spots.addAll(board.on(StickerBoard.EVERY));
			for (int i = 0; i < spots.size(); i++)
			{
				StickerBoard.Spot spot = spots.get(i);
				Sticker st = byKey.get(spot.sticker);
				if (st != null)
				{
					scene.stickers.add(new Scenes.Placement(st, spot.x, spot.y, spot.size, spot.angle, 0.35 + 0.15 * i, index * 1.7 + i));
				}
			}
			if (!board.autoFill || board.arranged(scene.name))
			{
				continue;
			}
			List<Sticker> picks = new ArrayList<>();
			if (badge != null && ("title".equals(scene.name) || "outro".equals(scene.name)))
			{
				picks.add(badge);
			}
			if ("kills".equals(scene.name))
			{
				addNpcs(picks, npcs, r.kills, 2);
			}
			else if ("loot".equals(scene.name))
			{
				addNpcs(picks, npcs, r.lootSources, 1);
				if (icons)
				{
					List<BufferedImage> drops = dropIcons(r, media);
					for (int i = 0; i < drops.size() && picks.size() < 2; i++)
					{
						picks.add(Sticker.still("drop", Sticker.Kind.GAME, Sprites.splash(drops.get(i), 160, look.theme.accent)));
					}
				}
			}
			else if ("xp".equals(scene.name) && icons && !r.skills.isEmpty())
			{
				BufferedImage skill = media.skill(r.skills.get(0).name);
				if (skill != null)
				{
					picks.add(Sticker.still("skill", Sticker.Kind.GAME, Sprites.splash(skill, 160, look.theme.accent)));
				}
			}
			else if (scene instanceof Scenes.Clip && ((Scenes.Clip) scene).moment != null)
			{
				Moment m = ((Scenes.Clip) scene).moment;
				String text = ClipLibrary.safeName((m.title == null ? "" : m.title) + " " + (m.detail == null ? "" : m.detail));
				for (Map.Entry<String, Sticker> e : npcs.entrySet())
				{
					if (text.contains(e.getKey()))
					{
						picks.add(e.getValue());
						break;
					}
				}
			}
			if (!mine.isEmpty() && !(scene instanceof Scenes.SplashTitle) && !(scene instanceof Scenes.Gallery) && picks.size() < 2)
			{
				picks.add(mine.get(mineNext++ % mine.size()));
			}
			for (int i = 0; i < picks.size(); i++)
			{
				double[] slot = SLOTS[(index + i) % SLOTS.length];
				double angle = ((index + i) % 2 == 0 ? 1 : -1) * (0.08 + 0.05 * ((index * 7 + i) % 3));
				double s = picks.get(i).kind == Sticker.Kind.GAME ? size * 0.8 : size;
				scene.stickers.add(new Scenes.Placement(picks.get(i), slot[0], slot[1], s, angle, 0.35 + 0.3 * i, index * 1.7 + i));
			}
			if (!picks.isEmpty())
			{
				index++;
			}
		}
	}

	private static BufferedImage trimmed(BufferedImage img)
	{
		return img == null ? null : Sprites.trim(img);
	}

	private static void addNpcs(List<Sticker> picks, Map<String, Sticker> npcs, List<Recap.Entry> entries, int max)
	{
		if (entries == null)
		{
			return;
		}
		for (Recap.Entry e : Recap.top(entries, 5))
		{
			Sticker s = npcs.get(ClipLibrary.safeName(e.name));
			if (s != null && !picks.contains(s))
			{
				picks.add(s);
				if (picks.size() >= max)
				{
					return;
				}
			}
		}
	}

	/**
	 * The player's own captions, one per line: {@code title:}, {@code outro:}, {@code footage:} and
	 * {@code share:} lines go to those places, anything else (or {@code card:}) becomes a text card.
	 * A {@code |} splits a line into a big and a small part.
	 */
	static final class CustomCaptions
	{
		final List<String> cards = new ArrayList<>();
		final List<String> footage = new ArrayList<>();
		String title;
		String outro;
		String share;

		static CustomCaptions parse(String text)
		{
			CustomCaptions c = new CustomCaptions();
			if (text == null)
			{
				return c;
			}
			for (String raw : text.split("\\r?\\n"))
			{
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				int colon = line.indexOf(':');
				String key = colon > 0 ? line.substring(0, colon).trim().toLowerCase(Locale.ROOT) : "";
				String value = colon > 0 ? line.substring(colon + 1).trim() : line;
				switch (key)
				{
					case "title":
						c.title = value;
						break;
					case "outro":
						c.outro = value;
						break;
					case "footage":
						c.footage.add(value);
						break;
					case "share":
						c.share = value;
						break;
					case "card":
						c.cards.add(value);
						break;
					default:
						c.cards.add(line);
				}
			}
			return c;
		}

		/** The big part and the small part after {@code |}, which is null when there isn't one. */
		static String[] split(String line)
		{
			int bar = line.indexOf('|');
			if (bar < 0)
			{
				return new String[]{line.trim(), null};
			}
			String small = line.substring(bar + 1).trim();
			return new String[]{line.substring(0, bar).trim(), small.isEmpty() ? null : small};
		}
	}

	/** The extra scenes switched on and with something to show, most important first. */
	static List<Scenes.Scene> extras(Recap r, Media media, GielinorWrappedConfig config, Scenes.Look look, double pace, Map<String, String> tok)
	{
		List<Scenes.Scene> out = new ArrayList<>();
		FriendFile friend = config.versusScene() ? media.friend() : null;
		if (friend != null)
		{
			MoreScenes.Duel duel = MoreScenes.Duel.versus(look, 5 * pace, r, friend, config.hideName() ? "You" : tok.get("player"));
			duel.leftBadge = trimmed(media.badge(r.accountType));
			duel.rightBadge = trimmed(media.badge(friend.type()));
			out.add(duel);
		}
		if (config.compareScene() && r.comparisons.size() >= 2)
		{
			out.add(MoreScenes.Duel.compare(look, 4.5 * pace, r, r.comparisons));
		}
		if (config.paceScene() && !r.paces.isEmpty())
		{
			List<BufferedImage> icons = new ArrayList<>();
			for (Recap.Pace p : r.paces)
			{
				icons.add(media.skill(p.skill));
			}
			out.add(new MoreScenes.Pace(look, 3.5 * pace, r.paces, icons));
		}
		if (config.mapScene() && !r.regions.isEmpty())
		{
			MoreScenes.WorldMap map = new MoreScenes.WorldMap(look, 4 * pace, r.regions);
			if (map.hasData())
			{
				out.add(map);
			}
		}
		if (config.graphScene())
		{
			MoreScenes.Graphs graphs = MoreScenes.Graphs.of(look, 4 * pace, r);
			if (graphs != null)
			{
				out.add(graphs);
			}
		}
		if (config.bossScene() && (!r.bosses.isEmpty() || r.total.slayerTasks > 0))
		{
			Map<String, BufferedImage> cutouts = new HashMap<>();
			if (!r.bosses.isEmpty() && config.bossStickers())
			{
				for (Sticker st : media.stickers())
				{
					if (st.kind == Sticker.Kind.NPC && !st.frames.isEmpty())
					{
						cutouts.putIfAbsent(ClipLibrary.safeName(st.name), st.frameAt(0));
					}
				}
			}
			out.add(new MoreScenes.Bosses(look, 4 * pace, r.bosses, r.fastest, cutouts, r.total.slayerTasks));
		}
		if (config.collectionScene() && (!r.collection.isEmpty() || r.combatTasks > 0 || r.diaryTasks > 0))
		{
			List<BufferedImage> icons = new ArrayList<>();
			for (Moment m : r.collection)
			{
				BufferedImage img = m.itemId > 0 ? media.item(m.itemId) : null;
				if (img != null && icons.size() < MoreScenes.Collection.MAX_ICONS)
				{
					icons.add(img);
				}
			}
			out.add(new MoreScenes.Collection(look, 3.5 * pace, icons, r.collection.size(), r.combatTasks, r.diaryTasks));
		}
		if (config.geScene() && r.hasGe())
		{
			List<DayRecord.Trade> trades = new ArrayList<>(r.trades);
			trades.sort((a, b) -> Long.compare(b.value, a.value));
			List<BufferedImage> icons = new ArrayList<>();
			for (int i = 0; i < Math.min(3, trades.size()); i++)
			{
				icons.add(trades.get(i).itemId > 0 ? media.item(trades.get(i).itemId) : null);
			}
			out.add(new MoreScenes.Exchange(look, 4 * pace, r.total.geBought, r.total.geSold, trades, icons));
		}
		if (config.montageScene() && r.months.size() >= MoreScenes.Montage.MIN_MONTHS)
		{
			out.add(new MoreScenes.Montage(look, r.months.size() * 2.5 * pace, r.months, media::clip));
		}
		return out;
	}

	private static double sum(List<? extends Scenes.Scene> scenes)
	{
		double s = 0;
		for (Scenes.Scene x : scenes)
		{
			s += x.duration;
		}
		return s;
	}

	private static double sum(Scenes.Scene... singles)
	{
		double s = 0;
		for (Scenes.Scene x : singles)
		{
			if (x != null)
			{
				s += x.duration;
			}
		}
		return s;
	}

	private static List<BufferedImage> rowIcons(List<Recap.Entry> entries, java.util.function.Function<String, BufferedImage> icon)
	{
		List<BufferedImage> out = new ArrayList<>();
		for (Recap.Entry e : Recap.top(entries, 5))
		{
			out.add(icon.apply(e.name));
		}
		return out;
	}

	/** Sprites of the most valuable drops, for the loot scene. */
	static List<BufferedImage> dropIcons(Recap r, Media media)
	{
		List<BufferedImage> out = new ArrayList<>();
		for (int id : Splash.featuredItems(r, 6))
		{
			BufferedImage img = media.item(id);
			if (img != null)
			{
				out.add(img);
			}
		}
		return out;
	}

	/** The item for drops, pets and log slots; the skill for level ups. */
	static BufferedImage momentIcon(Moment m, Media media)
	{
		if (m.itemId > 0)
		{
			return media.item(m.itemId);
		}
		if ((m.type == Moment.Type.LEVEL_UP || m.type == Moment.Type.MAX_LEVEL) && m.title != null && m.title.indexOf(' ') > 0)
		{
			return media.skill(m.title.substring(m.title.indexOf(' ') + 1));
		}
		return null;
	}

	/** The deaths scene, or null without deaths. Over the limit, the costliest deaths are kept, still in order. */
	static Scenes.Deaths deathsScene(Media media, GielinorWrappedConfig config, Scenes.Look look, String player)
	{
		List<DeathLog.Entry> all = media.deaths();
		if (all.isEmpty())
		{
			return null;
		}
		List<DeathLog.Entry> picked = new ArrayList<>(all);
		int max = Math.max(1, config.deathMax());
		if (picked.size() > max)
		{
			picked.sort(Comparator.comparingLong((DeathLog.Entry e) -> e.gpLost).thenComparingInt(e -> e.damage).reversed());
			picked = new ArrayList<>(picked.subList(0, max));
			picked.sort(Comparator.comparingLong(e -> e.time));
		}
		List<Scenes.Death> list = new ArrayList<>();
		for (DeathLog.Entry e : picked)
		{
			String card = e.card;
			String shot = e.shot;
			list.add(new Scenes.Death(card == null ? null : () -> media.deathImage(card), shot == null ? null : () -> media.deathImage(shot),
				e.killer, Recap.fill(config.deathText(), deathTokens(e, player))));
		}
		return new Scenes.Deaths(look, list);
	}

	static Map<String, String> deathTokens(DeathLog.Entry e, String player)
	{
		Map<String, String> t = new HashMap<>();
		ZonedDateTime at = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault());
		t.put("killer", e.killer != null ? e.killer : "Unknown");
		t.put("place", e.place != null ? e.place : (e.world > 0 ? "World " + e.world : "Gielinor"));
		t.put("gpLost", e.gpLost >= 0 ? Recap.gp(e.gpLost) : "?");
		t.put("damage", e.damage >= 0 ? Recap.number(e.damage) : "?");
		t.put("world", e.world > 0 ? String.valueOf(e.world) : "?");
		t.put("date", MOMENT_DATE.format(at));
		t.put("time", MOMENT_TIME.format(at));
		t.put("player", player);
		return t;
	}

	static Map<String, String> momentTokens(Moment m)
	{
		Map<String, String> t = new HashMap<>();
		t.put("title", m.title == null ? "" : m.title);
		t.put("detail", m.detail == null ? "" : m.detail);
		String value;
		switch (m.type)
		{
			case DROP:
				value = Recap.gp(m.value);
				break;
			case LEVEL_UP:
			case MAX_LEVEL:
				value = "Level " + m.value;
				break;
			default:
				value = m.value > 0 ? Recap.number(m.value) : "";
		}
		t.put("value", value);
		Instant at = Instant.ofEpochMilli(m.time);
		t.put("date", MOMENT_DATE.format(at.atZone(ZoneId.systemDefault())));
		t.put("time", MOMENT_TIME.format(at.atZone(ZoneId.systemDefault())));
		return t;
	}

	double duration()
	{
		double s = 0;
		for (Scenes.Scene x : scenes)
		{
			s += x.duration;
		}
		return s;
	}

	int frameCount()
	{
		int n = 0;
		for (Scenes.Scene s : scenes)
		{
			n += frames(s);
		}
		return n;
	}

	private int frames(Scenes.Scene s)
	{
		return Math.max(1, (int) Math.round(s.duration * fps));
	}

	/**
	 * Renders and encodes the video.
	 *
	 * @param progress receives the fraction done, from 0 to 1
	 * @param cancelled polled every frame; rendering stops with {@link InterruptedIOException} when it returns true
	 */
	void render(Output output, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException
	{
		if (format == GielinorWrappedConfig.VideoFormat.GIF)
		{
			try (GifWriter gif = new GifWriter(new BufferedOutputStream(output.result()), width, height))
			{
				int[] index = {0};
				renderFrames((px, key) ->
				{
					int i = index[0]++;
					gif.add(px, (int) (Math.round((i + 1) * 100.0 / fps) - Math.round(i * 100.0 / fps)));
				}, progress, cancelled);
				gif.finish();
			}
			return;
		}

		Music music = song != null && musicVolume > 0 ? music() : null;
		long videoBytes = targetBytes;
		if (music != null && videoBytes > 0)
		{
			videoBytes = Math.max(videoBytes / 4, videoBytes - music.bytes);
		}
		// First pass at good quality. Under a size limit that it overshoots, a second pass re-encodes at the
		// even quality the first pass says fits, rather than starving whichever scenes come last.
		int[] firstSizes = new int[frameCount()];
		boolean limited = videoBytes > 0;
		Mp4Writer mp4 = encodePass(output, FIRST_QP, null, firstSizes, music,
			limited ? p -> progress.accept(p / 2) : progress, cancelled);
		long first = 0;
		for (int s : firstSizes)
		{
			first += s;
		}
		if (limited && first > videoBytes)
		{
			RateControl rc = new RateControl(videoBytes, firstSizes, fps, RateControl.startQp(first, videoBytes, FIRST_QP));
			mp4 = encodePass(output, rc.qp(), rc, new int[firstSizes.length], music, p -> progress.accept(0.5 + p / 2), cancelled);
		}
		else
		{
			progress.accept(1);
		}
		try (OutputStream out = new BufferedOutputStream(output.result(), 1 << 16);
			InputStream in = new BufferedInputStream(output.readScratch(), 1 << 16))
		{
			mp4.finish(out, in);
		}
	}

	/**
	 * Renders every frame into a fresh encoder and MP4 track, writing the media to scratch.
	 *
	 * @param rc steers qp frame by frame, or null for a constant qp
	 * @param sizes receives each frame's encoded size
	 */
	private Mp4Writer encodePass(Output output, int qp, RateControl rc, int[] sizes, Music music, DoubleConsumer progress,
		BooleanSupplier cancelled) throws IOException
	{
		H264Encoder encoder = new H264Encoder(width, height, fps);
		encoder.setKeyInterval(fps * 2);
		encoder.setQp(qp);
		Mp4Writer mp4 = new Mp4Writer(width, height, fps, encoder.sps(), encoder.pps());
		if (music != null)
		{
			mp4.setAudio(music.config, 2, AUDIO_BITRATE, music.frames, AacEncoder.PRIMING, music.samples);
		}
		int[] index = {0};
		try (OutputStream media = new BufferedOutputStream(output.scratch(), 1 << 16))
		{
			renderFrames((px, key) ->
			{
				H264Encoder.Frame f = encoder.encode(px, key);
				mp4.addFrame(f, media);
				int i = index[0]++;
				if (i < sizes.length)
				{
					sizes[i] = f.nal.length;
				}
				if (rc != null)
				{
					encoder.setQp(rc.next(f.nal.length));
				}
			}, progress, cancelled);
		}
		return mp4;
	}

	/** The song as AAC frames, ready for any number of MP4 tracks. */
	private static final class Music
	{
		final byte[] config;
		final List<byte[]> frames;
		final int samples;
		final long bytes;

		Music(byte[] config, List<byte[]> frames, int samples)
		{
			this.config = config;
			this.frames = frames;
			this.samples = samples;
			long b = 0;
			for (byte[] f : frames)
			{
				b += f.length;
			}
			this.bytes = b;
		}
	}

	/** Synthesizes the song to the video's length and encodes it as AAC, or null if it failed. */
	private Music music()
	{
		try
		{
			Midi midi = Midi.parse(song);
			if (midi.size() == 0)
			{
				return null;
			}
			double seconds = frameCount() / (double) fps;
			float[][] pcm = Synth.render(midi, seconds, musicVolume, musicFade);
			AacEncoder aac = new AacEncoder(2, AUDIO_BITRATE);
			return new Music(aac.audioSpecificConfig(), aac.encode(pcm[0], pcm[1]), pcm[0].length);
		}
		catch (IllegalArgumentException e)
		{
			// A track the converter doesn't understand: make the video without music rather than fail.
			return null;
		}
	}

	private interface FrameSink
	{
		void accept(int[] argb, boolean sceneStart) throws IOException;
	}

	private void renderFrames(FrameSink sink, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException
	{
		BufferedImage cur = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] curPx = ((DataBufferInt) cur.getRaster().getDataBuffer()).getData();
		int[] outPx = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
		BufferedImage last = null;
		int total = frameCount();
		int done = 0;
		for (int si = 0; si < scenes.size(); si++)
		{
			Scenes.Scene scene = scenes.get(si);
			GielinorWrappedConfig.Transition tr = transitionFor(si);
			scene.openAll();
			try
			{
				int n = frames(scene);
				for (int f = 0; f < n; f++)
				{
					if (cancelled.getAsBoolean())
					{
						throw new InterruptedIOException("Recap cancelled");
					}
					double t = f / (double) fps;
					drawScene(cur, scene, t);

					boolean blending = last != null && tr != GielinorWrappedConfig.Transition.CUT && t < fade;
					if (blending)
					{
						Graphics2D o = out.createGraphics();
						hints(o);
						blend(o, tr, last, cur, Captions.clamp(t / fade));
						o.dispose();
					}
					else
					{
						System.arraycopy(curPx, 0, outPx, 0, curPx.length);
					}
					if (f == n - 1)
					{
						if (last == null)
						{
							last = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
						}
						System.arraycopy(outPx, 0, ((DataBufferInt) last.getRaster().getDataBuffer()).getData(), 0, outPx.length);
					}
					theme.finish(out, done);
					drawOverlays(out, done / (double) Math.max(1, total - 1));
					drawWatermark(out);
					sink.accept(outPx, f == 0 && !blending);
					progress.accept(++done / (double) total);
				}
			}
			finally
			{
				scene.closeAll();
			}
		}
	}

	private static final GielinorWrappedConfig.Transition[] MIX = {
		GielinorWrappedConfig.Transition.FADE, GielinorWrappedConfig.Transition.SLIDE, GielinorWrappedConfig.Transition.ZOOM,
		GielinorWrappedConfig.Transition.WIPE, GielinorWrappedConfig.Transition.PIXELATE, GielinorWrappedConfig.Transition.FLASH,
		GielinorWrappedConfig.Transition.IRIS, GielinorWrappedConfig.Transition.SPIN};

	/** The transition into scene {@code index}; Mixed takes a different one each time. */
	GielinorWrappedConfig.Transition transitionFor(int index)
	{
		return transition == GielinorWrappedConfig.Transition.MIXED ? MIX[Math.floorMod(index - 1, MIX.length)] : transition;
	}

	/** Draws one frame of an opened scene, laid out between the cinema bars when they are on so no text ends up under them. */
	void drawScene(BufferedImage img, Scenes.Scene scene, double t)
	{
		Graphics2D g = img.createGraphics();
		hints(g);
		if (bar > 0)
		{
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, width, height);
			g.translate(0, bar);
			g.clipRect(0, 0, width, height - 2 * bar);
		}
		scene.render(g, width, height - 2 * bar, t);
		scene.foreground(g, width, height - 2 * bar, t, wobble);
		g.dispose();
	}

	static int barHeight(int height)
	{
		return (int) Math.round(height * 0.075);
	}

	/** Letterbox bars and the progress bar. */
	private void drawOverlays(BufferedImage img, double progress)
	{
		if (!letterbox && !progressBar)
		{
			return;
		}
		Graphics2D g = img.createGraphics();
		int u = Math.min(width, height);
		if (letterbox)
		{
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, width, bar);
			g.fillRect(0, height - bar, width, bar);
		}
		if (progressBar)
		{
			int h = Math.max(3, u / 160);
			g.setColor(new Color(0, 0, 0, 90));
			g.fillRect(0, height - h, width, h);
			g.setColor(theme.accent);
			g.fillRect(0, height - h, (int) Math.round(width * Captions.clamp(progress)), h);
		}
		g.dispose();
	}

	private void blend(Graphics2D g, GielinorWrappedConfig.Transition tr, BufferedImage from, BufferedImage to, double p)
	{
		double e = p * p * (3 - 2 * p);
		int u = Math.min(width, height);
		switch (tr)
		{
			case SLIDE:
			{
				int dx = (int) Math.round(e * width);
				g.drawImage(from, -dx, 0, null);
				g.drawImage(to, width - dx, 0, null);
				break;
			}
			case ZOOM:
			{
				g.drawImage(to, 0, 0, null);
				double s = 1 + 0.3 * e;
				AffineTransform at = new AffineTransform(s, 0, 0, s, width * (1 - s) / 2, height * (1 - s) / 2);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (1 - e)));
				g.drawImage(from, at, null);
				break;
			}
			case WIPE:
			{
				// A slanted edge sweeping left to right, with an accent stripe on it.
				g.drawImage(from, 0, 0, null);
				double slant = height * 0.25;
				double x = e * (width + slant);
				java.awt.geom.Path2D.Double edge = new java.awt.geom.Path2D.Double();
				edge.moveTo(0, 0);
				edge.lineTo(x, 0);
				edge.lineTo(x - slant, height);
				edge.lineTo(0, height);
				edge.closePath();
				java.awt.Shape old = g.getClip();
				g.clip(edge);
				g.drawImage(to, 0, 0, null);
				g.setClip(old);
				g.setColor(theme.accent);
				g.setStroke(new java.awt.BasicStroke(Math.max(3f, u / 70f)));
				g.draw(new java.awt.geom.Line2D.Double(x, 0, x - slant, height));
				break;
			}
			case PIXELATE:
			{
				// Blocks grow on the old scene, then shrink away on the new one.
				boolean out = p < 0.5;
				double k = out ? p * 2 : (1 - p) * 2;
				int block = Math.max(1, (int) Math.round(1 + k * k * u / 14.0));
				BufferedImage src = out ? from : to;
				if (block <= 1)
				{
					g.drawImage(src, 0, 0, null);
					break;
				}
				int sw = Math.max(1, width / block);
				int sh = Math.max(1, height / block);
				BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
				Graphics2D sg = small.createGraphics();
				sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				sg.drawImage(src, 0, 0, sw, sh, null);
				sg.dispose();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
				g.drawImage(small, 0, 0, width, height, null);
				break;
			}
			case FLASH:
			{
				g.drawImage(p < 0.5 ? from : to, 0, 0, null);
				double a = p < 0.5 ? p * 2 : (1 - p) * 2;
				g.setColor(new Color(255, 255, 255, (int) Math.round(255 * Captions.clamp(a * 1.1))));
				g.fillRect(0, 0, width, height);
				break;
			}
			case IRIS:
			{
				g.drawImage(from, 0, 0, null);
				double r = e * Math.hypot(width, height) / 2;
				java.awt.Shape old = g.getClip();
				g.clip(new java.awt.geom.Ellipse2D.Double(width / 2.0 - r, height / 2.0 - r, r * 2, r * 2));
				g.drawImage(to, 0, 0, null);
				g.setClip(old);
				break;
			}
			case SPIN:
			{
				g.drawImage(to, 0, 0, null);
				double s = 1 - e;
				if (s > 0.01)
				{
					AffineTransform at = new AffineTransform();
					at.translate(width / 2.0, height / 2.0);
					at.rotate(e * Math.PI * 0.75);
					at.scale(s, s);
					at.translate(-width / 2.0, -height / 2.0);
					g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Captions.clamp(1.2 - e)));
					g.drawImage(from, at, null);
				}
				break;
			}
			default:
				g.drawImage(to, 0, 0, null);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (1 - e)));
				g.drawImage(from, 0, 0, null);
		}
	}

	private void drawWatermark(BufferedImage img)
	{
		if (watermark == null || watermark.trim().isEmpty())
		{
			return;
		}
		Graphics2D g = img.createGraphics();
		hints(g);
		int u = Math.min(width, height);
		Font f = theme.bold(Math.max(10f, u * 0.028f));
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int x = width - fm.stringWidth(watermark) - u / 40;
		int y = height - u / 40 - fm.getDescent();
		g.setColor(new Color(0, 0, 0, 110));
		g.drawString(watermark, x + 1, y + 1);
		g.setColor(Theme.alpha(theme.light() ? theme.muted : Color.WHITE, 0.7));
		g.drawString(watermark, x, y);
		g.dispose();
	}

	static void hints(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	/**
	 * Second-pass rate control. The first pass gives every frame's size at one qp, so the frames still to come
	 * are known in advance: every half second it scales their first-pass sizes by how the second pass compares so
	 * far, and moves qp a step only when that projection would miss the budget. The qp stays nearly constant, so
	 * quality stays even across the video.
	 */
	static final class RateControl
	{
		private final long budget;
		private final int[] firstSizes;
		private final long[] firstBefore;
		private final int window;
		private int qp;
		private long spent;
		private long windowSpent;
		private int frames;

		RateControl(long budget, int[] firstSizes, int fps, int qp)
		{
			this.budget = budget;
			this.firstSizes = firstSizes;
			this.firstBefore = new long[firstSizes.length + 1];
			for (int i = 0; i < firstSizes.length; i++)
			{
				firstBefore[i + 1] = firstBefore[i] + firstSizes[i];
			}
			this.window = Math.max(1, fps / 2);
			this.qp = Math.max(MIN_QP, Math.min(MAX_QP, qp));
		}

		/** The qp a second pass should start at: about 6 qp halves the size. */
		static int startQp(long firstBytes, long budget, int firstQp)
		{
			if (budget <= 0 || firstBytes <= budget)
			{
				return firstQp;
			}
			double steps = 6 * Math.log((double) firstBytes / budget) / Math.log(2);
			return Math.max(MIN_QP, Math.min(MAX_QP, firstQp + (int) Math.ceil(steps)));
		}

		int qp()
		{
			return qp;
		}

		/** Records a frame's size and returns the qp for the next frame. */
		int next(int bytes)
		{
			spent += bytes;
			windowSpent += bytes;
			frames++;
			if (frames % window != 0 || frames >= firstSizes.length)
			{
				return qp;
			}
			long firstWindow = firstBefore[frames] - firstBefore[frames - window];
			long firstSoFar = firstBefore[frames];
			// How this pass's sizes compare with the first pass's: the last half second shows the current qp best,
			// unless it was nearly all skipped blocks.
			double scale = firstWindow > window * 200L ? windowSpent / (double) firstWindow
				: firstSoFar > 0 ? spent / (double) firstSoFar : 1;
			windowSpent = 0;
			double projected = spent + (firstBefore[firstSizes.length] - firstSoFar) * scale;
			double ratio = projected / budget;
			if (ratio > 1.3)
			{
				qp += 2;
			}
			else if (ratio > 1.02)
			{
				qp++;
			}
			else if (ratio < 0.85)
			{
				qp--;
			}
			qp = Math.max(MIN_QP, Math.min(MAX_QP, qp));
			return qp;
		}
	}
}
