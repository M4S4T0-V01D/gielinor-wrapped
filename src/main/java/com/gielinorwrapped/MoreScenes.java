package com.gielinorwrapped;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.imageio.ImageIO;

/** The extra scenes: the map, graphs, comparisons, goal pace, bosses, collection log, Grand Exchange and the year montage. */
final class MoreScenes
{
	private MoreScenes()
	{
	}

	/** The muted heading every extra scene opens with. */
	static void heading(Graphics2D g, Scenes.Look look, String text, int w, int h, double t)
	{
		Theme th = look.theme;
		boolean tall = h > w * 1.2;
		Captions.centered(g, text.toUpperCase(Locale.ROOT), w / 2, (int) (h * (tall ? 0.14 : 0.11)), th.bold(Math.min(w, h) * 0.045f),
			Theme.alpha(th.muted, Captions.clamp(t / 0.3)), (int) (w * 0.9));
	}

	private static void shadowed(Graphics2D g, String s, int x, int y, Theme th, Color c)
	{
		if (!th.light())
		{
			g.setColor(new Color(0, 0, 0, Math.min(150, c.getAlpha())));
			g.drawString(s, x + 2, y + 2);
		}
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static Color ink(Theme th, double a)
	{
		return Theme.alpha(th.light() ? th.text : Color.WHITE, a);
	}

	/** "1:23" or "1:02:03". */
	static String time(long seconds)
	{
		long h = seconds / 3600;
		long m = seconds / 60 % 60;
		long s = seconds % 60;
		return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	/** A coloured disc with the first letter, for things with no picture. */
	private static void initial(Graphics2D g, String name, double cx, double cy, int size, Theme th, double scale)
	{
		int d = (int) (size * scale);
		if (d <= 0)
		{
			return;
		}
		g.setColor(th.accent2);
		g.fillOval((int) (cx - d / 2.0), (int) (cy - d / 2.0), d, d);
		String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
		Captions.centered(g, letter, (int) cx, (int) (cy + d * 0.18), th.bold(d * 0.5f), Scenes.isBright(th.accent2) ? new Color(0x111111) : Color.WHITE, d);
	}

	/** Where the player spent their time: a glowing heat map over the game's own map, and the top three places. */
	static final class WorldMap extends Scenes.Scene
	{
		/** Overworld window, in region coordinates. */
		static final int MIN_X = 16;
		static final int MAX_X = 63;
		static final int MIN_Y = 38;
		static final int MAX_Y = 65;
		/** The shown part of the window: the picture's last column and top row are empty. */
		static final int VIEW_MAX_X = 61;
		static final int VIEW_MAX_Y = 64;
		/** The bundled map covers the window above at this many pixels per region (one per two game tiles). */
		static final int MAP_SCALE = 32;
		/**
		 * The map is bundled as a grid of tiles, {@code world-map/<row>-<col>.jpg}, to keep each picture small;
		 * they are put back together on first use.
		 */
		private static final int TILE_ROWS = 4;
		private static final int TILE_COLS = 3;
		private static SoftReference<BufferedImage> surface;
		private BufferedImage backdrop;

		static final class Place
		{
			final String name;
			final int x;
			final int y;
			final long seconds;

			Place(String name, int x, int y, long seconds)
			{
				this.name = name;
				this.x = x;
				this.y = y;
				this.seconds = seconds;
			}
		}

		/** Seconds per folded cell, keyed by {@code x << 8 | y}. */
		final java.util.Map<Integer, Long> cells = new LinkedHashMap<>();
		final List<Place> places = new ArrayList<>();
		int x0;
		int y0;
		int x1;
		int y1;
		private long max = 1;

		WorldMap(Scenes.Look look, double duration, List<Recap.Entry> regions)
		{
			super("map", look, duration);
			java.util.Map<String, Place> best = new HashMap<>();
			java.util.Map<String, Long> areaTotals = new HashMap<>();
			for (Recap.Entry e : regions)
			{
				int id;
				try
				{
					id = Integer.parseInt(e.name);
				}
				catch (NumberFormatException ex)
				{
					continue;
				}
				int[] c = cell(id);
				if (c == null)
				{
					continue;
				}
				int key = c[0] << 8 | c[1];
				long v = cells.merge(key, e.value, Long::sum);
				max = Math.max(max, v);
				String area = Areas.name(id);
				if (area != null)
				{
					areaTotals.merge(area, e.value, Long::sum);
					Place p = best.get(area);
					if (p == null || p.seconds < e.value)
					{
						best.put(area, new Place(area, c[0], c[1], e.value));
					}
				}
			}
			areaTotals.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
				.limit(3)
				.forEach(a -> places.add(new Place(a.getKey(), best.get(a.getKey()).x, best.get(a.getKey()).y, a.getValue())));
			bounds();
		}

		/** The region's map cell with caves folded onto the surface, or null when it isn't on the main map. */
		static int[] cell(int region)
		{
			int x = region >> 8;
			int y = region & 0xff;
			if (y >= 100)
			{
				y -= 100;
			}
			if (x < MIN_X || x > VIEW_MAX_X || y < MIN_Y || y > VIEW_MAX_Y)
			{
				return null;
			}
			return new int[]{x, y};
		}

		boolean hasData()
		{
			return !cells.isEmpty();
		}

		private void bounds()
		{
			// Always the whole world, Kourend and Varlamore included, so the heat reads against the full map.
			x0 = MIN_X;
			x1 = VIEW_MAX_X;
			y0 = MIN_Y;
			y1 = VIEW_MAX_Y;
		}

		/** The game's surface map cut to this scene's window and scaled to {@code width} x {@code height}, made once per size; null without the picture. */
		BufferedImage backdrop(int width, int height)
		{
			if (width <= 0 || height <= 0)
			{
				return null;
			}
			if (backdrop != null && backdrop.getWidth() == width && backdrop.getHeight() == height)
			{
				return backdrop;
			}
			BufferedImage full = surface();
			if (full == null)
			{
				return null;
			}
			int sx = (x0 - MIN_X) * MAP_SCALE;
			int sy = (MAX_Y - y1) * MAP_SCALE;
			BufferedImage crop = full.getSubimage(sx, sy, (x1 - x0 + 1) * MAP_SCALE, (y1 - y0 + 1) * MAP_SCALE);
			// Halve big shrinks step by step so roads and coastlines don't break up.
			while (crop.getWidth() / 2 >= width && crop.getHeight() / 2 >= height)
			{
				crop = scaled(crop, crop.getWidth() / 2, crop.getHeight() / 2);
			}
			backdrop = scaled(crop, width, height);
			return backdrop;
		}

		private static BufferedImage scaled(BufferedImage src, int width, int height)
		{
			BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = out.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, width, height, null);
			g.dispose();
			return out;
		}

		/** The bundled surface map, loaded on first use and let go when memory runs short; null if it can't be read. */
		static synchronized BufferedImage surface()
		{
			BufferedImage img = surface == null ? null : surface.get();
			if (img != null)
			{
				return img;
			}
			int width = (MAX_X - MIN_X + 1) * MAP_SCALE;
			int height = (MAX_Y - MIN_Y + 1) * MAP_SCALE;
			int tileW = width / TILE_COLS;
			int tileH = height / TILE_ROWS;
			img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			try
			{
				for (int row = 0; row < TILE_ROWS; row++)
				{
					for (int col = 0; col < TILE_COLS; col++)
					{
						BufferedImage tile;
						try (InputStream in = MoreScenes.class.getResourceAsStream("world-map/" + row + "-" + col + ".jpg"))
						{
							tile = in == null ? null : ImageIO.read(in);
						}
						if (tile == null || tile.getWidth() != tileW || tile.getHeight() != tileH)
						{
							return null;
						}
						g.drawImage(tile, col * tileW, row * tileH, null);
					}
				}
			}
			catch (IOException e)
			{
				return null;
			}
			finally
			{
				g.dispose();
			}
			surface = new SoftReference<>(img);
			return img;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, "Where you played", w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			Rectangle area = new Rectangle((int) (w * 0.06), (int) (h * (tall ? 0.2 : 0.17)), (int) (w * 0.88), (int) (h * (tall ? 0.5 : 0.58)));
			int cols = x1 - x0 + 1;
			int rows = y1 - y0 + 1;
			double size = Math.min(area.width / (double) cols, area.height / (double) rows);
			double left = area.x + (area.width - size * cols) / 2;
			double top = area.y + (area.height - size * rows) / 2;

			double grid = Captions.clamp(t / 0.5);
			int mapW = (int) Math.round(size * cols);
			int mapH = (int) Math.round(size * rows);
			BufferedImage backdrop = backdrop(mapW, mapH);
			Shape oldClip = g.getClip();
			RoundRectangle2D frame = new RoundRectangle2D.Double(left, top, mapW, mapH, u * 0.03, u * 0.03);
			if (backdrop != null)
			{
				Composite oldComposite = g.getComposite();
				g.clip(frame);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) grid));
				g.drawImage(backdrop, (int) left, (int) top, null);
				g.setComposite(oldComposite);
				// Dimmed a little so the glow reads on top of it.
				g.setColor(Theme.alpha(th.light() ? Color.WHITE : Color.BLACK, 0.3 * grid));
				g.fill(frame);
				g.setClip(oldClip);
				g.setColor(Theme.alpha(th.panel, 0.9 * grid));
				g.draw(frame);
			}
			else
			{
				g.setColor(Theme.alpha(th.panel, 0.55 * grid));
				for (int cx = 0; cx < cols; cx++)
				{
					for (int cy = 0; cy < rows; cy++)
					{
						int gap = Math.max(1, (int) (size * 0.08));
						g.fillRect((int) (left + cx * size) + gap / 2, (int) (top + cy * size) + gap / 2, (int) size - gap, (int) size - gap);
					}
				}
			}

