package com.gielinorwrapped;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The player doing an emote, cut out of the game view: transparent PNG frames with their capture times,
 * stored like a clip in {@code emote.gwc}. The player performs the emote themselves; the plugin only
 * records the frames that follow.
 */
final class Emote
{
	private final List<BufferedImage> frames;
	/** Milliseconds from the first frame to each frame. */
	private final long[] times;
	private final long length;

	private Emote(List<BufferedImage> frames, long[] times)
	{
		this.frames = frames;
		this.times = times;
		this.length = Math.max(1, times[times.length - 1] + (times.length > 1 ? (times[times.length - 1] / (times.length - 1)) : 100));
	}

	/** Decodes stored frames, or returns null when there are none usable. */
	static Emote of(List<ClipFile.Frame> stored)
	{
		List<BufferedImage> frames = new ArrayList<>();
		List<Long> times = new ArrayList<>();
		for (ClipFile.Frame f : stored)
		{
			try
			{
				frames.add(f.image());
				times.add(f.time);
			}
			catch (IOException e)
			{
				// skip the frame
			}
		}
		// Crop every frame to the same box so the player doesn't jump about as arms move.
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = -1;
		int y1 = -1;
		for (BufferedImage img : frames)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				for (int x = 0; x < img.getWidth(); x++)
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
		}
		if (x1 < 0)
		{
			return null;
		}
		List<BufferedImage> cropped = new ArrayList<>();
		for (BufferedImage img : frames)
		{
			int w = Math.min(x1 + 1, img.getWidth()) - x0;
			int h = Math.min(y1 + 1, img.getHeight()) - y0;
			BufferedImage c = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
			if (w > 0 && h > 0)
			{
				Graphics2D g = c.createGraphics();
				g.drawImage(img.getSubimage(x0, y0, w, h), 0, 0, null);
				g.dispose();
			}
			cropped.add(c);
		}
		frames = cropped;
		long[] t = new long[times.size()];
		for (int i = 0; i < t.length; i++)
		{
			t[i] = times.get(i) - times.get(0);
		}
		return new Emote(frames, t);
	}

	int size()
	{
		return frames.size();
	}

	BufferedImage frameAt(double seconds)
	{
		long ms = Math.floorMod(Math.round(seconds * 1000), length);
		int lo = 0;
		int hi = times.length - 1;
		while (lo < hi)
		{
			int mid = (lo + hi + 1) >>> 1;
			if (times[mid] <= ms)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		return frames.get(lo);
	}

	/** Tallest frame, so the player keeps one size while the emote plays. */
	private int maxHeight()
	{
		int h = 1;
		for (BufferedImage f : frames)
		{
			h = Math.max(h, f.getHeight());
		}
		return h;
	}

	/**
	 * Draws the looping emote standing on {@code baseY}, centred on {@code cx}, at most {@code maxH} tall.
	 * It pops in over the first half second.
	 */
	void draw(Graphics2D g, double cx, double baseY, double maxH, double t)
	{
		BufferedImage img = frameAt(t);
		double scale = maxH / maxHeight();
		double pop = Splash.backOut(Captions.clamp(t / 0.5));
		if (pop <= 0)
		{
			return;
		}
		scale *= pop;
		double dw = img.getWidth() * scale;
		double dh = img.getHeight() * scale;
		Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		// Upscaling by a lot: nearest keeps the game's pixels crisp.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 2
			? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// Soft shadow under the feet.
		g.setColor(new java.awt.Color(0, 0, 0, (int) (70 * pop)));
		g.fillOval((int) (cx - dw * 0.45), (int) (baseY - dh * 0.03), (int) (dw * 0.9), (int) Math.max(2, dh * 0.06));
		g.drawImage(img, new AffineTransform(scale, 0, 0, scale, cx - dw / 2, baseY - dh), null);
		if (old != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
		}
	}
}
