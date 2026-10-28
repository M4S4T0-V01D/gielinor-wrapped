package com.gielinorwrapped.video;

/** Intra 16x16 luma and 8x8 chroma prediction (H.264 clauses 8.3.3 and 8.3.4) from reconstructed neighbours. */
final class IntraPred
{
	static final int LUMA_V = 0;
	static final int LUMA_H = 1;
	static final int LUMA_DC = 2;
	static final int LUMA_PLANE = 3;

	static final int CHROMA_DC = 0;
	static final int CHROMA_H = 1;
	static final int CHROMA_V = 2;
	static final int CHROMA_PLANE = 3;

	private IntraPred()
	{
	}

	static boolean lumaAvailable(int mode, boolean left, boolean top)
	{
		switch (mode)
		{
			case LUMA_V:
				return top;
			case LUMA_H:
				return left;
			case LUMA_PLANE:
				return left && top;
			default:
				return true;
		}
	}

	static boolean chromaAvailable(int mode, boolean left, boolean top)
	{
		switch (mode)
		{
			case CHROMA_V:
				return top;
			case CHROMA_H:
				return left;
			case CHROMA_PLANE:
				return left && top;
			default:
				return true;
		}
	}

	/** Fills pred (n*n raster) with the n x n block prediction at (x, y) of plane p (stride s); n is 16 for luma, 8 for chroma. */
	static void predict(int[] p, int s, int x, int y, int n, boolean chroma, int mode, boolean left, boolean top, int[] pred)
	{
		int o = y * s + x;
		boolean vertical = chroma ? mode == CHROMA_V : mode == LUMA_V;
		boolean horizontal = chroma ? mode == CHROMA_H : mode == LUMA_H;
		boolean plane = chroma ? mode == CHROMA_PLANE : mode == LUMA_PLANE;
		if (vertical)
		{
			for (int j = 0; j < n; j++)
			{
				System.arraycopy(p, o - s, pred, j * n, n);
			}
		}
		else if (horizontal)
		{
			for (int j = 0; j < n; j++)
			{
				int v = p[o + j * s - 1];
				for (int i = 0; i < n; i++)
				{
					pred[j * n + i] = v;
				}
			}
		}
		else if (plane)
		{
			plane(p, s, o, n, pred);
		}
		else if (chroma)
		{
			for (int by = 0; by < 8; by += 4)
			{
				for (int bx = 0; bx < 8; bx += 4)
				{
					int dc = chromaDc(p, s, o, bx, by, left, top);
					for (int j = 0; j < 4; j++)
					{
						for (int i = 0; i < 4; i++)
						{
							pred[(by + j) * 8 + bx + i] = dc;
						}
					}
				}
			}
		}
		else
		{
			int sumTop = 0;
			int sumLeft = 0;
			for (int i = 0; i < 16; i++)
			{
				sumTop += top ? p[o - s + i] : 0;
				sumLeft += left ? p[o + i * s - 1] : 0;
			}
			int dc = left && top ? (sumTop + sumLeft + 16) >> 5 : left ? (sumLeft + 8) >> 4 : top ? (sumTop + 8) >> 4 : 128;
			java.util.Arrays.fill(pred, 0, 256, dc);
		}
	}

	private static int chromaDc(int[] p, int s, int o, int bx, int by, boolean left, boolean top)
	{
		int sumTop = 0;
		int sumLeft = 0;
		for (int i = 0; i < 4; i++)
		{
			sumTop += top ? p[o - s + bx + i] : 0;
			sumLeft += left ? p[o + (by + i) * s - 1] : 0;
		}
		boolean preferTop = bx == 4 && by == 0;
		boolean preferLeft = bx == 0 && by == 4;
		if (!preferTop && !preferLeft && left && top)
		{
			return (sumTop + sumLeft + 4) >> 3;
		}
		if (preferTop)
		{
			return top ? (sumTop + 2) >> 2 : left ? (sumLeft + 2) >> 2 : 128;
		}
		return left ? (sumLeft + 2) >> 2 : top ? (sumTop + 2) >> 2 : 128;
	}

	private static void plane(int[] p, int s, int o, int n, int[] pred)
	{
		int half = n / 2;
		int h = 0;
		int v = 0;
		for (int i = 0; i < half; i++)
		{
			// p[-1, -1] is used where the index reaches -1.
			h += (i + 1) * (p[o - s + half + i] - p[o - s + half - 2 - i]);
			v += (i + 1) * (p[o + (half + i) * s - 1] - p[o + (half - 2 - i) * s - 1]);
		}
		int a = 16 * (p[o + (n - 1) * s - 1] + p[o - s + n - 1]);
		int scale = n == 16 ? 5 : 34;
		int b = (scale * h + 32) >> 6;
		int c = (scale * v + 32) >> 6;
		int centre = half - 1;
		for (int y = 0; y < n; y++)
		{
			for (int x = 0; x < n; x++)
			{
				int val = (a + b * (x - centre) + c * (y - centre) + 16) >> 5;
				pred[y * n + x] = val < 0 ? 0 : val > 255 ? 255 : val;
			}
		}
	}
}
