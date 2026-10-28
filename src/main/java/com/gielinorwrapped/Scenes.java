package com.gielinorwrapped;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** The scenes a recap video is made of. Each draws itself at a time {@code t} seconds into the scene. */
final class Scenes
{
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

	private Scenes()
	{
	}

	/** How text is drawn, shared by every scene. */
	static final class Look
	{
		final Theme theme;
		final GielinorWrappedConfig.CaptionStyle style;
		final GielinorWrappedConfig.CaptionPosition position;
		final float captionScale;
		final boolean captions;

		Look(Theme theme, GielinorWrappedConfig.CaptionStyle style, GielinorWrappedConfig.CaptionPosition position, float captionScale, boolean captions)
		{
			this.theme = theme;
			this.style = style;
			this.position = position;
			this.captionScale = captionScale;
			this.captions = captions;
		}

		static Look of(GielinorWrappedConfig config)
		{
			return new Look(Theme.of(config), config.captionStyle(), config.captionPosition(), config.captionSize() / 100f, config.captionsEnabled());
		}

		float captionSize(int w, int h)
		{
			return Math.min(w, h) * 0.06f * captionScale;
		}
	}

	abstract static class Scene
	{
		final String name;
		final Look look;
		double duration;

		Scene(String name, Look look, double duration)
		{
			this.name = name;
			this.look = look;
			this.duration = duration;
		}

		/** Loads what the scene needs just before it plays. */
		void open() throws IOException
		{
		}

		/** Frees what {@link #open} loaded. */
		void close()
		{
		}

		abstract void render(Graphics2D g, int w, int h, double t);

		/** Scenes that can't be shortened report their own length here. */
		double minDuration()
		{
			return duration;
		}

		/** Footage drawn darkened behind the scene, or null for the theme background. */
		Reel under;
		/** Green-screened footage drawn over the scene, or null. */
		Reel over;
		/** Stickers slapped on the scene. */
		final List<Placement> stickers = new ArrayList<>();

		/** Opens the scene and its reels. */
		final void openAll() throws IOException
		{
			open();
			if (under != null)
			{
				under.open();
			}
			if (over != null)
			{
				over.open();
			}
		}

		final void closeAll()
		{
			close();
			if (under != null)
			{
				under.close();
			}
			if (over != null)
			{
				over.close();
			}
		}

		/** The theme background, or footage behind it when the scene has some. */
		void background(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			BufferedImage img = under == null ? null : under.at(t);
			if (img == null)
			{
				th.background(g, w, h, t);
				return;
			}
			if (under.keyed())
			{
				th.background(g, w, h, t);
				drawCover(g, img, 0, 0, w, h, 1.0);
				return;
			}
			drawCover(g, img, 0, 0, w, h, 1.04 + 0.03 * Captions.clamp(t / Math.max(0.1, duration)));
			// Darken and tint so the text on top stays readable.
			g.setColor(Theme.alpha(th.top, th.light() ? 0.72 : 0.62));
			g.fillRect(0, 0, w, h);
		}

		/** Keyed footage and stickers, drawn over whatever {@link #render} drew. */
		void foreground(Graphics2D g, int w, int h, double t, boolean wobble)
		{
			BufferedImage img = over == null ? null : over.at(t);
			if (img != null)
			{
				// The player stands in the lower part of the frame, under the heading.
				int bh = (int) (h * 0.62);
				int bw = (int) (bh * img.getWidth() / (double) img.getHeight());
				drawCover(g, img, w - bw, h - bh, bw, bh, 1.0);
			}
			int u = Math.min(w, h);
			for (Placement p : stickers)
			{
				p.draw(g, w, h, u, t, duration, wobble);
			}
		}

		/** Draws a caption that appears {@code start} seconds into the scene and stays to its end. */
		void caption(Graphics2D g, String text, Rectangle area, GielinorWrappedConfig.CaptionPosition position, double t, double start, int w, int h)
		{
			if (look.captions)
			{
				Captions.draw(g, text, area, position, look.style, look.theme, look.captionSize(w, h), t - start, duration - start);
			}
		}
	}

	/** Opening card: the period label, the title and the dates. */
	static final class Title extends Scene
	{
		private final String label;
		private final String title;
		private final String dates;
		/** The player's emote, standing under the text, or null. */
		Emote player;

