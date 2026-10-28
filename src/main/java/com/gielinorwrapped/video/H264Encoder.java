package com.gielinorwrapped.video;

import static com.gielinorwrapped.video.H264Tables.INTER_CBP_CODE;
import static com.gielinorwrapped.video.H264Tables.ZIGZAG;

/**
 * A small H.264 Constrained Baseline encoder: CAVLC, Intra 16x16 and P 16x16 macroblocks with integer-pel
 * motion, one slice per frame and the in-loop deblocking filter. Frames are ARGB pixels; output NAL units carry no start
 * code, ready for MP4 length prefixes. Not thread-safe.
 */
public final class H264Encoder
{
	/** One encoded frame: a single slice NAL unit. */
	public static final class Frame
	{
		public final byte[] nal;
		public final boolean key;

		Frame(byte[] nal, boolean key)
		{
			this.nal = nal;
			this.key = key;
		}
	}

	private static final int[][] LEVELS = {
		{30, 40500, 1620}, {31, 108000, 3600}, {32, 216000, 5120}, {40, 245760, 8192},
		{42, 522240, 8704}, {50, 589824, 22080}, {51, 983040, 36864},
	};
	/** Largest motion vector component searched, in pels. */
	private static final int MAX_MV = 64;
	/** Extra SAD charged to intra macroblocks in P frames, in lambdas, for their costlier header. */
	private static final int INTRA_BIAS = 10;
	private static final int[] DECIMATE = {3, 2, 2, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

	private final int width;
	private final int height;
	private final int mbW;
	private final int mbH;
	private final int pw;
	private final int ph;
	private final int cw;
	private final int ch;
	private final int level;

	private final int[] srcY;
	private final int[] srcU;
	private final int[] srcV;
	private int[] recY;
	private int[] recU;
	private int[] recV;
	private int[] refY;
	private int[] refU;
	private int[] refV;

	private final boolean[] intra;
	private int[] mvx;
	private int[] mvy;
	private int[] prevMvx;
	private int[] prevMvy;
	private final int[] nzY;
	private final int[] nzU;
	private final int[] nzV;

	private final Cavlc cavlc = new Cavlc();
	private int qp = 28;
	private int lambda;
	private int keyInterval;
	private int sinceKey;
	private int frameNum;
	private int idrId;

	// Per-macroblock scratch.
	private final int[] predY = new int[256];
	private final int[] predU = new int[64];
	private final int[] predV = new int[64];
	private final int[] tmp = new int[16];
	private final int[] scan = new int[16];
	private final int[][] lumaLevels = new int[16][16];
	private final int[] lumaDc = new int[16];
	private final int[][][] chromaLevels = new int[2][4][16];
	private final int[][] chromaDc = new int[2][4];
	private final int[] dcTmp = new int[16];
	private int mvpX;
	private int mvpY;
	private int skipX;
	private int skipY;
	private int bestX;
	private int bestY;

	/** Width and height must be even. */
	public H264Encoder(int width, int height, int fps)
	{
		if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0)
		{
			throw new IllegalArgumentException("Frame size must be even: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		mbW = (width + 15) / 16;
		mbH = (height + 15) / 16;
		pw = mbW * 16;
		ph = mbH * 16;
		cw = pw / 2;
		ch = ph / 2;
		srcY = new int[pw * ph];
		srcU = new int[cw * ch];
		srcV = new int[cw * ch];
		recY = new int[pw * ph];
		recU = new int[cw * ch];
		recV = new int[cw * ch];
		refY = new int[pw * ph];
		refU = new int[cw * ch];
		refV = new int[cw * ch];
		int mbs = mbW * mbH;
		intra = new boolean[mbs];
		mvx = new int[mbs];
		mvy = new int[mbs];
		prevMvx = new int[mbs];
		prevMvy = new int[mbs];
		nzY = new int[mbs * 16];
		nzU = new int[mbs * 4];
		nzV = new int[mbs * 4];
		keyInterval = Math.max(1, fps) * 5;
		level = pickLevel(mbs, (long) mbs * Math.max(1, fps));
		setQp(qp);
	}

	private static int pickLevel(int frameMbs, long mbPerSecond)
	{
		for (int[] l : LEVELS)
		{
			if (mbPerSecond <= l[1] && frameMbs <= l[2])
			{
				return l[0];
			}
		}
		return 51;
	}

	public int getQp()
	{
		return qp;
	}

	/** Quantiser for the following frames, 10 (large, sharp) to 51 (small, blocky). */
	public void setQp(int qp)
	{
		this.qp = Math.max(10, Math.min(51, qp));
		lambda = Math.max(1, (int) Math.round(Math.pow(2, (this.qp - 12) / 6.0)));
	}

	/** Frames between key frames. */
	public void setKeyInterval(int frames)
	{
		keyInterval = Math.max(1, frames);
	}

	public int getWidth()
	{
		return width;
	}

	public int getHeight()
	{
		return height;
	}

	/** The last frame as a decoder reconstructs it, cropped: Y (width x height) then U and V (half size). For tests. */
	int[][] reconstruction()
	{
		int[][] planes = {new int[width * height], new int[width * height / 4], new int[width * height / 4]};
		for (int y = 0; y < height; y++)
		{
			System.arraycopy(refY, y * pw, planes[0], y * width, width);
		}
		for (int y = 0; y < height / 2; y++)
		{
			System.arraycopy(refU, y * cw, planes[1], y * width / 2, width / 2);
			System.arraycopy(refV, y * cw, planes[2], y * width / 2, width / 2);
		}
		return planes;
	}

	/** The sequence parameter set NAL unit. */
	public byte[] sps()
	{
		BitWriter bw = new BitWriter();
		bw.u(8, 66);
		bw.u(8, 0xC0);
		bw.u(8, level);
		bw.ue(0);
		bw.ue(4);
		bw.ue(2);
		bw.ue(1);
		bw.bit(0);
		bw.ue(mbW - 1);
		bw.ue(mbH - 1);
		bw.bit(1);
		bw.bit(1);
		boolean crop = pw != width || ph != height;
		bw.bit(crop ? 1 : 0);
		if (crop)
		{
			bw.ue(0);
			bw.ue((pw - width) / 2);
			bw.ue(0);
			bw.ue((ph - height) / 2);
		}
		bw.bit(1);
		bw.bit(0);
		bw.bit(0);
		bw.bit(1);
		bw.u(3, 5);
		bw.bit(0);
		bw.bit(1);
		bw.u(8, 1);
		bw.u(8, 1);
		bw.u(8, 1);
		bw.bit(0);
		bw.bit(0);
		bw.bit(0);
		bw.bit(0);
		bw.bit(0);
		bw.bit(0);
		bw.trailing();
		return bw.toNal(3, 7);
	}

	/** The picture parameter set NAL unit. */
	public byte[] pps()
	{
		BitWriter bw = new BitWriter();
		bw.ue(0);
		bw.ue(0);
		bw.bit(0);
		bw.bit(0);
		bw.ue(0);
		bw.ue(0);
		bw.ue(0);
		bw.bit(0);
		bw.u(2, 0);
		bw.se(0);
		bw.se(0);
		bw.se(0);
		bw.bit(1);
		bw.bit(0);
		bw.bit(0);
		bw.trailing();
		return bw.toNal(3, 8);
	}

	/** Encodes width x height ARGB pixels (row-major). */
	public Frame encode(int[] argb, boolean forceKey)
	{
		convert(argb);
		boolean key = forceKey || frameNum == 0 && sinceKey == 0 || sinceKey >= keyInterval;
		if (key)
		{
			sinceKey = 0;
			frameNum = 0;
		}

		BitWriter bw = new BitWriter();
		bw.ue(0);
		bw.ue(key ? 7 : 5);
		bw.ue(0);
		bw.u(8, frameNum);
		if (key)
		{
			bw.ue(idrId);
		}
		else
		{
			bw.bit(0);
			bw.bit(0);
		}
		if (key)
		{
			bw.bit(0);
			bw.bit(0);
		}
		else
		{
			bw.bit(0);
		}
		bw.se(qp - 26);
		bw.ue(0);
		bw.se(0);
		bw.se(0);

		int skipRun = 0;
		for (int mby = 0; mby < mbH; mby++)
		{
			for (int mbx = 0; mbx < mbW; mbx++)
			{
				if (key)
				{
					encodeIntra(bw, mbx, mby, false);
				}
				else
				{
					skipRun = encodeP(bw, mbx, mby, skipRun);
				}
			}
		}
		if (skipRun > 0)
		{
			bw.ue(skipRun);
		}
		bw.trailing();
		byte[] nal = key ? bw.toNal(3, 5) : bw.toNal(2, 1);
		Deblock.filter(recY, recU, recV, pw, cw, mbW, mbH, intra, mvx, mvy, nzY, qp, H264Tables.chromaQp(qp));

		int[] t = refY;
		refY = recY;
		recY = t;
		t = refU;
		refU = recU;
		recU = t;
		t = refV;
		refV = recV;
		recV = t;
		t = prevMvx;
		prevMvx = mvx;
		mvx = t;
		t = prevMvy;
		prevMvy = mvy;
		mvy = t;
		if (key)
		{
			idrId ^= 1;
		}
		frameNum = (frameNum + 1) & 255;
		sinceKey++;
		return new Frame(nal, key);
	}

	/** BT.709 limited-range 4:2:0, edges replicated into the macroblock padding. */
	private void convert(int[] argb)
	{
		for (int y = 0; y < ph; y++)
		{
			int row = Math.min(y, height - 1) * width;
			for (int x = 0; x < pw; x++)
			{
				int p = argb[row + Math.min(x, width - 1)];
				int r = (p >> 16) & 0xFF;
				int g = (p >> 8) & 0xFF;
				int b = p & 0xFF;
				srcY[y * pw + x] = ((11966 * r + 40254 * g + 4064 * b + 32768) >> 16) + 16;
			}
		}
		for (int y = 0; y < ch; y++)
		{
			int r0 = Math.min(2 * y, height - 1) * width;
			int r1 = Math.min(2 * y + 1, height - 1) * width;
			for (int x = 0; x < cw; x++)
			{
				int c0 = Math.min(2 * x, width - 1);
				int c1 = Math.min(2 * x + 1, width - 1);
				int p0 = argb[r0 + c0];
				int p1 = argb[r0 + c1];
				int p2 = argb[r1 + c0];
				int p3 = argb[r1 + c1];
				int r = ((p0 >> 16) & 0xFF) + ((p1 >> 16) & 0xFF) + ((p2 >> 16) & 0xFF) + ((p3 >> 16) & 0xFF);
				int g = ((p0 >> 8) & 0xFF) + ((p1 >> 8) & 0xFF) + ((p2 >> 8) & 0xFF) + ((p3 >> 8) & 0xFF);
				int b = (p0 & 0xFF) + (p1 & 0xFF) + (p2 & 0xFF) + (p3 & 0xFF);
				srcU[y * cw + x] = clip(((-6596 * r - 22189 * g + 28785 * b + (1 << 17)) >> 18) + 128);
				srcV[y * cw + x] = clip(((28784 * r - 26145 * g - 2639 * b + (1 << 17)) >> 18) + 128);
			}
		}
	}

	// ---- Macroblock decisions ----

	private int encodeP(BitWriter bw, int mbx, int mby, int skipRun)
	{
		int px = mbx * 16;
		int py = mby * 16;
		predictMv(mbx, mby);

		interPrediction(mbx, mby, skipX, skipY);
		if (quantLumaInter(mbx, mby) == 0 && quantChroma(mbx, mby, false) == 0)
		{
			commitSkip(mbx, mby);
			return skipRun + 1;
		}

		int interCost = search(mbx, mby);

		int intraCost = Integer.MAX_VALUE;
		boolean left = mbx > 0;
		boolean top = mby > 0;
		for (int mode = 0; mode < 4; mode++)
		{
			if (IntraPred.lumaAvailable(mode, left, top))
			{
				IntraPred.predict(recY, pw, px, py, 16, false, mode, left, top, predY);
				intraCost = Math.min(intraCost, Motion.sadPred(srcY, pw, px, py, predY, 16));
			}
		}
		intraCost += INTRA_BIAS * lambda;

		if (intraCost < interCost)
		{
			bw.ue(skipRun);
			encodeIntra(bw, mbx, mby, true);
			return 0;
		}

		interPrediction(mbx, mby, bestX, bestY);
		int cbpL = quantLumaInter(mbx, mby);
		int cbpC = quantChroma(mbx, mby, false);
		if (cbpL == 0 && cbpC == 0 && bestX == skipX && bestY == skipY)
		{
			commitSkip(mbx, mby);
			return skipRun + 1;
		}

		bw.ue(skipRun);
		int idx = mby * mbW + mbx;
		intra[idx] = false;
		mvx[idx] = bestX;
		mvy[idx] = bestY;
		setNzInter(mbx, mby, cbpL, cbpC);
		int cbp = cbpL | (cbpC << 4);
		bw.ue(0);
		bw.se(bestX - mvpX);
		bw.se(bestY - mvpY);
		bw.ue(INTER_CBP_CODE[cbp]);
		if (cbp != 0)
		{
			bw.se(0);
		}
		for (int i8 = 0; i8 < 4; i8++)
		{
			if ((cbpL & (1 << i8)) != 0)
			{
				for (int blk = i8 * 4; blk < i8 * 4 + 4; blk++)
				{
					for (int k = 0; k < 16; k++)
					{
						scan[k] = lumaLevels[blk][ZIGZAG[k]];
					}
					cavlc.write(bw, scan, 16, lumaNc(mbx, mby, blk));
				}
			}
		}
		writeChroma(bw, mbx, mby, cbpC);
		reconLumaInter(mbx, mby, cbpL);
		reconChroma(mbx, mby, cbpC);
		return 0;
	}

	private void interPrediction(int mbx, int mby, int x, int y)
	{
		Motion.luma(refY, pw, ph, mbx * 16, mby * 16, x, y, predY);
		Motion.chroma(refU, cw, ch, mbx * 8, mby * 8, x, y, predU);
		Motion.chroma(refV, cw, ch, mbx * 8, mby * 8, x, y, predV);
	}

	private void commitSkip(int mbx, int mby)
	{
		int idx = mby * mbW + mbx;
		intra[idx] = false;
		mvx[idx] = skipX;
		mvy[idx] = skipY;
		setNzInter(mbx, mby, 0, 0);
		copyPred(predY, recY, pw, mbx * 16, mby * 16, 16);
		copyPred(predU, recU, cw, mbx * 8, mby * 8, 8);
		copyPred(predV, recV, cw, mbx * 8, mby * 8, 8);
	}

	/** Motion vector prediction for a 16x16 partition (8.4.1.3) and the P_Skip vector (8.4.1.1). */
	private void predictMv(int mbx, int mby)
	{
		int idx = mby * mbW + mbx;
		boolean availA = mbx > 0;
		boolean availB = mby > 0;
		boolean availC = mby > 0 && mbx < mbW - 1;
		boolean availD = mbx > 0 && mby > 0;

		int refA = availA && !intra[idx - 1] ? 0 : -1;
		int ax = refA == 0 ? mvx[idx - 1] : 0;
		int ay = refA == 0 ? mvy[idx - 1] : 0;
		int refB = availB && !intra[idx - mbW] ? 0 : -1;
		int bx = refB == 0 ? mvx[idx - mbW] : 0;
		int by = refB == 0 ? mvy[idx - mbW] : 0;
		int cIdx = availC ? idx - mbW + 1 : availD ? idx - mbW - 1 : -1;
		int refC = cIdx >= 0 && !intra[cIdx] ? 0 : -1;
		int cx = refC == 0 ? mvx[cIdx] : 0;
		int cy = refC == 0 ? mvy[cIdx] : 0;

		if (!availA || !availB || refA == 0 && ax == 0 && ay == 0 || refB == 0 && bx == 0 && by == 0)
		{
			skipX = 0;
			skipY = 0;
		}
		else
		{
			skipX = Integer.MIN_VALUE;
		}

		if (!availB && cIdx < 0 && availA)
		{
			refB = refA;
			bx = ax;
			by = ay;
			refC = refA;
			cx = ax;
			cy = ay;
		}
		int matches = (refA == 0 ? 1 : 0) + (refB == 0 ? 1 : 0) + (refC == 0 ? 1 : 0);
		if (matches == 1)
		{
			mvpX = refA == 0 ? ax : refB == 0 ? bx : cx;
			mvpY = refA == 0 ? ay : refB == 0 ? by : cy;
		}
		else
		{
			mvpX = median(ax, bx, cx);
			mvpY = median(ay, by, cy);
		}
		if (skipX == Integer.MIN_VALUE)
		{
			skipX = mvpX;
			skipY = mvpY;
		}
	}

	private static int median(int a, int b, int c)
	{
		return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
	}

	/** Integer-pel motion search; leaves the vector (quarter-pel units) in bestX/bestY and returns its cost. */
	private int search(int mbx, int mby)
	{
		int px = mbx * 16;
		int py = mby * 16;
		int idx = mby * mbW + mbx;
		int minX = Math.max(-MAX_MV, -px);
		int maxX = Math.min(MAX_MV, pw - 16 - px);
		int minY = Math.max(-MAX_MV, -py);
		int maxY = Math.min(MAX_MV, ph - 16 - py);

		int[] candidates = {
			0, 0, mvpX >> 2, mvpY >> 2, skipX >> 2, skipY >> 2, prevMvx[idx] >> 2, prevMvy[idx] >> 2,
			mbx > 0 ? mvx[idx - 1] >> 2 : 0, mbx > 0 ? mvy[idx - 1] >> 2 : 0,
			mby > 0 ? mvx[idx - mbW] >> 2 : 0, mby > 0 ? mvy[idx - mbW] >> 2 : 0,
		};
		int best = Integer.MAX_VALUE;
		int bx = 0;
		int by = 0;
		for (int i = 0; i < candidates.length; i += 2)
		{
			int x = Math.max(minX, Math.min(maxX, candidates[i]));
			int y = Math.max(minY, Math.min(maxY, candidates[i + 1]));
			int cost = mvCost(px, py, x, y, best);
			if (cost < best)
			{
				best = cost;
				bx = x;
				by = y;
			}
		}

		for (int step = 4; step >= 1; step >>= 1)
		{
			for (int iter = 0; iter < 16; iter++)
			{
				int nx = bx;
				int ny = by;
				for (int d = 0; d < 4; d++)
				{
					int x = bx + (d == 0 ? step : d == 1 ? -step : 0);
					int y = by + (d == 2 ? step : d == 3 ? -step : 0);
					if (x < minX || x > maxX || y < minY || y > maxY)
					{
						continue;
					}
					int cost = mvCost(px, py, x, y, best);
					if (cost < best)
					{
						best = cost;
						nx = x;
						ny = y;
					}
				}
				if (nx == bx && ny == by)
				{
					break;
				}
				bx = nx;
				by = ny;
			}
		}
		bestX = bx * 4;
		bestY = by * 4;
		return best;
	}

	private int mvCost(int px, int py, int x, int y, int limit)
	{
		int bits = BitWriter.seBits(x * 4 - mvpX) + BitWriter.seBits(y * 4 - mvpY);
		return Motion.sad16(srcY, refY, pw, px, py, px + x, py + y, limit) + lambda * bits;
	}

	private void encodeIntra(BitWriter bw, int mbx, int mby, boolean pSlice)
	{
		int px = mbx * 16;
		int py = mby * 16;
		boolean left = mbx > 0;
		boolean top = mby > 0;

		int lumaMode = IntraPred.LUMA_DC;
		int best = Integer.MAX_VALUE;
		for (int mode = 0; mode < 4; mode++)
		{
			if (IntraPred.lumaAvailable(mode, left, top))
			{
				IntraPred.predict(recY, pw, px, py, 16, false, mode, left, top, predY);
				int sad = Motion.sadPred(srcY, pw, px, py, predY, 16);
				if (sad < best)
				{
					best = sad;
					lumaMode = mode;
				}
			}
		}
		IntraPred.predict(recY, pw, px, py, 16, false, lumaMode, left, top, predY);

		int chromaMode = IntraPred.CHROMA_DC;
		best = Integer.MAX_VALUE;
		for (int mode = 0; mode < 4; mode++)
		{
			if (IntraPred.chromaAvailable(mode, left, top))
			{
				IntraPred.predict(recU, cw, mbx * 8, mby * 8, 8, true, mode, left, top, predU);
				IntraPred.predict(recV, cw, mbx * 8, mby * 8, 8, true, mode, left, top, predV);
				int sad = Motion.sadPred(srcU, cw, mbx * 8, mby * 8, predU, 8) + Motion.sadPred(srcV, cw, mbx * 8, mby * 8, predV, 8);
				if (sad < best)
				{
					best = sad;
					chromaMode = mode;
				}
			}
		}
		IntraPred.predict(recU, cw, mbx * 8, mby * 8, 8, true, chromaMode, left, top, predU);
		IntraPred.predict(recV, cw, mbx * 8, mby * 8, 8, true, chromaMode, left, top, predV);

		int cbpL = quantLumaIntra(mbx, mby);
		int cbpC = quantChroma(mbx, mby, true);

		int idx = mby * mbW + mbx;
		intra[idx] = true;
		mvx[idx] = 0;
		mvy[idx] = 0;
		setNzIntra(mbx, mby, cbpL, cbpC);

		bw.ue((pSlice ? 5 : 0) + 1 + lumaMode + 4 * cbpC + (cbpL != 0 ? 12 : 0));
		bw.ue(chromaMode);
		bw.se(0);
		for (int k = 0; k < 16; k++)
		{
			scan[k] = lumaDc[ZIGZAG[k]];
		}
		cavlc.write(bw, scan, 16, lumaNc(mbx, mby, 0));
		if (cbpL != 0)
		{
			for (int blk = 0; blk < 16; blk++)
			{
				for (int k = 1; k < 16; k++)
				{
					scan[k - 1] = lumaLevels[blk][ZIGZAG[k]];
				}
				cavlc.write(bw, scan, 15, lumaNc(mbx, mby, blk));
			}
		}
		writeChroma(bw, mbx, mby, cbpC);
		reconLumaIntra(mbx, mby);
		reconChroma(mbx, mby, cbpC);
	}

	private void writeChroma(BitWriter bw, int mbx, int mby, int cbpC)
	{
		if (cbpC == 0)
		{
			return;
		}
		for (int c = 0; c < 2; c++)
		{
			cavlc.write(bw, chromaDc[c], 4, -1);
		}
		if (cbpC == 2)
		{
			for (int c = 0; c < 2; c++)
			{
				for (int blk = 0; blk < 4; blk++)
				{
					for (int k = 1; k < 16; k++)
					{
						scan[k - 1] = chromaLevels[c][blk][ZIGZAG[k]];
					}
					cavlc.write(bw, scan, 15, chromaNc(c == 0 ? nzU : nzV, mbx, mby, blk));
				}
			}
		}
	}

	// ---- Residual analysis and reconstruction (continued in the second half of this file) ----

	/** Column of 4x4 luma block blkIdx inside its macroblock, in blocks. */
	private static int blkX(int blk)
	{
		return ((blk >> 2) & 1) * 2 + (blk & 1);
	}

	private static int blkY(int blk)
	{
		return ((blk >> 3) & 1) * 2 + ((blk >> 1) & 1);
	}

	private void residual(int[] src, int stride, int x, int y, int[] pred, int n, int bx, int by)
	{
		for (int j = 0; j < 4; j++)
		{
			int s = (y + by * 4 + j) * stride + x + bx * 4;
			int p = (by * 4 + j) * n + bx * 4;
			for (int i = 0; i < 4; i++)
			{
				tmp[j * 4 + i] = src[s + i] - pred[p + i];
			}
		}
	}

	/** Intra 16x16 luma: fills lumaDc and the AC levels; returns cbp luma (0 or 15). */
	private int quantLumaIntra(int mbx, int mby)
	{
		int ac = 0;
		for (int blk = 0; blk < 16; blk++)
		{
			int bx = blkX(blk);
			int by = blkY(blk);
			residual(srcY, pw, mbx * 16, mby * 16, predY, 16, bx, by);
			Transform.forward(tmp);
			dcTmp[by * 4 + bx] = tmp[0];
			ac += Transform.quant(tmp, lumaLevels[blk], qp, true, 1);
		}
		Transform.hadamard4(dcTmp, true);
		Transform.quantDc(dcTmp, lumaDc, 16, qp, true);
		return ac > 0 ? 15 : 0;
	}

	/** Inter luma with decimation: fills the levels; returns cbp luma (one bit per 8x8). */
	private int quantLumaInter(int mbx, int mby)
	{
		int total = 0;
		int[] scores = dcTmp;
		for (int blk = 0; blk < 16; blk++)
		{
			residual(srcY, pw, mbx * 16, mby * 16, predY, 16, blkX(blk), blkY(blk));
			Transform.forward(tmp);
			Transform.quant(tmp, lumaLevels[blk], qp, false, 0);
			scores[blk] = decimateScore(lumaLevels[blk], 0);
			total += scores[blk];
		}
		if (total < 6)
		{
			for (int blk = 0; blk < 16; blk++)
			{
				java.util.Arrays.fill(lumaLevels[blk], 0);
			}
			return 0;
		}
		int cbp = 0;
		for (int i8 = 0; i8 < 4; i8++)
		{
			int s = scores[i8 * 4] + scores[i8 * 4 + 1] + scores[i8 * 4 + 2] + scores[i8 * 4 + 3];
			boolean any = false;
			for (int blk = i8 * 4; blk < i8 * 4 + 4; blk++)
			{
				if (s < 4)
				{
					java.util.Arrays.fill(lumaLevels[blk], 0);
				}
				else
				{
					any |= nonZero(lumaLevels[blk], 0) > 0;
				}
			}
			if (any)
			{
				cbp |= 1 << i8;
			}
		}
		return cbp;
	}

	/** How much a block's levels are worth keeping: 9 if any |level| > 1, otherwise a run-weighted count of the ones. */
	private static int decimateScore(int[] levels, int start)
	{
		int score = 0;
		int run = 0;
		for (int k = 15; k >= start; k--)
		{
			int l = levels[ZIGZAG[k]];
			if (l == 0)
			{
				run++;
				continue;
			}
			if (l > 1 || l < -1)
			{
				return 9;
			}
			score += run < DECIMATE.length ? DECIMATE[run] : 0;
			run = 0;
		}
		return score;
	}

	private static int nonZero(int[] levels, int start)
	{
		int n = 0;
		for (int i = start; i < 16; i++)
		{
			if (levels[i] != 0)
			{
				n++;
			}
		}
		return n;
	}

	/** Both chroma planes: fills DC and AC levels; returns cbp chroma (0 none, 1 DC only, 2 DC and AC). */
	private int quantChroma(int mbx, int mby, boolean isIntra)
	{
		int qpc = H264Tables.chromaQp(qp);
		boolean anyDc = false;
		boolean anyAc = false;
		for (int c = 0; c < 2; c++)
		{
			int[] src = c == 0 ? srcU : srcV;
			int[] pred = c == 0 ? predU : predV;
			int score = 0;
			int ac = 0;
			for (int blk = 0; blk < 4; blk++)
			{
				residual(src, cw, mbx * 8, mby * 8, pred, 8, blk & 1, blk >> 1);
				Transform.forward(tmp);
				dcTmp[blk] = tmp[0];
				ac += Transform.quant(tmp, chromaLevels[c][blk], qpc, isIntra, 1);
				score += decimateScore(chromaLevels[c][blk], 1);
			}
			if (!isIntra && score < 7 && ac > 0)
			{
				for (int blk = 0; blk < 4; blk++)
				{
					java.util.Arrays.fill(chromaLevels[c][blk], 0);
				}
				ac = 0;
			}
			Transform.hadamard2(dcTmp);
			anyDc |= Transform.quantDc(dcTmp, chromaDc[c], 4, qpc, isIntra) > 0;
			anyAc |= ac > 0;
		}
		return anyAc ? 2 : anyDc ? 1 : 0;
	}

	private void reconLumaIntra(int mbx, int mby)
	{
		System.arraycopy(lumaDc, 0, dcTmp, 0, 16);
		Transform.dequantLumaDc(dcTmp, qp);
		for (int blk = 0; blk < 16; blk++)
		{
			int bx = blkX(blk);
			int by = blkY(blk);
			tmp[0] = dcTmp[by * 4 + bx];
			Transform.dequant(lumaLevels[blk], tmp, qp, 1);
			Transform.inverse(tmp);
			addBlock(recY, pw, mbx * 16 + bx * 4, mby * 16 + by * 4, predY, 16, bx * 4, by * 4);
		}
	}

	private void reconLumaInter(int mbx, int mby, int cbpL)
	{
		for (int blk = 0; blk < 16; blk++)
		{
			int bx = blkX(blk);
			int by = blkY(blk);
			if ((cbpL & (1 << (blk >> 2))) != 0)
			{
				Transform.dequant(lumaLevels[blk], tmp, qp, 0);
				Transform.inverse(tmp);
			}
			else
			{
				java.util.Arrays.fill(tmp, 0);
			}
			addBlock(recY, pw, mbx * 16 + bx * 4, mby * 16 + by * 4, predY, 16, bx * 4, by * 4);
		}
	}

	private void reconChroma(int mbx, int mby, int cbpC)
	{
		int qpc = H264Tables.chromaQp(qp);
		for (int c = 0; c < 2; c++)
		{
			int[] rec = c == 0 ? recU : recV;
			int[] pred = c == 0 ? predU : predV;
			if (cbpC == 0)
			{
				copyPred(pred, rec, cw, mbx * 8, mby * 8, 8);
				continue;
			}
			System.arraycopy(chromaDc[c], 0, dcTmp, 0, 4);
			Transform.dequantChromaDc(dcTmp, qpc);
			for (int blk = 0; blk < 4; blk++)
			{
				int bx = blk & 1;
				int by = blk >> 1;
				if (cbpC == 2)
				{
					Transform.dequant(chromaLevels[c][blk], tmp, qpc, 1);
				}
				else
				{
					java.util.Arrays.fill(tmp, 0);
				}
				tmp[0] = dcTmp[blk];
				Transform.inverse(tmp);
				addBlock(rec, cw, mbx * 8 + bx * 4, mby * 8 + by * 4, pred, 8, bx * 4, by * 4);
			}
		}
	}

	/** Writes prediction plus the residual in tmp into the 4x4 block at (x, y) of a reconstructed plane. */
	private void addBlock(int[] rec, int stride, int x, int y, int[] pred, int n, int px, int py)
	{
		for (int j = 0; j < 4; j++)
		{
			int r = (y + j) * stride + x;
			int p = (py + j) * n + px;
			for (int i = 0; i < 4; i++)
			{
				rec[r + i] = clip(pred[p + i] + tmp[j * 4 + i]);
			}
		}
	}

	private static void copyPred(int[] pred, int[] rec, int stride, int x, int y, int n)
	{
		for (int j = 0; j < n; j++)
		{
			System.arraycopy(pred, j * n, rec, (y + j) * stride + x, n);
		}
	}

	private static int clip(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	// ---- CAVLC contexts: non-zero coefficient counts per 4x4 block ----

	private void setNzIntra(int mbx, int mby, int cbpL, int cbpC)
	{
		int stride = mbW * 4;
		for (int blk = 0; blk < 16; blk++)
		{
			int i = (mby * 4 + blkY(blk)) * stride + mbx * 4 + blkX(blk);
			nzY[i] = cbpL != 0 ? nonZero(lumaLevels[blk], 1) : 0;
		}
		setNzChroma(mbx, mby, cbpC);
	}

	private void setNzInter(int mbx, int mby, int cbpL, int cbpC)
	{
		int stride = mbW * 4;
		for (int blk = 0; blk < 16; blk++)
		{
			int i = (mby * 4 + blkY(blk)) * stride + mbx * 4 + blkX(blk);
			nzY[i] = (cbpL & (1 << (blk >> 2))) != 0 ? nonZero(lumaLevels[blk], 0) : 0;
		}
		setNzChroma(mbx, mby, cbpC);
	}

	private void setNzChroma(int mbx, int mby, int cbpC)
	{
		int stride = mbW * 2;
		for (int c = 0; c < 2; c++)
		{
			int[] nz = c == 0 ? nzU : nzV;
			for (int blk = 0; blk < 4; blk++)
			{
				nz[(mby * 2 + (blk >> 1)) * stride + mbx * 2 + (blk & 1)] = cbpC == 2 ? nonZero(chromaLevels[c][blk], 1) : 0;
			}
		}
	}

	private int lumaNc(int mbx, int mby, int blk)
	{
		return nc(nzY, mbW * 4, mbx * 4 + blkX(blk), mby * 4 + blkY(blk));
	}

	private int chromaNc(int[] nz, int mbx, int mby, int blk)
	{
		return nc(nz, mbW * 2, mbx * 2 + (blk & 1), mby * 2 + (blk >> 1));
	}

	private static int nc(int[] nz, int stride, int gx, int gy)
	{
		boolean a = gx > 0;
		boolean b = gy > 0;
		int na = a ? nz[gy * stride + gx - 1] : 0;
		int nb = b ? nz[(gy - 1) * stride + gx] : 0;
		return a && b ? (na + nb + 1) >> 1 : a ? na : b ? nb : 0;
	}
}
