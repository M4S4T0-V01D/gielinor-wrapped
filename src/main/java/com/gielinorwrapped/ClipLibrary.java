package com.gielinorwrapped;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Clips and timelapses on disk, under {@code accounts/<hash>/clips} and {@code accounts/<hash>/timelapse}.
 * A clip's file name is {@code <time>-<importance>.gwc}, so pruning needs no index. All methods do file
 * I/O and must not be called on the client thread.
 */
@Slf4j
class ClipLibrary
{
	static final String EXTENSION = ".gwc";
	private static final String BOARD = "sticker-board.json";
	/** Cutouts are small, so every monster fought fits; the player's own pictures can be big. */
	static final int MAX_NPC_STICKERS = 300;
	static final int MAX_MY_STICKERS = 64;

	private final StatsStore store;

	ClipLibrary(StatsStore store)
	{
		this.store = store;
	}

	private Filepath clipsDir(long account)
	{
		return store.accountDir(account).join("clips");
	}

	private Filepath timelapseDir(long account)
	{
		return store.accountDir(account).join("timelapse");
	}

	static String clipName(long time, int importance)
	{
		return time + "-" + importance + EXTENSION;
	}

	/** Writes a clip and returns its file name. */
	String writeClip(long account, long time, int importance, List<ClipFile.Frame> frames) throws IOException
	{
		Filepath dir = clipsDir(account);
		dir.createDirectories();
		String name = clipName(time, importance);
		Filepath tmp = dir.join(name + ".tmp");
		try (OutputStream out = tmp.openOutputStream())
		{
			ClipFile.write(out, frames);
		}
		tmp.moveTo(dir.join(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return name;
	}

	/** The clip's frames, or an empty list if it was pruned or can't be read. */
	List<ClipFile.Frame> readClip(long account, String name)
	{
		if (name == null || !name.endsWith(EXTENSION) || name.contains("/") || name.contains("\\"))
		{
			return Collections.emptyList();
		}
		return read(clipsDir(account).join(name));
	}

	void appendTimelapse(long account, LocalDate date, ClipFile.Frame frame) throws IOException
	{
		Filepath dir = timelapseDir(account);
		dir.createDirectories();
		Filepath file = dir.join(date + EXTENSION);
		boolean fresh = !file.exists();
		try (OutputStream out = file.openOutputStream(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			if (fresh)
			{
				ClipFile.writeHeader(out);
			}
			ClipFile.writeFrame(out, frame);
		}
	}

	/** At most {@code max} timelapse frames spread evenly over the days in range, oldest first. */
	List<ClipFile.Frame> readTimelapse(long account, LocalDate from, LocalDate to, int max)
	{
		List<Filepath> files = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
		{
			Filepath file = timelapseDir(account).join(d + EXTENSION);
			if (file.exists())
			{
				files.add(file);
			}
		}
		List<ClipFile.Frame> frames = new ArrayList<>();
		int count = readAll(files, i -> false, frames);
		if (count == 0 || max <= 0)
		{
			return frames;
		}
		readAll(files, i -> count <= max || (long) i * max / count != (long) (i + 1) * max / count, frames);
		return frames;
	}

	private static int readAll(List<Filepath> files, IntPredicate keep, List<ClipFile.Frame> out)
	{
		int index = 0;
		for (Filepath file : files)
		{
			try (InputStream in = file.openInputStream())
			{
				index = ClipFile.read(new BufferedInputStream(in), index, keep, out);
			}
			catch (IOException e)
			{
				log.warn("Unable to read {}", file, e);
			}
		}
		return index;
	}

	private static List<ClipFile.Frame> read(Filepath file)
	{
		if (!file.exists())
		{
			return Collections.emptyList();
		}
		try (InputStream in = file.openInputStream())
		{
			return ClipFile.read(new BufferedInputStream(in));
		}
		catch (IOException e)
		{
			log.warn("Unable to read {}", file, e);
			return Collections.emptyList();
		}
	}

	private Filepath footageDir(long account)
	{
		return store.accountDir(account).join("footage");
	}

	/** A finished footage recording, named {@code <start>-<lengthMs>.gwc}. */
	static final class Recording
	{
		final String name;
		/** Capture time of the first frame, epoch milliseconds. */
		final long start;
		final long length;
		final long size;

		Recording(String name, long start, long length, long size)
		{
			this.name = name;
			this.start = start;
			this.length = length;
			this.size = size;
		}

		long end()
		{
			return start + length;
		}

		static Recording parse(String name, long size)
		{
			if (name == null || !name.endsWith(EXTENSION))
			{
				return null;
			}
			String base = name.substring(0, name.length() - EXTENSION.length());
			int dash = base.indexOf('-');
			if (dash <= 0)
			{
				return null;
			}
			try
			{
				return new Recording(name, Long.parseLong(base.substring(0, dash)), Long.parseLong(base.substring(dash + 1)), size);
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
	}

	/** Footage being recorded: frames append to a {@code .part} file that becomes a recording on {@link #finish}. */
	final class FootageWriter implements AutoCloseable
	{
		private final Filepath dir;
		private final Filepath part;
		private final OutputStream out;
		private long first = -1;
		private long last = -1;
		private long bytes;

		private FootageWriter(long account, long start) throws IOException
		{
			dir = footageDir(account);
			dir.createDirectories();
			part = dir.join(start + ".part");
			out = new java.io.BufferedOutputStream(part.openOutputStream(), 1 << 16);
			ClipFile.writeHeader(out);
		}

		void add(ClipFile.Frame frame) throws IOException
		{
			ClipFile.writeFrame(out, frame);
			if (first < 0)
			{
				first = frame.time;
			}
			last = frame.time;
			bytes += frame.jpeg.length + 12;
		}

		long bytes()
		{
			return bytes;
		}

		/** Closes the file and returns the recording, or null when it caught nothing. */
		Recording finish() throws IOException
		{
			out.close();
			if (first < 0 || last <= first)
			{
				part.deleteIfExists();
				return null;
			}
			String name = first + "-" + (last - first) + EXTENSION;
			Filepath file = dir.join(name);
			part.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return new Recording(name, first, last - first, file.size());
		}

		@Override
		public void close() throws IOException
		{
			out.close();
		}
	}

	FootageWriter startFootage(long account, long start) throws IOException
	{
		return new FootageWriter(account, start);
	}

	/** Turns {@code .part} files left by a crash or a closed client into recordings. Call before recording starts. */
	void recoverFootage(long account)
	{
		Filepath dir = footageDir(account);
		if (!dir.exists())
		{
			return;
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			for (Filepath f : files.collect(Collectors.toList()))
			{
				String name = f.getFileName();
				if (name == null || !name.endsWith(".part"))
				{
					continue;
				}
				long[] span = null;
				try (InputStream in = new BufferedInputStream(f.openInputStream(), 1 << 16))
				{
					span = ClipFile.span(in);
				}
				catch (IOException e)
				{
					log.debug("Unreadable footage {}", f, e);
				}
				if (span == null || span[1] <= span[0])
				{
					f.deleteIfExists();
				}
				else
				{
					f.moveTo(dir.join(span[0] + "-" + (span[1] - span[0]) + EXTENSION), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to recover footage in {}", dir, e);
		}
	}

	/** Finished recordings, oldest first. */
	List<Recording> listRecordings(long account)
	{
		Filepath dir = footageDir(account);
		List<Recording> out = new ArrayList<>();
		if (!dir.exists())
		{
			return out;
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			for (Filepath f : files.collect(Collectors.toList()))
			{
				if (f.isFile())
				{
					Recording r = Recording.parse(f.getFileName(), f.size());
					if (r != null)
					{
						out.add(r);
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to list {}", dir, e);
		}
		out.sort((a, b) -> Long.compare(a.start, b.start));
		return out;
	}

	/** Frames of a recording captured between {@code from} and {@code to}, epoch milliseconds. */
	List<ClipFile.Frame> readRecording(long account, String name, long from, long to)
	{
		if (Recording.parse(name, 0) == null || name.contains("/") || name.contains("\\"))
		{
			return Collections.emptyList();
		}
		Filepath file = footageDir(account).join(name);
		if (!file.exists())
		{
			return Collections.emptyList();
		}
		try (InputStream in = new BufferedInputStream(file.openInputStream(), 1 << 16))
		{
			return ClipFile.readRange(in, from, to);
		}
		catch (IOException e)
		{
			log.warn("Unable to read {}", file, e);
			return Collections.emptyList();
		}
	}

	boolean deleteRecording(long account, String name)
	{
		if (Recording.parse(name, 0) == null || name.contains("/") || name.contains("\\"))
		{
			return false;
		}
		try
		{
			footageDir(account).join(name).deleteIfExists();
			return true;
		}
		catch (IOException e)
		{
			log.warn("Unable to delete {}", name, e);
			return false;
		}
	}

	/** Deletes the oldest recordings until the footage fits in {@code budgetBytes}; {@code keep} is never deleted. */
	long pruneFootage(long account, long budgetBytes, String keep)
	{
		List<Recording> all = listRecordings(account);
		long total = 0;
		for (Recording r : all)
		{
			total += r.size;
		}
		for (Recording r : all)
		{
			if (total <= budgetBytes)
			{
				break;
			}
			if (r.name.equals(keep))
			{
				continue;
			}
			if (deleteRecording(account, r.name))
			{
				total -= r.size;
			}
		}
		return total;
	}

	private Filepath emoteFile(long account)
	{
		return store.accountDir(account).join("emote" + EXTENSION);
	}

	void writeEmote(long account, List<ClipFile.Frame> frames) throws IOException
	{
		Filepath dir = store.accountDir(account);
		dir.createDirectories();
		Filepath tmp = dir.join("emote" + EXTENSION + ".tmp");
		try (OutputStream out = tmp.openOutputStream())
		{
			ClipFile.write(out, frames);
		}
		tmp.moveTo(emoteFile(account), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	List<ClipFile.Frame> readEmote(long account)
	{
		return read(emoteFile(account));
	}

	boolean hasEmote(long account)
	{
		return emoteFile(account).exists();
	}

	/** The player's own sticker files, shared by all accounts. */
	Filepath stickersDir()
	{
		return store.root().join("stickers");
	}

	/** Bosses and NPCs cut out of the footage, per account. */
	private Filepath npcDir(long account)
	{
		return store.accountDir(account).join("npcs");
	}

	/** Saves a cutout as {@code <name>.png}, replacing an older one of the same NPC. */
	void writeNpc(long account, String npc, byte[] png) throws IOException
	{
		Filepath dir = npcDir(account);
		dir.createDirectories();
		String file = safeName(npc) + ".png";
		Filepath tmp = dir.join(file + ".tmp");
		try (OutputStream out = tmp.openOutputStream())
		{
			out.write(png);
		}
		tmp.moveTo(dir.join(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Pixel count of the saved cutout of {@code npc}, read from its PNG header; 0 when there is none. */
	long npcArea(long account, String npc)
	{
		Filepath file = npcDir(account).join(safeName(npc) + ".png");
		if (!file.exists())
		{
			return 0;
		}
		byte[] head = new byte[24];
		try (InputStream in = file.openInputStream())
		{
			int n = 0;
			while (n < head.length)
			{
				int r = in.read(head, n, head.length - n);
				if (r < 0)
				{
					return 0;
				}
				n += r;
			}
		}
		catch (IOException e)
		{
			return 0;
		}
		return pngArea(head);
	}

	/** Width times height from the IHDR chunk that opens every PNG, or 0 if {@code head} isn't one. */
	static long pngArea(byte[] head)
	{
		if (head.length < 24 || (head[1] & 0xFF) != 'P' || (head[12] & 0xFF) != 'I' || (head[13] & 0xFF) != 'H')
		{
			return 0;
		}
		long w = ((head[16] & 0xFFL) << 24) | ((head[17] & 0xFF) << 16) | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
		long h = ((head[20] & 0xFFL) << 24) | ((head[21] & 0xFF) << 16) | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
		return w * h;
	}

	static String safeName(String name)
	{
		String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return s.isEmpty() ? "npc" : s.length() > 60 ? s.substring(0, 60) : s;
	}

	/** Removes the cutout of {@code npc}; fighting it again takes a new one. */
	void deleteNpc(long account, String npc) throws IOException
	{
		Filepath file = npcDir(account).join(safeName(npc) + ".png");
		if (file.exists())
		{
			file.delete();
		}
	}

	private Filepath boardFile(long account)
	{
		return store.accountDir(account).join(BOARD);
	}

	/** The account's sticker board, or an empty one when there is none or it can't be read. */
	StickerBoard readBoard(long account)
	{
		Filepath f = boardFile(account);
		if (f.exists())
		{
			try (Reader r = f.openReader())
			{
				StickerBoard b = store.gson().fromJson(r, StickerBoard.class);
				if (b != null)
				{
					return b.clean();
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Unable to read {}", f, e);
			}
		}
		return new StickerBoard();
	}

	void writeBoard(long account, StickerBoard board) throws IOException
	{
		Filepath dir = store.accountDir(account);
		dir.createDirectories();
		Filepath tmp = dir.join(BOARD + ".tmp");
		tmp.write(store.gson().toJson(board));
		tmp.moveTo(dir.join(BOARD), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** NPC cutouts, then the player's own stickers. Unreadable files are skipped. */
	List<Sticker> stickers(long account, boolean npcs, boolean mine)
	{
		List<Sticker> out = new ArrayList<>();
		if (npcs)
		{
			readStickers(npcDir(account), Sticker.Kind.NPC, MAX_NPC_STICKERS, out);
		}
		if (mine)
		{
			readStickers(stickersDir(), Sticker.Kind.MINE, MAX_MY_STICKERS, out);
		}
		return out;
	}

	private static void readStickers(Filepath dir, Sticker.Kind kind, int max, List<Sticker> out)
	{
		int start = out.size();
		if (!dir.exists())
		{
			return;
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			List<Filepath> list = files.filter(Filepath::isFile).collect(Collectors.toList());
			list.sort((a, b) -> String.valueOf(a.getFileName()).compareTo(String.valueOf(b.getFileName())));
			for (Filepath f : list)
			{
				String name = f.getFileName();
				String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
				if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")))
				{
					continue;
				}
				if (f.size() > 16L * 1024 * 1024 || out.size() - start >= max)
				{
					continue;
				}
				try (InputStream in = f.openInputStream())
				{
					byte[] data = readFully(in);
					String label = name.substring(0, name.lastIndexOf('.')).replace('_', ' ');
					Sticker s = Sticker.read(label, kind, data);
					if (s != null)
					{
						out.add(s);
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Skipping sticker {}", f, e);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to list {}", dir, e);
		}
	}

	private static byte[] readFully(InputStream in) throws IOException
	{
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		byte[] b = new byte[1 << 15];
		int n;
		while ((n = in.read(b)) > 0)
		{
			buf.write(b, 0, n);
		}
		return buf.toByteArray();
	}

	static final class Entry
	{
		final Filepath file;
		final long size;
		final double keep;

		Entry(Filepath file, long size, double keep)
		{
			this.file = file;
			this.size = size;
			this.keep = keep;
		}
	}

	/**
	 * Deletes clips and timelapse days until the account's media fits in {@code budgetBytes}. What goes
	 * first is the least important, oldest material: a clip scores its importance minus half a point per
	 * day of age, and a timelapse day scores 10 minus a point per day.
	 */
	long prune(long account, long budgetBytes, LocalDate today)
	{
		List<Entry> entries = new ArrayList<>();
		long total = 0;
		total += list(clipsDir(account), entries, today, true);
		total += list(timelapseDir(account), entries, today, false);
		if (total <= budgetBytes)
		{
			return total;
		}
		entries.sort((a, b) -> Double.compare(a.keep, b.keep));
		for (Entry e : entries)
		{
			if (total <= budgetBytes)
			{
				break;
			}
			try
			{
				e.file.deleteIfExists();
				total -= e.size;
				log.debug("Pruned {}", e.file);
			}
			catch (IOException ex)
			{
				log.warn("Unable to delete {}", e.file, ex);
			}
		}
		return total;
	}

	private static long list(Filepath dir, List<Entry> out, LocalDate today, boolean clips)
	{
		if (!dir.exists())
		{
			return 0;
		}
		long total = 0;
		try (Stream<Filepath> files = dir.walk(1))
		{
			for (Filepath f : files.collect(Collectors.toList()))
			{
				String name = f.getFileName();
				if (name == null || !f.isFile())
				{
					continue;
				}
				long size = f.size();
				if (name.endsWith(".tmp"))
				{
					// Left over from a crash.
					f.deleteIfExists();
					continue;
				}
				if (!name.endsWith(EXTENSION))
				{
					continue;
				}
				Double keep = clips ? clipScore(name, today) : timelapseScore(name, today);
				if (keep != null)
				{
					total += size;
					out.add(new Entry(f, size, keep));
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Unable to list {}", dir, e);
		}
		return total;
	}

	static Double clipScore(String name, LocalDate today)
	{
		String base = name.substring(0, name.length() - EXTENSION.length());
		int dash = base.indexOf('-');
		if (dash <= 0)
		{
			return null;
		}
		try
		{
			long time = Long.parseLong(base.substring(0, dash));
			int importance = Integer.parseInt(base.substring(dash + 1));
			long days = Math.max(0, ChronoUnit.DAYS.between(
				Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate(), today));
			return importance - days * 0.5;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static Double timelapseScore(String name, LocalDate today)
	{
		try
		{
			LocalDate d = LocalDate.parse(name.substring(0, name.length() - EXTENSION.length()));
			return 10.0 - Math.max(0, ChronoUnit.DAYS.between(d, today));
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}
}