		Title(Look look, double duration, String label, String title, String dates)
		{
			super("title", look, duration);
			this.label = label;
			this.title = title;
			this.dates = dates;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			int u = Math.min(w, h);
			double p = Captions.ease(Captions.clamp(t / 0.6));
			// Accent rule growing out from the centre.
			int lineW = (int) (u * 0.5 * p);
			// Text sits in the upper third, leaving the middle for the player.
			int cy = (int) (h * 0.3);
			g.setColor(th.accent);
			g.fillRect(w / 2 - lineW / 2, cy - (int) (u * 0.1), lineW, Math.max(3, u / 160));
			Captions.centered(g, label.toUpperCase(Locale.ROOT), w / 2, cy - (int) (u * 0.125), th.bold(u * 0.034f), Theme.alpha(th.muted, p), (int) (w * 0.9));
			Captions.draw(g, title, new Rectangle(0, cy - (int) (u * 0.09), w, (int) (u * 0.18)), GielinorWrappedConfig.CaptionPosition.CENTER,
				GielinorWrappedConfig.CaptionStyle.SHADOW, th, u * 0.065f * look.captionScale, t - 0.2, duration);
			double q = Captions.clamp((t - 0.7) / 0.5);
			Captions.centered(g, dates, w / 2, cy + (int) (u * 0.14), th.regular(u * 0.036f), Theme.alpha(th.muted, q), (int) (w * 0.9));
			if (player != null)
			{
				player.draw(g, w / 2.0, h * 0.95, Math.min(h * 0.5, u * 0.55), t - 0.3);
			}
		}
	}

	/** A big number counting up, a caption and a top-five bar chart or an hours-of-day histogram. */
	static final class Stat extends Scene
	{
		private final String heading;
		private final String caption;
		private final long value;
		private final LongFunction<String> format;
		private final List<Recap.Entry> entries;
		private final LongFunction<String> entryFormat;
		private final long[] hours;
		private List<BufferedImage> rowIcons = new ArrayList<>();
		private List<BufferedImage> strip = new ArrayList<>();
		private int iconsFor = -1;
		private List<BufferedImage> bigRowIcons;
		private List<BufferedImage> bigStrip;

		Stat(String name, Look look, double duration, String heading, String caption, long value, LongFunction<String> format,
			List<Recap.Entry> entries, LongFunction<String> entryFormat, long[] hours)
		{
			super(name, look, duration);
			this.heading = heading;
			this.caption = caption;
			this.value = value;
			this.format = format;
			this.entries = entries;
			this.entryFormat = entryFormat;
			this.hours = hours;
		}

		/** Icons for the top-five rows, in order; null entries are skipped. */
		Stat icons(List<BufferedImage> icons)
		{
			rowIcons = icons;
			return this;
		}

		/** A row of sprites shown under the chart, e.g. the best drops. */
		Stat strip(List<BufferedImage> sprites)
		{
			strip = sprites;
			return this;
		}

		private void prepare(int rowSize, int stripSize)
		{
			if (iconsFor == rowSize + stripSize * 10_000)
			{
				return;
			}
			iconsFor = rowSize + stripSize * 10_000;
			bigRowIcons = new ArrayList<>();
			for (BufferedImage img : rowIcons)
			{
				bigRowIcons.add(img == null ? null : Sprites.splash(img, rowSize, new Color(0x111111)));
			}
			bigStrip = new ArrayList<>();
			for (BufferedImage img : strip)
			{
				BufferedImage big = Sprites.splash(img, stripSize, look.theme.accent);
				if (big != null)
				{
					bigStrip.add(big);
				}
			}
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			int u = Math.min(w, h);
			boolean tall = h > w * 1.2;
			Captions.centered(g, heading.toUpperCase(Locale.ROOT), w / 2, (int) (h * (tall ? 0.16 : 0.12)), th.bold(u * 0.045f),
				Theme.alpha(th.muted, Captions.clamp(t / 0.3)), (int) (w * 0.9));
			long shown = Math.round(value * Captions.ease(Captions.clamp(t / 1.4)));
			float pop = (float) (1 + 0.08 * Math.max(0, 1 - Math.abs(t - 1.5) / 0.2));
			Captions.centered(g, format.apply(shown), w / 2, (int) (h * (tall ? 0.28 : 0.30)), th.bold(u * 0.15f * pop), th.accent, (int) (w * 0.92));
			caption(g, caption, new Rectangle(0, (int) (h * (tall ? 0.31 : 0.33)), w, (int) (h * 0.2)), GielinorWrappedConfig.CaptionPosition.TOP, t, 0.6, w, h);

			Rectangle chart = new Rectangle((int) (w * (tall ? 0.08 : 0.15)), (int) (h * (tall ? 0.52 : 0.56)), (int) (w * (tall ? 0.84 : 0.7)), (int) (h * (tall ? 0.38 : 0.36)));
			if (hours != null)
			{
				histogram(g, chart, t - 0.8, u);
			}
			else if (entries != null && !entries.isEmpty())
			{
				int stripH = strip.isEmpty() ? 0 : (int) (chart.height * 0.3);
				chart.height -= stripH;
				prepare((int) (chart.height / 5 * 0.62 * 1.3), Math.min(stripH, (int) (chart.width / Math.max(1, strip.size()) * 0.9)));
				bars(g, chart, t - 0.8, u);
				if (stripH > 0)
				{
					drawStrip(g, new Rectangle(chart.x, chart.y + chart.height, chart.width, stripH), t - 1.2);
				}
			}
		}

