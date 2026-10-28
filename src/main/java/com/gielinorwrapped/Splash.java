package com.gielinorwrapped;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A YouTube-thumbnail style splash: a screenshot backdrop, a fan of big glowing item sprites and a loud outlined
 * headline. Drawn as the splash share card and, animated, as the video's opening scene.
 */
final class Splash
{
	static final int MAX_ITEMS = 5;

	private final String label;
	private final List<String> headline;
	private final String player;
	private final String footer;
	private final BufferedImage backdrop;
	private final List<BufferedImage> items;
	private final BufferedImage skill;
	private final String skillText;

	private int preparedFor = -1;
	private List<BufferedImage> bigItems;
	private BufferedImage bigSkill;

	Splash(String label, String headline, String player, String footer, BufferedImage backdrop, List<BufferedImage> items, BufferedImage skill, String skillText)
	{
		this.label = label;
		this.headline = lines(headline == null ? "" : headline.toUpperCase(Locale.ROOT));
		this.player = player;
		this.footer = footer;
		this.backdrop = backdrop;
		this.items = items;
		this.skill = skill;
		this.skillText = skillText;
	}

	/** Builds the splash for a recap; sprites and the backdrop come from {@code media}, which may be null. */
	static Splash of(Recap r, Composer.Media media, Map<String, String> tok, GielinorWrappedConfig config)
	{
		return of(r, media, tok, config, null);
	}

	/** As above with the player's own headline, or the configured one when {@code headline} is null. */
	static Splash of(Recap r, Composer.Media media, Map<String, String> tok, GielinorWrappedConfig config, String headline)
	{
		List<BufferedImage> sprites = new ArrayList<>();
		BufferedImage skill = null;
		BufferedImage backdrop = null;
		if (media != null)
		{
			for (int id : featuredItems(r, MAX_ITEMS))
			{
				BufferedImage img = media.item(id);
				if (img != null)
				{
					sprites.add(img);
				}
			}
			if (!r.skills.isEmpty())
			{
				skill = media.skill(r.skills.get(0).name);
			}
			if (config.shotsAsBackdrop())
			{
				backdrop = Composer.read(media.shots().backdrop());
			}
			if (backdrop == null)
			{
				Moment top = ShareCard.topMoment(r);
				backdrop = top == null ? null : ShareCard.still(media, top);
			}
		}
		String skillText = r.skills.isEmpty() ? null : "+" + tok.get("topSkillXp") + " " + tok.get("topSkill");
		List<String> foot = new ArrayList<>();
		foot.add(tok.get("hours") + " played");
		if (r.total.lootValue > 0)
		{
			foot.add(tok.get("loot") + " loot");
		}
		if (r.total.totalLevels() > 0)
		{
			foot.add(tok.get("levels") + (r.total.totalLevels() == 1 ? " level" : " levels"));
		}
		return new Splash(r.period.describe(), headline != null ? headline : Recap.fill(config.splashText(), tok), tok.get("player"), String.join(" · ", foot),
			backdrop, sprites, skill, skillText);
	}

