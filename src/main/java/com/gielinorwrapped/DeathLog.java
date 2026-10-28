package com.gielinorwrapped;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Every death of an account, kept for good under {@code accounts/<hash>/deaths}: an index ({@code deaths.json})
 * plus the share card, screenshot and replay the Death Recap plugin sends with each death. Deaths seen by this
 * plugin and deaths sent by Death Recap are merged into one entry when they are close together. All methods do
 * file I/O; keep them off the client thread.
 */
@Slf4j
final class DeathLog
{
	/** Death Recap stamps a death a little after the death animation starts; entries this close are the same death. */
	static final long MERGE_MS = 20_000;
	static final String NAMESPACE = "death-recap";
	private static final String INDEX = "deaths.json";

	static final class Entry
	{
		/** When the death happened, epoch ms. */
		long time;
		/** Death Recap's timestamp for this death, or 0 if Death Recap didn't send it. */
		long recapTime;
		int world;
		String killer;
		String place;
		int damage = -1;
		long gpLost = -1;
		String card;
		String shot;
		String replay;
		/** Replay frame times in ms relative to {@link #recapTime}. */
		int[] replayTimes;

		boolean fromRecap()
		{
			return recapTime != 0;
		}
	}

	/** One death sent by Death Recap, read from its plugin message. */
	static final class Recap
	{
		long account = -1;
		long time;
		int world;
		String killer;
		String place;
		int damage = -1;
		long gpLost = -1;
		byte[] card;
		byte[] shot;
		byte[] replay;
		int[] replayTimes;

		/** Copies the message data, or returns null if it isn't a usable death. */
		static Recap from(Map<String, Object> data)
		{
			if (data == null)
			{
				return null;
			}
			Recap r = new Recap();
			r.account = number(data.get("accountHash"), -1);
			r.time = number(data.get("timestamp"), 0);
			if (r.account == -1 || r.time <= 0)
			{
				return null;
			}
			r.world = (int) number(data.get("world"), 0);
			r.killer = text(data.get("killer"));
			r.place = text(data.get("place"));
			r.damage = (int) number(data.get("damage"), -1);
			r.gpLost = number(data.get("gpLost"), -1);
			r.card = bytes(data.get("card"));
			r.shot = bytes(data.get("screenshot"));
			Object times = data.get("replayTimes");
			r.replay = bytes(data.get("replay"));
			if (r.replay != null && times instanceof int[])
			{
				r.replayTimes = ((int[]) times).clone();
			}
			else
			{
				r.replay = null;
			}
			return r;
		}

		private static long number(Object v, long fallback)
		{
			return v instanceof Number ? ((Number) v).longValue() : fallback;
		}

		private static String text(Object v)
		{
			return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
		}

		private static byte[] bytes(Object v)
		{
			return v instanceof byte[] && ((byte[]) v).length > 0 ? ((byte[]) v).clone() : null;
		}
	}

	private final StatsStore store;

	DeathLog(StatsStore store)
	{
		this.store = store;
	}

	private Filepath dir(long account)
	{
		return store.accountDir(account).join("deaths");
	}

	/** Every death, newest first. */
	synchronized List<Entry> list(long account)
	{
		Filepath file = dir(account).join(INDEX);
		if (!file.exists())
		{
			return new ArrayList<>();
		}
		try (Reader reader = file.openReader())
		{
			List<Entry> list = store.gson().fromJson(reader, new TypeToken<List<Entry>>()
			{
			}.getType());
			if (list == null)
			{
				return new ArrayList<>();
			}
			list.removeIf(e -> e == null || e.time <= 0);
			list.sort((a, b) -> Long.compare(b.time, a.time));
			return list;
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("Unable to read {}", file, e);
			return new ArrayList<>();
		}
	}

	/** Deaths from {@code from} to {@code until} (epoch ms, inclusive), oldest first. */
	List<Entry> range(long account, long from, long until)
	{
		List<Entry> out = new ArrayList<>();
		for (Entry e : list(account))
		{
			if (e.time >= from && e.time <= until)
			{
				out.add(0, e);
			}
		}
		return out;
	}