			double logMax = Math.log1p(max);
			int i = 0;
			Paint old = g.getPaint();
			for (java.util.Map.Entry<Integer, Long> e : cells.entrySet())
			{
				int x = e.getKey() >> 8;
				int y = e.getKey() & 0xff;
				double heat = Math.log1p(e.getValue()) / logMax;
				double p = Captions.ease(Captions.clamp((t - 0.4 - (1 - heat) * 0.8 - (i++ % 7) * 0.03) / 0.6));
				if (p <= 0)
				{
					continue;
				}
				float cx = (float) (left + (x - x0 + 0.5) * size);
				float cy = (float) (top + (y1 - y + 0.5) * size);
				float r = (float) (size * (1.3 + 2.2 * heat) * (0.6 + 0.4 * p));
				Color hot = Theme.mix(th.accent2, th.accent, heat);
				g.setPaint(new RadialGradientPaint(cx, cy, r, new float[]{0f, 0.45f, 1f},
					new Color[]{Theme.alpha(hot, (0.35 + 0.6 * heat) * p), Theme.alpha(hot, 0.25 * heat * p), Theme.alpha(hot, 0)},
					MultipleGradientPaint.CycleMethod.NO_CYCLE));
				g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
				g.setPaint(Theme.alpha(Color.WHITE, 0.5 * heat * p));
				int dot = Math.max(3, (int) (size * 0.3));
				g.fillOval((int) cx - dot / 2, (int) cy - dot / 2, dot, dot);
			}
			g.setPaint(old);

