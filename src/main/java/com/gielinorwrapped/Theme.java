package com.gielinorwrapped;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import net.runelite.client.ui.FontManager;

/** Colours, fonts, an animated background and a finishing pass (grade, grain, border) shared by videos and share cards. */
final class Theme
{
	final GielinorWrappedConfig.ThemeName name;
	final Color top;
	final Color bottom;
	final Color panel;
	final Color text;
	final Color muted;
	final Color accent;
	final Color accent2;

	GielinorWrappedConfig.Effect effect = GielinorWrappedConfig.Effect.AUTO;
	GielinorWrappedConfig.FontChoice font = GielinorWrappedConfig.FontChoice.RUNESCAPE;
	GielinorWrappedConfig.Grade grade = GielinorWrappedConfig.Grade.NONE;
	GielinorWrappedConfig.Border border = GielinorWrappedConfig.Border.NONE;
	double vignette;
	double grain;
	boolean scanlines;

	private int[] vignetteMap;
	private int vignetteW;
	private int vignetteH;

	private Theme(GielinorWrappedConfig.ThemeName name, int top, int bottom, int panel, int text, int muted, int accent, int accent2)
	{
		this(name, new Color(top), new Color(bottom), new Color(panel, true), new Color(text), new Color(muted), new Color(accent), new Color(accent2));
	}

	private Theme(GielinorWrappedConfig.ThemeName name, Color top, Color bottom, Color panel, Color text, Color muted, Color accent, Color accent2)
	{
		this.name = name;
		this.top = top;
		this.bottom = bottom;
		this.panel = panel;
		this.text = text;
		this.muted = muted;
		this.accent = accent;
		this.accent2 = accent2;
	}

	static Theme of(GielinorWrappedConfig config)
	{
		Theme t = config.theme() == GielinorWrappedConfig.ThemeName.CUSTOM ? custom(config) : of(config.theme());
		Color accent = config.accent();
		if (config.useAccent() && accent != null)
		{
			t = new Theme(t.name, t.top, t.bottom, t.panel, t.text, t.muted, new Color(accent.getRGB() & 0xFFFFFF), t.accent2);
		}
		t.effect = config.effect();
		t.font = config.font();
		t.grade = config.grade();
		t.border = config.border();
		t.vignette = config.vignette() / 100.0;
		t.grain = config.filmGrain() / 100.0;
		t.scanlines = config.scanlines();
		return t;
	}

	private static Theme custom(GielinorWrappedConfig config)
	{
		Color top = opaque(config.customTop(), 0x1E2A3A);
		Color bottom = opaque(config.customBottom(), 0x3A1E3A);
		Color text = opaque(config.customText(), 0xFFFFFF);
		boolean light = luma(mix(top, bottom, 0.5)) > 0.6;
		Color panel = light ? new Color(255, 255, 255, 208) : alpha(mix(bottom, Color.BLACK, 0.55), 0.69);
		return new Theme(GielinorWrappedConfig.ThemeName.CUSTOM, top, bottom, panel, text, mix(text, bottom, 0.4),
			opaque(config.customAccent(), 0xFFD23F), opaque(config.customAccent2(), 0x3FD2FF));
	}