		private void drawStrip(Graphics2D g, Rectangle r, double t)
		{
			int n = bigStrip.size();
			for (int i = 0; i < n; i++)
			{
				double p = Captions.clamp((t - i * 0.1) / 0.4);
				if (p <= 0)
				{
					continue;
				}
				double cx = r.x + r.width * (i + 0.5) / n;
				double bob = Math.sin(t * 2.2 + i) * r.height * 0.03;
				Sprites.drawCentered(g, bigStrip.get(i), cx, r.y + r.height * 0.55 + bob, Splash.backOut(p), 0);
			}
		}

		private void bars(Graphics2D g, Rectangle r, double t, int u)
		{
			Theme th = look.theme;
			List<Recap.Entry> top = Recap.top(entries, 5);
			int rowH = r.height / 5;
			int barH = (int) (rowH * 0.62);
			long max = top.get(0).value;
			Font labelFont = th.bold(Math.max(12f, barH * 0.55f));
			for (int i = 0; i < top.size(); i++)
			{
				double p = Captions.ease(Captions.clamp((t - i * 0.12) / 0.6));
				if (p <= 0)
				{
					continue;
				}
				Recap.Entry e = top.get(i);
				int y = r.y + i * rowH;
				int full = max <= 0 ? 0 : (int) (r.width * Math.max(0.04, e.value / (double) max));
				int bw = (int) (full * p);
				g.setColor(Theme.alpha(th.panel, p));
				g.fillRoundRect(r.x, y, r.width, barH, barH / 2, barH / 2);
				g.setColor(Theme.alpha(i == 0 ? th.accent : th.accent2, p * (i == 0 ? 1 : 0.75)));
				g.fillRoundRect(r.x, y, bw, barH, barH / 2, barH / 2);
				g.setFont(labelFont);
				FontMetrics fm = g.getFontMetrics();
				int base = y + (barH + fm.getAscent() - fm.getDescent()) / 2;
				String v = entryFormat.apply(e.value);
				int textX = r.x + barH / 3;
				BufferedImage icon = bigRowIcons != null && i < bigRowIcons.size() ? bigRowIcons.get(i) : null;
				if (icon != null)
				{
					Sprites.drawCentered(g, icon, r.x + barH * 0.62, y + barH / 2.0, p, 0);
					textX = r.x + (int) (barH * 1.25);
				}
				g.setColor(Theme.alpha(th.light() ? th.text : Color.WHITE, p));
				drawShadowed(g, (i + 1) + ". " + e.name, textX, base, th, p);
				drawShadowed(g, v, r.x + r.width - barH / 3 - fm.stringWidth(v), base, th, p);
			}
		}

		private static void drawShadowed(Graphics2D g, String s, int x, int y, Theme th, double a)
		{
			Color c = g.getColor();
			if (!th.light())
			{
				g.setColor(new Color(0, 0, 0, (int) (150 * a)));
				g.drawString(s, x + 2, y + 2);
			}
			g.setColor(c);
			g.drawString(s, x, y);
		}

		private void histogram(Graphics2D g, Rectangle r, double t, int u)
		{
			Theme th = look.theme;
			long max = 1;
			int peak = 0;
			for (int i = 0; i < 24; i++)
			{
				if (hours[i] > max)
				{
					max = hours[i];
					peak = i;
				}
			}
			int labelH = (int) (u * 0.04);
			int barArea = r.height - labelH * 2;
			double slot = r.width / 24.0;
			for (int i = 0; i < 24; i++)
			{
				double p = Captions.ease(Captions.clamp((t - i * 0.03) / 0.5));
				int bh = (int) (Math.max(u / 200.0, barArea * hours[i] / (double) max) * p);
				int x = (int) (r.x + i * slot + slot * 0.15);
				g.setColor(hours[i] > 0 && i == peak ? th.accent : Theme.alpha(th.accent2, 0.7));
				g.fillRect(x, r.y + barArea - bh, (int) Math.max(1, slot * 0.7), bh);
			}
			g.setColor(th.muted);
			Font f = th.regular(labelH);
			for (int i = 0; i < 24; i += 6)
			{
				Captions.centered(g, Recap.hour(i), (int) (r.x + i * slot + slot / 2), r.y + barArea + labelH + labelH / 2, f, th.muted, (int) (slot * 4));
			}
		}
	}

	/** A recorded highlight playing in real time, with a badge and caption. */
	static final class Clip extends Scene
	{
		private final Supplier<List<ClipFile.Frame>> loader;
		private final String badge;
		private final String caption;
		/** Offset into the clip, in milliseconds, where the scene starts. */
		long start;
		final long length;
		final long momentOffset;
		final int importance;
		/** When the moment happened, for ordering clips in the video. */
		long momentOrder;
		/** The highlight the clip shows, for picking a matching sticker. */
		Moment moment;
		/** Item or skill sprite shown beside the badge, or null. */
		BufferedImage icon;
		private int iconFor = -1;
		private BufferedImage bigIcon;
		private List<ClipFile.Frame> frames;
		private int decodedIndex = -1;
		private BufferedImage decoded;

		/**
		 * @param length milliseconds of footage in the clip
		 * @param momentOffset milliseconds from the first frame to the moment itself
		 */
		Clip(Look look, Supplier<List<ClipFile.Frame>> loader, long length, long momentOffset, int importance, String badge, String caption)
		{
			super("clip", look, length / 1000.0);
			this.loader = loader;
			this.length = length;
			this.momentOffset = momentOffset;
			this.importance = importance;
			this.badge = badge;
			this.caption = caption;
		}

