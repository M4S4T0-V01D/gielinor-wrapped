package com.gielinorwrapped;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Draws the still share card for a recap in one of the {@link GielinorWrappedConfig.CardLayout}s. */
@Slf4j
final class ShareCard
{
	private ShareCard()
	{
	}

	static Dimension size(GielinorWrappedConfig.Aspect aspect)
	{
		switch (aspect)
		{
			case SQUARE:
				return new Dimension(1080, 1080);
			case VERTICAL:
				return new Dimension(1080, 1350);
			default:
				return new Dimension(1200, 675);
		}
	}

	/** The card scaled to the given width in halving steps, which keeps text crisp at thumbnail size. */
	static BufferedImage scale(BufferedImage card, int width)
	{
		BufferedImage img = card;
		while (img.getWidth() > width)
		{
			int w = Math.max(width, img.getWidth() / 2);
			int h = Math.max(1, (int) Math.round((double) img.getHeight() * w / img.getWidth()));
			BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, 0, 0, w, h, null);
			g.dispose();
			img = next;
		}
		return img;
	}

	/** Renders the card. {@code media} may be null; the highlight layout then falls back to the summary, and there are no icons. */
	static BufferedImage render(Recap r, GielinorWrappedConfig config, Composer.Media media)
	{
		Dimension d = size(config.aspect());
		int w = d.width;
		int h = d.height;
		Theme theme = Theme.of(config);
		Map<String, String> tok = r.tokens(config.hideName());
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Composer.hints(g);
		theme.background(g, w, h, 7.3);
		int u = Math.min(w, h);
		int m = u / 18;

		GielinorWrappedConfig.CardLayout layout = config.cardLayout();
		BufferedImage still = null;
		Moment moment = null;
		if (layout == GielinorWrappedConfig.CardLayout.HIGHLIGHT)
		{
			moment = topMoment(r);
			still = moment == null || media == null ? null : still(media, moment);
			if (still == null)
			{
				layout = GielinorWrappedConfig.CardLayout.SUMMARY;
			}
		}

		if (layout == GielinorWrappedConfig.CardLayout.VERSUS_LAST && r.comparisons.isEmpty())
		{
			layout = GielinorWrappedConfig.CardLayout.SUMMARY;
		}

		Composer.Media art = media != null && config.icons() ? media : null;
		String share = Composer.CustomCaptions.parse(config.customCaptions()).share;
		share = share == null || share.isEmpty() ? null : Recap.fill(share, tok);
		boolean shareDrawn = false;
		switch (layout)
		{
			case POSTER:
				poster(g, theme, r, config, tok, backdrop(media, r), share, w, h, u, m);
				shareDrawn = true;
				break;
			case HISCORES:
				hiscores(g, theme, r, tok, art, w, h, u, m);
				break;
			case SCROLL:
				scroll(g, theme, r, tok, art, w, h, u);
				break;
			case RECEIPT:
				receipt(g, r, tok, art, share, w, h, u);
				shareDrawn = true;
				break;
			case SPLASH:
				Splash.of(r, media, tok, config).draw(g, theme, w, h, 100);
				break;
			case TOP_FIVE:
				header(g, theme, r, config, tok, w, m, u);
				topFives(g, theme, r, art, new Rectangle(m, m + u * 22 / 100, w - 2 * m, h - m * 2 - u * 22 / 100 - u / 12), u);
				break;
			case HIGHLIGHT:
				highlight(g, theme, r, tok, moment, still, art == null ? null : Composer.momentIcon(moment, art), w, h, u, m);
				break;
			case MINIMAL:
				minimal(g, theme, r, tok, w, h, u);
				break;
			case VERSUS_LAST:
				header(g, theme, r, config, tok, w, m, u);
				versusLast(g, theme, r, new Rectangle(m, m + u * 22 / 100, w - 2 * m, h - m * 2 - u * 22 / 100 - u / 12), u);
				break;
			default:
				header(g, theme, r, config, tok, w, m, u);
				tiles(g, theme, tiles(r, tok, art), new Rectangle(m, m + u * 22 / 100, w - 2 * m, h - m * 2 - u * 22 / 100 - u / 7), u);
				if (share != null)
				{
					Captions.centered(g, share, w / 2, h - m - u / 30, theme.bold(u * 0.04f), theme.accent, w - 2 * m);
					shareDrawn = true;
				}
				else
				{
					persona(g, theme, tok, w, h - m - u / 30, u);
				}
		}
		if (share != null && !shareDrawn)
		{
			sharePill(g, theme, share, w, h, u);
		}
		footer(g, theme, config.watermark(), w, h, u);
		badge(g, theme, media == null ? null : media.badge(r.accountType), r.accountType, w, h, u);
		g.dispose();
		theme.finish(img, 0);
		return img;
	}

	private static void header(Graphics2D g, Theme theme, Recap r, GielinorWrappedConfig config, Map<String, String> tok, int w, int m, int u)
	{
		int y = m + u / 30;
		Captions.centered(g, r.period.describe().toUpperCase(), w / 2, y, theme.bold(u * 0.032f), theme.muted, w - 2 * m);
		g.setColor(theme.accent);
		g.fillRect(w / 2 - u / 10, y + u / 60, u / 5, Math.max(2, u / 200));
		Captions.centered(g, Recap.fill(config.titleText(), tok), w / 2, y + u / 9, theme.bold(u * 0.07f), theme.text, w - 2 * m);
	}

	/** This period against the last: one row per number, with the change. */
	private static void versusLast(Graphics2D g, Theme theme, Recap r, Rectangle area, int u)
	{
		List<Recap.Comparison> rows = r.comparisons.size() > 8 ? r.comparisons.subList(0, 8) : r.comparisons;
		int headH = u / 16;
		int rowH = Math.min(u / 9, (area.height - headH) / Math.max(1, rows.size()));
		int colBefore = area.x + area.width * 50 / 100;
		int colNow = area.x + area.width * 72 / 100;
		Font head = theme.bold(u * 0.026f);
		g.setFont(head);
		g.setColor(theme.muted);
		String noun = r.period.noun().toUpperCase(java.util.Locale.ROOT);
		drawRight(g, "LAST " + noun, colBefore, area.y + headH / 2);
		drawRight(g, "THIS " + noun, colNow, area.y + headH / 2);
		Font label = theme.regular(rowH * 0.34f);
		Font value = theme.bold(rowH * 0.36f);
		for (int i = 0; i < rows.size(); i++)
		{
			Recap.Comparison c = rows.get(i);
			int y = area.y + headH + i * rowH;
			int bh = rowH * 86 / 100;
			g.setColor(Theme.alpha(theme.panel, 0.85));
			g.fillRoundRect(area.x, y, area.width, bh, bh / 2, bh / 2);
			g.setFont(label);
			FontMetrics fm = g.getFontMetrics();
			int base = y + (bh + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(theme.text);
			g.drawString(c.label, area.x + bh / 3, base);
			g.setColor(theme.muted);
			drawRight(g, c.show(c.before), colBefore, base);
			g.setFont(value);
			g.setColor(theme.text);
			drawRight(g, c.show(c.now), colNow, base);
			String change = c.change();
			boolean good = "Deaths".equals(c.label) ? c.now <= c.before : c.up();
			g.setColor("same".equals(change) ? theme.muted : good ? new Color(0x4CD07D) : new Color(0xE0605A));
			drawRight(g, change, area.x + area.width - bh / 3, base);
		}
	}

	private static void drawRight(Graphics2D g, String s, int right, int base)
	{
		g.drawString(s, right - g.getFontMetrics().stringWidth(s), base);
	}

	private static final class Tile
	{
		final String value;
		final String label;
		BufferedImage icon;

		Tile(String value, String label)
		{
			this.value = value;
			this.label = label;
		}

		Tile icon(BufferedImage img)
		{
			icon = img;
			return this;
		}
	}

	private static List<Tile> tiles(Recap r, Map<String, String> tok, Composer.Media art)
	{
		List<Integer> items = art == null ? new ArrayList<>() : Splash.featuredItems(r, 1);
		BufferedImage topItem = items.isEmpty() ? null : art.item(items.get(0));
		List<Tile> t = new ArrayList<>();
		t.add(new Tile(tok.get("xp"), "XP gained"));
		t.add(new Tile(tok.get("hours"), "played"));
		if (r.total.totalLevels() > 0)
		{
			t.add(new Tile(tok.get("levels"), r.total.totalLevels() == 1 ? "level gained" : "levels gained"));
		}
		if (r.total.lootValue > 0)
		{
			t.add(new Tile(tok.get("loot"), "loot").icon(topItem));
		}
		if (r.totalKills > 0)
		{
			t.add(new Tile(tok.get("kills"), "kills"));
		}
		if (!r.skills.isEmpty())
		{
			t.add(new Tile(r.skills.get(0).name, "top skill").icon(art == null ? null : art.skill(r.skills.get(0).name)));
		}
		if (r.period.days() > 1)
		{
			t.add(new Tile(r.daysPlayed + " / " + r.period.days(), "days played"));
		}
		if (r.totalClues > 0)
		{
			t.add(new Tile(tok.get("clues"), "clues"));
		}
		if (!r.areas.isEmpty())
		{
			t.add(new Tile(r.areas.get(0).name, "favourite spot"));
		}
		return t.size() > 6 ? t.subList(0, 6) : t;
	}

	private static void tiles(Graphics2D g, Theme theme, List<Tile> tiles, Rectangle area, int u)
	{
		boolean wide = area.width > area.height * 1.4;
		int cols = wide ? 3 : 2;
		int rows = (tiles.size() + cols - 1) / cols;
		int gap = u / 40;
		int tw = (area.width - gap * (cols - 1)) / cols;
		int th = Math.min((area.height - gap * (rows - 1)) / Math.max(1, rows), (int) (tw * 0.75));
		int top = area.y + (area.height - (th * rows + gap * (rows - 1))) / 2;
		int arc = u / 30;
		for (int i = 0; i < tiles.size(); i++)
		{
			Tile t = tiles.get(i);
			int row = i / cols;
			int inRow = Math.min(cols, tiles.size() - row * cols);
			int left = area.x + (area.width - (tw * inRow + gap * (inRow - 1))) / 2;
			int x = left + (i % cols) * (tw + gap);
			int y = top + row * (th + gap);
			g.setColor(theme.panel);
			g.fillRoundRect(x, y, tw, th, arc, arc);
			g.setColor(i == 0 ? theme.accent : theme.accent2);
			g.fillRoundRect(x, y, Math.max(3, u / 160), th, arc / 2, arc / 2);
			Captions.centered(g, t.value, x + tw / 2, y + th / 2 + (int) (th * 0.06), theme.bold(th * 0.26f), i == 0 ? theme.accent : theme.text, tw - u / 20);
			Captions.centered(g, t.label, x + tw / 2, y + th / 2 + (int) (th * 0.3), theme.regular(th * 0.15f), theme.muted, tw - u / 20);
			if (t.icon != null)
			{
				int size = (int) (th * 0.34);
				Sprites.drawCentered(g, Sprites.splash(t.icon, size, theme.accent), x + tw - size * 0.62, y + size * 0.62, 1, 0.12);
			}
		}
	}

	private static void persona(Graphics2D g, Theme theme, Map<String, String> tok, int w, int baseline, int u)
	{
		String text = tok.get("persona");
		if (text == null || text.isEmpty())
		{
			return;
		}
		Font label = theme.regular(u * 0.03f);
		Font name = theme.bold(u * 0.045f);
		FontMetrics lm = g.getFontMetrics(label);
		FontMetrics nm = g.getFontMetrics(name);
		String prefix = "Persona: ";
		int total = lm.stringWidth(prefix) + nm.stringWidth(text);
		int x = (w - total) / 2;
		g.setFont(label);
		g.setColor(theme.muted);
		g.drawString(prefix, x, baseline);
		g.setFont(name);
		g.setColor(theme.accent);
		g.drawString(text, x + lm.stringWidth(prefix), baseline);
	}

	private static void topFives(Graphics2D g, Theme theme, Recap r, Composer.Media art, Rectangle area, int u)
	{
		List<String> titles = new ArrayList<>();
		List<List<Recap.Entry>> lists = new ArrayList<>();
		List<Boolean> money = new ArrayList<>();
		add(titles, lists, money, "Skills", r.skills, false);
		add(titles, lists, money, "Loot", r.lootSources, true);
		add(titles, lists, money, "Kills", r.kills, false);
		if (lists.size() < 3)
		{
			add(titles, lists, money, "Places", r.areas, false);
		}
		if (lists.isEmpty())
		{
			Captions.centered(g, "Nothing tracked yet", area.x + area.width / 2, area.y + area.height / 2, theme.bold(u * 0.05f), theme.muted, area.width);
			return;
		}
		int n = Math.min(3, lists.size());
		boolean columns = (area.width - u / 30 * (n - 1)) / n >= u * 0.4;
		int gap = u / 30;
		for (int i = 0; i < n; i++)
		{
			Rectangle box = columns
				? new Rectangle(area.x + i * (area.width + gap) / n, area.y, (area.width - gap * (n - 1)) / n, area.height)
				: new Rectangle(area.x, area.y + i * (area.height + gap) / n, area.width, (area.height - gap * (n - 1)) / n);
			java.util.function.Function<String, BufferedImage> icon = art != null && "Skills".equals(titles.get(i)) ? art::skill : name -> null;
			list(g, theme, titles.get(i), Recap.top(lists.get(i), columns ? 5 : 3), money.get(i), icon, box, u);
		}
	}

	private static void add(List<String> titles, List<List<Recap.Entry>> lists, List<Boolean> money, String title, List<Recap.Entry> list, boolean gp)
	{
		if (!list.isEmpty())
		{
			titles.add(title);
			lists.add(Recap.top(list, 5));
			money.add(gp);
		}
	}

	private static void list(Graphics2D g, Theme theme, String title, List<Recap.Entry> entries, boolean gp,
		java.util.function.Function<String, BufferedImage> icon, Rectangle box, int u)
	{
		int arc = u / 30;
		g.setColor(theme.panel);
		g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc);
		int pad = u / 36;
		int rowH = (box.height - pad * 2) / (entries.size() <= 3 ? Math.max(3, entries.size() + 1) : 6);
		Font head = theme.bold(Math.min(rowH * 0.7f, u * 0.045f));
		g.setFont(head);
		g.setColor(theme.accent);
		g.drawString(title.toUpperCase(), box.x + pad, box.y + pad + g.getFontMetrics().getAscent());
		Font font = theme.regular(Math.min(rowH * 0.62f, u * 0.038f));
		Font bold = theme.bold(Math.min(rowH * 0.62f, u * 0.038f));
		long max = entries.get(0).value;
		for (int i = 0; i < entries.size(); i++)
		{
			Recap.Entry e = entries.get(i);
			int y = box.y + pad + rowH * (i + 1);
			int barW = (int) ((box.width - pad * 2) * (max <= 0 ? 0 : e.value / (double) max));
			g.setColor(Theme.alpha(i == 0 ? theme.accent : theme.accent2, 0.22));
			g.fillRoundRect(box.x + pad, y + rowH / 10, barW, rowH * 8 / 10, arc / 2, arc / 2);
			String value = gp ? Recap.gp(e.value) : Recap.number(e.value);
			g.setFont(bold);
			FontMetrics bm = g.getFontMetrics();
			int vw = bm.stringWidth(value);
			int baseline = y + rowH / 2 + bm.getAscent() / 2 - bm.getDescent() / 2;
			g.setColor(theme.text);
			g.drawString(value, box.x + box.width - pad - vw - u / 80, baseline);
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			int textX = box.x + pad + u / 80;
			BufferedImage img = icon.apply(e.name);
			if (img != null)
			{
				int size = rowH * 9 / 10;
				Sprites.drawCentered(g, Sprites.splash(img, size, new Color(0x111111)), textX + size / 2.0, y + rowH / 2.0, 1, 0);
				textX += size + u / 120;
			}
			int room = box.x + box.width - pad - vw - u / 25 - textX;
			String name = (i + 1) + ". " + e.name;
			for (int cut = e.name.length() - 1; cut > 1 && fm.stringWidth(name) > room; cut--)
			{
				name = (i + 1) + ". " + e.name.substring(0, cut).trim() + "…";
			}
			g.drawString(name, textX, baseline);
		}
	}

	private static void highlight(Graphics2D g, Theme theme, Recap r, Map<String, String> tok, Moment moment, BufferedImage still, BufferedImage icon, int w, int h, int u, int m)
	{
		Scenes.drawCover(g, still, 0, 0, w, h, 1);
		g.setPaint(new java.awt.GradientPaint(0, h * 0.35f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 215)));
		g.fillRect(0, 0, w, h);
		g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0, 0, 0, 150), 0, h * 0.2f, new Color(0, 0, 0, 0)));
		g.fillRect(0, 0, w, (int) (h * 0.2f));

		Captions.centered(g, (r.period.describe() + " · " + tok.get("player")).toUpperCase(), w / 2, m + u / 30, theme.bold(u * 0.032f), Color.WHITE, w - 2 * m);

		String badge = Scenes.badge(moment.type);
		Font bf = theme.bold(u * 0.04f);
		FontMetrics bm = g.getFontMetrics(bf);
		int bw = bm.stringWidth(badge) + u / 20;
		int bh = bm.getHeight() + u / 80;
		int by = h - m - u * 40 / 100;
		if (icon != null)
		{
			int size = (int) (u * 0.2);
			Sprites.drawCentered(g, Sprites.splash(icon, size, theme.accent), w / 2.0, by - size * 0.6, 1, -0.06);
		}
		g.setColor(theme.accent);
		g.fillRoundRect((w - bw) / 2, by, bw, bh, bh, bh);
		Captions.centered(g, badge, w / 2, by + bh / 2 + bm.getAscent() / 2 - bm.getDescent() / 2, bf, Scenes.isBright(theme.accent) ? Color.BLACK : Color.WHITE, w);

		Map<String, String> mt = Composer.momentTokens(moment);
		Captions.centered(g, mt.get("title"), w / 2, by + bh + u / 9, theme.bold(u * 0.08f), Color.WHITE, w - 2 * m);
		String sub = String.join(" · ", nonEmpty(mt.get("detail"), mt.get("value"), mt.get("date")));
		Captions.centered(g, sub, w / 2, by + bh + u / 9 + u / 15, theme.regular(u * 0.04f), new Color(255, 255, 255, 200), w - 2 * m);
		String stats = tok.get("xp") + " XP · " + tok.get("hours") + " played" + (r.total.lootValue > 0 ? " · " + tok.get("loot") + " loot" : "");
		Captions.centered(g, stats, w / 2, h - m, theme.bold(u * 0.036f), theme.accent, w - 2 * m);
	}

	private static List<String> nonEmpty(String... parts)
	{
		List<String> out = new ArrayList<>();
		for (String p : parts)
		{
			if (p != null && !p.isEmpty())
			{
				out.add(p);
			}
		}
		return out;
	}

	private static void minimal(Graphics2D g, Theme theme, Recap r, Map<String, String> tok, int w, int h, int u)
	{
		int cy = h / 2;
		Captions.centered(g, (r.period.describe()).toUpperCase(), w / 2, cy - u * 22 / 100, theme.bold(u * 0.034f), theme.muted, w * 9 / 10);
		Captions.centered(g, tok.get("xp"), w / 2, cy + u / 20, theme.bold(u * 0.2f), theme.accent, w * 9 / 10);
		Captions.centered(g, "XP gained by " + tok.get("player"), w / 2, cy + u * 14 / 100, theme.regular(u * 0.045f), theme.text, w * 9 / 10);
		String line = tok.get("hours") + " played" + (r.skills.isEmpty() ? "" : " · mostly " + tok.get("topSkill"));
		Captions.centered(g, line, w / 2, cy + u * 22 / 100, theme.regular(u * 0.036f), theme.muted, w * 9 / 10);
	}

	private static void footer(Graphics2D g, Theme theme, String watermark, int w, int h, int u)
	{
		if (watermark == null || watermark.trim().isEmpty())
		{
			return;
		}
		Font f = theme.bold(u * 0.026f);
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		g.setColor(Theme.alpha(theme.light() ? theme.muted : Color.WHITE, 0.55));
		g.drawString(watermark, w - fm.stringWidth(watermark) - u / 40, h - u / 40 - fm.getDescent());
	}

	/** The account's chat helmet and type, e.g. "Hardcore Ironman", in the bottom-left corner opposite the watermark. */
	static void badge(Graphics2D g, Theme theme, BufferedImage helmet, AccountType type, int w, int h, int u)
	{
		BufferedImage t = helmet == null ? null : Sprites.trim(helmet);
		if (t == null || type == null)
		{
			return;
		}
		BufferedImage big = Sprites.upscale(t, u * 6 / 100);
		Font f = theme.bold(u * 0.026f);
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int x = u / 40;
		int y = h - u / 40 - big.getHeight();
		g.drawImage(big, x, y, null);
		g.setColor(Theme.alpha(theme.light() ? theme.muted : Color.WHITE, 0.8));
		g.drawString(type.title, x + big.getWidth() + u / 80, y + big.getHeight() / 2 + fm.getAscent() / 2 - fm.getDescent() / 2);
	}

	/** The player's own {@code share:} caption in a pill near the bottom edge. */
	private static void sharePill(Graphics2D g, Theme theme, String text, int w, int h, int u)
	{
		Font f = theme.bold(u * 0.036f);
		FontMetrics fm = g.getFontMetrics(f);
		int tw = Math.min(w * 9 / 10, fm.stringWidth(text) + u / 15);
		int ph = fm.getHeight() + u / 50;
		int y = h - u / 11 - ph;
		g.setColor(Theme.alpha(theme.accent, 0.92));
		g.fillRoundRect((w - tw) / 2, y, tw, ph, ph, ph);
		Captions.centered(g, text, w / 2, y + ph / 2 + fm.getAscent() / 2 - fm.getDescent() / 2, f,
			Scenes.isBright(theme.accent) ? Color.BLACK : Color.WHITE, tw - u / 30);
	}

	/** The poster background: the cover shot, else the top moment's frame, else a timelapse still. */
	static BufferedImage backdrop(Composer.Media media, Recap r)
	{
		if (media == null)
		{
			return null;
		}
		Shots.Shot shot = media.shots().backdrop();
		if (shot != null)
		{
			try
			{
				BufferedImage img = shot.read();
				if (img != null)
				{
					return img;
				}
			}
			catch (IOException e)
			{
				log.debug("Unreadable shot {}", shot.file, e);
			}
		}
		Moment top = topMoment(r);
		BufferedImage still = top == null ? null : still(media, top);
		if (still != null)
		{
			return still;
		}
		List<ClipFile.Frame> frames = media.timelapse(1);
		if (!frames.isEmpty())
		{
			try
			{
				return frames.get(0).image();
			}
			catch (IOException e)
			{
				log.debug("Unreadable timelapse frame", e);
			}
		}
		return null;
	}

	/** A film poster: the game view full bleed, the title big, and the numbers set like credits. */
	private static void poster(Graphics2D g, Theme theme, Recap r, GielinorWrappedConfig config, Map<String, String> tok,
		BufferedImage backdrop, String share, int w, int h, int u, int m)
	{
		if (backdrop != null)
		{
			Scenes.drawCover(g, backdrop, 0, 0, w, h, 1.04);
		}
		Color dark = Theme.mix(theme.bottom, Color.BLACK, 0.55);
		g.setPaint(new java.awt.GradientPaint(0, h * 0.3f, Theme.alpha(dark, 0), 0, h * 0.78f, Theme.alpha(dark, 0.94)));
		g.fillRect(0, 0, w, h);
		g.setColor(Theme.alpha(dark, 0.94));
		g.fillRect(0, (int) (h * 0.78f), w, h);
		g.setPaint(new java.awt.GradientPaint(0, 0, Theme.alpha(dark, 0.7), 0, h * 0.16f, Theme.alpha(dark, 0)));
		g.fillRect(0, 0, w, (int) (h * 0.16f));

		Captions.centered(g, (tok.get("player") + " presents").toUpperCase(), w / 2, m + u / 40, theme.regular(u * 0.03f), new Color(255, 255, 255, 210), w - 2 * m);

		int credits = h - m - u / 30;
		String stats = String.join("   ·   ", nonEmpty(tok.get("xp") + " XP", tok.get("hours"),
			r.total.totalLevels() > 0 ? tok.get("levels") + " levels" : "",
			r.total.lootValue > 0 ? tok.get("loot") : "",
			r.totalKills > 0 ? tok.get("kills") + " kills" : ""));
		Captions.centered(g, r.period.describe().toUpperCase(), w / 2, credits, theme.bold(u * 0.028f), theme.accent, w - 2 * m);
		Captions.centered(g, stats, w / 2, credits - u / 18, theme.regular(u * 0.034f), new Color(255, 255, 255, 220), w - 2 * m);
		g.setColor(Theme.alpha(theme.accent, 0.8));
		g.fillRect(w / 2 - u / 6, credits - u / 9, u / 3, Math.max(2, u / 300));

		int y = credits - u / 7;
		if (share != null)
		{
			Captions.centered(g, "“" + share + "”", w / 2, y, theme.regular(u * 0.036f), new Color(255, 255, 255, 230), w - 2 * m);
			y -= u / 12;
		}
		else if (r.persona != null && !tok.get("persona").isEmpty())
		{
			Captions.centered(g, tok.get("persona"), w / 2, y, theme.bold(u * 0.04f), theme.accent, w - 2 * m);
			y -= u / 12;
		}
		List<String> title = Splash.lines(Recap.fill(config.titleText(), tok));
		Font tf = theme.bold(u * (title.size() > 1 ? 0.085f : 0.1f));
		for (int i = title.size() - 1; i >= 0; i--)
		{
			Sprites.outlined(g, title.get(i).toUpperCase(), w / 2.0, y, tf, Color.WHITE, 0, w - 2 * m);
			y -= g.getFontMetrics(tf).getAscent() * 1.05;
		}
	}

	/** The in-game hiscores page: one row per skill with the levels and XP gained, and the total underneath. */
	private static void hiscores(Graphics2D g, Theme theme, Recap r, Map<String, String> tok, Composer.Media art, int w, int h, int u, int m)
	{
		Captions.centered(g, ("Hiscores · " + tok.get("player")).toUpperCase(), w / 2, m + u / 25, theme.bold(u * 0.05f), theme.text, w - 2 * m);
		Captions.centered(g, r.period.describe(), w / 2, m + u / 25 + u / 20, theme.regular(u * 0.03f), theme.muted, w - 2 * m);

		Rectangle box = new Rectangle(m, m + u * 16 / 100, w - 2 * m, h - 2 * m - u * 16 / 100 - u / 25);
		Map<String, Long> levels = new java.util.HashMap<>();
		for (Recap.Entry e : r.levels)
		{
			levels.put(e.name, e.value);
		}
		int pad = u / 36;
		int maxRows = Math.max(3, Math.min(10, (box.height - pad * 2) / (u / 14) - 2));
		List<Recap.Entry> rows = Recap.top(r.skills, maxRows);
		int rowH = Math.min(u / 11, (box.height - pad * 2) / (Math.max(rows.size(), 1) + 3));
		int used = pad * 2 + rowH * (Math.max(rows.size(), 1) + 3);
		box.y += (box.height - used) / 2;
		box.height = used;
		int arc = u / 30;
		g.setColor(theme.panel);
		g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc);
		int colSkill = box.x + pad;
		int colXp = box.x + box.width - pad;
		int colLevels = box.x + box.width * 58 / 100;
		float textSize = Math.min(rowH * 0.5f, u * 0.042f);
		Font head = theme.bold(textSize * 0.9f);
		Font cell = theme.regular(textSize);
		Font cellBold = theme.bold(textSize);

		int y = box.y + pad;
		drawRow(g, head, theme.accent, "SKILL", "LEVELS", "XP", colSkill, colLevels, colXp, y, rowH);
		g.setColor(Theme.alpha(theme.muted, 0.4));
		g.fillRect(box.x + pad, y + rowH - Math.max(1, u / 400), box.width - pad * 2, Math.max(1, u / 400));
		y += rowH;
		if (rows.isEmpty())
		{
			Captions.centered(g, "No XP tracked yet", box.x + box.width / 2, y + rowH, cell, theme.muted, box.width);
		}
		for (int i = 0; i < rows.size(); i++)
		{
			Recap.Entry e = rows.get(i);
			if (i % 2 == 1)
			{
				g.setColor(Theme.alpha(theme.text, 0.05));
				g.fillRect(box.x + pad, y, box.width - pad * 2, rowH);
			}
			int textX = colSkill;
			BufferedImage icon = art == null ? null : art.skill(e.name);
			if (icon != null)
			{
				int size = rowH * 3 / 4;
				Sprites.drawCentered(g, Sprites.upscale(icon, size), textX + size / 2.0, y + rowH / 2.0, 1, 0);
				textX += size + u / 80;
			}
			Long lv = levels.get(e.name);
			drawRow(g, i == 0 ? cellBold : cell, i == 0 ? theme.accent : theme.text, e.name,
				lv == null || lv == 0 ? "–" : "+" + lv, Recap.number(e.value), textX, colLevels, colXp, y, rowH);
			y += rowH;
		}
		y = box.y + box.height - pad - rowH;
		g.setColor(Theme.alpha(theme.accent, 0.18));
		g.fillRoundRect(box.x + pad, y, box.width - pad * 2, rowH, arc / 2, arc / 2);
		drawRow(g, cellBold, theme.text, "Overall", r.total.totalLevels() > 0 ? "+" + tok.get("levels") : "–", tok.get("xp"), colSkill + u / 80, colLevels, colXp - u / 80, y, rowH);
	}

	/** A table row: the name left aligned, the levels centred on their column and the XP right aligned. */
	private static void drawRow(Graphics2D g, Font font, Color color, String name, String levels, String xp, int x, int levelsX, int xpRight, int y, int rowH)
	{
		g.setFont(font);
		g.setColor(color);
		FontMetrics fm = g.getFontMetrics();
		int baseline = y + rowH / 2 + fm.getAscent() / 2 - fm.getDescent() / 2;
		g.drawString(name, x, baseline);
		g.drawString(levels, levelsX - fm.stringWidth(levels) / 2, baseline);
		g.drawString(xp, xpRight - fm.stringWidth(xp), baseline);
	}

	private static final Color PARCHMENT = new Color(0xE9D8B0);
	private static final Color PARCHMENT_EDGE = new Color(0xB8955A);
	private static final Color INK = new Color(0x3B2A16);
	private static final Color QUEST_RED = new Color(0x8C1C13);

	/** The quest complete scroll: the period as the quest, and what the player earned as the rewards. */
	private static void scroll(Graphics2D g, Theme theme, Recap r, Map<String, String> tok, Composer.Media art, int w, int h, int u)
	{
		int sw = Math.min(w * 88 / 100, (int) (h * 1.25));
		int sh = h * 86 / 100;
		int x = (w - sw) / 2;
		int y = (h - sh) / 2;
		int roll = u / 22;
		g.setColor(new Color(0, 0, 0, 90));
		g.fillRoundRect(x + u / 120, y + u / 80, sw, sh, u / 40, u / 40);
		g.setPaint(new java.awt.GradientPaint(x, 0, Theme.mix(PARCHMENT, PARCHMENT_EDGE, 0.35f), x + sw / 2f, 0, PARCHMENT, true));
		g.fillRect(x, y + roll / 2, sw, sh - roll);
		for (int ry : new int[]{y, y + sh - roll})
		{
			g.setPaint(new java.awt.GradientPaint(0, ry, PARCHMENT_EDGE, 0, ry + roll / 2f, Theme.mix(PARCHMENT, Color.WHITE, 0.2), true));
			g.fillRoundRect(x - roll / 2, ry, sw + roll, roll, roll, roll);
		}
		g.setColor(Theme.alpha(PARCHMENT_EDGE, 0.5));
		g.setStroke(new java.awt.BasicStroke(Math.max(1, u / 300f)));
		g.drawRect(x + u / 30, y + roll + u / 50, sw - u / 15, sh - roll * 2 - u / 25);

		int cx = w / 2;
		int top = y + roll + u / 50;
		Captions.centered(g, "Congratulations!", cx, top + u / 12, theme.bold(u * 0.07f), QUEST_RED, sw - u / 10);
		Captions.centered(g, "You have completed your " + tok.get("periodNoun") + "!", cx, top + u / 12 + u / 16, theme.regular(u * 0.038f), INK, sw - u / 10);

		BufferedImage icon = art == null || r.skills.isEmpty() ? null : art.skill(r.skills.get(0).name);
		int iconY = top + u / 12 + u / 16 + u / 14;
		if (icon != null)
		{
			int size = u / 8;
			Sprites.drawCentered(g, Sprites.splash(icon, size, PARCHMENT_EDGE), cx, iconY + size / 2.0, 1, 0);
			iconY += size + u / 40;
		}
		Captions.centered(g, "You are awarded:", cx, iconY + u / 30, theme.bold(u * 0.042f), QUEST_RED, sw - u / 10);

		List<String> rewards = new ArrayList<>();
		for (Recap.Entry e : Recap.top(r.skills, 3))
		{
			rewards.add(Recap.number(e.value) + " " + e.name + " XP");
		}
		if (r.total.totalLevels() > 0)
		{
			rewards.add(tok.get("levels") + (r.total.totalLevels() == 1 ? " level" : " levels"));
		}
		if (r.total.lootValue > 0)
		{
			rewards.add(tok.get("loot") + " of loot");
		}
		if (r.totalKills > 0)
		{
			rewards.add(tok.get("kills") + " kills");
		}
		if (r.totalClues > 0)
		{
			rewards.add(tok.get("clues") + " clue scrolls");
		}
		rewards.add(tok.get("hours") + " of adventure");
		int bottom = y + sh - roll - u / 12;
		int lineH = u / 19;
		int first = iconY + u / 30 + lineH;
		int fit = Math.max(1, (bottom - first) / lineH);
		Font rf = theme.regular(u * 0.036f);
		for (int i = 0; i < Math.min(fit, rewards.size()); i++)
		{
			Captions.centered(g, rewards.get(i), cx, first + i * lineH, rf, INK, sw - u / 8);
		}
		Captions.centered(g, "Quest points: " + Math.max(1, r.daysPlayed), cx, y + sh - roll - u / 25, theme.bold(u * 0.03f), Theme.mix(INK, PARCHMENT, 0.3), sw - u / 10);
	}

	private static final Color PAPER = new Color(0xF7F4EC);
	private static final Color THERMAL = new Color(0x2A2A2A);

	/** A Grand Exchange till receipt: real trades when the GE was tracked, otherwise the best drops, with the totals at the bottom. */
	private static void receipt(Graphics2D g, Recap r, Map<String, String> tok, Composer.Media art, String share, int w, int h, int u)
	{
		Font big = new Font(Font.MONOSPACED, Font.BOLD, Math.round(u * 0.045f));
		Font mono = new Font(Font.MONOSPACED, Font.PLAIN, Math.round(u * 0.028f));
		Font monoBold = mono.deriveFont(Font.BOLD);
		int pad = u / 25;
		int line = g.getFontMetrics(mono).getHeight();
		int rowStep = line + u / 200;
		int tooth = Math.max(6, u / 60);
		// With Grand Exchange data the receipt lists real trades; otherwise the period's best drops.
		boolean ge = r.hasGe();
		List<ReceiptLine> drops = new ArrayList<>();
		if (ge)
		{
			for (DayRecord.Trade t : r.trades)
			{
				drops.add(new ReceiptLine(t.itemId, (t.buy ? "BUY " : "SELL ") + (t.quantity > 1 ? t.quantity + " x " : "") + t.name,
					(t.buy ? "-" : "+") + Recap.gp(t.value), t.value));
			}
		}
		else
		{
			for (DayRecord.Drop d : r.drops)
			{
				drops.add(new ReceiptLine(d.itemId, (d.quantity > 1 ? d.quantity + " x " : "") + d.name, Recap.gp(d.value), d.value));
			}
		}
		drops.sort((a, b) -> Long.compare(b.value, a.value));
		long volume = ge ? r.total.geBought + r.total.geSold : r.total.lootValue;
		long listed = 0;
		for (ReceiptLine d : drops)
		{
			listed += d.value;
		}
		int head = pad + g.getFontMetrics(big).getAscent() + line * 4 + line / 2;
		int foot = line * 4 + (share != null ? line : 0) + pad + tooth;
		int maxH = h * 92 / 100;
		int rows = Math.max(1, Math.min(drops.size(), (maxH - head - foot - line) / rowStep));
		boolean other = drops.size() > rows || volume > listed;
		int natural = head + rows * rowStep + (other ? line : 0) + foot;
		int rh = Math.min(maxH, Math.max(u * 6 / 10, natural));
		int rw = Math.min(w * 3 / 4, (int) (u * 0.78));
		int x = (w - rw) / 2;
		int y = (h - rh) / 2;
		g.setColor(new Color(0, 0, 0, 80));
		g.fillRect(x + u / 100, y + u / 80, rw, rh);
		java.awt.geom.Path2D paper = new java.awt.geom.Path2D.Double();
		paper.moveTo(x, y);
		paper.lineTo(x + rw, y);
		paper.lineTo(x + rw, y + rh - tooth);
		for (int tx = x + rw; tx > x; tx -= tooth * 2)
		{
			paper.lineTo(Math.max(x, tx - tooth), y + rh);
			paper.lineTo(Math.max(x, tx - tooth * 2), y + rh - tooth);
		}
		paper.closePath();
		g.setColor(PAPER);
		g.fill(paper);

		int cx = w / 2;
		// A short receipt keeps its minimum size; its text sits in the middle of the paper.
		int ty = y + pad + g.getFontMetrics(big).getAscent() + Math.max(0, (rh - natural) / 2);
		Captions.centered(g, "GRAND EXCHANGE", cx, ty, big, THERMAL, rw - pad * 2);
		ty += line;
		Captions.centered(g, "Varrock · Est. 2007", cx, ty, mono, THERMAL, rw - pad * 2);
		ty += line;
		Captions.centered(g, r.period.describe(), cx, ty, mono, THERMAL, rw - pad * 2);
		ty += line;
		Captions.centered(g, "Customer: " + tok.get("player"), cx, ty, mono, THERMAL, rw - pad * 2);
		ty += line / 2;
		dashes(g, mono, x + pad, ty, rw - pad * 2);
		ty += line;

		int iconSize = line;
		int shown = Math.min(rows, drops.size());
		long shownValue = 0;
		for (int i = 0; i < shown; i++)
		{
			ReceiptLine d = drops.get(i);
			shownValue += d.value;
			int tx = x + pad;
			BufferedImage icon = art == null || d.itemId <= 0 ? null : art.item(d.itemId);
			if (icon != null)
			{
				Sprites.drawCentered(g, Sprites.upscale(Sprites.trim(icon), iconSize), tx + iconSize / 2.0, ty - line / 3.0, 1, 0);
			}
			tx += iconSize + u / 100;
			leader(g, mono, d.name, d.amount, tx, x + rw - pad, ty);
			ty += rowStep;
		}
		if (drops.isEmpty())
		{
			Captions.centered(g, "** NO ITEMS **", cx, ty, mono, THERMAL, rw - pad * 2);
			ty += line;
		}
		else if (other && volume > shownValue)
		{
			String more = drops.size() > shown ? (drops.size() - shown) + (ge ? " more trades" : " more items") : ge ? "Other trades" : "Other loot";
			leader(g, mono, more, Recap.gp(Math.max(0, volume - shownValue)), x + pad + iconSize + u / 100, x + rw - pad, ty);
			ty += line;
		}
		ty -= line / 2;
		dashes(g, mono, x + pad, ty, rw - pad * 2);
		ty += line;
		if (ge)
		{
			long net = r.total.geNet();
			leader(g, monoBold, net >= 0 ? "NET PROFIT" : "NET SPENT", (net >= 0 ? "+" : "-") + Recap.gp(Math.abs(net)), x + pad, x + rw - pad, ty);
			ty += line;
			leader(g, mono, "Bought", Recap.gp(r.total.geBought), x + pad, x + rw - pad, ty);
			ty += line;
			leader(g, mono, "Sold", Recap.gp(r.total.geSold), x + pad, x + rw - pad, ty);
		}
		else
		{
			leader(g, monoBold, "TOTAL", tok.get("loot"), x + pad, x + rw - pad, ty);
			ty += line;
			leader(g, mono, "Kills", tok.get("kills"), x + pad, x + rw - pad, ty);
			ty += line;
			leader(g, mono, "Time on shift", tok.get("hours"), x + pad, x + rw - pad, ty);
		}
		ty += line * 3 / 2;
		Captions.centered(g, share != null ? share : "Thank you for trading!", cx, ty, monoBold, THERMAL, rw - pad * 2);
	}

	private static final class ReceiptLine
	{
		final int itemId;
		final String name;
		final String amount;
		final long value;

		ReceiptLine(int itemId, String name, String amount, long value)
		{
			this.itemId = itemId;
			this.name = name;
			this.amount = amount;
			this.value = value;
		}
	}

	private static void dashes(Graphics2D g, Font font, int x, int y, int width)
	{
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		StringBuilder sb = new StringBuilder();
		while (fm.stringWidth(sb.toString() + "-") <= width)
		{
			sb.append('-');
		}
		g.setColor(THERMAL);
		g.drawString(sb.toString(), x, y);
	}

	/** {@code name ...... value} across the receipt, cutting the name short when there is no room. */
	private static void leader(Graphics2D g, Font font, String name, String value, int x, int right, int y)
	{
		g.setFont(font);
		g.setColor(THERMAL);
		FontMetrics fm = g.getFontMetrics();
		int vw = fm.stringWidth(value);
		int room = right - x - vw - fm.stringWidth(" ..");
		String n = name;
		for (int cut = name.length() - 1; cut > 1 && fm.stringWidth(n) > room; cut--)
		{
			n = name.substring(0, cut).trim() + "…";
		}
		StringBuilder dots = new StringBuilder(" ");
		while (fm.stringWidth(n + dots + ". " + value) <= right - x)
		{
			dots.append('.');
		}
		g.drawString(n + dots + " ", x, y);
		g.drawString(value, right - vw, y);
	}

	/** The most important highlight that has footage. */
	static Moment topMoment(Recap r)
	{
		Moment best = null;
		for (Moment m : r.highlights)
		{
			if (m.clip != null && (best == null || m.importance > best.importance))
			{
				best = m;
			}
		}
		return best;
	}

	/** The clip frame nearest the moment, or null if the footage is gone. */
	static BufferedImage still(Composer.Media media, Moment moment)
	{
		List<ClipFile.Frame> frames = media.clip(moment.clip);
		ClipFile.Frame best = null;
		for (ClipFile.Frame f : frames)
		{
			if (best == null || Math.abs(f.time - moment.time) < Math.abs(best.time - moment.time))
			{
				best = f;
			}
		}
		if (best == null)
		{
			return null;
		}
		try
		{
			return best.image();
		}
		catch (IOException e)
		{
			log.debug("Unreadable clip frame in {}", moment.clip, e);
			return null;
		}
	}
}
