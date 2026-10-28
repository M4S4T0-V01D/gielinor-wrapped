package com.gielinorwrapped.music;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A standard MIDI file flattened to channel events in time order, with times in seconds. */
public final class Midi
{
	/** Event times in seconds, ascending. */
	final double[] time;
	/** Status byte (type in the top nibble, channel in the bottom). */
	final int[] status;
	final int[] data1;
	final int[] data2;
	/** Time of the last event, in seconds. */
	public final double length;

	private Midi(double[] time, int[] status, int[] data1, int[] data2)
	{
		this.time = time;
		this.status = status;
		this.data1 = data1;
		this.data2 = data2;
		this.length = time.length == 0 ? 0 : time[time.length - 1];
	}

	public int size()
	{
		return time.length;
	}

	/** Parses MIDI file bytes; throws IllegalArgumentException on anything malformed. */
	public static Midi parse(byte[] b)
	{
		try
		{
			return read(b);
		}
		catch (IndexOutOfBoundsException e)
		{
			throw new IllegalArgumentException("Truncated MIDI", e);
		}
	}

	private static Midi read(byte[] b)
	{
		if (int32(b, 0) != 0x4D546864)
		{
			throw new IllegalArgumentException("Not a MIDI file");
		}
		int headerLen = int32(b, 4);
		int tracks = int16(b, 10);
		int division = (short) int16(b, 12);
		int pos = 8 + headerLen;

		// Each raw event packs tick, track order and position so a sort keeps simultaneous events in file order.
		List<long[]> events = new ArrayList<>();
		List<long[]> tempos = new ArrayList<>();
		long seq = 0;
		for (int t = 0; t < tracks && pos + 8 <= b.length; t++)
		{
			if (int32(b, pos) != 0x4D54726B)
			{
				throw new IllegalArgumentException("Bad track chunk");
			}
			int len = int32(b, pos + 4);
			int p = pos + 8;
			int end = Math.min(b.length, p + len);
			pos = p + len;
			long tick = 0;
			int running = 0;
			while (p < end)
			{
				int delta = 0;
				int c;
				do
				{
					c = b[p++] & 0xFF;
					delta = (delta << 7) | (c & 0x7F);
				}
				while ((c & 0x80) != 0);
				tick += delta;
				int st = b[p] & 0xFF;
				if (st >= 0x80)
				{
					p++;
				}
				else
				{
					st = running;
				}
				if (st == 0xFF)
				{
					int type = b[p++] & 0xFF;
					int mlen = 0;
					do
					{
						c = b[p++] & 0xFF;
						mlen = (mlen << 7) | (c & 0x7F);
					}
					while ((c & 0x80) != 0);
					if (type == 0x51 && mlen == 3)
					{
						int us = (b[p] & 0xFF) << 16 | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF);
						tempos.add(new long[]{tick, seq++, us});
					}
					p += mlen;
					if (type == 0x2F)
					{
						break;
					}
					continue;
				}
				if (st == 0xF0 || st == 0xF7)
				{
					int slen = 0;
					do
					{
						c = b[p++] & 0xFF;
						slen = (slen << 7) | (c & 0x7F);
					}
					while ((c & 0x80) != 0);
					p += slen;
					continue;
				}
				if (st < 0x80)
				{
					throw new IllegalArgumentException("Data byte without a status");
				}
				running = st;
				int type = st & 0xF0;
				int d1 = b[p++] & 0x7F;
				int d2 = 0;
				if (type != 0xC0 && type != 0xD0)
				{
					d2 = b[p++] & 0x7F;
				}
				events.add(new long[]{tick, seq++, st, d1, d2});
			}
		}

		events.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
		tempos.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));

		int n = events.size();
		double[] time = new double[n];
		int[] status = new int[n];
		int[] d1 = new int[n];
		int[] d2 = new int[n];
		double secondsPerTick;
		boolean smpte = division < 0;
		if (smpte)
		{
			int fps = -(division >> 8);
			int sub = division & 0xFF;
			secondsPerTick = 1.0 / (fps * Math.max(1, sub));
		}
		else
		{
			secondsPerTick = 0.5 / Math.max(1, division);
		}
		int ti = 0;
		long lastTick = 0;
		double seconds = 0;
		for (int i = 0; i < n; i++)
		{
			long[] e = events.get(i);
			long tick = e[0];
			while (!smpte && ti < tempos.size() && tempos.get(ti)[0] <= tick)
			{
				long[] tempo = tempos.get(ti++);
				seconds += (tempo[0] - lastTick) * secondsPerTick;
				lastTick = tempo[0];
				secondsPerTick = tempo[2] / 1e6 / Math.max(1, division);
			}
			seconds += (tick - lastTick) * secondsPerTick;
			lastTick = tick;
			time[i] = seconds;
			status[i] = (int) e[2];
			d1[i] = (int) e[3];
			d2[i] = (int) e[4];
		}
		return new Midi(time, status, d1, d2);
	}

	private static int int32(byte[] b, int p)
	{
		return (b[p] & 0xFF) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF);
	}

	private static int int16(byte[] b, int p)
	{
		return (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
	}

	@Override
	public String toString()
	{
		return "Midi[" + time.length + " events, " + String.format("%.1f", length) + "s, " + Arrays.toString(Arrays.copyOf(status, Math.min(4, status.length))) + "...]";
	}
}