		/** Shortens the scene, keeping footage around the moment: two thirds before, one third after. */
		void trim(double seconds)
		{
			long keep = Math.min(length, Math.round(seconds * 1000));
			long s = momentOffset - keep * 2 / 3;
			start = Math.max(0, Math.min(length - keep, s));
			duration = keep / 1000.0;
		}

		@Override
		double minDuration()
		{
			return Math.min(duration, 2.5);
		}

		@Override
		void open()
		{
			frames = loader.get();
			decodedIndex = -1;
		}

		@Override
		void close()
		{
			frames = null;
			decoded = null;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			BufferedImage img = frameAt(t);
			int u = Math.min(w, h);
			if (img == null)
			{
				th.background(g, w, h, t);
			}
			else
			{
				double zoom = 1 + 0.04 * (t / Math.max(0.1, duration));
				double imgAspect = img.getWidth() / (double) img.getHeight();
				double outAspect = w / (double) h;
				if (Math.abs(Math.log(imgAspect / outAspect)) < 0.25)
				{
					drawCover(g, img, 0, 0, w, h, zoom);
				}
				else
				{
					// Very different shapes: game footage in a band over the theme background.
					th.background(g, w, h, t);
					int bw = w;
					int bh = (int) (w / imgAspect);
					if (bh > h)
					{
						bh = h;
						bw = (int) (h * imgAspect);
					}
					drawCover(g, img, (w - bw) / 2, (h - bh) / 2, bw, bh, zoom);
				}
			}

			if (badge != null)
			{
				double p = Captions.ease(Captions.clamp(t / 0.4));
				Font f = th.bold(u * 0.04f);
				g.setFont(f);
				FontMetrics fm = g.getFontMetrics();
				int pad = u / 60;
				int bw = fm.stringWidth(badge) + pad * 2;
				int bh = fm.getHeight() + pad;
				int x = (int) (u * 0.04 - (1 - p) * (bw + u * 0.04));
				int y = (int) (u * 0.04);
				g.setColor(th.accent);
				g.fillRoundRect(x, y, bw, bh, bh / 2, bh / 2);
				g.setColor(isBright(th.accent) ? new Color(0x111111) : Color.WHITE);
				g.drawString(badge, x + pad, y + pad / 2 + fm.getAscent());
				if (icon != null)
				{
					int size = (int) (u * 0.16);
					if (iconFor != size)
					{
						bigIcon = Sprites.splash(icon, size, th.accent);
						iconFor = size;
					}
					double q = Captions.clamp((t - 0.25) / 0.4);
					Sprites.drawCentered(g, bigIcon, x + size / 2.0, y + bh + size * 0.6 + Math.sin(t * 2.5) * size * 0.04, Splash.backOut(q), -0.08);
				}
			}
			double captionStart = Math.max(0.2, Math.min(duration - 2.5, (momentOffset - start) / 1000.0 - 0.3));
			caption(g, caption, new Rectangle(0, 0, w, h), look.position, t, captionStart, w, h);
		}

		private BufferedImage frameAt(double t)
		{
			if (frames == null || frames.isEmpty())
			{
				return null;
			}
			long target = frames.get(0).time + start + Math.round(t * 1000);
			int lo = 0;
			int hi = frames.size() - 1;
			while (lo < hi)
			{
				int mid = (lo + hi + 1) >>> 1;
				if (frames.get(mid).time <= target)
				{
					lo = mid;
				}
				else
				{
					hi = mid - 1;
				}
			}
			if (lo != decodedIndex)
			{
				try
				{
					BufferedImage next = frames.get(lo).image();
					if (next != null)
					{
						decoded = next;
					}
				}
				catch (IOException e)
				{
					// keep showing the previous frame
				}
				decodedIndex = lo;
			}
			return decoded;
		}
	}

	/** Timelapse stills played back quickly with a date stamp. */
	static final class Timelapse extends Scene
	{
		private final Supplier<List<ClipFile.Frame>> loader;
		private final String caption;
		private final int count;
		private List<ClipFile.Frame> frames;
		private int decodedIndex = -1;
		private BufferedImage decoded;

		Timelapse(Look look, Supplier<List<ClipFile.Frame>> loader, int count, double duration, String caption)
		{
			super("timelapse", look, duration);
			this.loader = loader;
			this.count = count;
			this.caption = caption;
		}

		@Override
		double minDuration()
		{
			return Math.min(duration, 2.5);
		}

		@Override
		void open()
		{
			frames = loader.get();
		}