	static Theme of(GielinorWrappedConfig.ThemeName name)
	{
		switch (name)
		{
			case MIDNIGHT:
				return new Theme(name, 0x0B1026, 0x1B2550, 0xB0141A38, 0xF2F4FF, 0x98A2CC, 0x7CC4FF, 0xC69CFF);
			case NEON:
				return new Theme(name, 0x0D0221, 0x261447, 0xB0180A33, 0xFFFFFF, 0xB59ADB, 0xFF2E97, 0x2DE2E6);
			case WILDERNESS:
				return new Theme(name, 0x1A0A06, 0x3D1309, 0xB0200C08, 0xF6E7D8, 0xC09A86, 0xFF6A2B, 0xFFC247);
			case CLEAN:
				return new Theme(name, 0xFAFAFA, 0xE6E9EF, 0xD0FFFFFF, 0x1D2230, 0x6B7285, 0x3866F2, 0x18A57A);
			case RUNE:
				return new Theme(name, 0x0E1C2B, 0x1F3F5C, 0xB00F1E2E, 0xEAF4FF, 0x8FB0CC, 0x5FD3E8, 0x9AB8D6);
			case DRAGON:
				return new Theme(name, 0x120404, 0x3A0808, 0xB0180606, 0xFFECEC, 0xC79A9A, 0xE8262B, 0xF0C060);
			case BARROWS:
				return new Theme(name, 0x0E0F0E, 0x262A24, 0xB0121410, 0xE6E9DC, 0x9BA190, 0xB8C46A, 0x7E8B6A);
			case ANCIENT:
				return new Theme(name, 0x12061E, 0x2E0F46, 0xB01A0A2A, 0xF5EAFF, 0xB59CCB, 0xB45CFF, 0xFF4F7A);
			case DESERT:
				return new Theme(name, 0xF4DFB0, 0xD9A860, 0xC8FFF3D6, 0x3A2410, 0x7A5A34, 0xC0392B, 0x1F6F8B);
			case FROST:
				return new Theme(name, 0x0B1A2A, 0x2B5276, 0xB00C1C2C, 0xF4FBFF, 0xA9C6DD, 0x9FE8FF, 0x7FA8FF);
			case VAPORWAVE:
				return new Theme(name, 0x2B0F54, 0xAB1F7A, 0xB0200840, 0xFFFFFF, 0xE3B7F5, 0x01CDFE, 0xFF71CE);
			case NOIR:
				return new Theme(name, 0x0A0A0A, 0x262626, 0xB0101010, 0xF2F2F2, 0x9A9A9A, 0xFFFFFF, 0xC8102E);
			case RETRO:
				return new Theme(name, 0x3E3529, 0x5D5447, 0xC02E2820, 0xFFFF00, 0xFF981F, 0x00FF80, 0x00FFFF);
			case TOXIC:
				return new Theme(name, 0x06140A, 0x0F3A1F, 0xB0061A0C, 0xEAFFF0, 0x8FC9A0, 0x6CFF3C, 0x2DE2C8);
			default:
				return new Theme(GielinorWrappedConfig.ThemeName.GIELINOR, 0x2B2217, 0x4A3B26, 0xB01E170F, 0xFFF4DC, 0xCBB89A, 0xFFC83C, 0xFF9B3C);
		}
	}

	/** Light backgrounds want dark text and soft shadows. */
	boolean light()
	{
		return luma(mix(top, bottom, 0.5)) > 0.6;
	}

