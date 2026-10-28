package com.gielinorwrapped.video;

import java.io.ByteArrayOutputStream;

/** Big-endian bit writer for H.264 RBSPs, with Exp-Golomb codes and NAL emulation prevention. */
final class BitWriter
{
	private byte[] buf = new byte[4096];
	private int bytePos;
	private int bitPos;

	void u(int bits, int value)
	{
		for (int i = bits - 1; i >= 0; i--)
		{
			bit((value >>> i) & 1);
		}
	}

	void bit(int b)
	{
		if (bytePos >= buf.length)
		{
			byte[] grown = new byte[buf.length * 2];
			System.arraycopy(buf, 0, grown, 0, buf.length);
			buf = grown;
		}
		if (b != 0)
		{
			buf[bytePos] |= (byte) (0x80 >>> bitPos);
		}
		if (++bitPos == 8)
		{
			bitPos = 0;
			bytePos++;
		}
	}

	/** Unsigned Exp-Golomb. */
	void ue(int value)
	{
		int v = value + 1;
		int len = 32 - Integer.numberOfLeadingZeros(v);
		u(len - 1, 0);
		u(len, v);
	}

	/** Signed Exp-Golomb. */
	void se(int value)
	{
		ue(value <= 0 ? -2 * value : 2 * value - 1);
	}

	static int ueBits(int value)
	{
		int len = 32 - Integer.numberOfLeadingZeros(value + 1);
		return 2 * len - 1;
	}

	static int seBits(int value)
	{
		return ueBits(value <= 0 ? -2 * value : 2 * value - 1);
	}

	/** rbsp_trailing_bits: a one, then zeros up to the byte boundary. */
	void trailing()
	{
		bit(1);
		while (bitPos != 0)
		{
			bit(0);
		}
	}

	int bitLength()
	{
		return bytePos * 8 + bitPos;
	}

	/** The NAL unit: header byte plus the RBSP with emulation prevention bytes, without a start code or length. */
	byte[] toNal(int nalRefIdc, int nalType)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(bytePos + bytePos / 64 + 8);
		out.write((nalRefIdc << 5) | nalType);
		int zeros = 0;
		int len = bytePos + (bitPos > 0 ? 1 : 0);
		for (int i = 0; i < len; i++)
		{
			int b = buf[i] & 0xFF;
			if (zeros >= 2 && b <= 3)
			{
				out.write(3);
				zeros = 0;
			}
			out.write(b);
			zeros = b == 0 ? zeros + 1 : 0;
		}
		return out.toByteArray();
	}
}
