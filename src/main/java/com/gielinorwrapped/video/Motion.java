package com.gielinorwrapped.video;

/** Motion compensation (integer-pel luma, eighth-pel bilinear chroma) and block SAD. */
final class Motion
{
	private Motion()
	{
	}

	/** 16x16 luma prediction for the block at (x, y) moved by an integer motion vector in quarter-pel units. */
	static void luma(int[] ref, int w, int h, int x, int y, int mvx, int mvy, int[] pred)
	{
		int sx = x + (mvx >> 2);
		int sy = y + (mvy >> 2);
		if (sx >= 0 && sy >= 0 && sx + 16 <= w && sy + 16 <= h)
		{
			for (int j = 0; j < 16; j++)
			{
				System.arraycopy(ref, (sy + j) * w + sx, pred, j * 16, 16);
			}
			return;
		}
		for (int j = 0; j < 16; j++)
		{
			int row = clamp(sy + j, h - 1) * w;
			for (int i = 0; i < 16; i++)
			{
				pred[j * 16 + i] = ref[row + clamp(sx + i, w - 1)];
			}
		}
	}

	/** 8x8 chroma prediction for the block at (x, y); the luma vector is in eighth-pel chroma units. */
	static void chroma(int[] ref, int w, int h, int x, int y, int mvx, int mvy, int[] pred)
	{
		int xi = x + (mvx >> 3);
		int yi = y + (mvy >> 3);
		int xf = mvx & 7;
		int yf = mvy & 7;
		int wa = (8 - xf) * (8 - yf);
		int wb = xf * (8 - yf);
		int wc = (8 - xf) * yf;
		int wd = xf * yf;
		boolean inside = xi >= 0 && yi >= 0 && xi + 9 <= w && yi + 9 <= h;
		for (int j = 0; j < 8; j++)
		{
			int y0 = yi + j;
			int r0 = (inside ? y0 : clamp(y0, h - 1)) * w;
			int r1 = (inside ? y0 + 1 : clamp(y0 + 1, h - 1)) * w;
			for (int i = 0; i < 8; i++)
			{
				int x0 = xi + i;
				int c0 = inside ? x0 : clamp(x0, w - 1);
				int c1 = inside ? x0 + 1 : clamp(x0 + 1, w - 1);
				pred[j * 8 + i] = (wa * ref[r0 + c0] + wb * ref[r0 + c1] + wc * ref[r1 + c0] + wd * ref[r1 + c1] + 32) >> 6;
			}
		}
	}

	/** SAD between the 16x16 source block at (x, y) and the reference block at (rx, ry); both must be inside the planes. */
	static int sad16(int[] src, int[] ref, int w, int x, int y, int rx, int ry, int limit)
	{
		int sad = 0;
		for (int j = 0; j < 16; j++)
		{
			int s = (y + j) * w + x;
			int r = (ry + j) * w + rx;
			for (int i = 0; i < 16; i++)
			{
				sad += Math.abs(src[s + i] - ref[r + i]);
			}
			if (sad >= limit)
			{
				return sad;
			}
		}
		return sad;
	}

	/** SAD between an n x n source block at (x, y) and a raster prediction. */
	static int sadPred(int[] src, int w, int x, int y, int[] pred, int n)
	{
		int sad = 0;
		for (int j = 0; j < n; j++)
		{
			int s = (y + j) * w + x;
			for (int i = 0; i < n; i++)
			{
				sad += Math.abs(src[s + i] - pred[j * n + i]);
			}
		}
		return sad;
	}

	private static int clamp(int v, int max)
	{
		return v < 0 ? 0 : v > max ? max : v;
	}
}
