package com.gielinorwrapped.video;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Writes a looping animated GIF with the JDK's own encoder, one frame at a time. After the first
 * frame only the area that changed is written, which keeps still scenes small.
 */
public final class GifWriter implements Closeable
{
	private static final String FORMAT = "javax_imageio_gif_image_1.0";

	private final ImageWriter writer;
	private final ImageOutputStream ios;
	private final int width;
	private final int height;
	private int[] previous;
	private int frames;

	public GifWriter(OutputStream out, int width, int height) throws IOException
	{
		this.width = width;
		this.height = height;
		writer = ImageIO.getImageWritersByFormatName("gif").next();
		ios = ImageIO.createImageOutputStream(out);
		writer.setOutput(ios);
		writer.prepareWriteSequence(null);
	}

	/**
	 * @param argb {@code width * height} pixels; the array may be reused by the caller afterwards
	 * @param delay display time in hundredths of a second
	 */
	public void add(int[] argb, int delay) throws IOException
	{
		Rectangle area = previous == null ? new Rectangle(0, 0, width, height) : changed(previous, argb, width, height);
		if (previous == null)
		{
			previous = new int[argb.length];
		}
		System.arraycopy(argb, 0, previous, 0, argb.length);

		BufferedImage part = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB);
		part.setRGB(0, 0, area.width, area.height, argb, area.y * width + area.x, width);
		IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(part), null);
		IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(FORMAT);

		IIOMetadataNode descriptor = child(root, "ImageDescriptor");
		descriptor.setAttribute("imageLeftPosition", Integer.toString(area.x));
		descriptor.setAttribute("imageTopPosition", Integer.toString(area.y));
		descriptor.setAttribute("imageWidth", Integer.toString(area.width));
		descriptor.setAttribute("imageHeight", Integer.toString(area.height));
		descriptor.setAttribute("interlaceFlag", "FALSE");

		IIOMetadataNode gce = child(root, "GraphicControlExtension");
		gce.setAttribute("disposalMethod", "doNotDispose");
		gce.setAttribute("userInputFlag", "FALSE");
		gce.setAttribute("transparentColorFlag", "FALSE");
		gce.setAttribute("transparentColorIndex", "0");
		gce.setAttribute("delayTime", Integer.toString(delay));

		if (frames == 0)
		{
			// NETSCAPE2.0 extension with a loop count of 0: loop forever.
			IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[]{1, 0, 0});
			child(root, "ApplicationExtensions").appendChild(app);
		}

		meta.setFromTree(FORMAT, root);
		writer.writeToSequence(new IIOImage(part, null, meta), null);
		frames++;
	}

	/** Ends the sequence. Call once all frames are added. */
	public void finish() throws IOException
	{
		writer.endWriteSequence();
		ios.flush();
	}

	@Override
	public void close() throws IOException
	{
		try
		{
			ios.close();
		}
		finally
		{
			writer.dispose();
		}
	}

	/** The smallest rectangle holding every pixel that differs, or a single pixel if nothing changed. */
	static Rectangle changed(int[] a, int[] b, int width, int height)
	{
		int minX = width;
		int minY = height;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < height; y++)
		{
			int row = y * width;
			for (int x = 0; x < width; x++)
			{
				if (a[row + x] != b[row + x])
				{
					if (x < minX)
					{
						minX = x;
					}
					if (x > maxX)
					{
						maxX = x;
					}
					if (y < minY)
					{
						minY = y;
					}
					maxY = y;
				}
			}
		}
		if (maxX < 0)
		{
			return new Rectangle(0, 0, 1, 1);
		}
		return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name)
	{
		for (int i = 0; i < root.getLength(); i++)
		{
			if (root.item(i).getNodeName().equals(name))
			{
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode node = new IIOMetadataNode(name);
		root.appendChild(node);
		return node;
	}
}
