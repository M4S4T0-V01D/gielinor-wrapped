package com.gielinorwrapped;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * A picture slapped onto a scene: a boss cut out of the player's footage, a game sprite or the player's
 * own PNG, JPG or GIF. Animated GIFs keep playing.
 */
final class Sticker
{
	enum Kind
	{
		/** An NPC cut out of the player's own footage. */
		NPC,
		/** An item or skill sprite. */
		GAME,
		/** A file from the stickers folder. */
		MINE
	}

	static final int MAX_SIDE = 512;
	static final int MAX_FRAMES = 120;
	private static final String GIF_FORMAT = "javax_imageio_gif_image_1.0";

	final String name;
	final Kind kind;
	final List<BufferedImage> frames;
	/** Milliseconds each frame shows. */
	final int[] delays;
	private final int total;

	Sticker(String name, Kind kind, List<BufferedImage> frames, int[] delays)
	{
		this.name = name;
		this.kind = kind;
		this.frames = frames;
		this.delays = delays;
		int t = 0;
		for (int d : delays)
		{
			t += d;
		}
		total = Math.max(1, t);
	}

	static Sticker still(String name, Kind kind, BufferedImage img)
	{
		return new Sticker(name, kind, Collections.singletonList(img), new int[]{100});
	}

	boolean animated()
	{
		return frames.size() > 1;
	}

	BufferedImage frameAt(double seconds)
	{
		if (frames.size() == 1)
		{
			return frames.get(0);
		}
		long ms = Math.floorMod(Math.round(seconds * 1000), (long) total);
		for (int i = 0; i < delays.length; i++)
		{
			ms -= delays[i];
			if (ms < 0)
			{
				return frames.get(i);
			}
		}
		return frames.get(frames.size() - 1);
	}

	/** Reads a PNG, JPG or GIF, or returns null if it isn't an image. Pictures are shrunk to {@link #MAX_SIDE}. */
	static Sticker read(String name, Kind kind, byte[] data) throws IOException
	{
		boolean gif = data.length > 3 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
		if (gif)
		{
			Sticker s = readGif(name, kind, data);
			if (s != null)
			{
				return s;
			}
		}
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
		if (img == null)
		{
			return null;
		}
		return still(name, kind, shrink(argb(img)));
	}

	private static Sticker readGif(String name, Kind kind, byte[] data) throws IOException
	{
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data)))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext())
			{
				return null;
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
				List<BufferedImage> frames = new ArrayList<>();
				List<Integer> delays = new ArrayList<>();
				BufferedImage canvas = null;
				for (int i = 0; i < MAX_FRAMES; i++)
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
					int delay = control == null ? 10 : attr(control, "delayTime", 10);
					String disposal = control == null ? "none" : ((IIOMetadataNode) control).getAttribute("disposalMethod");
					if (canvas == null)
					{
						canvas = new BufferedImage(width > 0 ? width : part.getWidth() + x, height > 0 ? height : part.getHeight() + y, BufferedImage.TYPE_INT_ARGB);
					}
					BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
					Graphics2D g = canvas.createGraphics();
					g.drawImage(part, x, y, null);
					g.dispose();
					frames.add(shrink(copy(canvas)));
					// Browsers treat tiny delays as 100 ms.
					delays.add(delay <= 1 ? 100 : delay * 10);
					if ("restoreToBackgroundColor".equals(disposal))
					{
						Graphics2D c = canvas.createGraphics();
						c.setComposite(java.awt.AlphaComposite.Clear);
						c.fillRect(x, y, part.getWidth(), part.getHeight());
						c.dispose();
					}
					else if (before != null)
					{
						canvas = before;
					}
				}
				if (frames.isEmpty())
				{
					return null;
				}
				int[] d = new int[delays.size()];
				for (int i = 0; i < d.length; i++)
				{
					d[i] = delays.get(i);
				}
				return new Sticker(name, kind, frames, d);
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	private static Node child(Node node, String name)
	{
		for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling())
		{
			if (name.equals(c.getNodeName()))
			{
				return c;
			}
		}
		return null;
	}

	private static int attr(Node node, String name, int fallback)
	{
		try
		{
			return Integer.parseInt(((IIOMetadataNode) node).getAttribute(name));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	private static BufferedImage copy(BufferedImage img)
	{
		BufferedImage c = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = c.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return c;
	}

	static BufferedImage argb(BufferedImage img)
	{
		return img.getType() == BufferedImage.TYPE_INT_ARGB ? img : copy(img);
	}

	private static BufferedImage shrink(BufferedImage img)
	{
		int side = Math.max(img.getWidth(), img.getHeight());
		if (side <= MAX_SIDE)
		{
			return img;
		}
		return ShareCard.scale(img, img.getWidth() * MAX_SIDE / side);
	}

	/** Draws the sticker centred on (cx, cy), {@code size} pixels on its longer side, with a white outline like a real sticker. */
	void draw(Graphics2D g, double cx, double cy, double size, double angle, double t, double alpha)
	{
		BufferedImage img = frameAt(t);
		double scale = size / Math.max(img.getWidth(), img.getHeight());
		if (scale <= 0 || alpha <= 0)
		{
			return;
		}
		java.awt.Composite old = g.getComposite();
		if (alpha < 1)
		{
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (float) alpha));
		}
		BufferedImage outlined = kind == Kind.GAME ? img : outline(img);
		Sprites.drawCentered(g, outlined, cx, cy, scale, angle);
		g.setComposite(old);
	}

	private BufferedImage lastOutlineSource;
	private BufferedImage lastOutline;

	/** A white border around the opaque part, cached for the frame last drawn. */
	private BufferedImage outline(BufferedImage img)
	{
		if (img == lastOutlineSource)
		{
			return lastOutline;
		}
		boolean opaque = true;
		int w = img.getWidth();
		int h = img.getHeight();
		int[] px = img.getRGB(0, 0, w, h, null, 0, w);
		for (int p : px)
		{
			if ((p >>> 24) < 250)
			{
				opaque = false;
				break;
			}
		}
		BufferedImage out;
		if (opaque)
		{
			// Rectangular photo: a plain white frame.
			int b = Math.max(2, Math.max(w, h) / 40);
			out = new BufferedImage(w + b * 2, h + b * 2, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, out.getWidth(), out.getHeight());
			g.drawImage(img, b, b, null);
			g.dispose();
		}
		else
		{
			out = Sprites.outline(img, Color.WHITE, Math.max(2, Math.max(w, h) / 40));
		}
		lastOutlineSource = img;
		lastOutline = out;
		return out;
	}
}
