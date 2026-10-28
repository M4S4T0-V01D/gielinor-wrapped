package com.gielinorwrapped.video;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Checks the MDCT against the direct formula, then decodes a muxed AAC track with ffmpeg and measures it. */
public class AacEncoderTest
{
	@Test
	public void mdctMatchesDirectFormula()
	{
		int n = 64;
		float[] x = new float[2 * n];
		java.util.Random rnd = new java.util.Random(3);
		for (int i = 0; i < x.length; i++)
		{
			x[i] = rnd.nextFloat() * 2 - 1;
		}
		float[] fast = new float[n];
		new AacEncoder.Mdct(n).forward(x, fast);
		for (int k = 0; k < n; k++)
		{
			double sum = 0;
			for (int i = 0; i < 2 * n; i++)
			{
				sum += x[i] * Math.cos(Math.PI / n * (i + 0.5 + n / 2.0) * (k + 0.5));
			}
			assertEquals("bin " + k, sum, fast[k], 1e-3);
		}
	}

	@Test
	public void decodesWithFfmpeg() throws Exception
	{
		assumeTrue(H264EncoderTest.haveFfmpeg());
		int rate = AacEncoder.SAMPLE_RATE;
		int samples = rate * 2;
		float[] left = new float[samples];
		float[] right = new float[samples];
		for (int i = 0; i < samples; i++)
		{
			double t = i / (double) rate;
			double env = Math.min(1, t * 20) * Math.min(1, (samples - i) / (rate * 0.05));
			left[i] = (float) (env * (0.4 * Math.sin(2 * Math.PI * 440 * t) + 0.15 * Math.sin(2 * Math.PI * 1320 * t)));
			right[i] = (float) (env * (0.3 * Math.sin(2 * Math.PI * 330 * t) + 0.2 * Math.sin(2 * Math.PI * 2475 * t)));
		}
		AacEncoder aac = new AacEncoder(2, 128000);
		long start = System.nanoTime();
		List<byte[]> frames = aac.encode(left, right);
		System.out.printf("aac: %d frames in %d ms%n", frames.size(), (System.nanoTime() - start) / 1000000);

		int w = 64;
		int h = 64;
		H264Encoder enc = new H264Encoder(w, h, 30);
		Mp4Writer mp4 = new Mp4Writer(w, h, 30, enc.sps(), enc.pps());
		File dir = Files.createTempDirectory("gw-aac").toFile();
		File media = new File(dir, "media.tmp");
		File out = new File(dir, "out.mp4");
		try (OutputStream m = new FileOutputStream(media))
		{
			for (int t = 0; t < 60; t++)
			{
				mp4.addFrame(enc.encode(H264EncoderTest.scene(w, h, t, 3), false), m);
			}
		}
		mp4.setAudio(aac.audioSpecificConfig(), 2, 128000, frames, AacEncoder.PRIMING, samples);
		try (OutputStream o = new FileOutputStream(out); InputStream m = new FileInputStream(media))
		{
			mp4.finish(o, m);
		}

		String probe = run("ffprobe", "-v", "error", "-select_streams", "a:0",
			"-show_entries", "stream=codec_name,profile,sample_rate,channels",
			"-of", "default=noprint_wrappers=1", out.getPath());
		assertTrue(probe, probe.contains("codec_name=aac"));
		assertTrue(probe, probe.contains("sample_rate=44100"));
		assertTrue(probe, probe.contains("channels=2"));

		File pcm = new File(dir, "out.f32");
		String err = run("ffmpeg", "-v", "error", "-y", "-i", out.getPath(), "-map", "0:a", "-f", "f32le",
			"-ac", "2", pcm.getPath());
		assertEquals("", err.trim());
		ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(pcm.toPath())).order(ByteOrder.LITTLE_ENDIAN);
		int got = buf.remaining() / 8;
		float[][] dec = new float[2][got];
		for (int i = 0; i < got; i++)
		{
			dec[0][i] = buf.getFloat();
			dec[1][i] = buf.getFloat();
		}
		System.out.printf("aac: decoded %d of %d samples, %d bytes%n", got, samples, out.length());
		assertTrue("length " + got, Math.abs(got - samples) <= 1024);
		float[][] src = {left, right};
		for (int c = 0; c < 2; c++)
		{
			int bestLag = 0;
			double best = -1e9;
			for (int lag = -1100; lag <= 1100; lag++)
			{
				double s = snr(src[c], dec[c], lag);
				if (s > best)
				{
					best = s;
					bestLag = lag;
				}
			}
			double gain = rms(dec[c]) / rms(src[c]);
			System.out.printf("aac channel %d: snr %.1f dB at lag %d, gain %.3f%n", c, best, bestLag, gain);
			assertEquals("lag", 0, bestLag, 2);
			assertTrue("snr " + best, best > 20);
			assertEquals("gain", 1, gain, 0.1);
		}
	}

	private static double snr(float[] a, float[] b, int lag)
	{
		double sig = 0;
		double noise = 0;
		for (int i = 4096; i < a.length - 4096; i += 3)
		{
			int j = i + lag;
			if (j < 0 || j >= b.length)
			{
				continue;
			}
			sig += a[i] * a[i];
			double d = a[i] - b[j];
			noise += d * d;
		}
		return 10 * Math.log10(sig / Math.max(noise, 1e-12));
	}

	private static double rms(float[] a)
	{
		double s = 0;
		for (int i = 4096; i < Math.min(a.length, 80000); i++)
		{
			s += a[i] * a[i];
		}
		return Math.sqrt(s / (Math.min(a.length, 80000) - 4096));
	}

	private static String run(String... cmd) throws Exception
	{
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (InputStream in = p.getInputStream())
		{
			in.transferTo(bytes);
		}
		p.waitFor(60, TimeUnit.SECONDS);
		return bytes.toString("UTF-8");
	}
}
