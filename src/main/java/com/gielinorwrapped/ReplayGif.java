package com.gielinorwrapped;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * Turns a Death Recap replay GIF into clip frames. Its frames only hold what changed since the one before, so
 * each is drawn onto a full canvas first.
 */
final class ReplayGif
{
	private static final String GIF_FORMAT = "javax_imageio_gif_image_1.0";
	private static final int MAX_FRAMES = 400;
	static final float QUALITY = 0.85f;

	private ReplayGif()
	{
	}

	/**
	 * The replay as clip frames timed {@code start + times[i]}. Frames without a time are dropped, and frames are
	 * scaled down to at most {@code maxWidth}. Returns an empty list if the GIF can't be read.
	 */
	static List<ClipFile.Frame> frames(byte[] gif, long start, int[] times, int maxWidth)
	{
		List<ClipFile.Frame> out = new ArrayList<>();
		if (gif == null || times == null || times.length == 0)
		{
			return out;
		}
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif)))
		{
			Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
			if (readers == null || !readers.hasNext())
			{
				return out;
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(in, false);
				int width = -1;
				int height = -1;
				IIOMetadata stream = reader.getStreamMetadata();
				if (stream != null)
				{
					Node screen = child(stream.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
					if (screen != null)
					{
						width = attr(screen, "logicalScreenWidth", -1);
						height = attr(screen, "logicalScreenHeight", -1);
					}
				}
				BufferedImage canvas = null;
				long last = Long.MIN_VALUE;
				for (int i = 0; i < Math.min(times.length, MAX_FRAMES); i++)
				{
					BufferedImage part;
					try
					{
						part = reader.read(i);
					}
					catch (IndexOutOfBoundsException e)
					{
						break;
					}
					IIOMetadataNode meta = (IIOMetadataNode) reader.getImageMetadata(i).getAsTree(GIF_FORMAT);
					Node desc = child(meta, "ImageDescriptor");
					Node control = child(meta, "GraphicControlExtension");
					int x = desc == null ? 0 : attr(desc, "imageLeftPosition", 0);
					int y = desc == null ? 0 : attr(desc, "imageTopPosition", 0);
					String disposal = control == null ? "none" : ((IIOMetadataNode) control).getAttribute("disposalMethod");
					if (canvas == null)
					{
						canvas = new BufferedImage(width > 0 ? width : part.getWidth() + x, height > 0 ? height : part.getHeight() + y, BufferedImage.TYPE_INT_RGB);
					}
					BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas, canvas.getWidth()) : null;
					Graphics2D g = canvas.createGraphics();
					g.drawImage(part, x, y, null);
					g.dispose();
					long time = start + times[i];
					if (time > last)
					{
						out.add(new ClipFile.Frame(time, ClipFile.encode(copy(canvas, maxWidth), QUALITY)));
						last = time;
					}
					if ("restoreToBackgroundColor".equals(disposal))
					{
						Graphics2D c = canvas.createGraphics();
						c.setColor(java.awt.Color.BLACK);
						c.fillRect(x, y, part.getWidth(), part.getHeight());
						c.dispose();
					}
					else if (before != null)
					{
						canvas = before;
					}
				}
			}
			finally
			{
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException e)
		{
			// A damaged replay just means no replay clip.
			out.clear();
		}
		return out;
	}

	/** An RGB copy, scaled down to at most {@code maxWidth}. */
	private static BufferedImage copy(BufferedImage img, int maxWidth)
	{
		double scale = Math.min(1, maxWidth / (double) img.getWidth());
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static Node child(Node parent, String name)
	{
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if (name.equals(n.getNodeName()))
			{
				return n;
			}
		}
		return null;
	}

	private static int attr(Node node, String name, int fallback)
	{
		Node a = node.getAttributes().getNamedItem(name);
		if (a == null)
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(a.getNodeValue());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}
}
