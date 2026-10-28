package com.gielinorwrapped.video;

/**
 * The H.264 in-loop deblocking filter (8.7) for one-slice frames with a single QP, zero filter offsets and
 * 16x16 partitions. Runs over the reconstructed picture once all macroblocks are coded, exactly as a decoder
 * does, so the filtered picture can serve as the next reference.
 */
final class Deblock
{
	private static final int[] ALPHA = {
		0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		4, 4, 5, 6, 7, 8, 9, 10, 12, 13, 15, 17, 20, 22, 25, 28, 32, 36, 40, 45,
		50, 56, 63, 71, 80, 90, 101, 113, 127, 144, 162, 182, 203, 226, 255, 255,
	};
	private static final int[] BETA = {
		0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
		2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
		11, 11, 12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 17, 18, 18,
	};
	/** tC0 by indexA, for bS 1, 2 and 3. */
	private static final int[][] TC0 = {
		{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
		{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
		{0, 0, 1}, {0, 0, 1}, {0, 0, 1}, {0, 0, 1}, {0, 1, 1}, {0, 1, 1}, {1, 1, 1}, {1, 1, 1}, {1, 1, 1},
		{1, 1, 1}, {1, 1, 2}, {1, 1, 2}, {1, 1, 2}, {1, 1, 2}, {1, 2, 3}, {1, 2, 3}, {2, 2, 3}, {2, 2, 4},
		{2, 3, 4}, {2, 3, 4}, {3, 3, 5}, {3, 4, 6}, {3, 4, 6}, {4, 5, 7}, {4, 5, 8}, {4, 6, 9}, {5, 7, 10},
		{6, 8, 11}, {6, 8, 13}, {7, 10, 14}, {8, 11, 16}, {9, 12, 18}, {10, 13, 20}, {11, 15, 23},
		{13, 17, 25},
	};

	private Deblock()
	{
	}

	/**
	 * Filters the picture in place, macroblock by macroblock in raster order. {@code nzY} holds the
	 * coefficient count of every 4x4 luma block (row stride {@code mbW * 4}); motion vectors are per
	 * macroblock in quarter pels, all against the one reference picture.
	 */
	static void filter(int[] y, int[] u, int[] v, int pw, int cw, int mbW, int mbH,
		boolean[] intra, int[] mvx, int[] mvy, int[] nzY, int qp, int qpc)
	{
		int[] bs = new int[16];
		for (int mby = 0; mby < mbH; mby++)
		{
			for (int mbx = 0; mbx < mbW; mbx++)
			{
				for (int dir = 0; dir < 2; dir++)
				{
					boolean vertical = dir == 0;
					for (int e = 0; e < 4; e++)
					{
						if (e == 0 && (vertical ? mbx == 0 : mby == 0))
						{
							continue;
						}
						if (!strengths(bs, vertical, e, mbx, mby, mbW, intra, mvx, mvy, nzY))
						{
							continue;
						}
						int lx = mbx * 16 + (vertical ? e * 4 : 0);
						int ly = mby * 16 + (vertical ? 0 : e * 4);
						edge(y, pw, lx, ly, vertical, 16, bs, 1, qp, true);
						if ((e & 1) == 0)
						{
							int cx = mbx * 8 + (vertical ? e * 2 : 0);
							int cy = mby * 8 + (vertical ? 0 : e * 2);
							edge(u, cw, cx, cy, vertical, 8, bs, 2, qpc, false);
							edge(v, cw, cx, cy, vertical, 8, bs, 2, qpc, false);
						}
					}
				}
			}
		}
	}

	/** Boundary strengths of the 16 luma lines across one edge (8.7.2.1); false when all are zero. */
	private static boolean strengths(int[] bs, boolean vertical, int e, int mbx, int mby, int mbW,
		boolean[] intra, int[] mvx, int[] mvy, int[] nzY)
	{
		int q = mby * mbW + mbx;
		int p = e > 0 ? q : vertical ? q - 1 : q - mbW;
		int stride = mbW * 4;
		boolean any = false;
		for (int k = 0; k < 4; k++)
		{
			int s;
			if (intra[p] || intra[q])
			{
				s = e == 0 ? 4 : 3;
			}
			else
			{
				int qx = mbx * 4 + (vertical ? e : k);
				int qy = mby * 4 + (vertical ? k : e);
				int qb = qy * stride + qx;
				int pb = vertical ? qb - 1 : qb - stride;
				if (nzY[pb] != 0 || nzY[qb] != 0)
				{
					s = 2;
				}
				else if (p != q && (Math.abs(mvx[p] - mvx[q]) >= 4 || Math.abs(mvy[p] - mvy[q]) >= 4))
				{
					s = 1;
				}
				else
				{
					s = 0;
				}
			}
			for (int i = 0; i < 4; i++)
			{
				bs[k * 4 + i] = s;
			}
			any |= s != 0;
		}
		return any;
	}

	/**
	 * Filters {@code lines} sample lines across an edge whose first q sample is at (x, y). Line {@code i}
	 * takes its strength from luma line {@code i * bsStep}.
	 */
	private static void edge(int[] pic, int stride, int x, int y, boolean vertical, int lines, int[] bs,
		int bsStep, int qp, boolean luma)
	{
		int alpha = ALPHA[qp];
		int beta = BETA[qp];
		int step = vertical ? 1 : stride;
		int along = vertical ? stride : 1;
		int base = y * stride + x;
		for (int i = 0; i < lines; i++)
		{
			int s = bs[i * bsStep];
			if (s == 0)
			{
				continue;
			}
			int o = base + i * along;
			int p0 = pic[o - step];
			int p1 = pic[o - 2 * step];
			int q0 = pic[o];
			int q1 = pic[o + step];
			if (Math.abs(p0 - q0) >= alpha || Math.abs(p1 - p0) >= beta || Math.abs(q1 - q0) >= beta)
			{
				continue;
			}
			if (!luma)
			{
				if (s == 4)
				{
					pic[o - step] = (2 * p1 + p0 + q1 + 2) >> 2;
					pic[o] = (2 * q1 + q0 + p1 + 2) >> 2;
				}
				else
				{
					int tc = TC0[qp][s - 1] + 1;
					int delta = clip3(-tc, tc, (((q0 - p0) << 2) + (p1 - q1) + 4) >> 3);
					pic[o - step] = clip(p0 + delta);
					pic[o] = clip(q0 - delta);
				}
				continue;
			}
			int p2 = pic[o - 3 * step];
			int q2 = pic[o + 2 * step];
			boolean ap = Math.abs(p2 - p0) < beta;
			boolean aq = Math.abs(q2 - q0) < beta;
			if (s == 4)
			{
				boolean strong = Math.abs(p0 - q0) < (alpha >> 2) + 2;
				if (ap && strong)
				{
					int p3 = pic[o - 4 * step];
					pic[o - step] = (p2 + 2 * p1 + 2 * p0 + 2 * q0 + q1 + 4) >> 3;
					pic[o - 2 * step] = (p2 + p1 + p0 + q0 + 2) >> 2;
					pic[o - 3 * step] = (2 * p3 + 3 * p2 + p1 + p0 + q0 + 4) >> 3;
				}
				else
				{
					pic[o - step] = (2 * p1 + p0 + q1 + 2) >> 2;
				}
				if (aq && strong)
				{
					int q3 = pic[o + 3 * step];
					pic[o] = (p1 + 2 * p0 + 2 * q0 + 2 * q1 + q2 + 4) >> 3;
					pic[o + step] = (p0 + q0 + q1 + q2 + 2) >> 2;
					pic[o + 2 * step] = (2 * q3 + 3 * q2 + q1 + q0 + p0 + 4) >> 3;
				}
				else
				{
					pic[o] = (2 * q1 + q0 + p1 + 2) >> 2;
				}
				continue;
			}
			int tc0 = TC0[qp][s - 1];
			int tc = tc0 + (ap ? 1 : 0) + (aq ? 1 : 0);
			int delta = clip3(-tc, tc, (((q0 - p0) << 2) + (p1 - q1) + 4) >> 3);
			pic[o - step] = clip(p0 + delta);
			pic[o] = clip(q0 - delta);
			if (ap)
			{
				pic[o - 2 * step] = p1 + clip3(-tc0, tc0, (p2 + ((p0 + q0 + 1) >> 1) - (p1 << 1)) >> 1);
			}
			if (aq)
			{
				pic[o + step] = q1 + clip3(-tc0, tc0, (q2 + ((p0 + q0 + 1) >> 1) - (q1 << 1)) >> 1);
			}
		}
	}

	private static int clip3(int lo, int hi, int v)
	{
		return v < lo ? lo : v > hi ? hi : v;
	}

	private static int clip(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}
}