		@Override
		void close()
		{
			frames = null;
			decoded = null;
			decodedIndex = -1;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			int n = frames == null ? 0 : frames.size();
			if (n == 0)
			{
				th.background(g, w, h, t);
				return;
			}
			int i = Math.min(n - 1, (int) (t / duration * n));
			if (i != decodedIndex)
			{
				try
				{
					BufferedImage next = frames.get(i).image();
					if (next != null)
					{
						decoded = next;
					}
				}
				catch (IOException e)
				{
					// keep the previous still
				}
				decodedIndex = i;
			}
			if (decoded == null)
			{
				th.background(g, w, h, t);
			}
			else
			{
				drawCover(g, decoded, 0, 0, w, h, 1);
			}
			int u = Math.min(w, h);
			String stamp = STAMP.format(Instant.ofEpochMilli(frames.get(i).time).atZone(ZoneId.systemDefault()));
			Font f = th.bold(u * 0.035f);
			g.setFont(f);
			FontMetrics fm = g.getFontMetrics();
			int pad = u / 70;
			int sw = fm.stringWidth(stamp);
			int x = w - sw - pad * 3;
			int y = h - fm.getHeight() - pad * 3;
			g.setColor(new Color(0, 0, 0, 150));
			g.fillRoundRect(x - pad, y - pad / 2, sw + pad * 2, fm.getHeight() + pad, pad * 2, pad * 2);
			g.setColor(Color.WHITE);
			g.drawString(stamp, x, y + fm.getAscent());
			caption(g, caption, new Rectangle(0, 0, w, h), GielinorWrappedConfig.CaptionPosition.TOP, t, 0.2, w, h);
		}

		int count()
		{
			return count;
		}
	}

	/** A centred caption on the theme background, used for the persona and the outro. */
	static final class Card extends Scene
	{
		private final String label;
		private final String text;
		private final String footer;
		/** Loads a screenshot to show darkened behind the text, or null for the theme background. */
		Supplier<BufferedImage> backdrop;
		/** The player's emote, standing under the text, or null. */
		Emote player;
		private BufferedImage image;

		Card(String name, Look look, double duration, String label, String text, String footer)
		{
			super(name, look, duration);
			this.label = label;
			this.text = text;
			this.footer = footer;
		}

		@Override
		void open()
		{
			image = backdrop == null ? null : backdrop.get();
		}

		@Override
		void close()
		{
			image = null;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			if (image != null)
			{
				drawCover(g, image, 0, 0, w, h, 1.08 - 0.06 * Captions.clamp(t / Math.max(0.1, duration)));
				g.setColor(new Color(0, 0, 0, th.light() ? 120 : 150));
				g.fillRect(0, 0, w, h);
			}
			else
			{
				background(g, w, h, t);
			}
			int u = Math.min(w, h);
			if (player != null)
			{
				// Text moves up to leave room for the player.
				g.translate(0, -(int) (h * 0.14));
			}
			if (label != null)
			{
				Captions.centered(g, label.toUpperCase(Locale.ROOT), w / 2, (int) (h * 0.3), th.bold(u * 0.04f),
					Theme.alpha(th.muted, Captions.clamp(t / 0.3)), (int) (w * 0.9));
			}
			Captions.draw(g, text, new Rectangle((int) (w * 0.05), (int) (h * 0.32), (int) (w * 0.9), (int) (h * 0.36)), GielinorWrappedConfig.CaptionPosition.CENTER,
				look.style == GielinorWrappedConfig.CaptionStyle.BOXED ? GielinorWrappedConfig.CaptionStyle.SHADOW : look.style, th,
				u * 0.075f * look.captionScale, t - 0.25, duration);
			if (footer != null)
			{
				Captions.centered(g, footer, w / 2, (int) (h * (player != null ? 0.7 : 0.8)), th.regular(u * 0.04f), Theme.alpha(th.muted, Captions.clamp((t - 0.8) / 0.4)), (int) (w * 0.9));
			}
			if (player != null)
			{
				g.translate(0, (int) (h * 0.14));
				player.draw(g, w / 2.0, h * 0.96, Math.min(h * 0.36, u * 0.4), t - 0.4);
			}
		}
	}

	/** The thumbnail-style opening: see {@link Splash}. */
	static final class SplashTitle extends Scene
	{
		private final Splash splash;
		/** The player's emote, in the corner opposite the art, or null. */
		Emote player;

		SplashTitle(Look look, double duration, Splash splash)
		{
			super("title", look, duration);
			this.splash = splash;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			splash.draw(g, look.theme, w, h, t);
			if (player != null)
			{
				int u = Math.min(w, h);
				player.draw(g, w * 0.13, h * 0.97, Math.min(h * 0.42, u * 0.45), t - 0.5);
			}
		}
	}

	/** The player's own recap shots, one after another with a slow pan and a caption. */
	static final class Gallery extends Scene
	{
		static final double PER_SHOT = 1.8;
		static final double MIN_PER_SHOT = 1.2;
		private final List<Shots.Shot> shots;
		private final List<String> captions;
		private List<BufferedImage> images;

		Gallery(Look look, List<Shots.Shot> shots, List<String> captions)
		{
			super("gallery", look, shots.size() * PER_SHOT);
			this.shots = new ArrayList<>(shots);
			this.captions = new ArrayList<>(captions);
		}

		int size()
		{
			return shots.size();
		}

