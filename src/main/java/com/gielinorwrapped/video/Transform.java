package com.gielinorwrapped.video;

import static com.gielinorwrapped.video.H264Tables.MF;
import static com.gielinorwrapped.video.H264Tables.POS_CLASS;
import static com.gielinorwrapped.video.H264Tables.V;

/** The H.264 integer 4x4 transform, the Hadamard DC transforms and (de)quantisation. All blocks are raster order. */
final class Transform
{
	static final int MAX_LEVEL = 2047;

	private Transform()
	{
	}

	/** Forward core transform of a 4x4 residual block, in place. */
	static void forward(int[] b)
	{
		for (int i = 0; i < 16; i += 4)
		{
			int s03 = b[i] + b[i + 3];
			int d03 = b[i] - b[i + 3];
			int s12 = b[i + 1] + b[i + 2];
			int d12 = b[i + 1] - b[i + 2];
			b[i] = s03 + s12;
			b[i + 1] = 2 * d03 + d12;
			b[i + 2] = s03 - s12;
			b[i + 3] = d03 - 2 * d12;
		}
		for (int i = 0; i < 4; i++)
		{
			int s03 = b[i] + b[i + 12];
			int d03 = b[i] - b[i + 12];
			int s12 = b[i + 4] + b[i + 8];
			int d12 = b[i + 4] - b[i + 8];
			b[i] = s03 + s12;
			b[i + 4] = 2 * d03 + d12;
			b[i + 8] = s03 - s12;
			b[i + 12] = d03 - 2 * d12;
		}
	}

	/** Inverse core transform of dequantised coefficients, in place, including the final (x + 32) >> 6. */
	static void inverse(int[] d)
	{
		for (int i = 0; i < 16; i += 4)
		{
			int e0 = d[i] + d[i + 2];
			int e1 = d[i] - d[i + 2];
			int e2 = (d[i + 1] >> 1) - d[i + 3];
			int e3 = d[i + 1] + (d[i + 3] >> 1);
			d[i] = e0 + e3;
			d[i + 1] = e1 + e2;
			d[i + 2] = e1 - e2;
			d[i + 3] = e0 - e3;
		}
		for (int i = 0; i < 4; i++)
		{
			int e0 = d[i] + d[i + 8];
			int e1 = d[i] - d[i + 8];
			int e2 = (d[i + 4] >> 1) - d[i + 12];
			int e3 = d[i + 4] + (d[i + 12] >> 1);
			d[i] = (e0 + e3 + 32) >> 6;
			d[i + 4] = (e1 + e2 + 32) >> 6;
			d[i + 8] = (e1 - e2 + 32) >> 6;
			d[i + 12] = (e0 - e3 + 32) >> 6;
		}
	}

	/** Quantises coefficients {@code start..15} of w into levels (levels[0] is zeroed when start is 1); returns the non-zero count. */
	static int quant(int[] w, int[] levels, int qp, boolean intra, int start)
	{
		int qbits = 15 + qp / 6;
		int f = (1 << qbits) / (intra ? 3 : 6);
		int[] mf = MF[qp % 6];
		int nz = 0;
		if (start > 0)
		{
			levels[0] = 0;
		}
		for (int i = start; i < 16; i++)
		{
			int v = w[i];
			int l = Math.min(MAX_LEVEL, (Math.abs(v) * mf[POS_CLASS[i]] + f) >> qbits);
			levels[i] = v < 0 ? -l : l;
			if (l != 0)
			{
				nz++;
			}
		}
		return nz;
	}

	/** Dequantises levels {@code start..15} into coefficients. */
	static void dequant(int[] levels, int[] out, int qp, int start)
	{
		int[] v = V[qp % 6];
		int shift = qp / 6;
		for (int i = start; i < 16; i++)
		{
			out[i] = (levels[i] * v[POS_CLASS[i]]) << shift;
		}
	}

	/** Quantises n DC coefficients (luma Hadamard output or chroma 2x2); returns the non-zero count. */
	static int quantDc(int[] y, int[] levels, int n, int qp, boolean intra)
	{
		int qbits = 15 + qp / 6;
		int f = (1 << qbits) / (intra ? 3 : 6);
		int mf = MF[qp % 6][0];
		int nz = 0;
		for (int i = 0; i < n; i++)
		{
			int v = y[i];
			int l = Math.min(MAX_LEVEL, (int) (((long) Math.abs(v) * mf + 2 * f) >> (qbits + 1)));
			levels[i] = v < 0 ? -l : l;
			if (l != 0)
			{
				nz++;
			}
		}
		return nz;
	}

	/** 4x4 Hadamard transform, in place; halved (forward direction) when {@code half} is set. */
	static void hadamard4(int[] b, boolean half)
	{
		for (int i = 0; i < 16; i += 4)
		{
			int s01 = b[i] + b[i + 1];
			int d01 = b[i] - b[i + 1];
			int s23 = b[i + 2] + b[i + 3];
			int d23 = b[i + 2] - b[i + 3];
			b[i] = s01 + s23;
			b[i + 1] = s01 - s23;
			b[i + 2] = d01 - d23;
			b[i + 3] = d01 + d23;
		}
		for (int i = 0; i < 4; i++)
		{
			int s01 = b[i] + b[i + 4];
			int d01 = b[i] - b[i + 4];
			int s23 = b[i + 8] + b[i + 12];
			int d23 = b[i + 8] - b[i + 12];
			b[i] = s01 + s23;
			b[i + 4] = s01 - s23;
			b[i + 8] = d01 - d23;
			b[i + 12] = d01 + d23;
		}
		if (half)
		{
			for (int i = 0; i < 16; i++)
			{
				b[i] = (b[i] + (b[i] > 0 ? 1 : 0)) >> 1;
			}
		}
	}

	/** Decodes luma DC levels (raster, in place) into the DC coefficient of each 4x4 block. */
	static void dequantLumaDc(int[] c, int qp)
	{
		hadamard4(c, false);
		int ls = 16 * V[qp % 6][0];
		int q = qp / 6;
		for (int i = 0; i < 16; i++)
		{
			c[i] = q >= 6 ? (c[i] * ls) << (q - 6) : (c[i] * ls + (1 << (5 - q))) >> (6 - q);
		}
	}

	/** 2x2 Hadamard transform of chroma DC values (raster), in place. */
	static void hadamard2(int[] b)
	{
		int a = b[0];
		int c = b[1];
		int d = b[2];
		int e = b[3];
		b[0] = a + c + d + e;
		b[1] = a - c + d - e;
		b[2] = a + c - d - e;
		b[3] = a - c - d + e;
	}

	/** Decodes chroma DC levels (raster, in place) into the DC coefficient of each 4x4 block. */
	static void dequantChromaDc(int[] c, int qpc)
	{
		hadamard2(c);
		int ls = 16 * V[qpc % 6][0];
		for (int i = 0; i < 4; i++)
		{
			c[i] = ((c[i] * ls) << (qpc / 6)) >> 5;
		}
	}
}