	/** Bold title font at {@code size} pixels in the chosen typeface. */
	Font bold(float size)
	{
		switch (font)
		{
			case SANS:
				return new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size);
			case SERIF:
				return new Font(Font.SERIF, Font.BOLD, 1).deriveFont(size);
			case MONO:
				return new Font(Font.MONOSPACED, Font.BOLD, 1).deriveFont(size);
			default:
				return FontManager.getRunescapeBoldFont().deriveFont(size);
		}
	}

	Font regular(float size)
	{
		switch (font)
		{
			case SANS:
				return new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size);
			case SERIF:
				return new Font(Font.SERIF, Font.PLAIN, 1).deriveFont(size);
			case MONO:
				return new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(size);
			default:
				return FontManager.getRunescapeFont().deriveFont(size);
		}
	}

	static Color alpha(Color c, double a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(Math.max(0, Math.min(1, a)) * c.getAlpha()));
	}

	static Color mix(Color a, Color b, double t)
	{
		return new Color(
			(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	static double luma(Color c)
	{
		return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
	}

	private static Color opaque(Color c, int fallback)
	{
		return new Color(c == null ? fallback : c.getRGB() & 0xFFFFFF);
	}

	/** The effect actually drawn: the chosen one, or the theme's own for {@code AUTO}. */
	GielinorWrappedConfig.Effect effect()
	{
		if (effect != GielinorWrappedConfig.Effect.AUTO)
		{
			return effect;
		}
		switch (name)
		{
			case MIDNIGHT:
				return GielinorWrappedConfig.Effect.STARS;
			case NEON:
			case VAPORWAVE:
				return GielinorWrappedConfig.Effect.GRID;
			case WILDERNESS:
			case DRAGON:
				return GielinorWrappedConfig.Effect.EMBERS;
			case CLEAN:
				return GielinorWrappedConfig.Effect.BLOBS;
			case RUNE:
			case CUSTOM:
				return GielinorWrappedConfig.Effect.SPARKLES;
			case BARROWS:
				return GielinorWrappedConfig.Effect.FOG;
			case ANCIENT:
				return GielinorWrappedConfig.Effect.AURORA;
			case FROST:
				return GielinorWrappedConfig.Effect.SNOW;
			case NOIR:
				return GielinorWrappedConfig.Effect.RAIN;
			case TOXIC:
				return GielinorWrappedConfig.Effect.BUBBLES;
			case DESERT:
				return GielinorWrappedConfig.Effect.RAYS;
			default:
				return GielinorWrappedConfig.Effect.MOTES;
		}
	}

	/** Paints the background at {@code t} seconds; decorations drift slowly so stills and video match. */
	void background(Graphics2D g, int w, int h, double t)
	{
		g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
		g.fillRect(0, 0, w, h);
		Composite old = g.getComposite();
		Stroke oldStroke = g.getStroke();
		int unit = Math.min(w, h);
		switch (effect())
		{
			case STARS:
				// Twinkling stars at fixed pseudo-random spots.
				for (int i = 0; i < 90; i++)
				{
					double x = hash(i, 1) * w;
					double y = hash(i, 2) * h;
					double tw = 0.5 + 0.5 * Math.sin(t * (1 + hash(i, 3) * 2) + i);
					double r = 1 + hash(i, 4) * unit / 400.0;
					g.setColor(alpha(text, 0.25 + 0.6 * tw));
					g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
				}
				break;
			case GRID:
			{
				// A receding grid on the lower half, with a sun on the horizon for vaporwave.
				int horizon = h * 11 / 20;
				if (name == GielinorWrappedConfig.ThemeName.VAPORWAVE)
				{
					double r = unit * 0.22;
					g.setPaint(new GradientPaint(0, (float) (horizon - r * 2), accent2, 0, horizon, new Color(0xFFD319)));
					g.fill(new Ellipse2D.Double(w / 2.0 - r, horizon - r * 1.6, r * 2, r * 2));
					g.setColor(bottom);
					for (int i = 0; i < 5; i++)
					{
						int band = (int) (r * 0.04 * (i + 1));
						g.fillRect(0, (int) (horizon - r * 0.9 + i * r * 0.18), w, band);
					}
					g.setPaint(new GradientPaint(0, horizon, mix(bottom, Color.BLACK, 0.4), 0, h, bottom));
					g.fillRect(0, horizon, w, h - horizon);
				}
				g.setColor(alpha(accent2, 0.25));
				for (int i = -12; i <= 12; i++)
				{
					g.drawLine(w / 2 + i * w / 24, horizon, w / 2 + i * w / 4, h);
				}
				double shift = (t * 0.6) % 1;
				for (int i = 0; i < 10; i++)
				{
					double p = (i + shift) / 10;
					int y = horizon + (int) ((h - horizon) * p * p);
					g.drawLine(0, y, w, y);
				}
				g.setPaint(new RadialGradientPaint(w / 2f, horizon, unit / 3f, new float[]{0, 1}, new Color[]{alpha(accent, 0.35), alpha(accent, 0)}));
				g.fillRect(0, 0, w, h);
				break;
			}
			case EMBERS:
				// Embers rising.
				for (int i = 0; i < 50; i++)
				{
					double speed = 0.03 + hash(i, 1) * 0.06;
					double y = (1 - ((hash(i, 2) + t * speed) % 1)) * h;
					double x = hash(i, 3) * w + Math.sin(t + i) * unit / 60.0;
					double r = 1.5 + hash(i, 4) * unit / 300.0;
					g.setColor(alpha(i % 3 == 0 ? accent2 : accent, 0.3 + 0.4 * hash(i, 5)));
					g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
				}
				break;
			case BLOBS:
				g.setColor(alpha(accent, 0.07));
				g.fill(new Ellipse2D.Double(w * 0.65 + Math.sin(t * 0.3) * unit * 0.03, -h * 0.2, unit * 0.8, unit * 0.8));
				g.setColor(alpha(accent2, 0.06));
				g.fill(new Ellipse2D.Double(-unit * 0.3, h * 0.6 + Math.cos(t * 0.3) * unit * 0.03, unit * 0.7, unit * 0.7));
				break;
			case SNOW:
				for (int i = 0; i < 110; i++)
				{
					double speed = 0.04 + hash(i, 1) * 0.07;
					double y = ((hash(i, 2) + t * speed) % 1) * h;
					double x = hash(i, 3) * w + Math.sin(t * (0.6 + hash(i, 6)) + i) * unit / 40.0;
					double r = 1 + hash(i, 4) * unit / 260.0;
					g.setColor(alpha(Color.WHITE, 0.35 + 0.5 * hash(i, 5)));
					g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
				}
				break;
			case RAIN:
			{
				g.setStroke(new BasicStroke(Math.max(1f, unit / 700f)));
				double len = unit / 22.0;
				for (int i = 0; i < 120; i++)
				{
					double speed = 0.6 + hash(i, 1) * 0.5;
					double y = ((hash(i, 2) + t * speed) % 1) * (h + len) - len;
					double x = hash(i, 3) * (w + h * 0.2) - y * 0.2;
					g.setColor(alpha(text, 0.12 + 0.2 * hash(i, 5)));
					g.drawLine((int) x, (int) y, (int) (x - len * 0.2), (int) (y + len));
				}
				break;
			}
			case BUBBLES:
				g.setStroke(new BasicStroke(Math.max(1f, unit / 500f)));
				for (int i = 0; i < 40; i++)
				{
					double speed = 0.03 + hash(i, 1) * 0.05;
					double y = (1 - ((hash(i, 2) + t * speed) % 1)) * (h * 1.1);
					double x = hash(i, 3) * w + Math.sin(t * 1.3 + i) * unit / 50.0;
					double r = unit / 120.0 + hash(i, 4) * unit / 30.0;
					g.setColor(alpha(i % 2 == 0 ? accent : accent2, 0.25 + 0.3 * hash(i, 5)));
					g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
					g.fill(new Ellipse2D.Double(x - r * 0.45, y - r * 0.55, r * 0.3, r * 0.3));
				}
				break;
			case SPARKLES:
				for (int i = 0; i < 45; i++)
				{
					double tw = Math.max(0, Math.sin(t * (1.2 + hash(i, 3) * 2) + i * 1.7));
					if (tw <= 0.05)
					{
						continue;
					}
					double x = hash(i, 1) * w;
					double y = hash(i, 2) * h;
					double r = (unit / 90.0 + hash(i, 4) * unit / 45.0) * tw;
					g.setColor(alpha(i % 3 == 0 ? accent2 : accent, 0.35 + 0.5 * tw));
					g.fill(star(x, y, r));
				}
				break;
			case AURORA:
				for (int band = 0; band < 3; band++)
				{
					Color c = band == 1 ? accent2 : accent;
					Path2D p = new Path2D.Double();
					double base = h * (0.22 + band * 0.12);
					double thick = h * 0.16;
					p.moveTo(0, base);
					for (int x = 0; x <= w; x += Math.max(4, w / 60))
					{
						p.lineTo(x, base + Math.sin(x * 4.0 / w + t * 0.35 + band * 1.9) * h * 0.06);
					}
					for (int x = w; x >= 0; x -= Math.max(4, w / 60))
					{
						p.lineTo(x, base + thick + Math.sin(x * 3.0 / w + t * 0.3 + band) * h * 0.05);
					}
					p.closePath();
					g.setPaint(new GradientPaint(0, (float) base, alpha(c, 0.28), 0, (float) (base + thick * 1.3), alpha(c, 0)));
					g.fill(p);
				}
				break;
			case FOG:
				for (int i = 0; i < 8; i++)
				{
					double x = ((hash(i, 1) + t * (0.01 + hash(i, 2) * 0.015)) % 1.4 - 0.2) * w;
					double y = h * (0.45 + hash(i, 3) * 0.55);
					float r = (float) (unit * (0.35 + hash(i, 4) * 0.35));
					g.setPaint(new RadialGradientPaint((float) x, (float) y, r, new float[]{0, 1},
						new Color[]{alpha(mix(text, bottom, 0.5), 0.16), alpha(mix(text, bottom, 0.5), 0)}));
					g.fillRect(0, 0, w, h);
				}
				break;
			case RAYS:
			{
				int n = 14;
				double turn = t * 0.05;
				g.setColor(alpha(light() ? Color.WHITE : accent, light() ? 0.25 : 0.08));
				double r = Math.max(w, h) * 1.5;
				for (int i = 0; i < n; i += 2)
				{
					double a0 = turn + i * Math.PI * 2 / n;
					double a1 = turn + (i + 1) * Math.PI * 2 / n;
					Path2D p = new Path2D.Double();
					p.moveTo(w / 2.0, -h * 0.1);
					p.lineTo(w / 2.0 + Math.cos(a0) * r, -h * 0.1 + Math.sin(a0) * r);
					p.lineTo(w / 2.0 + Math.cos(a1) * r, -h * 0.1 + Math.sin(a1) * r);
					p.closePath();
					g.fill(p);
				}
				break;
			}
			case NONE:
				break;
			default:
				// Parchment: soft warm light with a dark vignette.
				g.setPaint(new RadialGradientPaint(w / 2f, h * 0.4f, Math.max(w, h) * 0.75f, new float[]{0, 0.6f, 1},
					new Color[]{alpha(accent, 0.18), alpha(accent, 0.04), new Color(0, 0, 0, light() ? 30 : 120)}));
				g.fillRect(0, 0, w, h);
				for (int i = 0; i < 30; i++)
				{
					double y = (1 - ((hash(i, 2) + t * 0.02) % 1)) * h;
					double r = 1 + hash(i, 4) * unit / 350.0;
					g.setColor(alpha(accent, 0.15 + 0.25 * hash(i, 5)));
					g.fill(new Ellipse2D.Double(hash(i, 3) * w - r, y - r, r * 2, r * 2));
				}
		}
		g.setStroke(oldStroke);
		g.setComposite(old);
	}

	/** A four-pointed twinkle. */
	static Path2D star(double x, double y, double r)
	{
		Path2D p = new Path2D.Double();
		double in = r * 0.22;
		p.moveTo(x, y - r);
		p.lineTo(x + in, y - in);
		p.lineTo(x + r, y);
		p.lineTo(x + in, y + in);
		p.lineTo(x, y + r);
		p.lineTo(x - in, y + in);
		p.lineTo(x - r, y);
		p.lineTo(x - in, y - in);
		p.closePath();
		return p;
	}

	/** Darkens or lightens a band behind text so it reads over busy backgrounds. */
	void scrim(Graphics2D g, int x, int y, int w, int h)
	{
		g.setComposite(AlphaComposite.SrcOver);
		g.setColor(panel);
		g.fillRoundRect(x, y, w, h, Math.min(w, h) / 6, Math.min(w, h) / 6);
	}

	boolean hasFinish()
	{
		return grade != GielinorWrappedConfig.Grade.NONE || vignette > 0 || grain > 0 || scanlines || border != GielinorWrappedConfig.Border.NONE;
	}

	/**
	 * The finishing pass over a whole frame or card: colour grade, vignette, grain, scanlines, then the border.
	 * {@code frame} seeds the grain so it moves from frame to frame.
	 */
	void finish(BufferedImage img, int frame)
	{
		if (!hasFinish())
		{
			return;
		}
		int w = img.getWidth();
		int h = img.getHeight();
		boolean direct = img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_INT_ARGB;
		int[] px = direct ? ((DataBufferInt) img.getRaster().getDataBuffer()).getData() : img.getRGB(0, 0, w, h, null, 0, w);
		int[] vig = vignette > 0 ? vignetteMap(w, h) : null;
		int grainAmp = (int) Math.round(grain * 48);
		int lineStep = Math.max(2, h / 300);
		long seed = 0x9E3779B97F4A7C15L * (frame + 1);
		for (int y = 0; y < h; y++)
		{
			boolean dark = scanlines && (y / (lineStep / 2 == 0 ? 1 : lineStep / 2)) % 2 == 1;
			int row = y * w;
			for (int x = 0; x < w; x++)
			{
				int i = row + x;
				int c = px[i];
				int r = (c >> 16) & 0xFF;
				int gr = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				if (grade != GielinorWrappedConfig.Grade.NONE)
				{
					int g2 = gradePixel(grade, r, gr, b);
					r = (g2 >> 16) & 0xFF;
					gr = (g2 >> 8) & 0xFF;
					b = g2 & 0xFF;
				}
				int mul = 256;
				if (vig != null)
				{
					mul = vig[i];
				}
				if (dark)
				{
					mul = mul * 210 >> 8;
				}
				if (mul != 256)
				{
					r = r * mul >> 8;
					gr = gr * mul >> 8;
					b = b * mul >> 8;
				}
				if (grainAmp > 0)
				{
					seed ^= seed << 13;
					seed ^= seed >>> 7;
					seed ^= seed << 17;
					int n = (int) ((seed >>> 40) & 0xFF) - 128;
					int d = n * grainAmp >> 7;
					r = clamp255(r + d);
					gr = clamp255(gr + d);
					b = clamp255(b + d);
				}
				px[i] = (c & 0xFF000000) | (r << 16) | (gr << 8) | b;
			}
		}
		if (!direct)
		{
			img.setRGB(0, 0, w, h, px, 0, w);
		}
		if (border != GielinorWrappedConfig.Border.NONE)
		{
			Graphics2D g = img.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			drawBorder(g, w, h);
			g.dispose();
		}
	}

	private int[] vignetteMap(int w, int h)
	{
		if (vignetteMap != null && vignetteW == w && vignetteH == h)
		{
			return vignetteMap;
		}
		int[] map = new int[w * h];
		double cx = w / 2.0;
		double cy = h / 2.0;
		double max = Math.hypot(cx, cy);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				double d = Math.hypot(x - cx, y - cy) / max;
				double s = Captions.clamp((d - 0.4) / 0.65);
				s = s * s * (3 - 2 * s);
				map[y * w + x] = (int) Math.round(256 * (1 - vignette * 0.85 * s));
			}
		}
		vignetteMap = map;
		vignetteW = w;
		vignetteH = h;
		return map;
	}

	static int gradePixel(GielinorWrappedConfig.Grade grade, int r, int g, int b)
	{
		double rf = r;
		double gf = g;
		double bf = b;
		double l = 0.299 * r + 0.587 * g + 0.114 * b;
		switch (grade)
		{
			case WARM:
				rf = r * 1.08 + 8;
				gf = g * 1.02 + 2;
				bf = b * 0.86;
				break;
			case COOL:
				rf = r * 0.88;
				gf = g * 1.0 + 2;
				bf = b * 1.08 + 10;
				break;
			case VINTAGE:
				rf = 24 + (l * 0.25 + r * 0.75) * 0.86;
				gf = 18 + (l * 0.25 + g * 0.75) * 0.80;
				bf = 34 + (l * 0.25 + b * 0.75) * 0.64;
				break;
			case SEPIA:
				rf = r * 0.393 + g * 0.769 + b * 0.189;
				gf = r * 0.349 + g * 0.686 + b * 0.168;
				bf = r * 0.272 + g * 0.534 + b * 0.131;
				break;
			case MONO:
			{
				double m = (l - 128) * 1.12 + 128;
				rf = m;
				gf = m;
				bf = m;
				break;
			}
			case VIVID:
				rf = ((l + (r - l) * 1.4) - 128) * 1.08 + 128;
				gf = ((l + (g - l) * 1.4) - 128) * 1.08 + 128;
				bf = ((l + (b - l) * 1.4) - 128) * 1.08 + 128;
				break;
			case DREAMY:
				rf = 28 + (l * 0.2 + r * 0.8) * 0.9 + 10;
				gf = 22 + (l * 0.2 + g * 0.8) * 0.88;
				bf = 30 + (l * 0.2 + b * 0.8) * 0.9 + 8;
				break;
			case TEAL_ORANGE:
			{
				double k = (l / 255 - 0.5) * 2;
				rf = r + k * 26;
				gf = g + k * 6;
				bf = b - k * 30;
				break;
			}
			default:
				break;
		}
		return clamp255((int) rf) << 16 | clamp255((int) gf) << 8 | clamp255((int) bf);
	}

	private static int clamp255(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	private void drawBorder(Graphics2D g, int w, int h)
	{
		int u = Math.min(w, h);
		float line = Math.max(2f, u / 260f);
		int m = u / 36;
		switch (border)
		{
			case LINE:
				g.setColor(accent);
				g.setStroke(new BasicStroke(line));
				g.drawRect(m, m, w - 2 * m, h - 2 * m);
				break;
			case DOUBLE:
				g.setColor(accent);
				g.setStroke(new BasicStroke(line));
				g.drawRect(m, m, w - 2 * m, h - 2 * m);
				g.setStroke(new BasicStroke(line / 2));
				int m2 = m + (int) (line * 3);
				g.drawRect(m2, m2, w - 2 * m2, h - 2 * m2);
				break;
			case CORNERS:
			{
				g.setColor(accent);
				g.setStroke(new BasicStroke(line * 1.5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
				int len = u / 9;
				int r = w - m;
				int bt = h - m;
				g.drawPolyline(new int[]{m, m, m + len}, new int[]{m + len, m, m}, 3);
				g.drawPolyline(new int[]{r - len, r, r}, new int[]{m, m, m + len}, 3);
				g.drawPolyline(new int[]{m, m, m + len}, new int[]{bt - len, bt, bt}, 3);
				g.drawPolyline(new int[]{r - len, r, r}, new int[]{bt, bt, bt - len}, 3);
				break;
			}
			case INTERFACE:
			{
				// Stone-and-wood frame like the game's interfaces.
				int t = Math.max(6, u / 45);
				g.setColor(new Color(0x3E3529));
				g.fillRect(0, 0, w, t);
				g.fillRect(0, h - t, w, t);
				g.fillRect(0, 0, t, h);
				g.fillRect(w - t, 0, t, h);
				g.setStroke(new BasicStroke(Math.max(1f, t / 5f)));
				g.setColor(new Color(0x5D5447));
				g.drawRect(t / 5, t / 5, w - 2 * t / 5, h - 2 * t / 5);
				g.setColor(new Color(0x18140F));
				g.drawRect(t, t, w - 2 * t, h - 2 * t);
				g.setColor(new Color(0x7A6A52));
				for (int[] c : new int[][]{{0, 0}, {w - t * 2, 0}, {0, h - t * 2}, {w - t * 2, h - t * 2}})
				{
					g.fillRect(c[0] + t / 3, c[1] + t / 3, t * 2 - 2 * t / 3, t * 2 - 2 * t / 3);
				}
				break;
			}
			case POLAROID:
			{
				int s = u / 30;
				int bottomBand = u / 9;
				g.setColor(new Color(0xF7F4EC));
				g.fillRect(0, 0, w, s);
				g.fillRect(0, h - bottomBand, w, bottomBand);
				g.fillRect(0, 0, s, h);
				g.fillRect(w - s, 0, s, h);
				g.setColor(new Color(0, 0, 0, 40));
				g.setStroke(new BasicStroke(Math.max(1f, u / 600f)));
				g.drawRect(s, s, w - 2 * s, h - s - bottomBand);
				break;
			}
			default:
				break;
		}
	}

	/** Deterministic value in [0, 1). */
	static double hash(int i, int salt)
	{
		long x = i * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
		x ^= x >>> 31;
		x *= 0xBF58476D1CE4E5B9L;
		x ^= x >>> 29;
		return (x >>> 11) / (double) (1L << 53);
	}
}