		/** Drops the middle shot to save time, keeping the first and last. */
		void dropOne()
		{
			int i = shots.size() / 2;
			shots.remove(i);
			captions.remove(i);
			duration = Math.min(duration, shots.size() * PER_SHOT);
		}

		@Override
		double minDuration()
		{
			return shots.size() * MIN_PER_SHOT;
		}

		@Override
		void open()
		{
			images = new ArrayList<>();
			for (Shots.Shot s : shots)
			{
				images.add(Composer.read(s));
			}
		}

		@Override
		void close()
		{
			images = null;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			int n = shots.size();
			double each = duration / Math.max(1, n);
			int i = Math.max(0, Math.min(n - 1, (int) (t / each)));
			double local = t - i * each;
			BufferedImage img = images == null ? null : images.get(i);
			if (img == null)
			{
				th.background(g, w, h, t);
			}
			else
			{
				// Alternate pushing in and pulling out so consecutive shots don't feel the same.
				double p = Captions.clamp(local / each);
				double zoom = i % 2 == 0 ? 1.02 + 0.07 * p : 1.09 - 0.07 * p;
				drawCover(g, img, 0, 0, w, h, zoom);
				double flash = 1 - Captions.clamp(local / 0.18);
				if (flash > 0 && i > 0)
				{
					g.setColor(new Color(255, 255, 255, (int) (120 * flash)));
					g.fillRect(0, 0, w, h);
				}
			}
			int u = Math.min(w, h);
			String count = (i + 1) + " / " + n;
			Font f = th.bold(u * 0.03f);
			g.setFont(f);
			FontMetrics fm = g.getFontMetrics();
			int pad = u / 70;
			int x = w - fm.stringWidth(count) - pad * 4;
			int y = (int) (u * 0.04);
			g.setColor(new Color(0, 0, 0, 150));
			g.fillRoundRect(x - pad, y, fm.stringWidth(count) + pad * 2, fm.getHeight() + pad / 2, pad * 2, pad * 2);
			g.setColor(Color.WHITE);
			g.drawString(count, x, y + pad / 4 + fm.getAscent());
			if (look.captions)
			{
				Captions.draw(g, captions.get(i), new Rectangle(0, 0, w, h), look.position, look.style, th, look.captionSize(w, h), local - 0.15, each - 0.15);
			}
		}
	}

	/** One death in the {@link Deaths} scene: Death Recap's share card or screenshot when there is one. */
	static final class Death
	{
		final Supplier<BufferedImage> card;
		final Supplier<BufferedImage> shot;
		/** Who did it, shown when there's no card. */
		final String killer;
		final String caption;

		Death(Supplier<BufferedImage> card, Supplier<BufferedImage> shot, String killer, String caption)
		{
			this.card = card;
			this.shot = shot;
			this.killer = killer;
			this.caption = caption;
		}
	}

	/** Every death of the period, one after another: the share card on the theme background, with a counter and a caption. */
	static final class Deaths extends Scene
	{
		static final double PER_DEATH = 2.2;
		static final double MIN_PER_DEATH = 1.4;
		private final List<Death> deaths;
		private List<BufferedImage> cards;
		private List<BufferedImage> shots;

		Deaths(Look look, List<Death> deaths)
		{
			super("deaths", look, deaths.size() * PER_DEATH);
			this.deaths = new ArrayList<>(deaths);
		}

		int size()
		{
			return deaths.size();
		}

		/** Drops the middle death to save time. */
		void dropOne()
		{
			deaths.remove(deaths.size() / 2);
			duration = Math.min(duration, deaths.size() * PER_DEATH);
		}

		@Override
		double minDuration()
		{
			return deaths.size() * MIN_PER_DEATH;
		}

		@Override
		void open()
		{
			cards = new ArrayList<>();
			shots = new ArrayList<>();
			for (Death d : deaths)
			{
				BufferedImage card = d.card == null ? null : d.card.get();
				cards.add(card);
				// The screenshot sits behind the card, or fills the screen when there's no card.
				shots.add(d.shot == null ? null : d.shot.get());
			}
		}

