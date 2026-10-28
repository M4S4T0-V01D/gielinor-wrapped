package com.gielinorwrapped;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Green screen keying: makes pixels near the key colour transparent. Distance is measured on chroma
 * (colour without brightness), so shadows on the screen still key out.
 */
final class Chroma
{
	final int key;
	/** Chroma distance, 0 to 1, under which a pixel is fully cut. */
	final double tolerance;
	/** Extra distance over which a pixel fades back in. */
	final double softness;
	final boolean spill;
	private final double keyCb;
	private final double keyCr;

	Chroma(Color key, int tolerancePercent, int softnessPercent, boolean spill)
	{
		this.key = key.getRGB() & 0xFFFFFF;
		this.tolerance = tolerancePercent / 100.0 * 0.5;
		this.softness = Math.max(0.005, softnessPercent / 100.0 * 0.3);
		this.spill = spill;
		double[] c = chroma(key.getRed(), key.getGreen(), key.getBlue());
		keyCb = c[0];
		keyCr = c[1];
	}

	static Chroma of(GielinorWrappedConfig config)
	{
		return config.chromaKey() ? new Chroma(config.chromaColor(), config.chromaTolerance(), config.chromaSoftness(), config.chromaSpill()) : null;
	}

	private static double[] chroma(int r, int g, int b)
	{
		double cb = (-0.168736 * r - 0.331264 * g + 0.5 * b) / 255.0;
		double cr = (0.5 * r - 0.418688 * g - 0.081312 * b) / 255.0;
		return new double[]{cb, cr};
	}

	/** The share of {@code img} that keys out, sampled sparsely; a green-screen recording is mostly key colour. */
	double coverage(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int step = Math.max(1, Math.min(w, h) / 60);
		int hits = 0;
		int total = 0;
		for (int y = step / 2; y < h; y += step)
		{
			for (int x = step / 2; x < w; x += step)
			{
				int p = img.getRGB(x, y);
				double[] c = chroma(p >> 16 & 0xFF, p >> 8 & 0xFF, p & 0xFF);
				if (Math.hypot(c[0] - keyCb, c[1] - keyCr) < tolerance + softness * 0.5)
				{
					hits++;
				}
				total++;
			}
		}
		return total == 0 ? 0 : hits / (double) total;
	}

	/** A keyed ARGB copy of {@code src}. */
	BufferedImage key(BufferedImage src)
	{
		int w = src.getWidth();
		int h = src.getHeight();
		int[] px = src.getRGB(0, 0, w, h, null, 0, w);
		int kr = key >> 16 & 0xFF;
		int kg = key >> 8 & 0xFF;
		int kb = key & 0xFF;
		// The key's strongest channel is the one spill shows up in.
		int dominant = kg >= kr && kg >= kb ? 1 : kb >= kr ? 2 : 0;
		for (int i = 0; i < px.length; i++)
		{
			int p = px[i];
			int r = p >> 16 & 0xFF;
			int g = p >> 8 & 0xFF;
			int b = p & 0xFF;
			double cb = (-0.168736 * r - 0.331264 * g + 0.5 * b) / 255.0;
			double cr = (0.5 * r - 0.418688 * g - 0.081312 * b) / 255.0;
			double d = Math.hypot(cb - keyCb, cr - keyCr);
			double a = Captions.clamp((d - tolerance) / softness);
			a = a * a * (3 - 2 * a);
			int alpha = (int) Math.round(a * (p >>> 24));
			if (alpha == 0)
			{
				px[i] = 0;
				continue;
			}
			if (spill && d < tolerance + softness * 3)
			{
				// Near the key only, so green things in the scene stay green: clamp the key channel to the brighter of the other two.
				switch (dominant)
				{
					case 1:
						g = Math.min(g, Math.max(r, b));
						break;
					case 2:
						b = Math.min(b, Math.max(r, g));
						break;
					default:
						r = Math.min(r, Math.max(g, b));
				}
			}
			px[i] = alpha << 24 | r << 16 | g << 8 | b;
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, w, h, px, 0, w);
		return out;
	}
}
