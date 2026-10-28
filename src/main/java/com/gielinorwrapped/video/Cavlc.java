package com.gielinorwrapped.video;

import static com.gielinorwrapped.video.H264Tables.CHROMA_DC_TOKEN_BITS;
import static com.gielinorwrapped.video.H264Tables.CHROMA_DC_TOKEN_LEN;
import static com.gielinorwrapped.video.H264Tables.CHROMA_DC_TOTAL_ZEROS_BITS;
import static com.gielinorwrapped.video.H264Tables.CHROMA_DC_TOTAL_ZEROS_LEN;
import static com.gielinorwrapped.video.H264Tables.COEFF_TOKEN_BITS;
import static com.gielinorwrapped.video.H264Tables.COEFF_TOKEN_LEN;
import static com.gielinorwrapped.video.H264Tables.RUN_BITS;
import static com.gielinorwrapped.video.H264Tables.RUN_LEN;
import static com.gielinorwrapped.video.H264Tables.TOTAL_ZEROS_BITS;
import static com.gielinorwrapped.video.H264Tables.TOTAL_ZEROS_LEN;

/** CAVLC residual block coding (H.264 clause 9.2). Not thread-safe; each encoder owns one. */
final class Cavlc
{
	private final int[] level = new int[16];
	private final int[] run = new int[16];

	/**
	 * Writes one residual block whose coefficients are given in scan order, {@code coef[0..n-1]}.
	 * {@code nC} is -1 for chroma DC. Returns the block's total coefficient count.
	 */
	int write(BitWriter bw, int[] coef, int n, int nC)
	{
		int last = -1;
		for (int i = n - 1; i >= 0; i--)
		{
			if (coef[i] != 0)
			{
				last = i;
				break;
			}
		}

		int total = 0;
		int prev = -1;
		for (int i = last; i >= 0; i--)
		{
			if (coef[i] != 0)
			{
				if (total > 0)
				{
					run[total - 1] = prev - i - 1;
				}
				level[total++] = coef[i];
				prev = i;
			}
		}
		int totalZeros = last + 1 - total;

		int t1 = 0;
		while (t1 < total && t1 < 3 && Math.abs(level[t1]) == 1)
		{
			t1++;
		}

		writeCoeffToken(bw, total, t1, nC);
		if (total == 0)
		{
			return 0;
		}

		for (int k = 0; k < t1; k++)
		{
			bw.bit(level[k] < 0 ? 1 : 0);
		}

		int suffixLength = total > 10 && t1 < 3 ? 1 : 0;
		for (int k = t1; k < total; k++)
		{
			int l = level[k];
			int code = l > 0 ? 2 * l - 2 : -2 * l - 1;
			if (k == t1 && t1 < 3)
			{
				code -= 2;
			}
			writeLevel(bw, code, suffixLength);
			if (suffixLength == 0)
			{
				suffixLength = 1;
			}
			if (Math.abs(l) > (3 << (suffixLength - 1)) && suffixLength < 6)
			{
				suffixLength++;
			}
		}

		if (total < n)
		{
			if (nC == -1)
			{
				bw.u(CHROMA_DC_TOTAL_ZEROS_LEN[total - 1][totalZeros], CHROMA_DC_TOTAL_ZEROS_BITS[total - 1][totalZeros]);
			}
			else
			{
				bw.u(TOTAL_ZEROS_LEN[total - 1][totalZeros], TOTAL_ZEROS_BITS[total - 1][totalZeros]);
			}
		}

		int zerosLeft = totalZeros;
		for (int k = 0; k < total - 1 && zerosLeft > 0; k++)
		{
			int r = run[k];
			int t = Math.min(zerosLeft, 7) - 1;
			bw.u(RUN_LEN[t][r], RUN_BITS[t][r]);
			zerosLeft -= r;
		}
		return total;
	}

	private static void writeCoeffToken(BitWriter bw, int total, int t1, int nC)
	{
		int i = total * 4 + t1;
		if (nC == -1)
		{
			bw.u(CHROMA_DC_TOKEN_LEN[i], CHROMA_DC_TOKEN_BITS[i]);
		}
		else if (nC >= 8)
		{
			bw.u(6, total == 0 ? 3 : ((total - 1) << 2) | t1);
		}
		else
		{
			int table = nC < 2 ? 0 : nC < 4 ? 1 : 2;
			bw.u(COEFF_TOKEN_LEN[table][i], COEFF_TOKEN_BITS[table][i]);
		}
	}

	private static void writeLevel(BitWriter bw, int code, int suffixLength)
	{
		if (suffixLength == 0)
		{
			if (code < 14)
			{
				prefix(bw, code);
			}
			else if (code < 30)
			{
				prefix(bw, 14);
				bw.u(4, code - 14);
			}
			else
			{
				prefix(bw, 15);
				bw.u(12, code - 30);
			}
		}
		else if (code < (15 << suffixLength))
		{
			prefix(bw, code >> suffixLength);
			bw.u(suffixLength, code & ((1 << suffixLength) - 1));
		}
		else
		{
			prefix(bw, 15);
			bw.u(12, code - (15 << suffixLength));
		}
	}

	private static void prefix(BitWriter bw, int zeros)
	{
		bw.u(zeros, 0);
		bw.bit(1);
	}
}
