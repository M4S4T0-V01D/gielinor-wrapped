package com.gielinorwrapped;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws captions: "|" splits lines, the first line is the headline and the rest are smaller. Lines
 * slide in one after another, and numbers in the headline are picked out in the accent colour.
 */
final class Captions
{
	private static final double INTRO = 0.35;
	private static final double STAGGER = 0.15;
	private static final double OUTRO = 0.25;

	private Captions()
	{
	}

	private static final class Line
	{
		final String[] words;
		final Font font;
		final int width;
		final int height;
		final int ascent;
		final int lineIndex;
		final int firstWord;

		Line(String[] words, Font font, FontMetrics fm, int lineIndex, int firstWord)
		{
			this.words = words;
			this.font = font;
			width = fm.stringWidth(String.join(" ", words));
			height = fm.getHeight();
			ascent = fm.getAscent();
			this.lineIndex = lineIndex;
			this.firstWord = firstWord;
		}
	}

	/**
	 * @param area where the caption may go; it is placed at the top, middle or bottom of it
	 * @param size headline height in pixels
	 * @param t seconds since the caption appeared
	 * @param duration seconds it stays up, for the fade out and word-by-word timing
	 */
	static void draw(Graphics2D g, String text, Rectangle area, GielinorWrappedConfig.CaptionPosition position,
		GielinorWrappedConfig.CaptionStyle style, Theme theme, float size, double t, double duration)
	{
		if (text == null || text.trim().isEmpty() || t < 0)
		{
			return;
		}
		List<Line> lines = layout(g, theme, text, (int) (area.width * 0.9), size);
		if (lines.isEmpty())
		{
			return;
		}
		int gap = Math.round(size * 0.15f);
		int pad = Math.round(size * 0.4f);
		int blockH = -gap;
		int blockW = 0;
		int totalWords = 0;
		for (Line l : lines)
		{
			blockH += l.height + gap;
			blockW = Math.max(blockW, l.width);
			totalWords += l.words.length;
		}
		int y;
		switch (position)
		{
			case TOP:
				y = area.y + pad * 2;
				break;
			case CENTER:
				y = area.y + (area.height - blockH) / 2;
				break;
			default:
				y = area.y + area.height - blockH - pad * 2;
		}
		double fadeOut = duration > 0 ? clamp((duration - t) / OUTRO) : 1;

		if (style == GielinorWrappedConfig.CaptionStyle.BOXED)
		{
			double a = ease(clamp(t / INTRO)) * fadeOut;
			int bx = area.x + (area.width - blockW) / 2 - pad;
			Color panel = theme.panel;
			g.setColor(Theme.alpha(panel, a));
			int arc = Math.round(size * 0.6f);
			g.fillRoundRect(bx, y - pad / 2, blockW + pad * 2, blockH + pad, arc, arc);
			g.setColor(Theme.alpha(theme.accent, a));
			g.fillRect(bx + arc / 2, y + blockH + pad / 2 - Math.max(2, Math.round(size / 12f)), blockW + pad * 2 - arc, Math.max(2, Math.round(size / 12f)));
		}

		double wordTime = totalWords == 0 ? 0 : Math.min(0.4, Math.max(0.1, (duration > 0 ? duration * 0.6 : 2) / totalWords));
		Color outline = theme.light() && style != GielinorWrappedConfig.CaptionStyle.OUTLINE ? new Color(255, 255, 255) : new Color(0, 0, 0);
		Color fill = style == GielinorWrappedConfig.CaptionStyle.BOXED ? theme.text : (theme.light() && style == GielinorWrappedConfig.CaptionStyle.SHADOW ? theme.text : new Color(255, 255, 255));

		for (Line l : lines)
		{
			double p = ease(clamp((t - l.lineIndex * STAGGER) / INTRO));
			double a = p * fadeOut;
			if (a <= 0)
			{
				y += l.height + gap;
				continue;
			}
			int lift = (int) Math.round((1 - p) * size * 0.5);
			g.setFont(l.font);
			FontMetrics fm = g.getFontMetrics();
			int x = area.x + (area.width - l.width) / 2;
			int baseline = y + l.ascent + lift;
			int space = fm.charWidth(' ');
			for (int i = 0; i < l.words.length; i++)
			{
				String word = l.words[i];
				Color c = l.lineIndex == 0 && hasDigit(word) ? theme.accent : fill;
				double wa = a;
				if (style == GielinorWrappedConfig.CaptionStyle.KARAOKE)
				{
					double start = (l.firstWord + i) * wordTime;
					double since = t - start;
					if (since < 0)
					{
						wa *= 0.3;
						c = fill;
					}
					else if (since < wordTime * 1.5)
					{
						c = theme.accent;
					}
				}
				drawWord(g, word, x, baseline, style, Theme.alpha(c, wa), Theme.alpha(outline, wa), size);
				x += fm.stringWidth(word) + space;
			}
			y += l.height + gap;
		}
	}

