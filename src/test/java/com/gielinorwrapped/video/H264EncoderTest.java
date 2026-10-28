package com.gielinorwrapped.video;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Decodes encoder output with ffmpeg (skipped when ffmpeg isn't installed) and compares it with the encoder's reconstruction. */
public class H264EncoderTest
{
	static boolean haveFfmpeg()
	{
		try
		{
			Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
		}
		catch (IOException | InterruptedException e)
		{
			return false;
		}
	}

	/** A moving scene: gradient, drifting textured square, a bouncing dot and noise speckles. */
	static int[] scene(int w, int h, int t, long seed)
	{
		int[] px = new int[w * h];
		Random rnd = new Random(seed + t);
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int r = (x * 255 / w + t * 3) & 0xFF;
				int g = y * 255 / h;
				int b = ((x ^ y) & 0x3F) + 96;
				int sx = x - t * 2 - 20;
				int sy = y - t - 10;
				if (sx >= 0 && sx < 48 && sy >= 0 && sy < 40)
				{
					r = ((sx / 4 + sy / 4) & 1) != 0 ? 240 : 30;
					g = 200 - sy * 3;
					b = sx * 5;
				}
				int dx = x - (int) (w / 2 + Math.sin(t / 5.0) * w / 3);
				int dy = y - (int) (h / 2 + Math.cos(t / 7.0) * h / 3);
				if (dx * dx + dy * dy < 81)
				{
					r = 255;
					g = 255;
					b = 0;
				}
				if (rnd.nextInt(200) == 0)
				{
					r = g = b = rnd.nextInt(256);
				}
				px[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return px;
	}

	@Test
	public void decodesExactlyAsReconstructed() throws Exception
	{
		assumeTrue(haveFfmpeg());
		check(200, 120, 26, 60, 25);
		check(64, 48, 40, 20, 8);
		check(34, 18, 18, 6, 3);
		check(160, 96, 44, 30, 30);
	}

	private void check(int w, int h, int qp, int frames, int keyInterval) throws Exception
	{
		H264Encoder enc = new H264Encoder(w, h, 30);
		enc.setQp(qp);
		enc.setKeyInterval(keyInterval);
		File dir = Files.createTempDirectory("gw-h264").toFile();
		File stream = new File(dir, "out.h264");
		List<int[][]> recon = new ArrayList<>();
		List<int[]> sources = new ArrayList<>();
		try (OutputStream out = new FileOutputStream(stream))
		{
			writeNal(out, enc.sps());
			writeNal(out, enc.pps());
			for (int t = 0; t < frames; t++)
			{
				int[] src = scene(w, h, t, 7);
				sources.add(src);
				writeNal(out, enc.encode(src, false).nal);
				recon.add(enc.reconstruction());
			}
		}

		File yuv = new File(dir, "out.yuv");
		Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", stream.getPath(), "-f", "rawvideo", "-pix_fmt", "yuv420p", yuv.getPath())
			.redirectErrorStream(true).start();
		String log = new String(readAll(p.getInputStream()));
		assertTrue(p.waitFor(60, TimeUnit.SECONDS));
		assertEquals("ffmpeg errors: " + log, "", log.trim());

		byte[] decoded = Files.readAllBytes(yuv.toPath());
		int frameSize = w * h * 3 / 2;
		assertEquals(frames * frameSize, decoded.length);
		double mse = 0;
		for (int f = 0; f < frames; f++)
		{
			int[][] planes = recon.get(f);
			int o = f * frameSize;
			for (int[] plane : planes)
			{
				for (int i = 0; i < plane.length; i++)
				{
					int d = decoded[o + i] & 0xFF;
					if (d != plane[i])
					{
						throw new AssertionError(w + "x" + h + " frame " + f + " plane offset " + (o - f * frameSize) + " index " + i + ": decoder " + d + " encoder " + plane[i]);
					}
				}
				o += plane.length;
			}
			int[] src = sources.get(f);
			for (int i = 0; i < w * h; i++)
			{
				int c = src[i];
				int y = ((11966 * ((c >> 16) & 0xFF) + 40254 * ((c >> 8) & 0xFF) + 4064 * (c & 0xFF) + 32768) >> 16) + 16;
				double d = y - planes[0][i];
				mse += d * d;
			}
		}
		mse /= (double) frames * w * h;
		double psnr = 10 * Math.log10(255 * 255 / Math.max(mse, 1e-9));
		System.out.printf("%dx%d qp %d: %d bytes for %d frames, luma PSNR %.2f dB%n", w, h, qp, stream.length(), frames, psnr);
		assertTrue("PSNR " + psnr, psnr > (qp <= 26 ? 34 : 26));
	}

	static void writeNal(OutputStream out, byte[] nal) throws IOException
	{
		out.write(new byte[]{0, 0, 0, 1});
		out.write(nal);
	}

	static byte[] readAll(InputStream in) throws IOException
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
		{
			b.write(buf, 0, n);
		}
		return b.toByteArray();
	}
}
