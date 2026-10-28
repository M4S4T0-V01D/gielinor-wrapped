package com.gielinorwrapped.video;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Muxes H.264 frames into a single-track MP4 with the index (moov) before the media data, so it plays
 * while streaming. Samples are first written to a temporary stream with {@link #addFrame}; {@link #finish}
 * then writes the file header and copies the temporary data after it.
 */
public final class Mp4Writer
{
	private static final int[] MATRIX = {0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000};

	private final int width;
	private final int height;
	private final int fps;
	private final byte[] sps;
	private final byte[] pps;
	private int[] sizes = new int[256];
	private int[] keys = new int[16];
	private int count;
	private int keyCount;
	private long dataSize;
	private byte[] audioConfig;
	private int audioChannels;
	private int audioBitrate;
	private java.util.List<byte[]> audioFrames;
	private int audioPriming;
	private long audioSamples;
	private long audioSize;

	public Mp4Writer(int width, int height, int fps, byte[] sps, byte[] pps)
	{
		this.width = width;
		this.height = height;
		this.fps = fps;
		this.sps = sps.clone();
		this.pps = pps.clone();
	}

	/** Appends a frame's sample (a length-prefixed NAL unit) to the temporary media stream. */
	public void addFrame(H264Encoder.Frame frame, OutputStream media) throws IOException
	{
		int len = frame.nal.length;
		media.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
		media.write(frame.nal);
		if (count == sizes.length)
		{
			sizes = java.util.Arrays.copyOf(sizes, count * 2);
		}
		sizes[count++] = len + 4;
		if (frame.key)
		{
			if (keyCount == keys.length)
			{
				keys = java.util.Arrays.copyOf(keys, keyCount * 2);
			}
			keys[keyCount++] = count;
		}
		dataSize += len + 4;
		if (dataSize > 0xFFFFFFF0L)
		{
			throw new IOException("Video too large");
		}
	}

	public int getFrameCount()
	{
		return count;
	}

	/** Bytes of media data written so far. */
	public long getDataSize()
	{
		return dataSize;
	}

	/**
	 * Adds an AAC track: raw frames of 1024 samples at 44.1 kHz, the first priming samples of which are
	 * encoder delay (hidden with an edit list), and samples of real audio in total.
	 */
	public void setAudio(byte[] audioSpecificConfig, int channels, int bitrate, java.util.List<byte[]> frames,
		int priming, long samples)
	{
		audioConfig = audioSpecificConfig.clone();
		audioChannels = channels;
		audioBitrate = bitrate;
		audioFrames = frames;
		audioPriming = priming;
		audioSamples = samples;
		audioSize = 0;
		for (byte[] f : frames)
		{
			audioSize += f.length;
		}
	}

	/** Writes the finished file to out, copying the media data back from the temporary stream. */
	public void finish(OutputStream out, InputStream media) throws IOException
	{
		byte[] ftyp = box("ftyp", d ->
		{
			d.writeBytes("isom");
			d.writeInt(512);
			d.writeBytes("isomiso2avc1mp41");
		});
		byte[] moov = moov(0);
		moov = moov(ftyp.length + moov.length + 8);
		out.write(ftyp);
		out.write(moov);
		DataOutputStream d = new DataOutputStream(out);
		if (dataSize + audioSize > 0xFFFFFFF0L)
		{
			throw new IOException("Video too large");
		}
		d.writeInt((int) (dataSize + audioSize + 8));
		d.writeBytes("mdat");
		d.flush();
		byte[] buf = new byte[65536];
		long left = dataSize;
		while (left > 0)
		{
			int n = media.read(buf, 0, (int) Math.min(buf.length, left));
			if (n < 0)
			{
				throw new IOException("Media data ended early");
			}
			out.write(buf, 0, n);
			left -= n;
		}
		if (audioFrames != null)
		{
			for (byte[] f : audioFrames)
			{
				out.write(f);
			}
		}
		out.flush();
	}

	private byte[] moov(long dataOffset) throws IOException
	{
		int timescale = fps * 1000;
		long mediaDuration = (long) count * 1000;
		long videoDuration = (long) count * 1000 / fps;
		long audioDuration = audioFrames == null ? 0 : audioSamples * 1000 / AUDIO_RATE;
		long movieDuration = Math.max(videoDuration, audioDuration);
		return box("moov", moov ->
		{
			moov.write(fullBox("mvhd", 0, 0, d ->
			{
				d.writeInt(0);
				d.writeInt(0);
				d.writeInt(1000);
				d.writeInt((int) movieDuration);
				d.writeInt(0x10000);
				d.writeShort(0x100);
				d.write(new byte[10]);
				matrix(d);
				d.write(new byte[24]);
				d.writeInt(audioFrames == null ? 2 : 3);
			}));
			moov.write(box("trak", trak ->
			{
				trak.write(fullBox("tkhd", 0, 3, d ->
				{
					d.writeInt(0);
					d.writeInt(0);
					d.writeInt(1);
					d.writeInt(0);
					d.writeInt((int) videoDuration);
					d.write(new byte[8]);
					d.writeShort(0);
					d.writeShort(0);
					d.writeShort(0);
					d.writeShort(0);
					matrix(d);
					d.writeInt(width << 16);
					d.writeInt(height << 16);
				}));
				trak.write(box("mdia", mdia ->
				{
					mdia.write(fullBox("mdhd", 0, 0, d ->
					{
						d.writeInt(0);
						d.writeInt(0);
						d.writeInt(timescale);
						d.writeInt((int) mediaDuration);
						d.writeShort(0x55C4);
						d.writeShort(0);
					}));
					mdia.write(fullBox("hdlr", 0, 0, d ->
					{
						d.writeInt(0);
						d.writeBytes("vide");
						d.write(new byte[12]);
						d.write("VideoHandler\0".getBytes(StandardCharsets.US_ASCII));
					}));
					mdia.write(box("minf", minf ->
					{
						minf.write(fullBox("vmhd", 0, 1, d -> d.write(new byte[8])));
						minf.write(box("dinf", dinf -> dinf.write(fullBox("dref", 0, 0, d ->
						{
							d.writeInt(1);
							d.write(fullBox("url ", 0, 1, u ->
							{
							}));
						}))));
						minf.write(box("stbl", stbl -> stbl(stbl, dataOffset)));
					}));
				}));
			}));
			if (audioFrames != null)
			{
				moov.write(audioTrak(dataOffset + dataSize, audioDuration));
			}
		});
	}

	private static final int AUDIO_RATE = 44100;

	private byte[] audioTrak(long offset, long duration) throws IOException
	{
		int n = audioFrames.size();
		return box("trak", trak ->
		{
			trak.write(fullBox("tkhd", 0, 3, d ->
			{
				d.writeInt(0);
				d.writeInt(0);
				d.writeInt(2);
				d.writeInt(0);
				d.writeInt((int) duration);
				d.write(new byte[8]);
				d.writeShort(0);
				d.writeShort(1);
				d.writeShort(0x100);
				d.writeShort(0);
				matrix(d);
				d.writeInt(0);
				d.writeInt(0);
			}));
			trak.write(box("edts", edts -> edts.write(fullBox("elst", 0, 0, d ->
			{
				d.writeInt(1);
				d.writeInt((int) duration);
				d.writeInt(audioPriming);
				d.writeInt(0x10000);
			}))));
			trak.write(box("mdia", mdia ->
			{
				mdia.write(fullBox("mdhd", 0, 0, d ->
				{
					d.writeInt(0);
					d.writeInt(0);
					d.writeInt(AUDIO_RATE);
					d.writeInt(n * 1024);
					d.writeShort(0x55C4);
					d.writeShort(0);
				}));
				mdia.write(fullBox("hdlr", 0, 0, d ->
				{
					d.writeInt(0);
					d.writeBytes("soun");
					d.write(new byte[12]);
					d.write("SoundHandler\0".getBytes(StandardCharsets.US_ASCII));
				}));
				mdia.write(box("minf", minf ->
				{
					minf.write(fullBox("smhd", 0, 0, d -> d.writeInt(0)));
					minf.write(box("dinf", dinf -> dinf.write(fullBox("dref", 0, 0, d ->
					{
						d.writeInt(1);
						d.write(fullBox("url ", 0, 1, u ->
						{
						}));
					}))));
					minf.write(box("stbl", stbl ->
					{
						stbl.write(fullBox("stsd", 0, 0, d ->
						{
							d.writeInt(1);
							d.write(box("mp4a", e ->
							{
								e.write(new byte[6]);
								e.writeShort(1);
								e.write(new byte[8]);
								e.writeShort(audioChannels);
								e.writeShort(16);
								e.writeShort(0);
								e.writeShort(0);
								e.writeInt(AUDIO_RATE << 16);
								e.write(fullBox("esds", 0, 0, this::esds));
							}));
						}));
						stbl.write(fullBox("stts", 0, 0, d ->
						{
							d.writeInt(1);
							d.writeInt(n);
							d.writeInt(1024);
						}));
						stbl.write(fullBox("stsc", 0, 0, d ->
						{
							d.writeInt(1);
							d.writeInt(1);
							d.writeInt(n);
							d.writeInt(1);
						}));
						stbl.write(fullBox("stsz", 0, 0, d ->
						{
							d.writeInt(0);
							d.writeInt(n);
							for (byte[] f : audioFrames)
							{
								d.writeInt(f.length);
							}
						}));
						stbl.write(fullBox("stco", 0, 0, d ->
						{
							d.writeInt(1);
							d.writeInt((int) offset);
						}));
					}));
				}));
			}));
		});
	}

	private void esds(DataOutputStream d) throws IOException
	{
		int max = 0;
		for (byte[] f : audioFrames)
		{
			max = Math.max(max, f.length);
		}
		int asc = audioConfig.length;
		int dsi = 2 + asc;
		int dcd = 2 + 13 + dsi;
		int es = 2 + 3 + dcd + 3;
		d.writeByte(3);
		d.writeByte(es - 2);
		d.writeShort(2);
		d.writeByte(0);
		d.writeByte(4);
		d.writeByte(dcd - 2);
		d.writeByte(0x40);
		d.writeByte(0x15);
		d.writeByte(0);
		d.writeShort(Math.max(max, 768 * audioChannels));
		d.writeInt(Math.max(audioBitrate, max * 8 * AUDIO_RATE / 1024));
		d.writeInt(audioBitrate);
		d.writeByte(5);
		d.writeByte(asc);
		d.write(audioConfig);
		d.writeByte(6);
		d.writeByte(1);
		d.writeByte(2);
	}

	private void stbl(DataOutputStream stbl, long dataOffset) throws IOException
	{
		stbl.write(fullBox("stsd", 0, 0, d ->
		{
			d.writeInt(1);
			d.write(box("avc1", e ->
			{
				e.write(new byte[6]);
				e.writeShort(1);
				e.write(new byte[16]);
				e.writeShort(width);
				e.writeShort(height);
				e.writeInt(0x00480000);
				e.writeInt(0x00480000);
				e.writeInt(0);
				e.writeShort(1);
				e.write(new byte[32]);
				e.writeShort(0x18);
				e.writeShort(0xFFFF);
				e.write(box("avcC", c ->
				{
					c.writeByte(1);
					c.writeByte(sps[1]);
					c.writeByte(sps[2]);
					c.writeByte(sps[3]);
					c.writeByte(0xFF);
					c.writeByte(0xE1);
					c.writeShort(sps.length);
					c.write(sps);
					c.writeByte(1);
					c.writeShort(pps.length);
					c.write(pps);
				}));
			}));
		}));
		stbl.write(fullBox("stts", 0, 0, d ->
		{
			d.writeInt(1);
			d.writeInt(count);
			d.writeInt(1000);
		}));
		stbl.write(fullBox("stss", 0, 0, d ->
		{
			d.writeInt(keyCount);
			for (int i = 0; i < keyCount; i++)
			{
				d.writeInt(keys[i]);
			}
		}));
		stbl.write(fullBox("stsc", 0, 0, d ->
		{
			d.writeInt(1);
			d.writeInt(1);
			d.writeInt(count);
			d.writeInt(1);
		}));
		stbl.write(fullBox("stsz", 0, 0, d ->
		{
			d.writeInt(0);
			d.writeInt(count);
			for (int i = 0; i < count; i++)
			{
				d.writeInt(sizes[i]);
			}
		}));
		stbl.write(fullBox("stco", 0, 0, d ->
		{
			d.writeInt(1);
			d.writeInt((int) dataOffset);
		}));
	}

	private static void matrix(DataOutputStream d) throws IOException
	{
		for (int v : MATRIX)
		{
			d.writeInt(v);
		}
	}

	private interface Body
	{
		void write(DataOutputStream d) throws IOException;
	}

	private static byte[] box(String type, Body body) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(bytes);
		d.writeInt(0);
		d.writeBytes(type);
		body.write(d);
		d.flush();
		byte[] b = bytes.toByteArray();
		int len = b.length;
		b[0] = (byte) (len >>> 24);
		b[1] = (byte) (len >>> 16);
		b[2] = (byte) (len >>> 8);
		b[3] = (byte) len;
		return b;
	}

	private static byte[] fullBox(String type, int version, int flags, Body body) throws IOException
	{
		return box(type, d ->
		{
			d.writeInt((version << 24) | flags);
			body.write(d);
		});
	}
}
