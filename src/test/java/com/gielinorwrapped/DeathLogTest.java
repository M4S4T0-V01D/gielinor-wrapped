package com.gielinorwrapped;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Test;

public class DeathLogTest
{
	private static final long ACCOUNT = 7;
	private static final long T0 = 1_761_000_000_000L;

	private DeathLog deaths;

	@Before
	public void setUp() throws IOException
	{
		Filepath data = new RecapsTest.DirPlugin().dir();
		data.createDirectories();
		deaths = new DeathLog(new StatsStore(new Gson(), data.createTempDir("deaths")));
	}

	private static Map<String, Object> message(long time)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("version", 1);
		m.put("accountHash", ACCOUNT);
		m.put("timestamp", time);
		m.put("world", 330);
		m.put("killer", "Zulrah");
		m.put("damage", 41);
		m.put("gpLost", 1_250_000L);
		m.put("place", "Zul-Andra");
		m.put("card", new byte[]{1, 2, 3});
		m.put("replay", new byte[]{4, 5});
		m.put("replayTimes", new int[]{-600, 0});
		return m;
	}

	@Test
	public void readsMessages()
	{
		DeathLog.Recap r = DeathLog.Recap.from(message(T0));
		assertNotNull(r);
		assertEquals(ACCOUNT, r.account);
		assertEquals("Zulrah", r.killer);
		assertEquals(1_250_000L, r.gpLost);
		assertArrayEquals(new int[]{-600, 0}, r.replayTimes);

		Map<String, Object> noAccount = message(T0);
		noAccount.remove("accountHash");
		assertNull(DeathLog.Recap.from(noAccount));
		Map<String, Object> badTime = message(T0);
		badTime.put("timestamp", "soon");
		assertNull(DeathLog.Recap.from(badTime));
		assertNull(DeathLog.Recap.from(null));

		Map<String, Object> noTimes = message(T0);
		noTimes.remove("replayTimes");
		assertNull("a replay without times is dropped", DeathLog.Recap.from(noTimes).replay);
	}

	@Test
	public void mergesNativeDeathWithRecap() throws IOException
	{
		deaths.died(ACCOUNT, T0, 330);
		deaths.recap(DeathLog.Recap.from(message(T0 + 3000)), true);
		List<DeathLog.Entry> list = deaths.list(ACCOUNT);
		assertEquals(1, list.size());
		DeathLog.Entry e = list.get(0);
		assertEquals("the plugin's own time is kept", T0, e.time);
		assertEquals(T0 + 3000, e.recapTime);
		assertEquals("Zulrah", e.killer);
		assertArrayEquals(new byte[]{1, 2, 3}, deaths.read(ACCOUNT, e.card));
		assertArrayEquals(new byte[]{4, 5}, deaths.read(ACCOUNT, e.replay));

		// Seeing the death after Death Recap sent it doesn't add another.
		deaths.died(ACCOUNT, T0 + 1000, 330);
		assertEquals(1, deaths.list(ACCOUNT).size());
		assertArrayEquals(new long[]{T0 + 3000}, deaths.recapTimes(ACCOUNT));
	}

	@Test
	public void updatesByRecapTime() throws IOException
	{
		DeathLog.Recap first = DeathLog.Recap.from(message(T0));
		first.card = null;
		deaths.recap(first, true);
		Map<String, Object> again = message(T0);
		again.put("screenshot", new byte[]{9});
		deaths.recap(DeathLog.Recap.from(again), true);

		List<DeathLog.Entry> list = deaths.list(ACCOUNT);
		assertEquals(1, list.size());
		assertNotNull(list.get(0).card);
		assertArrayEquals(new byte[]{9}, deaths.read(ACCOUNT, list.get(0).shot));
	}

	@Test
	public void keepsSeparateDeathsApart() throws IOException
	{
		deaths.died(ACCOUNT, T0, 330);
		deaths.died(ACCOUNT, T0 + DeathLog.MERGE_MS + 1, 330);
		deaths.recap(DeathLog.Recap.from(message(T0 + 600_000)), false);
		List<DeathLog.Entry> list = deaths.list(ACCOUNT);
		assertEquals(3, list.size());
		assertTrue("newest first", list.get(0).time > list.get(1).time);
		assertNull("replays aren't kept when turned off", list.get(0).replay);
		assertEquals(2, deaths.range(ACCOUNT, T0, T0 + 60_000).size());
		assertEquals(T0, deaths.range(ACCOUNT, T0, T0 + 60_000).get(0).time);
	}

	@Test
	public void refusesPathsOutsideTheLog()
	{
		assertNull(deaths.read(ACCOUNT, "../deaths.json"));
		assertNull(deaths.read(ACCOUNT, ".hidden"));
		assertNull(deaths.read(ACCOUNT, null));
	}
}