		@Override
		void close()
		{
			cards = null;
			shots = null;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			int n = deaths.size();
			double each = duration / Math.max(1, n);
			int i = Math.max(0, Math.min(n - 1, (int) (t / each)));
			double local = t - i * each;
			Death d = deaths.get(i);
			BufferedImage card = cards == null ? null : cards.get(i);
			BufferedImage shot = shots == null ? null : shots.get(i);
			int u = Math.min(w, h);
			double p = Captions.clamp(local / each);

			if (shot != null)
			{
				drawCover(g, shot, 0, 0, w, h, 1.03 + 0.05 * p);
				g.setColor(new Color(0, 0, 0, card != null ? 150 : 70));
				g.fillRect(0, 0, w, h);
			}
			else
			{
				th.background(g, w, h, t);
			}
			// A red pulse as each death comes in.
			double hit = 1 - Captions.clamp(local / 0.35);
			if (hit > 0)
			{
				g.setColor(new Color(170, 0, 0, (int) (110 * hit)));
				g.fillRect(0, 0, w, h);
			}

			double bottom = look.captions ? 0.78 : 0.92;
			if (card != null)
			{
				double pop = 0.9 + 0.1 * Captions.ease(Captions.clamp(local / 0.3));
				double scale = Math.min(w * 0.86 / card.getWidth(), h * (bottom - 0.14) / card.getHeight()) * pop;
				int cw = (int) (card.getWidth() * scale);
				int ch = (int) (card.getHeight() * scale);
				int cx = (w - cw) / 2;
				int cy = (int) (h * (0.12 + bottom) / 2 - ch / 2.0);
				g.setColor(new Color(0, 0, 0, 120));
				int sh = Math.max(2, u / 80);
				g.fillRoundRect(cx + sh, cy + sh, cw, ch, u / 40, u / 40);
				Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(card, cx, cy, cw, ch, null);
				if (old != null)
				{
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
				}
			}
			else
			{
				// No card: a big "Oh dear" and who did it.
				Font big = th.bold(u * 0.11f);
				g.setFont(big);
				FontMetrics fm = g.getFontMetrics();
				String head = "OH DEAR";
				int y = (int) (h * 0.42);
				shout(g, head, (w - fm.stringWidth(head)) / 2, y, new Color(220, 30, 30), Captions.clamp(local / 0.25));
				if (d.killer != null)
				{
					g.setFont(th.bold(u * 0.05f));
					FontMetrics km = g.getFontMetrics();
					String who = fit(km, d.killer, (int) (w * 0.85));
					shout(g, who, (w - km.stringWidth(who)) / 2, y + fm.getDescent() + km.getAscent() + u / 40, Color.WHITE,
						Captions.clamp((local - 0.15) / 0.25));
				}
			}

			// Title chip top left, counter top right.
			Font f = th.bold(u * 0.03f);
			g.setFont(f);
			FontMetrics fm = g.getFontMetrics();
			int pad = u / 70;
			int y = (int) (u * 0.04);
			String label = "DEATHS";
			g.setColor(new Color(150, 0, 0, 190));
			g.fillRoundRect(pad * 4 - pad, y, fm.stringWidth(label) + pad * 2, fm.getHeight() + pad / 2, pad * 2, pad * 2);
			g.setColor(Color.WHITE);
			g.drawString(label, pad * 4, y + pad / 4 + fm.getAscent());
			String count = (i + 1) + " / " + n;
			int x = w - fm.stringWidth(count) - pad * 4;
			g.setColor(new Color(0, 0, 0, 150));
			g.fillRoundRect(x - pad, y, fm.stringWidth(count) + pad * 2, fm.getHeight() + pad / 2, pad * 2, pad * 2);
			g.setColor(Color.WHITE);
			g.drawString(count, x, y + pad / 4 + fm.getAscent());

			if (look.captions && d.caption != null && !d.caption.isEmpty())
			{
				Captions.draw(g, d.caption, new Rectangle(0, 0, w, h), GielinorWrappedConfig.CaptionPosition.BOTTOM, look.style, th, look.captionSize(w, h),
					local - 0.15, each - 0.15);
			}
		}

		/** Text with a dark outline so it reads over any screenshot. */
		private static void shout(Graphics2D g, String s, int x, int y, Color c, double a)
		{
			if (a <= 0)
			{
				return;
			}
			int alpha = (int) (255 * Math.min(1, a));
			g.setColor(new Color(0, 0, 0, alpha * 3 / 4));
			for (int dx = -2; dx <= 2; dx += 2)
			{
				for (int dy = -2; dy <= 2; dy += 2)
				{
					g.drawString(s, x + dx, y + dy);
				}
			}
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			g.drawString(s, x, y);
		}

		private static String fit(FontMetrics fm, String s, int width)
		{
			if (fm.stringWidth(s) <= width)
			{
				return s;
			}
			String out = s;
			while (out.length() > 1 && fm.stringWidth(out + "…") > width)
			{
				out = out.substring(0, out.length() - 1);
			}
			return out.trim() + "…";
		}
	}

	/**
	 * A stretch of recorded footage, loaded when the scene opens and optionally green-screen keyed.
	 * {@code from} and {@code to} are capture times in epoch milliseconds.
	 */
	static final class Reel
	{
		private final ReelLoader loader;
		final long from;
		final long to;
		private final Chroma chroma;
		private List<ClipFile.Frame> frames;
		private int decodedIndex = -1;
		private BufferedImage decoded;

		Reel(ReelLoader loader, long from, long to, Chroma chroma)
		{
			this.loader = loader;
			this.from = from;
			this.to = to;
			this.chroma = chroma;
		}

		boolean keyed()
		{
			return chroma != null;
		}

		double seconds()
		{
			return (to - from) / 1000.0;
		}

		void open()
		{
			frames = loader.load(from, to);
			decodedIndex = -1;
		}

		void close()
		{
			frames = null;
			decoded = null;
		}