	/** Items worth showing off, best first: pets, new log slots, big drops, then the most valuable loot. */
	static List<Integer> featuredItems(Recap r, int max)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (Moment.Type type : new Moment.Type[]{Moment.Type.PET, Moment.Type.COLLECTION_LOG, Moment.Type.DROP})
		{
			List<Moment> ms = new ArrayList<>();
			for (Moment m : r.highlights)
			{
				if (m.type == type && m.itemId > 0)
				{
					ms.add(m);
				}
			}
			ms.sort((a, b) -> type == Moment.Type.DROP ? Long.compare(b.value, a.value) : Integer.compare(b.importance, a.importance));
			for (Moment m : ms)
			{
				ids.add(m.itemId);
			}
		}
		for (DayRecord.Drop d : r.drops)
		{
			if (d.itemId > 0)
			{
				ids.add(d.itemId);
			}
		}
		List<Integer> out = new ArrayList<>(ids);
		return out.size() > max ? out.subList(0, max) : out;
	}

	boolean hasArt()
	{
		return backdrop != null || !items.isEmpty();
	}

	/** Draws the splash {@code t} seconds in; anything past two seconds is the settled still. */
	void draw(Graphics2D g, Theme th, int w, int h, double t)
	{
		int u = Math.min(w, h);
		boolean wide = w > h * 1.25;
		prepare(u, th);

		// Backdrop: the player's shot with a slow push-in, darkened so the text pops.
		if (backdrop != null)
		{
			Scenes.drawCover(g, backdrop, 0, 0, w, h, 1.04 + 0.04 * Captions.clamp(t / 4));
			g.setColor(new Color(0, 0, 0, 70));
			g.fillRect(0, 0, w, h);
		}
		else
		{
			th.background(g, w, h, t);
		}
		float fx = wide ? w * 0.70f : w * 0.5f;
		float fy = wide ? h * 0.50f : h * 0.53f;
		rays(g, th, fx, fy, Math.max(w, h), t, backdrop != null ? 0.10 : 0.16);
		g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, Math.max(w, h) * 0.75f, new float[]{0.45f, 1f},
			new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 170)}));
		g.fillRect(0, 0, w, h);
		g.setPaint(new GradientPaint(0, h * 0.72f, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 190)));
		g.fillRect(0, (int) (h * 0.72f), w, h - (int) (h * 0.72f));

		fan(g, fx, fy, wide ? w * 0.40f : w * 0.78f, u, t);

		// Period pill, top left.
		double lp = Captions.ease(Captions.clamp(t / 0.35));
		java.awt.Font lf = th.bold(u * 0.034f);
		g.setFont(lf);
		java.awt.FontMetrics lm = g.getFontMetrics();
		String pill = label.toUpperCase(Locale.ROOT);
		int pad = u / 50;
		int pw = lm.stringWidth(pill) + pad * 2;
		int ph = lm.getHeight() + pad / 2;
		int px = (int) (u * 0.045 - (1 - lp) * (pw + u * 0.05));
		int py = (int) (u * 0.045);
		g.setColor(th.accent);
		g.fillRoundRect(px, py, pw, ph, ph, ph);
		g.setColor(Scenes.isBright(th.accent) ? new Color(0x111111) : Color.WHITE);
		g.drawString(pill, px + pad, py + pad / 4 + lm.getAscent());

		// Headline slams in: big, outlined, tilted.
		double hp = Captions.clamp((t - 0.15) / 0.35);
		if (hp > 0)
		{
			// Kept small and high, in the upper left, so it doesn't cover the player in the middle of the shot.
			float cx = wide ? w * 0.25f : w * 0.5f;
			float size = u * (headline.size() > 1 ? 0.09f : 0.105f);
			float top = wide ? h * 0.25f : h * 0.13f;
			double scale = 1 + 0.6 * Math.pow(1 - hp, 3);
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Captions.clamp(hp * 2)));
			for (int i = 0; i < headline.size(); i++)
			{
				Color fill = i == headline.size() - 1 ? th.accent : Color.WHITE;
				Sprites.outlined(g, headline.get(i), cx, top + i * size * 1.0, th.bold((float) (size * scale)), fill, -0.05, (int) (wide ? w * 0.44 : w * 0.85));
			}
			if (player != null && !player.isEmpty())
			{
				Sprites.outlined(g, player, cx, top + headline.size() * size * 1.0 - size * 0.25 + u * 0.035, th.bold(u * 0.038f), Color.WHITE, -0.05, (int) (wide ? w * 0.42 : w * 0.75));
			}
			g.setComposite(old);
		}

		// Top skill badge and the stats line along the bottom.
		double sp = Captions.ease(Captions.clamp((t - 0.9) / 0.4));
		if (sp > 0)
		{
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) sp));
			int base = (int) (h - u * 0.07);
			if (skillText != null)
			{
				float sx = wide ? w * 0.25f : w * 0.5f;
				int sy = wide ? (int) (h * 0.68) : (int) (h * 0.80);
				int iconW = 0;
				if (bigSkill != null)
				{
					iconW = bigSkill.getWidth();
					java.awt.FontMetrics fm = g.getFontMetrics(th.bold(u * 0.055f));
					int total = iconW + u / 60 + Math.min(fm.stringWidth(skillText), (int) (wide ? w * 0.46 : w * 0.8));
					float left = sx - total / 2f;
					Sprites.drawCentered(g, bigSkill, left + iconW / 2.0, sy - fm.getAscent() * 0.35, 1, 0);
					Sprites.outlined(g, skillText, left + iconW + u / 60f + (total - iconW - u / 60f) / 2, sy, th.bold(u * 0.055f), Color.WHITE, 0, (int) (wide ? w * 0.46 : w * 0.8));
				}
				else
				{
					Sprites.outlined(g, skillText, sx, sy, th.bold(u * 0.055f), Color.WHITE, 0, (int) (wide ? w * 0.5 : w * 0.85));
				}
			}
			Sprites.outlined(g, footer, w / 2.0, base, th.bold(u * 0.042f), th.accent, 0, (int) (w * 0.9));
			g.setComposite(old);
		}
	}

	private void prepare(int u, Theme th)
	{
		if (preparedFor == u)
		{
			return;
		}
		preparedFor = u;
		bigItems = new ArrayList<>();
		int size = (int) (u * 0.34);
		for (BufferedImage img : items)
		{
			BufferedImage big = Sprites.splash(img, size, th.accent);
			if (big != null)
			{
				bigItems.add(big);
			}
		}
		bigSkill = skill == null ? null : Sprites.splash(skill, (int) (u * 0.085), new Color(0, 0, 0, 200));
	}

	/** The best item in the middle, the rest alternating out to the sides, each popping in. */
	private void fan(Graphics2D g, float cx, float cy, float spread, int u, double t)
	{
		int n = bigItems.size();
		if (n == 0)
		{
			return;
		}
		double step = n == 1 ? 0 : Math.min(spread / (n - 1), u * 0.26);
		// Slot k holds item order[k]; slot offsets run -2..2 around the middle.
		int[] slotOf = new int[n];
		for (int i = 0; i < n; i++)
		{
			int k = (i + 1) / 2;
			slotOf[i] = i == 0 ? 0 : (i % 2 == 1 ? -k : k);
		}
		double shift = n % 2 == 0 ? 0.5 : 0;
		// Outer items first so the best one sits on top.
		for (int pass = n - 1; pass >= 0; pass--)
		{
			int i = pass;
			double off = slotOf[i] + (n % 2 == 0 ? shift : 0);
			double appear = 0.35 + i * 0.12;
			double p = Captions.clamp((t - appear) / 0.45);
			if (p <= 0)
			{
				continue;
			}
			double size = 1 - 0.16 * Math.min(2, Math.abs(off));
			double bob = Math.sin(t * 1.6 + i) * u * 0.006;
			double x = cx + off * step;
			double y = cy + off * off * u * 0.025 + bob;
			Sprites.drawCentered(g, bigItems.get(i), x, y, size * backOut(p), off * 0.16);
		}
	}

	private static void rays(Graphics2D g, Theme th, float cx, float cy, int r, double t, double alpha)
	{
		int n = 18;
		double turn = t * 0.08;
		Color c = Theme.alpha(th.accent, alpha);
		g.setColor(c);
		for (int i = 0; i < n; i += 2)
		{
			double a0 = turn + i * Math.PI * 2 / n;
			double a1 = turn + (i + 1) * Math.PI * 2 / n;
			Polygon p = new Polygon();
			p.addPoint((int) cx, (int) cy);
			p.addPoint((int) (cx + Math.cos(a0) * r * 1.5), (int) (cy + Math.sin(a0) * r * 1.5));
			p.addPoint((int) (cx + Math.cos(a1) * r * 1.5), (int) (cy + Math.sin(a1) * r * 1.5));
			g.fillPolygon(p);
		}
	}

	/** Overshoots a little before settling, like a sticker slapped on. */
	static double backOut(double p)
	{
		double c1 = 1.70158;
		double c3 = c1 + 1;
		double x = p - 1;
		return 1 + c3 * x * x * x + c1 * x * x;
	}

	/** Splits long headlines into two lines at the space nearest the middle. */
	static List<String> lines(String text)
	{
		List<String> out = new ArrayList<>();
		String s = text.trim();
		if (s.length() <= 14 || s.indexOf(' ') < 0)
		{
			out.add(s);
			return out;
		}
		int mid = s.length() / 2;
		int best = -1;
		for (int i = 0; i < s.length(); i++)
		{
			if (s.charAt(i) == ' ' && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid)))
			{
				best = i;
			}
		}
		out.add(s.substring(0, best).trim());
		out.add(s.substring(best + 1).trim());
		return out;
	}
}