	/** Death Recap timestamps already logged, so it only sends the rest. */
	long[] recapTimes(long account)
	{
		return list(account).stream().filter(Entry::fromRecap).mapToLong(e -> e.recapTime).toArray();
	}

	/** Logs a death this plugin saw. Returns the entry, which may be one Death Recap already sent. */
	synchronized Entry died(long account, long time, int world) throws IOException
	{
		List<Entry> list = list(account);
		Entry e = nearest(list, time, false);
		if (e == null)
		{
			e = new Entry();
			e.time = time;
			e.world = world;
			list.add(e);
			save(account, list);
		}
		return e;
	}

	/** Logs or updates a death Death Recap sent; it sends each death again when its screenshot and replay are saved. */
	synchronized Entry recap(Recap r, boolean keepReplay) throws IOException
	{
		List<Entry> list = list(r.account);
		Entry e = null;
		for (Entry x : list)
		{
			if (x.recapTime == r.time)
			{
				e = x;
				break;
			}
		}
		if (e == null)
		{
			e = nearest(list, r.time, true);
		}
		if (e == null)
		{
			e = new Entry();
			e.time = r.time;
			list.add(e);
		}
		e.recapTime = r.time;
		if (r.world != 0)
		{
			e.world = r.world;
		}
		e.killer = r.killer != null ? r.killer : e.killer;
		e.place = r.place != null ? r.place : e.place;
		e.damage = r.damage >= 0 ? r.damage : e.damage;
		e.gpLost = r.gpLost >= 0 ? r.gpLost : e.gpLost;
		Filepath dir = dir(r.account);
		dir.createDirectories();
		if (r.card != null)
		{
			e.card = write(dir, r.time + "-card.png", r.card);
		}
		if (r.shot != null)
		{
			e.shot = write(dir, r.time + "-shot.png", r.shot);
		}
		if (r.replay != null && keepReplay)
		{
			e.replay = write(dir, r.time + "-replay.gif", r.replay);
			e.replayTimes = r.replayTimes;
		}
		save(r.account, list);
		return e;
	}

	/** One of an entry's files, or null if it's missing. */
	byte[] read(long account, String name)
	{
		if (!safe(name))
		{
			return null;
		}
		Filepath file = dir(account).join(name);
		if (!file.exists())
		{
			return null;
		}
		try (InputStream in = file.openInputStream())
		{
			return in.readAllBytes();
		}
		catch (IOException e)
		{
			log.debug("Unable to read {}", file, e);
			return null;
		}
	}

	/** The entry closest to {@code time} within {@link #MERGE_MS}; with {@code onlyNative}, only one Death Recap hasn't sent. */
	private static Entry nearest(List<Entry> list, long time, boolean onlyNative)
	{
		Entry best = null;
		for (Entry e : list)
		{
			long gap = Math.abs(e.time - time);
			if ((!onlyNative || !e.fromRecap()) && gap <= MERGE_MS && (best == null || gap < Math.abs(best.time - time)))
			{
				best = e;
			}
		}
		return best;
	}

	private void save(long account, List<Entry> list) throws IOException
	{
		list.sort((a, b) -> Long.compare(b.time, a.time));
		Filepath dir = dir(account);
		dir.createDirectories();
		Filepath tmp = dir.join(INDEX + ".tmp");
		tmp.write(store.gson().toJson(list));
		tmp.moveTo(dir.join(INDEX), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static String write(Filepath dir, String name, byte[] data) throws IOException
	{
		Filepath file = dir.join(name);
		if (file.exists())
		{
			try (InputStream in = file.openInputStream())
			{
				if (Arrays.equals(in.readAllBytes(), data))
				{
					return name;
				}
			}
		}
		Filepath tmp = dir.join(name + ".tmp");
		try (OutputStream out = tmp.openOutputStream())
		{
			out.write(data);
		}
		tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
		return name;
	}

	private static boolean safe(String name)
	{
		return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.startsWith(".");
	}
}
