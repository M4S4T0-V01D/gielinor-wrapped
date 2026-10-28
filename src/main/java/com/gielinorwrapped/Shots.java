package com.gielinorwrapped;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.runelite.client.util.Filepath;

/**
 * Screenshots the player sets up in game for their recaps. Stored as {@code shots/shot-<time>-<role>.jpg};
 * the role lives in the name, so changing it is a rename. All methods do file I/O; keep them off the client thread.
 */
final class Shots
{
	static final int MAX_GALLERY = 8;
	private static final String PREFIX = "shot-";
	private static final String EXT = ".jpg";
	private static final float QUALITY = 0.92f;

	enum Role
	{
		COVER("Cover"),
		OUTRO("Outro"),
		GALLERY("Gallery");

		final String label;

		Role(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	static final class Shot
	{
		final long account;
		final Filepath file;
		final long time;
		final Role role;

		Shot(long account, Filepath file, long time, Role role)
		{
			this.account = account;
			this.file = file;
			this.time = time;
			this.role = role;
		}

		BufferedImage read() throws IOException
		{
			try (InputStream in = file.openInputStream())
			{
				return ImageIO.read(in);
			}
		}
	}

	/** The shots a recap uses. */
	static final class Pick
	{
		Shot cover;
		Shot outro;
		final List<Shot> gallery = new ArrayList<>();

		/** The cover, else the newest gallery shot. */
		Shot backdrop()
		{
			return cover != null ? cover : gallery.isEmpty() ? null : gallery.get(gallery.size() - 1);
		}
	}

	private final StatsStore store;

	Shots(StatsStore store)
	{
		this.store = store;
	}

	private Filepath dir(long account)
	{
		return store.accountDir(account).join("shots");
	}

	Shot save(long account, long time, Role role, BufferedImage image) throws IOException
	{
		Filepath dir = dir(account);
		dir.createDirectories();
		Filepath file = dir.join(name(time, role));
		Filepath tmp = dir.join(file.getFileName() + ".tmp");
		try (OutputStream out = tmp.openOutputStream())
		{
			out.write(ClipFile.encode(image, QUALITY));
		}
		tmp.moveTo(file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return new Shot(account, file, time, role);
	}

	/** Every shot, newest first. */
	List<Shot> list(long account) throws IOException
	{
		Filepath dir = dir(account);
		if (!dir.isDirectory())
		{
			return new ArrayList<>();
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			List<Shot> out = new ArrayList<>();
			for (Filepath f : files.collect(Collectors.toList()))
			{
				Shot s = parse(account, f);
				if (s != null)
				{
					out.add(s);
				}
			}
			out.sort(Comparator.comparingLong((Shot s) -> s.time).reversed());
			return out;
		}
	}

	Shot setRole(Shot shot, Role role) throws IOException
	{
		if (shot.role == role)
		{
			return shot;
		}
		Filepath target = dir(shot.account).join(name(shot.time, role));
		shot.file.moveTo(target);
		return new Shot(shot.account, target, shot.time, role);
	}

	void delete(Shot shot) throws IOException
	{
		shot.file.deleteIfExists();
	}

	/**
	 * Shots taken from {@code from} up to {@code until} (epoch ms): the newest cover and outro,
	 * and up to {@link #MAX_GALLERY} gallery shots, oldest first.
	 */
	static Pick pick(List<Shot> shots, long from, long until)
	{
		Pick p = new Pick();
		List<Shot> sorted = new ArrayList<>(shots);
		sorted.sort(Comparator.comparingLong(s -> s.time));
		for (Shot s : sorted)
		{
			if (s.time < from || s.time > until)
			{
				continue;
			}
			switch (s.role)
			{
				case COVER:
					p.cover = s;
					break;
				case OUTRO:
					p.outro = s;
					break;
				default:
					p.gallery.add(s);
			}
		}
		int n = p.gallery.size();
		if (n > MAX_GALLERY)
		{
			// Spread over the whole period rather than keeping only one end of it.
			List<Shot> kept = new ArrayList<>();
			for (int i = 0; i < MAX_GALLERY; i++)
			{
				kept.add(p.gallery.get((int) Math.round(i * (n - 1) / (double) (MAX_GALLERY - 1))));
			}
			p.gallery.clear();
			p.gallery.addAll(kept);
		}
		return p;
	}

	static String name(long time, Role role)
	{
		return PREFIX + time + "-" + role.name().toLowerCase(Locale.ROOT) + EXT;
	}

	static Shot parse(long account, Filepath f)
	{
		String n = f.getFileName();
		if (n == null || !n.startsWith(PREFIX) || !n.endsWith(EXT))
		{
			return null;
		}
		String[] parts = n.substring(PREFIX.length(), n.length() - EXT.length()).split("-");
		if (parts.length != 2)
		{
			return null;
		}
		try
		{
			return new Shot(account, f, Long.parseLong(parts[0]), Role.valueOf(parts[1].toUpperCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
