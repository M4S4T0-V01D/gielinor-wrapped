package com.gielinorwrapped.video;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/** Muxes encoded frames, then checks ffprobe's view of the file and that ffmpeg decodes it bit-exactly. */
public class Mp4WriterTest
{
	@Test
	public void muxesPlayableMp4() throws Exception
	{
		assumeTrue(H264EncoderTest.haveFfmpeg());
		int w = 160;
		int h = 90;
		int frames = 45;
		H264Encoder enc = new H264Encoder(w, h, 30);
		enc.setQp(28);
		enc.setKeyInterval(20);
		Mp4Writer mp4 = new Mp4Writer(w, h, 30, enc.sps(), enc.pps());
		File dir = Files.createTempDirectory("gw-mp4").toFile();
		File media = new File(dir, "media.tmp");
		File out = new File(dir, "out.mp4");
		List<int[][]> recon = new ArrayList<>();
		try (OutputStream m = new FileOutputStream(media))
		{
			for (int t = 0; t < frames; t++)
			{
				mp4.addFrame(enc.encode(H264EncoderTest.scene(w, h, t, 3), false), m);
				recon.add(enc.reconstruction());
			}
		}
		try (OutputStream o = new FileOutputStream(out); InputStream m = new FileInputStream(media))
		{
			mp4.finish(o, m);
		}

		String probe = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
			"-show_entries", "stream=codec_name,width,height,r_frame_rate,nb_read_frames,duration",
			"-of", "default=noprint_wrappers=1", out.getPath());
		assertTrue(probe, probe.contains("codec_name=h264"));
		assertTrue(probe, probe.contains("width=" + w));
		assertTrue(probe, probe.contains("height=" + h));
		assertTrue(probe, probe.contains("r_frame_rate=30/1"));
		assertTrue(probe, probe.contains("nb_read_frames=" + frames));
		assertTrue(probe, probe.contains("duration=1.500000"));

		File yuv = new File(dir, "out.yuv");
		assertEquals("", run("ffmpeg", "-v", "error", "-y", "-i", out.getPath(), "-f", "rawvideo", "-pix_fmt", "yuv420p", yuv.getPath()).trim());
		byte[] decoded = Files.readAllBytes(yuv.toPath());
		int frameSize = w * h * 3 / 2;
		assertEquals(frames * frameSize, decoded.length);
		for (int f = 0; f < frames; f++)
		{
			int o = f * frameSize;
			for (int[] plane : recon.get(f))
			{
				for (int i = 0; i < plane.length; i++)
				{
					assertEquals("frame " + f, plane[i], decoded[o + i] & 0xFF);
				}
				o += plane.length;
			}
		}
		System.out.printf("mp4 %dx%d: %d bytes, %d frames%n", w, h, out.length(), frames);
	}

	private static String run(String... cmd) throws Exception
	{
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String log = new String(H264EncoderTest.readAll(p.getInputStream()));
		assertTrue(p.waitFor(60, TimeUnit.SECONDS));
		return log;
	}
}
