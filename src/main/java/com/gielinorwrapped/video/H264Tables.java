package com.gielinorwrapped.video;

/** Constant tables from ITU-T H.264 (CAVLC codes, quantisation, scans). */
final class H264Tables
{
	private H264Tables()
	{
	}

	/** Zig-zag scan: scan position to raster index (row * 4 + col) in a 4x4 block. */
	static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};

	/** Quantiser multipliers per QP % 6 for position classes (even,even), (odd,odd), other. */
	static final int[][] MF = {
		{13107, 5243, 8066}, {11916, 4660, 7490}, {10082, 4194, 6554},
		{9362, 3647, 5825}, {8192, 3355, 5243}, {7282, 2893, 4559},
	};

	/** Dequantiser normAdjust values with the same layout as {@link #MF}. */
	static final int[][] V = {
		{10, 16, 13}, {11, 18, 14}, {13, 20, 16}, {14, 23, 18}, {16, 25, 20}, {18, 29, 23},
	};

	/** Position class of each raster index in a 4x4 block, indexing {@link #MF} and {@link #V}. */
	static final int[] POS_CLASS = new int[16];

	static
	{
		for (int i = 0; i < 16; i++)
		{
			int r = i >> 2;
			int c = i & 3;
			POS_CLASS[i] = (r % 2 == 0 && c % 2 == 0) ? 0 : (r % 2 == 1 && c % 2 == 1) ? 1 : 2;
		}
	}

	static int chromaQp(int qpY)
	{
		int[] high = {29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38, 38, 38, 39, 39, 39, 39};
		int q = Math.max(0, Math.min(51, qpY));
		return q < 30 ? q : high[q - 30];
	}

	/** codeNum for each inter coded_block_pattern (inverse of table 9-4, chroma format 4:2:0). */
	static final int[] INTER_CBP_CODE = new int[48];

	static
	{
		int[] golombToInter = {
			0, 16, 1, 2, 4, 8, 32, 3, 5, 10, 12, 15, 47, 7, 11, 13,
			14, 6, 9, 31, 35, 37, 42, 44, 33, 34, 36, 40, 39, 43, 45, 46,
			17, 18, 20, 24, 19, 21, 26, 28, 23, 27, 29, 30, 22, 25, 38, 41,
		};
		for (int code = 0; code < 48; code++)
		{
			INTER_CBP_CODE[golombToInter[code]] = code;
		}
	}

	// coeff_token, indexed [table][totalCoeff * 4 + trailingOnes]; table 3 is the 6-bit fixed-length code.
	static final int[][] COEFF_TOKEN_LEN = {
		{
			1, 0, 0, 0,
			6, 2, 0, 0, 8, 6, 3, 0, 9, 8, 7, 5, 10, 9, 8, 6,
			11, 10, 9, 7, 13, 11, 10, 8, 13, 13, 11, 9, 13, 13, 13, 10,
			14, 14, 13, 11, 14, 14, 14, 13, 15, 15, 14, 14, 15, 15, 15, 14,
			16, 15, 15, 15, 16, 16, 16, 15, 16, 16, 16, 16, 16, 16, 16, 16,
		},
		{
			2, 0, 0, 0,
			6, 2, 0, 0, 6, 5, 3, 0, 7, 6, 6, 4, 8, 6, 6, 4,
			8, 7, 7, 5, 9, 8, 8, 6, 11, 9, 9, 6, 11, 11, 11, 7,
			12, 11, 11, 9, 12, 12, 12, 11, 12, 12, 12, 11, 13, 13, 13, 12,
			13, 13, 13, 13, 13, 14, 13, 13, 14, 14, 14, 13, 14, 14, 14, 14,
		},
		{
			4, 0, 0, 0,
			6, 4, 0, 0, 6, 5, 4, 0, 6, 5, 5, 4, 7, 5, 5, 4,
			7, 5, 5, 4, 7, 6, 6, 4, 7, 6, 6, 4, 8, 7, 7, 5,
			8, 8, 7, 6, 9, 8, 8, 7, 9, 9, 8, 8, 9, 9, 9, 8,
			10, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
		},
	};

	static final int[][] COEFF_TOKEN_BITS = {
		{
			1, 0, 0, 0,
			5, 1, 0, 0, 7, 4, 1, 0, 7, 6, 5, 3, 7, 6, 5, 3,
			7, 6, 5, 4, 15, 6, 5, 4, 11, 14, 5, 4, 8, 10, 13, 4,
			15, 14, 9, 4, 11, 10, 13, 12, 15, 14, 9, 12, 11, 10, 13, 8,
			15, 1, 9, 12, 11, 14, 13, 8, 7, 10, 9, 12, 4, 6, 5, 8,
		},
		{
			3, 0, 0, 0,
			11, 2, 0, 0, 7, 7, 3, 0, 7, 10, 9, 5, 7, 6, 5, 4,
			4, 6, 5, 6, 7, 6, 5, 8, 15, 6, 5, 4, 11, 14, 13, 4,
			15, 10, 9, 4, 11, 14, 13, 12, 8, 10, 9, 8, 15, 14, 13, 12,
			11, 10, 9, 12, 7, 11, 6, 8, 9, 8, 10, 1, 7, 6, 5, 4,
		},
		{
			15, 0, 0, 0,
			15, 14, 0, 0, 11, 15, 13, 0, 8, 12, 14, 12, 15, 10, 11, 11,
			11, 8, 9, 10, 9, 14, 13, 9, 8, 10, 9, 8, 15, 14, 13, 13,
			11, 14, 10, 12, 15, 10, 13, 12, 11, 14, 9, 12, 8, 10, 13, 8,
			13, 7, 9, 12, 9, 12, 11, 10, 5, 8, 7, 6, 1, 4, 3, 2,
		},
	};

	/** coeff_token for chroma DC (nC = -1), indexed [totalCoeff * 4 + trailingOnes]. */
	static final int[] CHROMA_DC_TOKEN_LEN = {
		2, 0, 0, 0, 6, 1, 0, 0, 6, 6, 3, 0, 6, 7, 7, 6, 6, 8, 8, 7,
	};
	static final int[] CHROMA_DC_TOKEN_BITS = {
		1, 0, 0, 0, 7, 1, 0, 0, 4, 6, 1, 0, 3, 3, 2, 5, 2, 3, 2, 0,
	};

	/** total_zeros, indexed [totalCoeff - 1][totalZeros]. */
	static final int[][] TOTAL_ZEROS_LEN = {
		{1, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 9},
		{3, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 6, 6, 6, 6},
		{4, 3, 3, 3, 4, 4, 3, 3, 4, 5, 5, 6, 5, 6},
		{5, 3, 4, 4, 3, 3, 3, 4, 3, 4, 5, 5, 5},
		{4, 4, 4, 3, 3, 3, 3, 3, 4, 5, 4, 5},
		{6, 5, 3, 3, 3, 3, 3, 3, 4, 3, 6},
		{6, 5, 3, 3, 3, 2, 3, 4, 3, 6},
		{6, 4, 5, 3, 2, 2, 3, 3, 6},
		{6, 6, 4, 2, 2, 3, 2, 5},
		{5, 5, 3, 2, 2, 2, 4},
		{4, 4, 3, 3, 1, 3},
		{4, 4, 2, 1, 3},
		{3, 3, 1, 2},
		{2, 2, 1},
		{1, 1},
	};
	static final int[][] TOTAL_ZEROS_BITS = {
		{1, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 1},
		{7, 6, 5, 4, 3, 5, 4, 3, 2, 3, 2, 3, 2, 1, 0},
		{5, 7, 6, 5, 4, 3, 4, 3, 2, 3, 2, 1, 1, 0},
		{3, 7, 5, 4, 6, 5, 4, 3, 3, 2, 2, 1, 0},
		{5, 4, 3, 7, 6, 5, 4, 3, 2, 1, 1, 0},
		{1, 1, 7, 6, 5, 4, 3, 2, 1, 1, 0},
		{1, 1, 5, 4, 3, 3, 2, 1, 1, 0},
		{1, 1, 1, 3, 3, 2, 2, 1, 0},
		{1, 0, 1, 3, 2, 1, 1, 1},
		{1, 0, 1, 3, 2, 1, 1},
		{0, 1, 1, 2, 1, 3},
		{0, 1, 1, 1, 1},
		{0, 1, 1, 1},
		{0, 1, 1},
		{0, 1},
	};

	/** total_zeros for chroma DC, indexed [totalCoeff - 1][totalZeros]. */
	static final int[][] CHROMA_DC_TOTAL_ZEROS_LEN = {{1, 2, 3, 3}, {1, 2, 2}, {1, 1}};
	static final int[][] CHROMA_DC_TOTAL_ZEROS_BITS = {{1, 1, 1, 0}, {1, 1, 0}, {1, 0}};

	/** run_before, indexed [min(zerosLeft, 7) - 1][runBefore]. */
	static final int[][] RUN_LEN = {
		{1, 1},
		{1, 2, 2},
		{2, 2, 2, 2},
		{2, 2, 2, 3, 3},
		{2, 2, 3, 3, 3, 3},
		{2, 3, 3, 3, 3, 3, 3},
		{3, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7, 8, 9, 10, 11},
	};
	static final int[][] RUN_BITS = {
		{1, 0},
		{1, 1, 0},
		{3, 2, 1, 0},
		{3, 2, 1, 1, 0},
		{3, 2, 3, 2, 1, 0},
		{3, 0, 1, 3, 2, 5, 4},
		{7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1},
	};
}