	private static void drawWord(Graphics2D g, String word, int x, int y, GielinorWrappedConfig.CaptionStyle style, Color fill, Color outline, float size)
	{
		switch (style)
		{
			case OUTLINE:
			case KARAOKE:
			{
				Shape shape = g.getFont().createGlyphVector(g.getFontRenderContext(), word).getOutline(x, y);
				g.setStroke(new BasicStroke(Math.max(2f, size / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(outline);
				g.draw(shape);
				g.setColor(fill);
				g.fill(shape);
				break;
			}
			case SHADOW:
			{
				int off = Math.max(2, Math.round(size / 14f));
				g.setColor(Theme.alpha(outline, outline.getAlpha() / 255.0 * 0.7));
				g.drawString(word, x + off, y + off);
				g.setColor(fill);
				g.drawString(word, x, y);
				break;
			}
			default:
				g.setColor(fill);
				g.drawString(word, x, y);
		}
	}

	private static List<Line> layout(Graphics2D g, Theme theme, String text, int maxWidth, float size)
	{
		List<Line> lines = new ArrayList<>();
		String[] parts = text.split("\\|");
		int lineIndex = 0;
		int wordIndex = 0;
		for (int p = 0; p < parts.length; p++)
		{
			String part = parts[p].trim();
			if (part.isEmpty())
			{
				continue;
			}
			Font font = lines.isEmpty() ? theme.bold(size) : theme.regular(size * 0.72f);
			FontMetrics fm = g.getFontMetrics(font);
			for (String row : wrap(part, fm, maxWidth))
			{
				String[] words = row.split(" ");
				lines.add(new Line(words, font, fm, lineIndex++, wordIndex));
				wordIndex += words.length;
			}
		}
		return lines;
	}

	/** Greedy word wrap; a single word wider than the line is kept whole. */
	static List<String> wrap(String text, FontMetrics fm, int maxWidth)
	{
		List<String> rows = new ArrayList<>();
		StringBuilder row = new StringBuilder();
		for (String word : text.trim().split("\\s+"))
		{
			if (row.length() > 0 && fm.stringWidth(row + " " + word) > maxWidth)
			{
				rows.add(row.toString());
				row.setLength(0);
			}
			if (row.length() > 0)
			{
				row.append(' ');
			}
			row.append(word);
		}
		if (row.length() > 0)
		{
			rows.add(row.toString());
		}
		return rows;
	}

	/** Draws {@code text} centred on {@code cx} with its baseline at {@code y}, shrinking it to fit {@code maxWidth}. */
	static void centered(Graphics2D g, String text, int cx, int y, Font font, Color color, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics(font);
		int w = fm.stringWidth(text);
		if (w > maxWidth && w > 0)
		{
			font = font.deriveFont(AffineTransform.getScaleInstance(maxWidth / (double) w, 1));
			w = maxWidth;
		}
		g.setFont(font);
		g.setColor(color);
		g.drawString(text, cx - w / 2, y);
	}

	private static boolean hasDigit(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (Character.isDigit(s.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	static double clamp(double v)
	{
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	/** Ease-out cubic. */
	static double ease(double p)
	{
		double q = 1 - p;
		return 1 - q * q * q;
	}
}
