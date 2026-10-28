package com.gielinorwrapped;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.w3c.dom.NodeList;

/**
 * A recorded clip: JPEG frames, each with its capture time. The file is the magic {@code GWC1} followed
 * by records of (long time ms, int length, JPEG bytes) up to the end of the file, so timelapses can be
 * appended to frame by frame. A truncated last record (a crash mid-write) is ignored.
 */
final class ClipFile
{
	private static final int MAGIC = 0x47574331;
	private static final int MAX_FRAME = 16 * 1024 * 1024;
	private static final String JPEG_FORMAT = "javax_imageio_jpeg_image_1.0";

	static final class Frame
	{
		final long time;
		final byte[] jpeg;

		Frame(long time, byte[] jpeg)
		{
			this.time = time;
			this.jpeg = jpeg;
		}

		BufferedImage image() throws IOException
		{
			return decode(jpeg);
		}
	}

	private ClipFile()
	{
	}

	static void writeHeader(OutputStream out) throws IOException
	{
		new DataOutputStream(out).writeInt(MAGIC);
	}

	static void writeFrame(OutputStream out, Frame frame) throws IOException
	{
		DataOutputStream data = new DataOutputStream(out);
		data.writeLong(frame.time);
		data.writeInt(frame.jpeg.length);
		data.write(frame.jpeg);
		data.flush();
	}

	static void write(OutputStream out, List<Frame> frames) throws IOException
	{
		writeHeader(out);
		for (Frame f : frames)
		{
			writeFrame(out, f);
		}
	}

	static List<Frame> read(InputStream in) throws IOException
	{
		List<Frame> frames = new ArrayList<>();
		read(in, 0, i -> true, frames);
		return frames;
	}

	/**
	 * Reads the frames {@code keep} accepts, skipping the image data of the rest.
	 *
	 * @param index index of the file's first frame, so selections can span several files
	 * @return the index after the file's last frame
	 */
	static int read(InputStream in, int index, IntPredicate keep, List<Frame> out) throws IOException
	{
		DataInputStream data = new DataInputStream(in);
		if (data.readInt() != MAGIC)
		{
			throw new IOException("Not a Gielinor Wrapped clip");
		}
		while (true)
		{
			long time;
			try
			{
				time = data.readLong();
			}
			catch (EOFException e)
			{
				break;
			}
			try
			{
				int length = data.readInt();
				if (length <= 0 || length > MAX_FRAME)
				{
					throw new IOException("Corrupt clip frame");
				}
				if (keep.test(index))
				{
					byte[] jpeg = new byte[length];
					data.readFully(jpeg);
					out.add(new Frame(time, jpeg));
				}
				else
				{
					skipFully(data, length);
				}
				index++;
			}
			catch (EOFException e)
			{
				break;
			}
		}
		return index;
	}

	/** Reads the frames captured between {@code from} and {@code to} inclusive, plus the one before {@code from} so playback can start on it. */
	static List<Frame> readRange(InputStream in, long from, long to) throws IOException
	{
		DataInputStream data = new DataInputStream(in);
		if (data.readInt() != MAGIC)
		{
			throw new IOException("Not a Gielinor Wrapped clip");
		}
		List<Frame> out = new ArrayList<>();
		Frame before = null;
		while (true)
		{
			try
			{
				long time = data.readLong();
				int length = data.readInt();
				if (length <= 0 || length > MAX_FRAME)
				{
					throw new IOException("Corrupt clip frame");
				}
				if (time > to)
				{
					break;
				}
				byte[] jpeg = new byte[length];
				data.readFully(jpeg);
				if (time < from)
				{
					before = new Frame(time, jpeg);
				}
				else
				{
					if (before != null && out.isEmpty())
					{
						out.add(before);
					}
					out.add(new Frame(time, jpeg));
				}
			}
			catch (EOFException e)
			{
				break;
			}
		}
		if (out.isEmpty() && before != null)
		{
			out.add(before);
		}
		return out;
	}

	/** The first and last capture times without decoding anything, or null when there are no whole frames. */
	static long[] span(InputStream in) throws IOException
	{
		DataInputStream data = new DataInputStream(in);
		if (data.readInt() != MAGIC)
		{
			throw new IOException("Not a Gielinor Wrapped clip");
		}
		long first = -1;
		long last = -1;
		while (true)
		{
			try
			{
				long time = data.readLong();
				int length = data.readInt();
				if (length <= 0 || length > MAX_FRAME)
				{
					break;
				}
				skipFully(data, length);
				if (first < 0)
				{
					first = time;
				}
				last = time;
			}
			catch (EOFException e)
			{
				break;
			}
		}
		return first < 0 ? null : new long[]{first, last};
	}

	private static void skipFully(DataInputStream in, int n) throws IOException
	{
		while (n > 0)
		{
			int skipped = in.skipBytes(n);
			if (skipped <= 0)
			{
				// skipBytes gives up at the end of the stream; read() tells EOF apart from a slow source
				if (in.read() < 0)
				{
					throw new EOFException();
				}
				skipped = 1;
			}
			n -= skipped;
		}
	}

	/** JPEG without chroma subsampling, so coloured game text stays readable. */
	static byte[] encode(BufferedImage image, float quality) throws IOException
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(128 * 1024);
		// In memory: ImageIO would otherwise buffer every frame through a temporary file.
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
		{
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);

			IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
			IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(JPEG_FORMAT);
			NodeList components = root.getElementsByTagName("componentSpec");
			for (int i = 0; i < components.getLength(); i++)
			{
				IIOMetadataNode c = (IIOMetadataNode) components.item(i);
				c.setAttribute("HsamplingFactor", "1");
				c.setAttribute("VsamplingFactor", "1");
			}
			meta.setFromTree(JPEG_FORMAT, root);
			writer.write(null, new IIOImage(image, null, meta), param);
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	static BufferedImage decode(byte[] jpeg) throws IOException
	{
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		if (image == null)
		{
			throw new IOException("Unreadable clip frame");
		}
		return image;
	}
}