			// Pins on the map, names listed below it.
			Font pinFont = th.bold(u * 0.032f);
			for (int k = 0; k < places.size(); k++)
			{
				Place pl = places.get(k);
				double p = Splash.backOut(Captions.clamp((t - 1.6 - k * 0.25) / 0.4));
				if (p <= 0)
				{
					continue;
				}
				int cx = (int) (left + (pl.x - x0 + 0.5) * size);
				int cy = (int) (top + (y1 - pl.y + 0.5) * size);
				int d = (int) (u * 0.05 * p);
				g.setColor(th.accent);
				g.fillOval(cx - d / 2, cy - d / 2, d, d);
				Captions.centered(g, Integer.toString(k + 1), cx, cy + (int) (d * 0.22), th.bold(d * 0.6f), Scenes.isBright(th.accent) ? new Color(0x111111) : Color.WHITE, d * 2);
			}
			int listTop = (int) (area.y + area.height + u * 0.07);
			int lineH = (int) (u * 0.055);
			g.setFont(pinFont);
			FontMetrics fm = g.getFontMetrics();
			for (int k = 0; k < places.size(); k++)
			{
				Place pl = places.get(k);
				double p = Captions.clamp((t - 1.8 - k * 0.25) / 0.4);
				if (p <= 0)
				{
					continue;
				}
				String line = (k + 1) + ". " + pl.name + "  ·  " + Recap.duration(pl.seconds);
				int x = (w - fm.stringWidth(line)) / 2;
				shadowed(g, line, x, listTop + k * lineH, th, k == 0 ? Theme.alpha(th.accent, p) : ink(th, p));
			}
		}
	}

	/** Bar charts of XP, time and loot across the period, or of the hours played for a one-day recap. */
	static final class Graphs extends Scenes.Scene
	{
		/** Bar labels are thinned to about this many. */
		private static final int MAX_LABELS = 8;
		private static final DateTimeFormatter DAY_OF_MONTH = DateTimeFormatter.ofPattern("d", Locale.ENGLISH);
		private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
		private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
		private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

		static final class Chart
		{
			final String title;
			final long[] values;
			final Function<Long, String> format;

			Chart(String title, long[] values, Function<Long, String> format)
			{
				this.title = title;
				this.values = values;
				this.format = format;
			}

			long max()
			{
				long m = 0;
				for (long v : values)
				{
					m = Math.max(m, v);
				}
				return m;
			}

			int peak()
			{
				int best = 0;
				for (int i = 1; i < values.length; i++)
				{
					if (values[i] > values[best])
					{
						best = i;
					}
				}
				return best;
			}
		}

		final String title;
		final List<String> labels;
		final List<Chart> charts;

		Graphs(Scenes.Look look, double duration, String title, List<String> labels, List<Chart> charts)
		{
			super("graphs", look, duration);
			this.title = title;
			this.labels = labels;
			this.charts = charts;
		}

		/** The graphs for a recap, or null when there's too little to draw. */
		static Graphs of(Scenes.Look look, double duration, Recap r)
		{
			List<String> labels = new ArrayList<>();
			List<Chart> charts = new ArrayList<>();
			if (r.series.isEmpty())
			{
				long[] hours = r.total.hours.clone();
				for (int hr = 0; hr < hours.length; hr++)
				{
					labels.add(hr % 6 == 0 ? Integer.toString(hr) : "");
				}
				add(charts, new Chart("Time played by hour", hours, Recap::duration));
				return charts.isEmpty() ? null : new Graphs(look, duration, "When you played", labels, charts);
			}
			if (r.series.size() < 2)
			{
				return null;
			}
			int n = r.series.size();
			long[] xp = new long[n];
			long[] time = new long[n];
			long[] loot = new long[n];
			for (int i = 0; i < n; i++)
			{
				Recap.Bucket b = r.series.get(i);
				xp[i] = b.xp;
				time[i] = b.seconds;
				loot[i] = b.loot;
				labels.add(label(b.start, r.step, n));
			}
			add(charts, new Chart("XP gained", xp, Recap::number));
			add(charts, new Chart("Time played", time, Recap::duration));
			add(charts, new Chart("Loot", loot, Recap::gp));
			String title = r.step == Recap.Step.DAY ? "By the day" : r.step == Recap.Step.WEEK ? "By the week" : "By the month";
			return charts.isEmpty() ? null : new Graphs(look, duration, title, labels, charts);
		}

		private static void add(List<Chart> charts, Chart c)
		{
			if (c.max() > 0)
			{
				charts.add(c);
			}
		}

		static String label(LocalDate start, Recap.Step step, int bars)
		{
			switch (step)
			{
				case WEEK:
					return start.format(WEEK);
				case MONTH:
					return start.format(MONTH);
				default:
					return (bars <= 7 ? start.format(WEEKDAY) : start.format(DAY_OF_MONTH));
			}
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, title, w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			int left = (int) (w * (tall ? 0.08 : 0.1));
			int width = w - 2 * left;
			int top = (int) (h * (tall ? 0.2 : 0.17));
			int bottom = (int) (h * (tall ? 0.82 : 0.88));
			int labelH = (int) (u * 0.045);
			int gapH = (int) (u * 0.03);
			int each = (bottom - top - labelH - gapH * (charts.size() - 1)) / charts.size();
			int n = labels.size();
			double slot = width / (double) n;
			double barW = Math.max(1, slot * (n > 40 ? 0.8 : 0.66));
			Font titleFont = th.bold(u * 0.034f);
			Font noteFont = th.regular(u * 0.028f);

			for (int c = 0; c < charts.size(); c++)
			{
				Chart chart = charts.get(c);
				double start = 0.3 + c * 0.35;
				double a = Captions.clamp((t - start) / 0.3);
				if (a <= 0)
				{
					continue;
				}
				int y = top + c * (each + gapH);
				g.setColor(Theme.alpha(th.panel, 0.7 * a));
				g.fillRoundRect(left - u / 60, y, width + u / 30, each, u / 30, u / 30);

				g.setFont(titleFont);
				FontMetrics fm = g.getFontMetrics();
				int titleY = y + fm.getAscent() + u / 80;
				shadowed(g, chart.title, left, titleY, th, ink(th, a));
				int peak = chart.peak();
				long max = chart.max();
				String best = "best " + chart.format.apply(max);
				g.setFont(noteFont);
				FontMetrics nm = g.getFontMetrics();
				shadowed(g, best, left + width - nm.stringWidth(best), titleY, th, Theme.alpha(th.accent, a));

				int plotTop = titleY + u / 60;
				int plotBottom = y + each - u / 60;
				int plotH = Math.max(1, plotBottom - plotTop);
				g.setColor(Theme.alpha(th.muted, 0.5 * a));
				g.fillRect(left, plotBottom, width, Math.max(1, u / 400));
				for (int i = 0; i < n; i++)
				{
					long v = chart.values[i];
					if (v <= 0)
					{
						continue;
					}
					double p = Captions.ease(Captions.clamp((t - start - 0.15 - i * Math.min(0.04, 1.0 / n)) / 0.5));
					int bh = (int) Math.max(2, plotH * (v / (double) max) * p);
					int bx = (int) (left + i * slot + (slot - barW) / 2);
					Color fill = i == peak ? th.accent : Theme.mix(th.accent2, th.accent, 0.35 * v / max);
					g.setColor(Theme.alpha(fill, (i == peak ? 1 : 0.8) * a));
					int arc = (int) Math.min(barW, u * 0.012);
					g.fillRoundRect(bx, plotBottom - bh, (int) Math.ceil(barW), bh + arc, arc, arc);
				}
			}

			// Labels under the last chart, thinned out so they don't overlap.
			double a = Captions.clamp((t - 0.6) / 0.4);
			g.setFont(noteFont);
			FontMetrics fm = g.getFontMetrics();
			int every = Math.max(1, (int) Math.ceil(n / (double) MAX_LABELS));
			int labelY = bottom - labelH + fm.getAscent();
			for (int i = 0; i < n; i++)
			{
				String s = labels.get(i);
				if (s.isEmpty() || (every > 1 && i % every != 0))
				{
					continue;
				}
				int cx = (int) (left + (i + 0.5) * slot);
				shadowed(g, s, cx - fm.stringWidth(s) / 2, labelY, th, Theme.alpha(th.muted, a));
			}
		}
	}

	/** Two columns of numbers side by side: this period against the last one, or the player against a friend. */
	static final class Duel extends Scenes.Scene
	{
		static final class Row
		{
			final String label;
			final long left;
			final long right;
			final String leftText;
			final String rightText;
			/** "+23%" and similar, or null. */
			final String note;
			/** For deaths: the smaller number wins. */
			final boolean lowerWins;

			Row(String label, long left, long right, String leftText, String rightText, String note, boolean lowerWins)
			{
				this.label = label;
				this.left = left;
				this.right = right;
				this.leftText = leftText;
				this.rightText = rightText;
				this.note = note;
				this.lowerWins = lowerWins;
			}

			/** 1 when the right side wins, -1 when the left does, 0 for a draw. */
			int winner()
			{
				int c = Long.compare(right, left);
				return lowerWins ? -c : c;
			}
		}

		static final int MAX_ROWS = 6;
		private final String title;
		private final String leftName;
		private final String rightName;
		final List<Row> rows;
		final String summary;
		/** Chat badges drawn before each name, e.g. an Ironman helmet; null for none. */
		BufferedImage leftBadge;
		BufferedImage rightBadge;

		Duel(String name, Scenes.Look look, double duration, String title, String leftName, String rightName, List<Row> rows, String summary)
		{
			super(name, look, duration);
			this.title = title;
			this.leftName = leftName;
			this.rightName = rightName;
			this.rows = rows.size() > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;
			this.summary = summary;
		}

		/** "Up on 4 of 6" for a this-vs-last comparison. */
		static Duel compare(Scenes.Look look, double duration, Recap r, List<Recap.Comparison> comparisons)
		{
			List<Row> rows = new ArrayList<>();
			int up = 0;
			for (Recap.Comparison c : comparisons)
			{
				boolean lower = "Deaths".equals(c.label);
				Row row = new Row(c.label, c.before, c.now, c.show(c.before), c.show(c.now), c.change(), lower);
				rows.add(row);
				if (rows.size() <= MAX_ROWS && row.winner() > 0)
				{
					up++;
				}
			}
			int n = Math.min(MAX_ROWS, rows.size());
			String noun = r.period.noun();
			String summary = up * 2 > n ? "Better on " + up + " of " + n : up == 0 ? "A quieter " + noun : "Up on " + up + " of " + n;
			return new Duel("compare", look, duration, "This " + noun + " vs last", ("Last " + noun), ("This " + noun), rows, summary);
		}

		/** The player on the left, the friend on the right; "You win 4–2". */
		static Duel versus(Scenes.Look look, double duration, Recap r, FriendFile f, String you)
		{
			List<Row> rows = new ArrayList<>();
			add(rows, "XP gained", r.total.totalXp(), f.xp, Recap::number);
			add(rows, "Time played", r.total.playSeconds, f.seconds, Recap::duration);
			add(rows, "Loot", r.total.lootValue, f.loot, Recap::gp);
			add(rows, "Levels", r.total.totalLevels(), f.levels, Recap::number);
			add(rows, "Boss kills", r.totalBossKills(), f.bossKills, Recap::number);
			add(rows, "Kills", r.totalKills, f.kills, Recap::number);
			add(rows, "Days played", r.daysPlayed, f.days, Recap::number);
			add(rows, "Collection log", r.collection.size(), f.collection, Recap::number);
			if (r.total.deaths > 0 || f.deaths > 0)
			{
				rows.add(new Row("Deaths", r.total.deaths, f.deaths, Recap.number(r.total.deaths), Recap.number(f.deaths), null, true));
			}
			List<Row> shown = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
			int mine = 0;
			int theirs = 0;
			for (Row row : shown)
			{
				// Left is the player here, so a right win is the friend's.
				if (row.winner() < 0)
				{
					mine++;
				}
				else if (row.winner() > 0)
				{
					theirs++;
				}
			}
			String them = f.player;
			String summary = mine > theirs ? "You win " + mine + "–" + theirs : mine < theirs ? them + " wins " + theirs + "–" + mine : "A draw, " + mine + "–" + theirs;
			return new Duel("versus", look, duration, "You vs " + them, you, them, new ArrayList<>(shown), summary);
		}

		/** The badge just before a name centred on {@code cx}, as tall as the name's capitals. */
		private static void nameBadge(Graphics2D g, BufferedImage badge, String name, int cx, int baseline, Font f, int maxW, double alpha)
		{
			if (badge == null || name == null || alpha <= 0)
			{
				return;
			}
			FontMetrics fm = g.getFontMetrics(f);
			int side = (int) (fm.getAscent() * 0.8);
			BufferedImage big = Sprites.upscale(badge, side);
			int x = cx - Math.min(maxW, fm.stringWidth(name)) / 2 - big.getWidth() - side / 4;
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, alpha)));
			g.drawImage(big, Math.max(0, x), baseline - big.getHeight(), null);
			g.setComposite(old);
		}

		private static void add(List<Row> rows, String label, long a, long b, java.util.function.LongFunction<String> fmt)
		{
			if (a > 0 || b > 0)
			{
				rows.add(new Row(label, a, b, fmt.apply(a), fmt.apply(b), null, false));
			}
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, title, w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			int cx = w / 2;
			int colW = (int) (w * (tall ? 0.42 : 0.3));
			int headY = (int) (h * (tall ? 0.24 : 0.23));
			double hp = Captions.clamp((t - 0.2) / 0.4);
			Font nameFont = th.bold(u * 0.05f);
			Captions.centered(g, leftName, cx - colW / 2 - u / 40, headY, nameFont, Theme.alpha(th.accent2, hp), colW);
			Captions.centered(g, rightName, cx + colW / 2 + u / 40, headY, nameFont, Theme.alpha(th.accent, hp), colW);
			nameBadge(g, leftBadge, leftName, cx - colW / 2 - u / 40, headY, nameFont, colW, hp);
			nameBadge(g, rightBadge, rightName, cx + colW / 2 + u / 40, headY, nameFont, colW, hp);

			int top = (int) (headY + u * 0.05);
			int bottom = (int) (h * (tall ? 0.8 : 0.84));
			int rowH = rows.isEmpty() ? 0 : Math.min((int) (u * 0.13), (bottom - top) / rows.size());
			Font labelFont = th.regular(rowH * 0.24f);
			Font valueFont = th.bold(rowH * 0.34f);
			Font noteFont = th.bold(rowH * 0.22f);
			for (int i = 0; i < rows.size(); i++)
			{
				Row row = rows.get(i);
				double p = Captions.ease(Captions.clamp((t - 0.5 - i * 0.18) / 0.5));
				if (p <= 0)
				{
					continue;
				}
				int y = top + i * rowH;
				Captions.centered(g, row.label.toUpperCase(Locale.ROOT), cx, y + (int) (rowH * 0.3), labelFont, Theme.alpha(th.muted, p), (int) (w * 0.8));
				long most = Math.max(1, Math.max(row.left, row.right));
				int barH = (int) (rowH * 0.42);
				int barY = y + (int) (rowH * 0.4);
				int gap = u / 80;
				int lw = (int) (colW * Math.max(0.03, row.left / (double) most) * p);
				int rw = (int) (colW * Math.max(0.03, row.right / (double) most) * p);
				g.setColor(Theme.alpha(th.panel, 0.8 * p));
				g.fillRoundRect(cx - gap - colW, barY, colW, barH, barH / 2, barH / 2);
				g.fillRoundRect(cx + gap, barY, colW, barH, barH / 2, barH / 2);
				int win = row.winner();
				g.setColor(Theme.alpha(th.accent2, (win < 0 ? 1 : 0.55) * p));
				g.fillRoundRect(cx - gap - lw, barY, lw, barH, barH / 2, barH / 2);
				g.setColor(Theme.alpha(th.accent, (win > 0 ? 1 : 0.55) * p));
				g.fillRoundRect(cx + gap, barY, rw, barH, barH / 2, barH / 2);
				g.setFont(valueFont);
				FontMetrics fm = g.getFontMetrics();
				int base = barY + (barH + fm.getAscent() - fm.getDescent()) / 2;
				shadowed(g, row.leftText, cx - gap - colW + barH / 3, base, th, ink(th, p));
				shadowed(g, row.rightText, cx + gap + colW - barH / 3 - fm.stringWidth(row.rightText), base, th, ink(th, p));
				if (row.note != null)
				{
					g.setFont(noteFont);
					FontMetrics nm = g.getFontMetrics();
					boolean good = win > 0;
					Color c = "same".equals(row.note) ? th.muted : good ? new Color(0x4CD07D) : new Color(0xE0605A);
					int nx = cx + gap + colW + u / 60;
					if (nx + nm.stringWidth(row.note) < w - u / 60)
					{
						shadowed(g, row.note, nx, base, th, Theme.alpha(c, p));
					}
				}
			}
			double sp = Splash.backOut(Captions.clamp((t - 0.8 - rows.size() * 0.18) / 0.5));
			if (sp > 0 && summary != null)
			{
				Captions.centered(g, summary, cx, (int) (h * (tall ? 0.88 : 0.93)), th.bold((float) (u * 0.06 * sp)), th.accent, (int) (w * 0.9));
			}
		}
	}

	/** "99 Mining in 41 days" at this period's rate. */
	static final class Pace extends Scenes.Scene
	{
		private final List<Recap.Pace> paces;
		private final List<BufferedImage> icons;
		private int iconsFor = -1;
		private final List<BufferedImage> big = new ArrayList<>();

		Pace(Scenes.Look look, double duration, List<Recap.Pace> paces, List<BufferedImage> icons)
		{
			super("pace", look, duration);
			this.paces = paces;
			this.icons = icons;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, "At this pace", w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			int iconSize = (int) (u * 0.2);
			if (iconsFor != iconSize)
			{
				iconsFor = iconSize;
				big.clear();
				for (BufferedImage img : icons)
				{
					big.add(img == null ? null : Sprites.splash(img, iconSize, th.accent));
				}
			}
			Recap.Pace first = paces.get(0);
			double p = Splash.backOut(Captions.clamp((t - 0.2) / 0.5));
			int iconY = (int) (h * (tall ? 0.28 : 0.27));
			BufferedImage icon = big.isEmpty() ? null : big.get(0);
			if (icon != null)
			{
				Sprites.drawCentered(g, icon, w / 2.0, iconY + Math.sin(t * 2) * u * 0.008, p, 0);
			}
			else
			{
				initial(g, first.skill, w / 2.0, iconY, (int) (iconSize * 0.7), th, p);
			}
			double q = Captions.clamp((t - 0.5) / 0.4);
			Captions.centered(g, first.goal, w / 2, (int) (h * (tall ? 0.43 : 0.47)), th.bold(u * 0.085f), ink(th, q), (int) (w * 0.9));
			float pop = (float) (1 + 0.08 * Math.max(0, 1 - Math.abs(t - 1.2) / 0.2));
			Captions.centered(g, first.when(), w / 2, (int) (h * (tall ? 0.52 : 0.6)), th.bold(u * 0.1f * pop), Theme.alpha(th.accent, Captions.clamp((t - 0.9) / 0.3)), (int) (w * 0.92));
			Captions.centered(g, "at " + Recap.number(first.xpPerDay) + " XP a day  ·  " + Recap.number(first.xpLeft) + " to go", w / 2,
				(int) (h * (tall ? 0.57 : 0.67)), th.regular(u * 0.035f), Theme.alpha(th.muted, Captions.clamp((t - 1.2) / 0.4)), (int) (w * 0.9));

			// Days-to-go bar: a full year across, the goal's day marked.
			int barW = (int) (w * (tall ? 0.8 : 0.6));
			int barH = (int) (u * 0.025);
			int bx = (w - barW) / 2;
			int by = (int) (h * (tall ? 0.61 : 0.72));
			double fill = Captions.ease(Captions.clamp((t - 1.0) / 1.0)) * Math.min(1, first.days / (double) Recap.PACE_HORIZON_DAYS);
			g.setColor(Theme.alpha(th.panel, q));
			g.fillRoundRect(bx, by, barW, barH, barH, barH);
			g.setColor(Theme.alpha(th.accent, q));
			g.fillRoundRect(bx, by, Math.max(barH, (int) (barW * fill)), barH, barH, barH);

			int lineY = (int) (h * (tall ? 0.72 : 0.83));
			int lineH = (int) (u * 0.07);
			for (int i = 1; i < paces.size(); i++)
			{
				double a = Captions.clamp((t - 1.6 - i * 0.3) / 0.4);
				if (a <= 0)
				{
					continue;
				}
				Captions.centered(g, paces.get(i).line(), w / 2, lineY + (i - 1) * lineH, th.bold(u * 0.045f), ink(th, a), (int) (w * 0.9));
			}
		}
	}

	/** Top bosses with their cutouts, kill counts and fastest kills, and Slayer tasks done. */
	static final class Bosses extends Scenes.Scene
	{
		static final int MAX = 4;
		private final List<Recap.Entry> bosses;
		private final java.util.Map<String, Long> fastest = new HashMap<>();
		private final java.util.Map<String, BufferedImage> cutouts;
		private final int slayerTasks;
		private int cutoutsFor = -1;
		private final java.util.Map<String, BufferedImage> big = new HashMap<>();

		/**
		 * @param cutouts NPC sticker images keyed by {@link ClipLibrary#safeName}
		 */
		Bosses(Scenes.Look look, double duration, List<Recap.Entry> bosses, List<Recap.Entry> fastest, java.util.Map<String, BufferedImage> cutouts, int slayerTasks)
		{
			super("bosses", look, duration);
			this.bosses = Recap.top(bosses, MAX);
			for (Recap.Entry e : fastest)
			{
				this.fastest.put(e.name, e.value);
			}
			this.cutouts = cutouts;
			this.slayerTasks = slayerTasks;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, bosses.isEmpty() ? "Slayer" : "Boss hunting", w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			int n = bosses.size();
			if (n > 0)
			{
				int cols = tall ? Math.min(2, n) : n;
				int rowsN = (n + cols - 1) / cols;
				Rectangle area = new Rectangle((int) (w * 0.06), (int) (h * (tall ? 0.2 : 0.18)), (int) (w * 0.88), (int) (h * (tall ? 0.58 : 0.6)));
				int cellW = area.width / cols;
				int cellH = area.height / rowsN;
				int pic = (int) Math.min(cellW * 0.7, cellH * 0.55);
				if (cutoutsFor != pic)
				{
					cutoutsFor = pic;
					big.clear();
					for (Recap.Entry e : bosses)
					{
						BufferedImage img = cutoutFor(e.name, cutouts);
						if (img != null)
						{
							big.put(e.name, Sprites.upscale(bust(Sprites.trim(img)), pic));
						}
					}
				}
				for (int i = 0; i < n; i++)
				{
					Recap.Entry e = bosses.get(i);
					double p = Splash.backOut(Captions.clamp((t - 0.3 - i * 0.25) / 0.5));
					if (p <= 0)
					{
						continue;
					}
					int cx = area.x + cellW * (i % cols) + cellW / 2;
					int top = area.y + cellH * (i / cols);
					double picY = top + pic * 0.55 + Math.sin(t * 2 + i) * u * 0.006;
					g.setColor(Theme.alpha(th.panel, 0.7 * Math.min(1, p)));
					int disc = (int) (pic * 1.05 * p);
					g.fillOval(cx - disc / 2, (int) picY - disc / 2, disc, disc);
					BufferedImage img = big.get(e.name);
					if (img != null)
					{
						portrait(g, img, cx, picY, disc);
					}
					else
					{
						initial(g, e.name, cx, picY, (int) (pic * 0.7), th, p);
					}
					double a = Captions.clamp((t - 0.6 - i * 0.25) / 0.4);
					int textY = (int) (top + pic * 1.2);
					String[] label = label(e.name, e.value);
					Captions.centered(g, label[0], cx, textY, th.bold(u * 0.036f), ink(th, a), (int) (cellW * 0.95));
					Captions.centered(g, label[1], cx, textY + (int) (u * 0.05), th.bold(u * 0.045f), Theme.alpha(th.accent, a), (int) (cellW * 0.95));
					Long pb = fastest.get(e.name);
					if (pb != null && pb > 0)
					{
						Captions.centered(g, "Fastest " + time(pb), cx, textY + (int) (u * 0.09), th.regular(u * 0.03f), Theme.alpha(th.muted, a), (int) (cellW * 0.95));
					}
				}
			}
			if (slayerTasks > 0)
			{
				double a = Splash.backOut(Captions.clamp((t - 0.6 - n * 0.25) / 0.5));
				String line = slayerTasks + (slayerTasks == 1 ? " Slayer task done" : " Slayer tasks done");
				float size = (float) (u * (n == 0 ? 0.09 : 0.05) * Math.max(0.01, a));
				Captions.centered(g, line, w / 2, (int) (h * (n == 0 ? 0.5 : tall ? 0.88 : 0.9)), th.bold(size), th.accent, (int) (w * 0.9));
			}
		}

		private static final String[] RAIDS = {"chambers of xeric", "theatre of blood", "tombs of amascut", "gauntlet", "corrupted gauntlet"};

		/**
		 * The name and count lines under a boss bubble. Kill counts named after a chest, like "Bryophyta chests
		 * opened", read as the boss and "3 chests opened"; raids count completions.
		 */
		static String[] label(String name, long count)
		{
			String n = Recap.number(count);
			String lower = name.toLowerCase(java.util.Locale.ROOT);
			for (String suffix : new String[]{" chests opened", " chest opened", " chests", " chest"})
			{
				if (lower.endsWith(suffix) && name.length() > suffix.length())
				{
					return new String[]{name.substring(0, name.length() - suffix.length()), n + (count == 1 ? " chest opened" : " chests opened")};
				}
			}
			for (String raid : RAIDS)
			{
				if (lower.startsWith(raid))
				{
					return new String[]{name, n + (count == 1 ? " completion" : " completions")};
				}
			}
			return new String[]{name, n + (count == 1 ? " kill" : " kills")};
		}

		/**
		 * The cutout for a boss: one under its own name, else the longest NPC name the boss name starts with,
		 * so "Bryophyta chests opened" finds Bryophyta. Null when there is none.
		 */
		static BufferedImage cutoutFor(String boss, java.util.Map<String, BufferedImage> cutouts)
		{
			String key = ClipLibrary.safeName(boss);
			BufferedImage exact = cutouts.get(key);
			if (exact != null)
			{
				return exact;
			}
			String best = null;
			for (String k : cutouts.keySet())
			{
				if (key.startsWith(k + "_") && (best == null || k.length() > best.length()))
				{
					best = k;
				}
			}
			return best == null ? null : cutouts.get(best);
		}

		/** The top of a trimmed cutout, from about the waist up; creatures wider than tall are kept whole. */
		static BufferedImage bust(BufferedImage img)
		{
			int w = img.getWidth();
			int h = img.getHeight();
			int keep = Math.min(h, Math.max(w, (int) Math.round(h * 0.6)));
			return keep == h ? img : img.getSubimage(0, 0, w, keep);
		}

		/** Draws a bust sitting on the bottom of the bubble of diameter {@code disc}, clipped to the circle. */
		private static void portrait(Graphics2D g, BufferedImage img, double cx, double cy, int disc)
		{
			if (disc < 2)
			{
				return;
			}
			double scale = Math.min(disc * 0.95 / img.getWidth(), disc * 0.92 / img.getHeight());
			double dw = img.getWidth() * scale;
			double dh = img.getHeight() * scale;
			Shape clip = g.getClip();
			g.clip(new Ellipse2D.Double(cx - disc / 2.0, cy - disc / 2.0, disc, disc));
			Object interp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			AffineTransform at = new AffineTransform();
			at.translate(cx - dw / 2, cy + disc / 2.0 - dh);
			at.scale(scale, scale);
			g.drawImage(img, at, null);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp == null ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : interp);
			g.setClip(clip);
		}
	}

	/** New collection log slots and pets as a grid of item icons, with Combat Achievement and diary tasks. */
	static final class Collection extends Scenes.Scene
	{
		static final int MAX_ICONS = 24;
		private final List<BufferedImage> icons;
		private final int slots;
		private final int combatTasks;
		private final int diaryTasks;
		private int iconsFor = -1;
		private final List<BufferedImage> big = new ArrayList<>();

		Collection(Scenes.Look look, double duration, List<BufferedImage> icons, int slots, int combatTasks, int diaryTasks)
		{
			super("collection", look, duration);
			this.icons = icons.size() > MAX_ICONS ? new ArrayList<>(icons.subList(0, MAX_ICONS)) : icons;
			this.slots = slots;
			this.combatTasks = combatTasks;
			this.diaryTasks = diaryTasks;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, slots > 0 ? "Collection log" : "Achievements", w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			if (slots > 0)
			{
				long shown = Math.round(slots * Captions.ease(Captions.clamp(t / 1.0)));
				Captions.centered(g, shown + (slots == 1 ? " new slot" : " new slots"), w / 2, (int) (h * (tall ? 0.24 : 0.25)), th.bold(u * 0.09f), th.accent, (int) (w * 0.9));
			}
			int n = icons.size();
			if (n > 0)
			{
				int cols = (int) Math.ceil(Math.sqrt(n * (tall ? 0.6 : 1.8)));
				cols = Math.max(1, Math.min(n, cols));
				int rows = (n + cols - 1) / cols;
				Rectangle area = new Rectangle((int) (w * 0.1), (int) (h * (tall ? 0.3 : 0.32)), (int) (w * 0.8), (int) (h * (tall ? 0.45 : 0.44)));
				int cell = Math.min(area.width / cols, area.height / rows);
				int size = (int) (cell * 0.85);
				if (iconsFor != size)
				{
					iconsFor = size;
					big.clear();
					for (BufferedImage img : icons)
					{
						big.add(Sprites.splash(img, size, th.accent));
					}
				}
				double left = area.x + (area.width - cell * cols) / 2.0;
				double top = area.y + (area.height - cell * rows) / 2.0;
				double step = Math.min(0.15, 1.6 / n);
				for (int i = 0; i < n; i++)
				{
					double p = Captions.clamp((t - 0.5 - i * step) / 0.35);
					BufferedImage img = big.get(i);
					if (p <= 0 || img == null)
					{
						continue;
					}
					double cx = left + cell * (i % cols + 0.5);
					double cy = top + cell * (i / cols + 0.5);
					g.setColor(Theme.alpha(th.panel, 0.6 * p));
					int pad = cell / 12;
					g.fillRoundRect((int) (cx - cell / 2.0) + pad, (int) (cy - cell / 2.0) + pad, cell - pad * 2, cell - pad * 2, cell / 5, cell / 5);
					Sprites.drawCentered(g, img, cx, cy + Math.sin(t * 2.4 + i) * cell * 0.03, Splash.backOut(p), 0);
				}
			}
			List<String> parts = new ArrayList<>();
			if (combatTasks > 0)
			{
				parts.add(combatTasks + (combatTasks == 1 ? " combat task" : " combat tasks"));
			}
			if (diaryTasks > 0)
			{
				parts.add(diaryTasks + (diaryTasks == 1 ? " diary task" : " diary tasks"));
			}
			if (!parts.isEmpty())
			{
				double a = Splash.backOut(Captions.clamp((t - 1.2 - Math.min(1.6, n * 0.15)) / 0.5));
				boolean alone = slots == 0 && n == 0;
				Captions.centered(g, String.join("  ·  ", parts), w / 2, (int) (h * (alone ? 0.5 : tall ? 0.85 : 0.88)),
					th.bold((float) (u * (alone ? 0.07 : 0.045) * Math.max(0.01, a))), alone ? th.accent : ink(th, 1), (int) (w * 0.92));
			}
		}
	}

	/** Grand Exchange: gp in and out, the net and the biggest trades. */
	static final class Exchange extends Scenes.Scene
	{
		private final long bought;
		private final long sold;
		private final List<DayRecord.Trade> trades;
		private final List<BufferedImage> icons;
		private int iconsFor = -1;
		private final List<BufferedImage> big = new ArrayList<>();

		Exchange(Scenes.Look look, double duration, long bought, long sold, List<DayRecord.Trade> trades, List<BufferedImage> icons)
		{
			super("ge", look, duration);
			this.bought = bought;
			this.sold = sold;
			this.trades = trades.size() > 3 ? new ArrayList<>(trades.subList(0, 3)) : trades;
			this.icons = icons;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			background(g, w, h, t);
			heading(g, look, "Grand Exchange", w, h, t);
			boolean tall = h > w * 1.2;
			int u = Math.min(w, h);
			long net = sold - bought;
			long shown = Math.round(net * Captions.ease(Captions.clamp(t / 1.4)));
			Color netColor = net >= 0 ? new Color(0x4CD07D) : new Color(0xE0605A);
			float pop = (float) (1 + 0.08 * Math.max(0, 1 - Math.abs(t - 1.5) / 0.2));
			Captions.centered(g, (shown >= 0 ? "+" : "-") + Recap.gp(Math.abs(shown)), w / 2, (int) (h * (tall ? 0.26 : 0.28)), th.bold(u * 0.13f * pop), netColor, (int) (w * 0.92));
			Captions.centered(g, net >= 0 ? "net profit" : "net spent", w / 2, (int) (h * (tall ? 0.31 : 0.35)), th.regular(u * 0.035f), Theme.alpha(th.muted, Captions.clamp((t - 0.4) / 0.4)), w);

			double a = Captions.clamp((t - 0.8) / 0.4);
			int colY = (int) (h * (tall ? 0.39 : 0.46));
			int dx = (int) (w * (tall ? 0.22 : 0.17));
			Captions.centered(g, "BOUGHT", w / 2 - dx, colY, th.regular(u * 0.03f), Theme.alpha(th.muted, a), dx * 2);
			Captions.centered(g, "SOLD", w / 2 + dx, colY, th.regular(u * 0.03f), Theme.alpha(th.muted, a), dx * 2);
			Captions.centered(g, Recap.gp(bought), w / 2 - dx, colY + (int) (u * 0.06), th.bold(u * 0.055f), ink(th, a), dx * 2);
			Captions.centered(g, Recap.gp(sold), w / 2 + dx, colY + (int) (u * 0.06), th.bold(u * 0.055f), ink(th, a), dx * 2);

			if (trades.isEmpty())
			{
				return;
			}
			int rowH = (int) (u * 0.085);
			int iconSize = (int) (rowH * 0.9);
			if (iconsFor != iconSize)
			{
				iconsFor = iconSize;
				big.clear();
				for (BufferedImage img : icons)
				{
					big.add(img == null ? null : Sprites.splash(img, iconSize, new Color(0x111111)));
				}
			}
			int listW = (int) (w * (tall ? 0.84 : 0.6));
			int lx = (w - listW) / 2;
			int ly = (int) (h * (tall ? 0.52 : 0.62));
			Captions.centered(g, trades.size() == 1 ? "BIGGEST TRADE" : "BIGGEST TRADES", w / 2, ly, th.regular(u * 0.03f), Theme.alpha(th.muted, Captions.clamp((t - 1.2) / 0.4)), w);
			Font f = th.bold(rowH * 0.36f);
			for (int i = 0; i < trades.size(); i++)
			{
				double p = Captions.ease(Captions.clamp((t - 1.4 - i * 0.2) / 0.4));
				if (p <= 0)
				{
					continue;
				}
				DayRecord.Trade tr = trades.get(i);
				int y = ly + (int) (u * 0.025) + i * rowH;
				g.setColor(Theme.alpha(th.panel, 0.8 * p));
				g.fillRoundRect(lx, y, listW, (int) (rowH * 0.86), rowH / 3, rowH / 3);
				int textX = lx + rowH / 4;
				BufferedImage icon = i < big.size() ? big.get(i) : null;
				if (icon != null)
				{
					Sprites.drawCentered(g, icon, lx + rowH * 0.5, y + rowH * 0.43, p, 0);
					textX = lx + (int) (rowH * 1.0);
				}
				g.setFont(f);
				FontMetrics fm = g.getFontMetrics();
				int base = y + ((int) (rowH * 0.86) + fm.getAscent() - fm.getDescent()) / 2;
				String v = (tr.buy ? "-" : "+") + Recap.gp(tr.value);
				String label = (tr.buy ? "Bought " : "Sold ") + (tr.quantity > 1 ? Recap.number(tr.quantity) + " x " : "") + (tr.name == null ? "item" : tr.name);
				int room = listW - (textX - lx) - fm.stringWidth(v) - rowH / 2;
				while (label.length() > 4 && fm.stringWidth(label) > room)
				{
					label = label.substring(0, label.length() - 2) + "…";
					label = label.replace("……", "…");
				}
				shadowed(g, label, textX, base, th, ink(th, p));
				shadowed(g, v, lx + listW - rowH / 4 - fm.stringWidth(v), base, th, Theme.alpha(tr.buy ? new Color(0xE0605A) : new Color(0x4CD07D), p));
			}
		}
	}

	/** A year (or any long recap) in review: each month's best clip, a few seconds each, with the month's name. */
	static final class Montage extends Scenes.Scene
	{
		static final int MIN_MONTHS = 3;
		static final double MIN_SEGMENT = 1.2;
		final List<Recap.Month> months;
		private final Function<String, List<ClipFile.Frame>> loader;
		private int loadedIndex = -1;
		private List<ClipFile.Frame> frames;
		private int decodedIndex = -1;
		private BufferedImage decoded;

		Montage(Scenes.Look look, double duration, List<Recap.Month> months, Function<String, List<ClipFile.Frame>> loader)
		{
			super("montage", look, duration);
			this.months = months;
			this.loader = loader;
		}

		@Override
		double minDuration()
		{
			return Math.min(duration, months.size() * MIN_SEGMENT);
		}

		@Override
		void open()
		{
			loadedIndex = -1;
		}

		@Override
		void close()
		{
			frames = null;
			decoded = null;
			loadedIndex = -1;
		}

		private double segment()
		{
			return duration / Math.max(1, months.size());
		}

		private BufferedImage frame(int index, double local)
		{
			if (index != loadedIndex)
			{
				loadedIndex = index;
				decodedIndex = -1;
				decoded = null;
				Moment m = months.get(index).best;
				frames = m == null || m.clip == null ? null : loader.apply(m.clip);
			}
			if (frames == null || frames.isEmpty())
			{
				return null;
			}
			long first = frames.get(0).time;
			long length = frames.get(frames.size() - 1).time - first;
			long seg = Math.round(segment() * 1000);
			// Play the part of the clip just before its end, where the moment usually is.
			long start = Math.max(0, (length - seg) * 2 / 3);
			long target = first + start + Math.round(local * 1000);
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
					// keep the previous frame
				}
				decodedIndex = lo;
			}
			return decoded;
		}

		@Override
		void render(Graphics2D g, int w, int h, double t)
		{
			Theme th = look.theme;
			int u = Math.min(w, h);
			double seg = segment();
			int index = Math.max(0, Math.min(months.size() - 1, (int) (t / seg)));
			double local = t - index * seg;
			Recap.Month m = months.get(index);
			BufferedImage img = frame(index, local);
			if (img != null)
			{
				Scenes.drawCover(g, img, 0, 0, w, h, 1.02 + 0.04 * local / seg);
				g.setColor(Theme.alpha(Color.BLACK, 0.25));
				g.fillRect(0, (int) (h * 0.62), w, h - (int) (h * 0.62));
			}
			else
			{
				background(g, w, h, t);
				List<String> lines = new ArrayList<>();
				if (m.xp > 0)
				{
					lines.add(Recap.number(m.xp) + " XP");
				}
				if (m.loot > 0)
				{
					lines.add(Recap.gp(m.loot) + " loot");
				}
				if (m.seconds > 0)
				{
					lines.add(Recap.duration(m.seconds) + " played");
				}
				for (int i = 0; i < lines.size(); i++)
				{
					double a = Captions.clamp((local - 0.15 - i * 0.12) / 0.3);
					Captions.centered(g, lines.get(i), w / 2, (int) (h * 0.42 + i * u * 0.08), th.bold(u * (i == 0 ? 0.075f : 0.05f)), i == 0 ? Theme.alpha(th.accent, a) : ink(th, a), (int) (w * 0.9));
				}
			}

			// Month name slams in at the start of each segment.
			double p = Splash.backOut(Captions.clamp(local / 0.3));
			Font big = th.bold((float) (u * 0.13 * Math.max(0.01, p)));
			Sprites.outlined(g, m.name().toUpperCase(Locale.ROOT), w / 2.0, h * 0.24, big, th.accent, -0.04, (int) (w * 0.9));
			if (img != null && m.best != null && m.best.title != null)
			{
				Captions.centered(g, m.best.title, w / 2, (int) (h * 0.8), th.bold(u * 0.045f), Theme.alpha(Color.WHITE, Captions.clamp((local - 0.2) / 0.3)), (int) (w * 0.9));
			}

			// Twelve ticks along the bottom, the current month lit.
			int n = months.size();
			int tick = (int) (u * 0.022);
			int gap = tick;
			int rowW = n * tick + (n - 1) * gap;
			int x = (w - rowW) / 2;
			int y = (int) (h * 0.9);
			for (int i = 0; i < n; i++)
			{
				g.setColor(i == index ? th.accent : i < index ? Theme.alpha(th.accent, 0.5) : Theme.alpha(Color.WHITE, 0.3));
				g.fillOval(x + i * (tick + gap), y, tick, tick);
			}
		}
	}
}
