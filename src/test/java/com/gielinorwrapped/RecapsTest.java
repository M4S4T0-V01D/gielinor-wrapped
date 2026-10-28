package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Makes recaps end to end on disk (under build/test-home, see build.gradle). */
public class RecapsTest
{
	private static final long ACCOUNT = 42;
	private static final long T0 = 1_761_000_000_000L;

	@PluginDescriptor(name = "Recaps test", internalName = "gielinorwrappedtest")
	public static class DirPlugin extends Plugin
	{
		Filepath dir() throws IOException
		{
			return getPluginDirectory();
		}
	}

	private static class Config implements GielinorWrappedConfig
	{
		@Override
		public Quality quality()
		{
			return Quality.SMALL;
		}

		@Override
		public int maxLength()
		{
			return 6;
		}

		@Override
		public int videoFps()
		{
			return 10;
		}

		@Override
		public boolean autoDaily()
		{
			return false;
		}

		@Override
		public boolean autoWeekly()
		{
			return true;
		}

		@Override
		public boolean autoMonthly()
		{
			return false;
		}

		@Override
		public boolean autoYearly()
		{
			return false;
		}

		@Override
		public DayOfWeek weekStart()
		{
			return DayOfWeek.MONDAY;
		}

		@Override
		public boolean autoVideo()
		{
			return true;
		}

		@Override
		public boolean notifyReady()
		{
			return true;
		}
	}

	private Filepath root;
	private StatsStore store;
	private Recaps recaps;

	@Before
	public void setUp() throws IOException
	{
		Filepath data = new DirPlugin().dir();
		data.createDirectories();
		root = data.createTempDir("recaps");
		store = new StatsStore(new Gson(), root);
		ClipLibrary library = new ClipLibrary(store);

		List<ClipFile.Frame> frames = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			frames.add(new ClipFile.Frame(T0 + i * 100, jpeg(i)));
		}
		DayRecord d = new DayRecord("2026-10-20");
		d.playSeconds = 5400;
		d.xp.put("Woodcutting", 120_000L);
		d.levels.put("Woodcutting", 1);
		Moment m = new Moment(T0 + 2000, Moment.Type.LEVEL_UP, "70 Woodcutting", null, 70, 50);
		m.clip = library.writeClip(ACCOUNT, m.time, m.importance, frames);
		d.addMoment(m);
		store.writeDay(ACCOUNT, d.date, store.gson().toJson(d));

		recaps = new Recaps(store, library, new Shots(store), new DeathLog(store), null, new Config(), j -> { });
	}

	@After
	public void tearDown() throws Exception
	{
		if (recaps != null)
		{
			recaps.close();
		}
		if (root != null)
		{
			root.deleteRecursively();
		}
	}

	@Test
	public void makesCardAndVideo() throws Exception
	{
		Period week = Period.containing(Period.Kind.WEEK, LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY);
		Recaps.Job job = await(recaps.request(ACCOUNT, "Zezima", week, true));
		assertEquals(job.message, Recaps.Status.DONE, job.status);

		List<Recaps.Saved> saved = recaps.saved(ACCOUNT);
		assertEquals(1, saved.size());
		Recaps.Saved s = saved.get(0);
		assertEquals(week, s.meta.period());
		assertEquals("Zezima", s.meta.player);
		assertTrue(s.card().size() > 1000);
		assertNotNull(s.video());
		assertTrue(s.video().size() > 1000);
		try (java.util.stream.Stream<Filepath> files = s.dir.walk(1))
		{
			assertTrue("no partial files", files.noneMatch(f -> f.getFileName().endsWith(".part") || f.getFileName().endsWith(".tmp")));
		}

		recaps.delete(s);
		assertTrue(recaps.saved(ACCOUNT).isEmpty());
	}

	@Test
	public void emptyPeriodFails() throws Exception
	{
		Period day = Period.containing(Period.Kind.DAY, LocalDate.of(2026, 10, 21), DayOfWeek.MONDAY);
		Recaps.Job job = await(recaps.request(ACCOUNT, "Zezima", day, false));
		assertEquals(Recaps.Status.FAILED, job.status);
		assertTrue(recaps.saved(ACCOUNT).isEmpty());
	}

	@Test
	public void scheduleRunsOnce() throws Exception
	{
		List<String> ready = new ArrayList<>();
		LocalDate monday = LocalDate.of(2026, 10, 26);
		recaps.checkSchedule(ACCOUNT, "Zezima", monday, ready::add);
		assertEquals(1, recaps.jobs().size());
		await(recaps.jobs().get(0));
		recaps.checkSchedule(ACCOUNT, "Zezima", monday, ready::add);
		assertEquals(1, recaps.jobs().size());

		// The notification is queued behind the render.
		for (int i = 0; i < 250 && ready.isEmpty(); i++)
		{
			Thread.sleep(20);
		}
		assertEquals(ready.toString(), 1, ready.size());
		assertTrue(ready.get(0), ready.get(0).contains("weekly"));
		assertEquals(1, recaps.saved(ACCOUNT).size());
	}

	private static Recaps.Job await(Recaps.Job job) throws InterruptedException
	{
		long end = System.currentTimeMillis() + 60_000;
		while (!job.status.finished() && System.currentTimeMillis() < end)
		{
			Thread.sleep(20);
		}
		return job;
	}

	private static byte[] jpeg(int i) throws IOException
	{
		BufferedImage img = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.getHSBColor(i / 40f, 0.5f, 0.7f));
		g.fillRect(0, 0, 160, 100);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}
}
