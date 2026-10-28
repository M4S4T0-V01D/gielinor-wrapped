package com.gielinorwrapped;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** Game sprites blown up for video, and the thick outlined text of a YouTube thumbnail. */
final class Sprites
{
	private Sprites()
	{
	}

	/** Crops transparent borders, so icons of different shapes line up. */
	static BufferedImage trim(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int x0 = w;
		int y0 = h;
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((img.getRGB(x, y) >>> 24) > 16)
				{
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0)
		{
			return null;
		}
		return copy(img, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
	}

	private static BufferedImage copy(BufferedImage img, int x, int y, int w, int h)
	{
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, -x, -y, null);
		g.dispose();
		return out;
	}

	/** Scales the sprite to fit a {@code size} square with hard pixel edges, by a whole factor where that fits. */
	static BufferedImage upscale(BufferedImage img, int size)
	{
		double fit = size / (double) Math.max(img.getWidth(), img.getHeight());
		double scale = fit >= 1 ? Math.floor(fit) : fit;
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, fit >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	/**
	 * The sprite with a soft coloured halo around its outline, {@code radius} pixels wide.
	 * The result is larger than the sprite by the radius on each side.
	 */
	static BufferedImage glow(BufferedImage img, Color color, int radius)
	{
		return halo(img, color, radius, false);
	}

	/** Like {@link #glow} but a solid border, like the white edge of a die-cut sticker. */
	static BufferedImage outline(BufferedImage img, Color color, int radius)
	{
		return halo(img, color, radius, true);
	}

	private static BufferedImage halo(BufferedImage img, Color color, int radius, boolean solid)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int ow = w + radius * 2;
		int oh = h + radius * 2;
		// Distance to the nearest solid pixel, by two passes of a chamfer transform.
		int far = radius * 3 + 3;
		int[] d = new int[ow * oh];
		for (int y = 0; y < oh; y++)
		{
			for (int x = 0; x < ow; x++)
			{
				int sx = x - radius;
				int sy = y - radius;
				boolean inside = sx >= 0 && sy >= 0 && sx < w && sy < h && (img.getRGB(sx, sy) >>> 24) > 96;
				d[y * ow + x] = inside ? 0 : far;
			}
		}
		for (int y = 0; y < oh; y++)
		{
			for (int x = 0; x < ow; x++)
			{
				int i = y * ow + x;
				if (x > 0)
				{
					d[i] = Math.min(d[i], d[i - 1] + 3);
				}
				if (y > 0)
				{
					d[i] = Math.min(d[i], d[i - ow] + 3);
					if (x > 0)
					{
						d[i] = Math.min(d[i], d[i - ow - 1] + 4);
					}
					if (x < ow - 1)
					{
						d[i] = Math.min(d[i], d[i - ow + 1] + 4);
					}
				}
			}
		}
		for (int y = oh - 1; y >= 0; y--)
		{
			for (int x = ow - 1; x >= 0; x--)
			{
				int i = y * ow + x;
				if (x < ow - 1)
				{
					d[i] = Math.min(d[i], d[i + 1] + 3);
				}
				if (y < oh - 1)
				{
					d[i] = Math.min(d[i], d[i + ow] + 3);
					if (x < ow - 1)
					{
						d[i] = Math.min(d[i], d[i + ow + 1] + 4);
					}
					if (x > 0)
					{
						d[i] = Math.min(d[i], d[i + ow - 1] + 4);
					}
				}
			}
		}
		BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
		int rgb = color.getRGB() & 0xFFFFFF;
		for (int i = 0; i < d.length; i++)
		{
			double dist = d[i] / 3.0;
			if (dist <= radius)
			{
				double a = solid ? Captions.clamp(radius + 0.5 - dist) : 1 - dist / (radius + 1);
				out.setRGB(i % ow, i / ow, (int) (solid ? a * 255 : a * a * 235) << 24 | rgb);
			}
		}
		Graphics2D g = out.createGraphics();
		g.drawImage(img, radius, radius, null);
		g.dispose();
		return out;
	}

	/** A sprite ready for a splash: trimmed, upscaled to {@code size} and glowing. Null if the sprite is empty. */
	static BufferedImage splash(BufferedImage img, int size, Color glow)
	{
		if (img == null)
		{
			return null;
		}
		BufferedImage t = trim(img);
		if (t == null)
		{
			return null;
		}
		int radius = Math.max(2, size / 14);
		return glow(upscale(t, size - radius * 2), glow, radius);
	}

	/** Draws {@code img} centred on (cx, cy), scaled and rotated by {@code angle} radians. */
	static void drawCentered(Graphics2D g, BufferedImage img, double cx, double cy, double scale, double angle)
	{
		if (img == null || scale <= 0)
		{
			return;
		}
		AffineTransform at = new AffineTransform();
		at.translate(cx, cy);
		at.rotate(angle);
		at.scale(scale, scale);
		at.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
		Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, at, null);
		if (old != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
		}
	}

	/**
	 * Thumbnail text: filled, with a thick dark outline and a hard drop shadow, centred on {@code cx}.
	 * The font shrinks until the text fits {@code maxWidth}.
	 */
	static void outlined(Graphics2D g, String text, double cx, double baseline, Font font, Color fill, double angle, int maxWidth)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		Font f = font;
		TextLayout layout = new TextLayout(text, f, g.getFontRenderContext());
		while (layout.getAdvance() > maxWidth && f.getSize2D() > 8)
		{
			f = f.deriveFont(f.getSize2D() * 0.92f);
			layout = new TextLayout(text, f, g.getFontRenderContext());
		}
		float stroke = f.getSize2D() * 0.14f;
		AffineTransform at = new AffineTransform();
		at.translate(cx, baseline);
		at.rotate(angle);
		at.translate(-layout.getAdvance() / 2, 0);
		Shape shape = layout.getOutline(at);
		Shape shadow = AffineTransform.getTranslateInstance(stroke * 0.6, stroke * 0.8).createTransformedShape(shape);
		BasicStroke round = new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		g.setStroke(round);
		g.setColor(new Color(0, 0, 0, 140));
		g.draw(shadow);
		g.fill(shadow);
		g.setColor(new Color(0x111111));
		g.draw(shape);
		g.setColor(fill);
		g.fill(shape);
		g.setStroke(new BasicStroke(1));
	}
}
