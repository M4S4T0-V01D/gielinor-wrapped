package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.Test;

public class ReplayGifTest
{
	private static final long START = 1_761_000_000_000L;

	/** A small animated GIF: a red square moving right over a blue background. */
	static byte[] gif(int frames) throws IOException
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes))
		{
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames; i++)
			{
				BufferedImage img = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = img.createGraphics();
				g.setColor(Color.BLUE);
				g.fillRect(0, 0, 80, 40);
				g.setColor(Color.RED);
				g.fillRect(i * 10, 10, 20, 20);
				g.dispose();
				writer.writeToSequence(new IIOImage(img, null, null), null);
			}
			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	@Test
	public void framesFollowReplayTimes() throws IOException
	{
		List<ClipFile.Frame> frames = ReplayGif.frames(gif(4), START, new int[]{-1800, -1200, -600, 0}, 40);
		assertEquals(4, frames.size());
		assertEquals(START - 1800, frames.get(0).time);
		assertEquals(START, frames.get(3).time);
		BufferedImage last = ImageIO.read(new ByteArrayInputStream(frames.get(3).jpeg));
		assertEquals("scaled to the clip width", 40, last.getWidth());
		Color c = new Color(last.getRGB(18, 10));
		assertTrue("the square moved: " + c, c.getRed() > 150 && c.getBlue() < 100);
	}

	@Test
	public void dropsFramesWithoutTimes() throws IOException
	{
		assertEquals(2, ReplayGif.frames(gif(4), START, new int[]{0, 600}, 80).size());
		assertEquals("times must go forward", 2, ReplayGif.frames(gif(3), START, new int[]{0, 0, 600}, 80).size());
	}

	@Test
	public void survivesBadData()
	{
		assertTrue(ReplayGif.frames(new byte[]{1, 2, 3}, START, new int[]{0}, 80).isEmpty());
		assertTrue(ReplayGif.frames(null, START, new int[]{0}, 80).isEmpty());
	}
}
