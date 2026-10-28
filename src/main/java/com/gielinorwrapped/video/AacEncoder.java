package com.gielinorwrapped.video;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small AAC-LC encoder for 44.1 kHz mono or stereo: long windows only, one scalefactor per channel
 * (picked per frame to fit the bit budget) and Huffman codebook 11 for every non-empty band. That is far
 * from a tuned encoder, but at 64 kbps per channel it is clean enough for synthesized game music.
 *
 * <p>Input samples are floats in -1..1. {@link #encode} returns raw AAC frames (no ADTS header) of 1024
 * samples each; the first {@link #PRIMING} decoded samples are encoder delay.
 */
public final class AacEncoder
{
	public static final int SAMPLE_RATE = 44100;
	public static final int FRAME = 1024;
	public static final int PRIMING = FRAME;

	private static final int[] SWB = {
		0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176,
		196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768,
		800, 832, 864, 896, 928, 1024,
	};
	private static final int BANDS = SWB.length - 1;
	private static final int MAX_QUANT = 8191;

	private final int channels;
	private final int bitsPerChannel;
	private final int bandLimit;
	private final float[] window = new float[2 * FRAME];
	private final Mdct mdct = new Mdct(FRAME);

	/** @param bitrate total bits per second over all channels */
	public AacEncoder(int channels, int bitrate)
	{
		if (channels != 1 && channels != 2)
		{
			throw new IllegalArgumentException("channels");
		}
		this.channels = channels;
		this.bitsPerChannel = (int) ((long) bitrate * FRAME / SAMPLE_RATE / channels) - 24;
		for (int i = 0; i < window.length; i++)
		{
			window[i] = (float) Math.sin(Math.PI * (i + 0.5) / window.length);
		}
		// Above about 16 kHz there is little to hear in synthesized music, so those bins are dropped.
		int band = BANDS;
		while (band > 0 && SWB[band] > 16000 * 2 * FRAME / SAMPLE_RATE)
		{
			band--;
		}
		bandLimit = band;
	}

	/** The AudioSpecificConfig for the MP4 esds box. */
	public byte[] audioSpecificConfig()
	{
		// object type 2 (LC), sampling index 4 (44100), channel configuration, then three zero flags
		return new byte[]{0x12, (byte) (channels << 3)};
	}

	public int getChannels()
	{
		return channels;
	}

	/** Encodes the whole signal; right is ignored for mono. */
	public List<byte[]> encode(float[] left, float[] right)
	{
		int samples = left.length;
		int frames = (samples + FRAME - 1) / FRAME + 1;
		List<byte[]> out = new ArrayList<>(frames);
		float[][] input = channels == 2 ? new float[][]{left, right} : new float[][]{left};
		float[] block = new float[2 * FRAME];
		float[] spec = new float[FRAME];
		int[][] quant = new int[channels][FRAME];
		int[] gains = new int[channels];
		int[] maxSfb = new int[channels];
		for (int f = 0; f < frames; f++)
		{
			int start = (f - 1) * FRAME;
			for (int c = 0; c < channels; c++)
			{
				float[] src = input[c];
				for (int i = 0; i < block.length; i++)
				{
					int s = start + i;
					float v = s >= 0 && s < samples ? src[s] : 0;
					// The decoder's inverse transform scales by 1/N, so 2x keeps 16-bit sample levels.
					block[i] = v * 65534f * window[i];
				}
				mdct.forward(block, spec);
				for (int i = SWB[bandLimit]; i < FRAME; i++)
				{
					spec[i] = 0;
				}
				gains[c] = chooseGain(spec, quant[c]);
				maxSfb[c] = lastBand(quant[c]);
			}
			out.add(writeFrame(quant, gains, maxSfb));
		}
		return out;
	}

	/** Finds the finest global gain whose quantized spectrum fits the per-channel bit budget. */
	private int chooseGain(float[] spec, int[] quant)
	{
		float peak = 0;
		for (float v : spec)
		{
			peak = Math.max(peak, Math.abs(v));
		}
		if (peak < 1e-3f)
		{
			Arrays.fill(quant, 0);
			return 100;
		}
		// The smallest gain that keeps every value within the escape range.
		int lo = (int) Math.ceil(100 + 4 * (Math.log(peak) / Math.log(2) - Math.log(MAX_QUANT) / Math.log(2) * 4 / 3));
		lo = Math.max(0, Math.min(255, lo));
		while (lo < 255 && quantize(spec, quant, lo) > MAX_QUANT)
		{
			lo++;
		}
		int hi = 255;
		if (bits(quant(spec, quant, lo)) <= bitsPerChannel)
		{
			return lo;
		}
		while (lo + 1 < hi)
		{
			int mid = (lo + hi) >>> 1;
			if (bits(quant(spec, quant, mid)) <= bitsPerChannel)
			{
				hi = mid;
			}
			else
			{
				lo = mid;
			}
		}
		quantize(spec, quant, hi);
		return hi;
	}

	private int[] quant(float[] spec, int[] quant, int gain)
	{
		quantize(spec, quant, gain);
		return quant;
	}

	/** Quantizes with one scalefactor and returns the largest magnitude. */
	private static int quantize(float[] spec, int[] quant, int gain)
	{
		double inv = Math.pow(2, -(gain - 100) / 4.0);
		int max = 0;
		for (int i = 0; i < FRAME; i++)
		{
			float v = spec[i];
			int q = (int) (Math.pow(Math.abs(v) * inv, 0.75) + 0.4054);
			max = Math.max(max, q);
			quant[i] = v < 0 ? -q : q;
		}
		return max;
	}

	private static int lastBand(int[] q)
	{
		for (int b = BANDS; b > 0; b--)
		{
			for (int i = SWB[b - 1]; i < SWB[b]; i++)
			{
				if (q[i] != 0)
				{
					return b;
				}
			}
		}
		return 0;
	}

	private static boolean empty(int[] q, int band)
	{
		for (int i = SWB[band]; i < SWB[band + 1]; i++)
		{
			if (q[i] != 0)
			{
				return false;
			}
		}
		return true;
	}

	/** Bits the channel stream takes for this quantization (header, sections, scalefactors, spectrum). */
	private static int bits(int[] q)
	{
		int max = lastBand(q);
		int total = 8 + 11 + 3;
		int prev = -1;
		for (int b = 0; b < max; b++)
		{
			boolean zero = empty(q, b);
			int cb = zero ? 0 : 11;
			if (cb != prev)
			{
				total += 4 + 5;
				prev = cb;
			}
			if (!zero)
			{
				total += SF_LENGTH[60];
				for (int i = SWB[b]; i < SWB[b + 1]; i += 2)
				{
					total += pairBits(q[i], q[i + 1]);
				}
			}
		}
		return total;
	}

	private static int pairBits(int y, int z)
	{
		int ay = Math.abs(y);
		int az = Math.abs(z);
		int n = CB11_LENGTH[Math.min(ay, 16) * 17 + Math.min(az, 16)];
		n += (ay != 0 ? 1 : 0) + (az != 0 ? 1 : 0);
		return n + escapeBits(ay) + escapeBits(az);
	}

	private static int escapeBits(int v)
	{
		if (v < 16)
		{
			return 0;
		}
		int n = 31 - Integer.numberOfLeadingZeros(v) - 4;
		return 2 * n + 5;
	}

	private byte[] writeFrame(int[][] quant, int[] gains, int[] maxSfb)
	{
		Bits w = new Bits();
		if (channels == 2)
		{
			w.put(3, 1);
			w.put(4, 0);
			w.put(1, 0);
		}
		else
		{
			w.put(3, 0);
			w.put(4, 0);
		}
		for (int c = 0; c < channels; c++)
		{
			writeChannel(w, quant[c], gains[c], maxSfb[c]);
		}
		w.put(3, 7);
		return w.toBytes();
	}

	private static void writeChannel(Bits w, int[] q, int gain, int max)
	{
		w.put(8, gain);
		// ics_info: reserved bit, ONLY_LONG_SEQUENCE, sine window, max_sfb, no prediction
		w.put(1, 0);
		w.put(2, 0);
		w.put(1, 0);
		w.put(6, max);
		w.put(1, 0);

		int b = 0;
		while (b < max)
		{
			boolean zero = empty(q, b);
			int end = b + 1;
			while (end < max && empty(q, end) == zero)
			{
				end++;
			}
			w.put(4, zero ? 0 : 11);
			int len = end - b;
			while (len >= 31)
			{
				w.put(5, 31);
				len -= 31;
			}
			w.put(5, len);
			b = end;
		}
		for (b = 0; b < max; b++)
		{
			if (!empty(q, b))
			{
				w.put(SF_LENGTH[60], SF_CODE[60]);
			}
		}
		// pulse, tns and gain control all absent
		w.put(3, 0);
		for (b = 0; b < max; b++)
		{
			if (empty(q, b))
			{
				continue;
			}
			for (int i = SWB[b]; i < SWB[b + 1]; i += 2)
			{
				int y = q[i];
				int z = q[i + 1];
				int ay = Math.abs(y);
				int az = Math.abs(z);
				int idx = Math.min(ay, 16) * 17 + Math.min(az, 16);
				w.put(CB11_LENGTH[idx], CB11_CODE[idx]);
				if (ay != 0)
				{
					w.put(1, y < 0 ? 1 : 0);
				}
				if (az != 0)
				{
					w.put(1, z < 0 ? 1 : 0);
				}
				escape(w, ay);
				escape(w, az);
			}
		}
	}

	private static void escape(Bits w, int v)
	{
		if (v < 16)
		{
			return;
		}
		int n = 31 - Integer.numberOfLeadingZeros(v) - 4;
		for (int i = 0; i < n; i++)
		{
			w.put(1, 1);
		}
		w.put(1, 0);
		w.put(n + 4, v - (1 << (n + 4)));
	}

	/** Big-endian bit packer. */
	private static final class Bits
	{
		private byte[] buf = new byte[1024];
		private int pos;

		void put(int bits, int value)
		{
			for (int i = bits - 1; i >= 0; i--)
			{
				if ((pos >> 3) >= buf.length)
				{
					buf = Arrays.copyOf(buf, buf.length * 2);
				}
				if (((value >>> i) & 1) != 0)
				{
					buf[pos >> 3] |= (byte) (0x80 >>> (pos & 7));
				}
				pos++;
			}
		}

		byte[] toBytes()
		{
			return Arrays.copyOf(buf, (pos + 7) >> 3);
		}
	}

	/**
	 * MDCT of 2N windowed samples into N coefficients, X[k] = sum x[n] cos(pi/N (n + 1/2 + N/2)(k + 1/2)),
	 * computed as a fold into a DCT-IV and an N/2-point complex FFT.
	 */
	static final class Mdct
	{
		private final int n;
		private final float[] u;
		private final double[] re;
		private final double[] im;
		private final double[] twCos;
		private final double[] twSin;
		private final double[] fftCos;
		private final double[] fftSin;

		Mdct(int n)
		{
			this.n = n;
			u = new float[n];
			int h = n / 2;
			re = new double[h];
			im = new double[h];
			twCos = new double[h];
			twSin = new double[h];
			for (int m = 0; m < h; m++)
			{
				double a = Math.PI / n * (m + 0.125);
				twCos[m] = Math.cos(a);
				twSin[m] = Math.sin(a);
			}
			fftCos = new double[h / 2];
			fftSin = new double[h / 2];
			for (int i = 0; i < h / 2; i++)
			{
				fftCos[i] = Math.cos(2 * Math.PI * i / h);
				fftSin[i] = Math.sin(2 * Math.PI * i / h);
			}
		}

		void forward(float[] x, float[] out)
		{
			int h = n / 2;
			// x = [a b c d] in quarters of N/2; the DCT-IV input is (-c_r - d, a - b_r)
			for (int i = 0; i < h; i++)
			{
				u[i] = -x[n + h - 1 - i] - x[n + h + i];
				u[h + i] = x[i] - x[n - 1 - i];
			}
			for (int m = 0; m < h; m++)
			{
				double a = u[2 * m];
				double b = -u[n - 1 - 2 * m];
				re[m] = a * twCos[m] - b * twSin[m];
				im[m] = a * twSin[m] + b * twCos[m];
			}
			fft(re, im);
			for (int p = 0; p < h; p++)
			{
				double r = re[p] * twCos[p] - im[p] * twSin[p];
				double i = re[p] * twSin[p] + im[p] * twCos[p];
				out[2 * p] = (float) r;
				out[n - 1 - 2 * p] = (float) i;
			}
		}

		/** In-place radix-2 FFT with a positive exponent. */
		private void fft(double[] re, double[] im)
		{
			int len = re.length;
			for (int i = 1, j = 0; i < len; i++)
			{
				int bit = len >> 1;
				for (; (j & bit) != 0; bit >>= 1)
				{
					j ^= bit;
				}
				j ^= bit;
				if (i < j)
				{
					double t = re[i];
					re[i] = re[j];
					re[j] = t;
					t = im[i];
					im[i] = im[j];
					im[j] = t;
				}
			}
			for (int size = 2; size <= len; size <<= 1)
			{
				int half = size >> 1;
				int step = len / size;
				for (int s = 0; s < len; s += size)
				{
					for (int k = 0; k < half; k++)
					{
						double c = fftCos[k * step];
						double sn = fftSin[k * step];
						int a = s + k;
						int b = a + half;
						double tr = re[b] * c - im[b] * sn;
						double ti = re[b] * sn + im[b] * c;
						re[b] = re[a] - tr;
						im[b] = im[a] - ti;
						re[a] += tr;
						im[a] += ti;
					}
				}
			}
		}
	}

	// Huffman codebook 11 and the scalefactor codebook from ISO/IEC 14496-3, as tabulated in the
	// VisualOn AAC encoder (Apache License 2.0). Codebook 11 is indexed by |y| * 17 + |z|; the
	// scalefactor book by delta + 60.
	static final int[] CB11_LENGTH = {
		0x4, 0x5, 0x6, 0x7, 0x8, 0x8, 0x9, 0xa, 0xa, 0xa, 0xb, 0xb, 0xc, 0xb, 0xc, 0xc, 0xa,
		0x5, 0x4, 0x5, 0x6, 0x7, 0x7, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xb, 0x8,
		0x6, 0x5, 0x5, 0x6, 0x7, 0x7, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0x8,
		0x7, 0x6, 0x6, 0x6, 0x7, 0x7, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0x8,
		0x8, 0x7, 0x7, 0x7, 0x7, 0x8, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0x8,
		0x8, 0x7, 0x7, 0x7, 0x7, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0x8,
		0x9, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0x8,
		0x9, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0x8,
		0xa, 0x9, 0x8, 0x8, 0x9, 0x9, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0x8,
		0xa, 0x9, 0x9, 0x9, 0x9, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0x8,
		0xb, 0x9, 0x9, 0x9, 0x9, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xa, 0xb, 0xb, 0x8,
		0xb, 0xa, 0x9, 0x9, 0xa, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xb, 0x8,
		0xb, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xb, 0x9,
		0xb, 0xa, 0x9, 0x9, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xb, 0xb, 0x9,
		0xb, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xb, 0xb, 0x9,
		0xc, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xb, 0xb, 0xc, 0xc, 0x9,
		0x9, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x8, 0x9, 0x5
	};

	static final int[] CB11_CODE = {
		0x0, 0x6, 0x19, 0x3d, 0x9c, 0xc6, 0x1a7, 0x390, 0x3c2, 0x3df, 0x7e6, 0x7f3, 0xffb, 0x7ec, 0xffa, 0xffe, 0x38e,
		0x5, 0x1, 0x8, 0x14, 0x37, 0x42, 0x92, 0xaf, 0x191, 0x1a5, 0x1b5, 0x39e, 0x3c0, 0x3a2, 0x3cd, 0x7d6, 0xae,
		0x17, 0x7, 0x9, 0x18, 0x39, 0x40, 0x8e, 0xa3, 0xb8, 0x199, 0x1ac, 0x1c1, 0x3b1, 0x396, 0x3be, 0x3ca, 0x9d,
		0x3c, 0x15, 0x16, 0x1a, 0x3b, 0x44, 0x91, 0xa5, 0xbe, 0x196, 0x1ae, 0x1b9, 0x3a1, 0x391, 0x3a5, 0x3d5, 0x94,
		0x9a, 0x36, 0x38, 0x3a, 0x41, 0x8c, 0x9b, 0xb0, 0xc3, 0x19e, 0x1ab, 0x1bc, 0x39f, 0x38f, 0x3a9, 0x3cf, 0x93,
		0xbf, 0x3e, 0x3f, 0x43, 0x45, 0x9e, 0xa7, 0xb9, 0x194, 0x1a2, 0x1ba, 0x1c3, 0x3a6, 0x3a7, 0x3bb, 0x3d4, 0x9f,
		0x1a0, 0x8f, 0x8d, 0x90, 0x98, 0xa6, 0xb6, 0xc4, 0x19f, 0x1af, 0x1bf, 0x399, 0x3bf, 0x3b4, 0x3c9, 0x3e7, 0xa8,
		0x1b6, 0xab, 0xa4, 0xaa, 0xb2, 0xc2, 0xc5, 0x198, 0x1a4, 0x1b8, 0x38c, 0x3a4, 0x3c4, 0x3c6, 0x3dd, 0x3e8, 0xad,
		0x3af, 0x192, 0xbd, 0xbc, 0x18e, 0x197, 0x19a, 0x1a3, 0x1b1, 0x38d, 0x398, 0x3b7, 0x3d3, 0x3d1, 0x3db, 0x7dd, 0xb4,
		0x3de, 0x1a9, 0x19b, 0x19c, 0x1a1, 0x1aa, 0x1ad, 0x1b3, 0x38b, 0x3b2, 0x3b8, 0x3ce, 0x3e1, 0x3e0, 0x7d2, 0x7e5, 0xb7,
		0x7e3, 0x1bb, 0x1a8, 0x1a6, 0x1b0, 0x1b2, 0x1b7, 0x39b, 0x39a, 0x3ba, 0x3b5, 0x3d6, 0x7d7, 0x3e4, 0x7d8, 0x7ea, 0xba,
		0x7e8, 0x3a0, 0x1bd, 0x1b4, 0x38a, 0x1c4, 0x392, 0x3aa, 0x3b0, 0x3bc, 0x3d7, 0x7d4, 0x7dc, 0x7db, 0x7d5, 0x7f0, 0xc1,
		0x7fb, 0x3c8, 0x3a3, 0x395, 0x39d, 0x3ac, 0x3ae, 0x3c5, 0x3d8, 0x3e2, 0x3e6, 0x7e4, 0x7e7, 0x7e0, 0x7e9, 0x7f7, 0x190,
		0x7f2, 0x393, 0x1be, 0x1c0, 0x394, 0x397, 0x3ad, 0x3c3, 0x3c1, 0x3d2, 0x7da, 0x7d9, 0x7df, 0x7eb, 0x7f4, 0x7fa, 0x195,
		0x7f8, 0x3bd, 0x39c, 0x3ab, 0x3a8, 0x3b3, 0x3b9, 0x3d0, 0x3e3, 0x3e5, 0x7e2, 0x7de, 0x7ed, 0x7f1, 0x7f9, 0x7fc, 0x193,
		0xffd, 0x3dc, 0x3b6, 0x3c7, 0x3cc, 0x3cb, 0x3d9, 0x3da, 0x7d3, 0x7e1, 0x7ee, 0x7ef, 0x7f5, 0x7f6, 0xffc, 0xfff, 0x19d,
		0x1c2, 0xb5, 0xa1, 0x96, 0x97, 0x95, 0x99, 0xa0, 0xa2, 0xac, 0xa9, 0xb1, 0xb3, 0xbb, 0xc0, 0x18f, 0x4
	};

	static final int[] SF_LENGTH = {
		0x12, 0x12, 0x12, 0x12, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13,
		0x13, 0x13, 0x12, 0x13, 0x12, 0x11, 0x11, 0x10, 0x11, 0x10, 0x10, 0x10, 0x10, 0xf, 0xf, 0xe, 0xe,
		0xe, 0xe, 0xe, 0xe, 0xd, 0xd, 0xc, 0xc, 0xc, 0xb, 0xc, 0xb, 0xa, 0xa, 0xa, 0x9, 0x9,
		0x8, 0x8, 0x8, 0x7, 0x6, 0x6, 0x5, 0x4, 0x3, 0x1, 0x4, 0x4, 0x5, 0x6, 0x6, 0x7, 0x7,
		0x8, 0x8, 0x9, 0x9, 0xa, 0xa, 0xa, 0xb, 0xb, 0xb, 0xb, 0xc, 0xc, 0xd, 0xd, 0xd, 0xe,
		0xe, 0x10, 0xf, 0x10, 0xf, 0x12, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13,
		0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13, 0x13,
		0x13, 0x13
	};

	static final int[] SF_CODE = {
		0x3ffe8, 0x3ffe6, 0x3ffe7, 0x3ffe5, 0x7fff5, 0x7fff1, 0x7ffed, 0x7fff6, 0x7ffee, 0x7ffef, 0x7fff0, 0x7fffc, 0x7fffd, 0x7ffff, 0x7fffe, 0x7fff7, 0x7fff8,
		0x7fffb, 0x7fff9, 0x3ffe4, 0x7fffa, 0x3ffe3, 0x1ffef, 0x1fff0, 0xfff5, 0x1ffee, 0xfff2, 0xfff3, 0xfff4, 0xfff1, 0x7ff6, 0x7ff7, 0x3ff9, 0x3ff5,
		0x3ff7, 0x3ff3, 0x3ff6, 0x3ff2, 0x1ff7, 0x1ff5, 0xff9, 0xff7, 0xff6, 0x7f9, 0xff4, 0x7f8, 0x3f9, 0x3f7, 0x3f5, 0x1f8, 0x1f7,
		0xfa, 0xf8, 0xf6, 0x79, 0x3a, 0x38, 0x1a, 0xb, 0x4, 0x0, 0xa, 0xc, 0x1b, 0x39, 0x3b, 0x78, 0x7a,
		0xf7, 0xf9, 0x1f6, 0x1f9, 0x3f4, 0x3f6, 0x3f8, 0x7f5, 0x7f4, 0x7f6, 0x7f7, 0xff5, 0xff8, 0x1ff4, 0x1ff6, 0x1ff8, 0x3ff8,
		0x3ff4, 0xfff0, 0x7ff4, 0xfff6, 0x7ff5, 0x3ffe2, 0x7ffd9, 0x7ffda, 0x7ffdb, 0x7ffdc, 0x7ffdd, 0x7ffde, 0x7ffd8, 0x7ffd2, 0x7ffd3, 0x7ffd4, 0x7ffd5,
		0x7ffd6, 0x7fff2, 0x7ffdf, 0x7ffe7, 0x7ffe8, 0x7ffe9, 0x7ffea, 0x7ffeb, 0x7ffe6, 0x7ffe0, 0x7ffe1, 0x7ffe2, 0x7ffe3, 0x7ffe4, 0x7ffe5, 0x7ffd7, 0x7ffec,
		0x7fff4, 0x7fff3
	};
}
