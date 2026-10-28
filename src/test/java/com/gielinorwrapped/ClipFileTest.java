package com.gielinorwrapped;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClipFileTest
{
	@Test
	public void roundTripAndTruncation() throws Exception
	{
		BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 36; y++)
		{
			for (int x = 0; x < 64; x++)
			{
				img.setRGB(x, y, (x * 4) << 16 | (y * 7) << 8 | 0x40);
			}
		}
		byte[] jpeg = ClipFile.encode(img, 0.85f);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ClipFile.write(out, Arrays.asList(new ClipFile.Frame(1000, jpeg), new ClipFile.Frame(1066, jpeg)));
		byte[] bytes = out.toByteArray();

		List<ClipFile.Frame> frames = ClipFile.read(new ByteArrayInputStream(bytes));
		assertEquals(2, frames.size());
		assertEquals(1066, frames.get(1).time);
		assertArrayEquals(jpeg, frames.get(0).jpeg);
		BufferedImage back = frames.get(0).image();
		assertEquals(64, back.getWidth());
		assertEquals(36, back.getHeight());

		// A crash mid-write leaves the complete frames readable.
		frames = ClipFile.read(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 10)));
		assertEquals(1, frames.size());
	}

	@Test
	public void pruneScores()
	{
		LocalDate today = LocalDate.of(2026, 10, 26);
		long now = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000;
		long weekAgo = now - 7 * 86_400_000L;
		assertEquals(100.0, ClipLibrary.clipScore(ClipLibrary.clipName(now, 100), today), 1e-9);
		assertEquals(46.5, ClipLibrary.clipScore(ClipLibrary.clipName(weekAgo, 50), today), 1e-9);
		assertEquals(8.0, ClipLibrary.timelapseScore("2026-10-24.gwc", today), 1e-9);
		assertNull(ClipLibrary.clipScore("junk.gwc", today));
		assertNull(ClipLibrary.timelapseScore("junk.gwc", today));
	}

	@Test
	public void pixelateHidesDetail()
	{
		BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 40; x++)
		{
			img.setRGB(x, 5, 0xFFFFFF);
		}
		Capture.pixelate(img, new Rectangle(0, 0, 20, 20));
		// Inside the area the line is averaged into its block; outside it is untouched.
		assertEquals(img.getRGB(3, 0), img.getRGB(3, 5));
		assertTrue((img.getRGB(3, 5) & 0xFF) < 0xFF);
		assertEquals(0xFFFFFF, img.getRGB(30, 5) & 0xFFFFFF);
	}

	@Test
	public void clipToggles()
	{
		GielinorWrappedConfig config = new GielinorWrappedConfig()
		{
			@Override
			public boolean clipDeaths()
			{
				return false;
			}
		};
		assertTrue(Capture.wanted(new Moment(0, Moment.Type.PET, "Pet", null, 0, 100), config));
		assertTrue(Capture.wanted(new Moment(0, Moment.Type.MANUAL, "Clip", null, 0, 45), config));
		assertTrue(!Capture.wanted(new Moment(0, Moment.Type.DEATH, "Dead", null, 0, 40), config));
		assertTrue(!Capture.wanted(new Moment(0, Moment.Type.CLUE, "Easy clue", null, 0, 20), config));
	}
}