		/** The frame showing {@code t} seconds in; the reel holds its last frame if the scene runs longer. */
		BufferedImage at(double t)
		{
			if (frames == null || frames.isEmpty())
			{
				return null;
			}
			long target = from + Math.round(t * 1000);
			int lo = 0;
			int hi = frames.size() - 1;
			while (lo < hi)
			{
				int mid = (lo + hi + 1) >>> 1;
				if (frames.get(mid).time <= target)
				{
					lo = mid;
				}
				else
				{
					hi = mid - 1;
				}
			}
			if (lo != decodedIndex)
			{
				try
				{
					BufferedImage next = frames.get(lo).image();
					if (next != null)
					{
						decoded = chroma == null ? next : chroma.key(next);
					}
				}
				catch (IOException e)
				{
					// keep showing the previous frame
				}
				decodedIndex = lo;
			}
			return decoded;
		}
	}

	interface ReelLoader
	{
		List<ClipFile.Frame> load(long from, long to);
	}

	/** A cut from one of the player's own recordings, played between the other scenes. */
	static final class Footage extends Scene
	{
		final Reel reel;
		String caption;
		/** What shows through green-screened footage: another recording, or null for the theme background. */
		Reel base;

		Footage(Look look, Reel reel, String caption)
		{
			super("footage", look, reel.seconds());
			this.reel = reel;
			this.caption = caption;
		}

		@Override
		double minDuration()
		{
			return Math.min(duration, 1.5);
		}

		@Override
		void open()
		{
			reel.open();
			if (base != null)
			{
				base.open();
			}
		}

		@Override
		void close()
		{
			reel.close();
			if (base != null)
			{
				base.close();
			}
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			BufferedImage img = reel.at(t);
			double zoom = 1 + 0.035 * Captions.clamp(t / Math.max(0.1, duration));
			if (reel.keyed() || img == null)
			{
				BufferedImage back = base == null ? null : base.at(t);
				if (back != null)
				{
					drawCover(g, back, 0, 0, w, h, 1.06 - 0.04 * Captions.clamp(t / Math.max(0.1, duration)));
				}
				else
				{
					th.background(g, w, h, t);
				}
			}
			if (img != null)
			{
				drawCover(g, img, 0, 0, w, h, zoom);
			}
			if (caption != null && !caption.isEmpty())
			{
				caption(g, caption, new Rectangle(0, 0, w, h), look.position, t, 0.25, w, h);
			}
		}
	}

	/** Where a sticker sits on a scene. Positions and size are fractions of the frame. */
	static final class Placement
	{
		final Sticker sticker;
		final double x;
		final double y;
		final double size;
		final double angle;
		/** Seconds into the scene the sticker slaps on. */
		final double start;
		private final double phase;

		Placement(Sticker sticker, double x, double y, double size, double angle, double start, double phase)
		{
			this.sticker = sticker;
			this.x = x;
			this.y = y;
			this.size = size;
			this.angle = angle;
			this.start = start;
			this.phase = phase;
		}

		void draw(Graphics2D g, int w, int h, int u, double t, double duration, boolean wobble)
		{
			double local = t - start;
			if (local < 0)
			{
				return;
			}
			// Slap: overshoot in, then settle; fade out at the very end of the scene.
			double pop = Splash.backOut(Captions.clamp(local / 0.35));
			double out = Captions.clamp((duration - t) / 0.2);
			double a = angle;
			double dy = 0;
			if (wobble)
			{
				a += Math.sin(t * 2.1 + phase) * 0.06;
				dy = Math.sin(t * 1.7 + phase * 2) * u * 0.008;
			}
			sticker.draw(g, w * x, h * y + dy, u * size * pop, a, local, out);
		}
	}

	/** Scales {@code img} to fill the box, cropping the overflow, with an optional zoom about the centre. */
	static void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h, double zoom)
	{
		double scale = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight()) * zoom;
		double dw = img.getWidth() * scale;
		double dh = img.getHeight() * scale;
		Rectangle oldClip = g.getClipBounds();
		g.setClip(x, y, w, h);
		Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		AffineTransform at = new AffineTransform(scale, 0, 0, scale, x + (w - dw) / 2, y + (h - dh) / 2);
		g.drawImage(img, at, null);
		if (old != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
		}
		g.setClip(oldClip);
	}

	static boolean isBright(Color c)
	{
		return c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114 > 150_000;
	}

	static String badge(Moment.Type type)
	{
		switch (type)
		{
			case LEVEL_UP:
				return "LEVEL UP";
			case MAX_LEVEL:
				return "MAXED SKILL";
			case DROP:
				return "BIG DROP";
			case PET:
				return "PET!";
			case COLLECTION_LOG:
				return "NEW LOG SLOT";
			case QUEST:
				return "QUEST COMPLETE";
			case COMBAT_TASK:
				return "COMBAT TASK";
			case DIARY:
				return "DIARY DONE";
			case CLUE:
				return "CLUE";
			case PERSONAL_BEST:
				return "NEW PB";
			case KILL_COUNT:
				return "MILESTONE";
			case DEATH:
				return "OH DEAR";
			default:
				return "CLIPPED";
		}
	}
}
