package com.gielinorwrapped;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The extra scenes: map, graphs, vs last time, goal pace, bosses, collection, GE, montage, friend compare, upload text. */
public class ExtrasTest
{
	private static final long T0 = 1_761_400_000_000L;
	private static final Period YEAR = Period.containing(Period.Kind.YEAR, LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY);

	private static class Config implements GielinorWrappedConfig
	{
		CardLayout layout = CardLayout.SUMMARY;

		@Override
		public Quality quality()
		{
			return Quality.SMALL;
		}

		@Override
		public int videoFps()
		{
			return 8;
		}

		@Override
		public int maxLength()
		{
			return 180;
		}

		@Override
		public CardLayout cardLayout()
		{
			return layout;
		}

		@Override
		public boolean letterbox()
		{
			return true;
		}
	}

	private static DayRecord day(String date, long seconds, long miningXp)
	{
		DayRecord d = new DayRecord(date);
		d.playSeconds = seconds;
		d.hours[19] = seconds;
		d.xp.put("Mining", miningXp);
		return d;
	}

	static Recap richRecap()
	{
		List<DayRecord> days = new ArrayList<>();
		String[] months = {"2026-01-10", "2026-03-14", "2026-05-02", "2026-07-21", "2026-10-20"};
		for (int i = 0; i < months.length; i++)
		{
			DayRecord d = day(months[i], 3600 * (i + 1), 500_000);
			Moment m = new Moment(T0 + i * 1000L, Moment.Type.LEVEL_UP, (80 + i) + " Mining", null, 80 + i, 40 + i);
			m.clip = i % 2 == 0 ? "a" : "b";
			d.addMoment(m);
			days.add(d);
		}
		DayRecord d = days.get(days.size() - 1);
		d.skillXp.put("Mining", 12_000_000L);
		d.skillXp.put("Fishing", 3_000_000L);
		d.xp.put("Fishing", 300_000L);
		d.kills.put("Zulrah", 40);
		d.kills.put("Vorkath", 12);
		d.bossKills.put("Zulrah", 40);
		d.bossKills.put("Vorkath", 12);
		d.fastest.put("Zulrah", 61L);
		d.fastest.put("Vorkath", 95L);
		d.slayerTasks = 7;
		d.regions.put("12850", 9000L);
		d.regions.put("12342", 4000L);
		d.regions.put("11828", 1500L);
		d.areas.put("Lumbridge", 9000L);
		d.geBought = 12_500_000;
		d.geSold = 30_000_000;
		d.addTrade(new DayRecord.Trade(T0, 12922, "Tanzanite fang", 1, 3_100_000, false));
		d.addTrade(new DayRecord.Trade(T0 + 1, 11832, "Bandos chestplate", 1, 12_500_000, true));
		d.addTrade(new DayRecord.Trade(T0 + 2, 12924, "Toxic blowpipe", 1, 26_900_000, false));
		Moment slot = new Moment(T0 + 9000, Moment.Type.COLLECTION_LOG, "Tanzanite fang", null, 0, 60);
		slot.itemId = 12922;
		d.addMoment(slot);
		d.addMoment(new Moment(T0 + 9100, Moment.Type.COMBAT_TASK, "Snake Rebound", "elite", 0, 30));
		d.addMoment(new Moment(T0 + 9200, Moment.Type.DIARY, "Varrock", "hard", 0, 30));

		DayRecord before = day("2025-06-01", 7200, 900_000);
		before.deaths = 9;
		return Recap.build(YEAR, "Zezima", days, Collections.singletonList(before));
	}

	@Test
	public void parsesSlayerAndKillTimes()
	{
		ChatParser.Result r = ChatParser.parse("You've completed your task! You killed 152 Dust devils. You gained 14,592 xp.");
		assertEquals(ChatParser.Kind.SLAYER_TASK, r.kind);
		assertEquals("Dust devils", r.name);
		assertEquals(152, r.number);
		r = ChatParser.parse("Fight duration: 1:01.20. Personal best: 0:58.40");
		assertEquals(ChatParser.Kind.DURATION, r.kind);
		assertEquals(61, r.number);
		assertEquals(3723, ChatParser.seconds("1:02:03"));
	}

	@Test
	public void mergesTheNewTotals()
	{
		DayRecord a = new DayRecord("2026-10-20");
		a.regions.put("12850", 100L);
		a.fastest.put("Zulrah", 70L);
		a.slayerTasks = 2;
		a.geBought = 100;
		a.addTrade(new DayRecord.Trade(T0, 1, "A", 1, 100, true));
		DayRecord b = new DayRecord("2026-10-21");
		b.regions.put("12850", 50L);
		b.fastest.put("Zulrah", 61L);
		b.slayerTasks = 1;
		b.geSold = 500;
		b.addTrade(new DayRecord.Trade(T0, 2, "B", 1, 500, false));
		a.merge(b);
		assertEquals(150L, (long) a.regions.get("12850"));
		assertEquals(61L, (long) a.fastest.get("Zulrah"));
		assertEquals(3, a.slayerTasks);
		assertEquals(400, a.geNet());
		assertEquals("B", a.trades.get(0).name);
	}

	@Test
	public void buildsTheExtraData()
	{
		Recap r = richRecap();
		assertTrue(r.hasGe());
		assertEquals("Zulrah", r.bosses.get(0).name);
		assertEquals("Zulrah", r.fastest.get(0).name);
		assertEquals("12850", r.regions.get(0).name);
		assertEquals(1, r.collection.size());
		assertEquals(1, r.combatTasks);
		assertEquals(1, r.diaryTasks);
		assertEquals(5, r.months.size());
		assertEquals("XP gained", r.comparisons.get(0).label);
		assertTrue(r.comparisons.stream().anyMatch(c -> c.label.equals("Deaths") && !c.up()));
		assertFalse(r.paces.isEmpty());
		assertEquals("99 Mining", r.paces.get(0).goal);
		assertTrue(r.paces.get(0).days <= Recap.PACE_HORIZON_DAYS);
	}

	@Test
	public void pacesPickTheBiggestReachableGoal()
	{
		Recap.Pace p = Recap.pace("Mining", 13_000_000, 10_000);
		assertEquals("99 Mining", p.goal);
		assertEquals(4, p.days);
		assertEquals("200M Mining XP", Recap.pace("Mining", 199_000_000, 10_000_000).goal);
		assertNull(Recap.pace("Mining", 0, 0));
		// 99 is too far off at 100 xp a day, so the next ten levels are the goal.
		assertEquals("10 Mining", Recap.pace("Mining", 0, 100).goal);
		assertEquals("2 Mining", Recap.pace("Mining", 0, 1).goal);
	}

	@Test
	public void friendFilesRoundTripAndRejectJunk()
	{
		Gson gson = new Gson();
		Recap r = richRecap();
		FriendFile f = FriendFile.of(r, false);
		FriendFile back = FriendFile.parse(gson, f.toJson(gson));
		assertNotNull(back);
		assertEquals("Zezima", back.player);
		assertEquals(r.total.totalXp(), back.xp);
		assertEquals(52, back.bossKills);
		assertEquals("Mining", back.skills.get(0).name);
		assertEquals("Adventurer", FriendFile.of(r, true).player);

		assertNull(FriendFile.parse(gson, "not json {"));
		assertNull(FriendFile.parse(gson, "{}"));
		assertNull(FriendFile.parse(gson, "[1,2]"));
		StringBuilder big = new StringBuilder("{\"player\":\"x\",\"pad\":\"");
		while (big.length() <= FriendFile.MAX_BYTES)
		{
			big.append("aaaaaaaaaa");
		}
		assertNull(FriendFile.parse(gson, big.append("\"}").toString()));

		FriendFile odd = FriendFile.parse(gson, "{\"version\":1,\"player\":\"A\\u0000B" + "c".repeat(80) + "\",\"xp\":-5,\"days\":999999999}");
		assertNotNull(odd);
		assertEquals(40, odd.player.length());
		assertFalse(odd.player.contains("\u0000"));
		assertEquals(0, odd.xp);
		assertEquals(100_000, odd.days);
		assertTrue(FriendFile.fileName("Zezima", YEAR).endsWith("." + FriendFile.EXTENSION));
	}

	@Test
	public void chaptersFollowTheScenes()
	{
		Scenes.Look look = Scenes.Look.of(new Config());
		List<Scenes.Scene> scenes = Arrays.asList(
			new MoreScenes.WorldMap(look, 4, Collections.emptyList()),
			new MoreScenes.Pace(look, 12, Collections.emptyList(), Collections.emptyList()),
			new MoreScenes.Pace(look, 3, Collections.emptyList(), Collections.emptyList()),
			new MoreScenes.Exchange(look, 11, 0, 0, Collections.emptyList(), Collections.emptyList()),
			new MoreScenes.WorldMap(look, 12, Collections.emptyList()));
		List<UploadText.Chapter> c = UploadText.chapters(scenes, 10);
		// The short map shares a chapter with the goals; the two goal scenes merge.
		assertEquals(Arrays.asList("0:00 Where I played & Goals", "0:19 Grand Exchange", "0:30 Where I played"),
			c.stream().map(UploadText.Chapter::line).collect(Collectors.toList()));
		assertTrue(UploadText.chapters(scenes.subList(0, 2), 10).isEmpty());

		String text = UploadText.text(richRecap(), false, scenes, 10);
		assertTrue(text.startsWith("Zezima's Gielinor Wrapped: "));
		assertTrue(text.contains("Chapters\n0:00 "));
		assertTrue(UploadText.text(richRecap(), true, null, 10).startsWith("My Gielinor Wrapped"));
	}

	private Composer.Media media(FriendFile friend) throws IOException
	{
		Composer.Media base = new ComposerTest().media();
		return new Composer.Media()
		{
			@Override
			public List<ClipFile.Frame> clip(String name)
			{
				return base.clip(name);
			}

			@Override
			public List<ClipFile.Frame> timelapse(int max)
			{
				return base.timelapse(max);
			}

			@Override
			public BufferedImage item(int id)
			{
				return base.item(id);
			}

			@Override
			public BufferedImage skill(String name)
			{
				return base.skill(name);
			}

			@Override
			public List<Sticker> stickers()
			{
				return Collections.singletonList(Sticker.still("Zulrah", Sticker.Kind.NPC, ComposerTest.sprite(new java.awt.Color(0x30A060))));
			}

			@Override
			public FriendFile friend()
			{
				return friend;
			}
		};
	}

	@Test
	public void plansAndDrawsTheExtraScenes() throws Exception
	{
		Recap r = richRecap();
		FriendFile friend = FriendFile.of(r, false);
		friend.player = "Lynx Titan";
		friend.xp = r.total.totalXp() / 2;
		friend.bossKills = 300;
		Config c = new Config();
		Composer composer = new Composer(r, media(friend), c);
		List<String> names = composer.scenes.stream().map(s -> s.name).collect(Collectors.toList());
		for (String n : new String[]{"map", "graphs", "bosses", "collection", "ge", "montage", "compare", "versus", "pace"})
		{
			assertTrue(n + " missing from " + names, names.contains(n));
		}
		assertTrue(names.indexOf("map") < names.indexOf("montage"));
		assertTrue(names.indexOf("montage") < names.indexOf("compare"));
		assertEquals("outro", names.get(names.size() - 1));

		File dir = new File("build/features");
		dir.mkdirs();
		for (Scenes.Scene s : composer.scenes)
		{
			if (!Arrays.asList("map", "graphs", "bosses", "collection", "ge", "montage", "compare", "versus", "pace").contains(s.name))
			{
				continue;
			}
			BufferedImage img = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
			s.openAll();
			try
			{
				composer.drawScene(img, s, s.duration * 0.7);
			}
			finally
			{
				s.closeAll();
			}
			ImageIO.write(img, "png", new File(dir, "extra-" + s.name + ".png"));
		}

		String upload = UploadText.text(r, false, composer.scenes, composer.fps);
		assertTrue(upload, upload.contains("Vs a friend"));
		assertTrue(upload, upload.contains("Month by month"));
	}

	@Test
	public void extrasCanBeSwitchedOffAndDropForLength() throws Exception
	{
		Recap r = richRecap();
		Composer.Media media = media(null);
		Config off = new Config()
		{
			@Override
			public boolean mapScene()
			{
				return false;
			}

			@Override
			public boolean montageScene()
			{
				return false;
			}

			@Override
			public boolean graphScene()
			{
				return false;
			}
		};
		List<String> names = Composer.plan(r, media, off).stream().map(s -> s.name).collect(Collectors.toList());
		assertFalse(names.contains("map"));
		assertFalse(names.contains("montage"));
		assertFalse(names.contains("graphs"));
		assertFalse(names.contains("versus"));
		assertTrue(names.contains("compare"));

		Config tight = new Config()
		{
			@Override
			public int maxLength()
			{
				return 20;
			}
		};
		List<Scenes.Scene> plan = Composer.plan(r, media, tight);
		double length = plan.stream().mapToDouble(s -> s.duration).sum();
		assertTrue("too long: " + length, length <= 20 + 1e-6);
	}

	@Test
	public void bucketsTheSeriesByDayWeekAndMonth()
	{
		Period week = Period.containing(Period.Kind.WEEK, LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY);
		Recap r = Recap.build(week, "Zezima", Arrays.asList(day("2026-10-19", 600, 100), day("2026-10-21", 1200, 300)), Collections.emptyList());
		assertEquals(Recap.Step.DAY, r.step);
		// Monday to the last day played, with the quiet Tuesday kept as a zero.
		assertEquals(3, r.series.size());
		assertEquals(LocalDate.of(2026, 10, 19), r.series.get(0).start);
		assertEquals(0, r.series.get(1).xp);
		assertEquals(300, r.series.get(2).xp);
		assertEquals(1200, r.series.get(2).seconds);

		Recap year = richRecap();
		assertEquals(Recap.Step.MONTH, year.step);
		assertEquals(10, year.series.size());
		assertEquals(LocalDate.of(2026, 1, 1), year.series.get(0).start);
		assertEquals(3600 * 2, year.series.get(2).seconds);
		assertEquals(0, year.series.get(1).seconds);

		LocalDate from = LocalDate.of(2026, 1, 1);
		assertEquals(LocalDate.of(2026, 1, 8), Recap.bucketStart(LocalDate.of(2026, 1, 14), from, Recap.Step.WEEK));
		assertEquals(LocalDate.of(2026, 1, 1), Recap.bucketStart(LocalDate.of(2026, 1, 7), from, Recap.Step.WEEK));
		assertEquals(LocalDate.of(2026, 3, 1), Recap.bucketStart(LocalDate.of(2026, 3, 31), from, Recap.Step.MONTH));

		Period one = Period.containing(Period.Kind.DAY, LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY);
		Recap single = Recap.build(one, "Zezima", Collections.singletonList(day("2026-10-20", 600, 100)), Collections.emptyList());
		assertTrue(single.series.isEmpty());
		MoreScenes.Graphs hours = MoreScenes.Graphs.of(Scenes.Look.of(new Config()), 4, single);
		assertNotNull(hours);
		assertEquals("When you played", hours.title);
		assertEquals(24, hours.labels.size());
	}

	@Test
	public void drawsTheGraphsAndTheRealMap() throws Exception
	{
		BufferedImage surface = MoreScenes.WorldMap.surface();
		assertNotNull(surface);
		assertEquals(1536, surface.getWidth());
		assertEquals(896, surface.getHeight());

		Recap r = richRecap();
		Config c = new Config();
		Composer composer = new Composer(r, media(null), c);
		File dir = new File("build/features");
		dir.mkdirs();
		for (Scenes.Scene s : composer.scenes)
		{
			if (!s.name.equals("map") && !s.name.equals("graphs"))
			{
				continue;
			}
			BufferedImage img = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
			s.openAll();
			try
			{
				composer.drawScene(img, s, s.duration * 0.9);
			}
			finally
			{
				s.closeAll();
			}
			ImageIO.write(img, "png", new File(dir, "extra-" + s.name + "-late.png"));
			if (s.name.equals("map"))
			{
				// The real map has many colours; the old grid had a handful.
				java.util.Set<Integer> colours = new java.util.HashSet<>();
				for (int y = 0; y < img.getHeight(); y += 3)
				{
					for (int x = 0; x < img.getWidth(); x += 3)
					{
						colours.add(img.getRGB(x, y));
					}
				}
				assertTrue("map looks flat: " + colours.size(), colours.size() > 500);
			}
		}
	}

	@Test
	public void drawsTheNewCards() throws Exception
	{
		Recap r = richRecap();
		File dir = new File("build/features");
		dir.mkdirs();
		for (GielinorWrappedConfig.CardLayout layout : new GielinorWrappedConfig.CardLayout[]{
			GielinorWrappedConfig.CardLayout.RECEIPT, GielinorWrappedConfig.CardLayout.VERSUS_LAST})
		{
			Config c = new Config();
			c.layout = layout;
			BufferedImage img = ShareCard.render(r, c, media(null));
			assertNotNull(img);
			ImageIO.write(img, "png", new File(dir, "card-" + layout.name().toLowerCase() + ".png"));
		}
	}

	@Test
	public void bossLabelsReadNaturally()
	{
		assertArrayEquals(new String[]{"Bryophyta", "3 chests opened"}, MoreScenes.Bosses.label("Bryophyta chests opened", 3));
		assertArrayEquals(new String[]{"Barrows", "1 chest opened"}, MoreScenes.Bosses.label("Barrows chests", 1));
		assertArrayEquals(new String[]{"Tombs of Amascut", "12 completions"}, MoreScenes.Bosses.label("Tombs of Amascut", 12));
		assertArrayEquals(new String[]{"Vorkath", "1 kill"}, MoreScenes.Bosses.label("Vorkath", 1));
	}

	@Test
	public void bossesFindTheirCutoutByNamePrefix()
	{
		BufferedImage bryophyta = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		BufferedImage kalphite = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		BufferedImage queen = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		java.util.Map<String, BufferedImage> cutouts = new java.util.HashMap<>();
		cutouts.put("bryophyta", bryophyta);
		cutouts.put("kalphite", kalphite);
		cutouts.put("kalphite_queen", queen);
		assertTrue(MoreScenes.Bosses.cutoutFor("Bryophyta chests opened", cutouts) == bryophyta);
		assertTrue(MoreScenes.Bosses.cutoutFor("Bryophyta", cutouts) == bryophyta);
		assertTrue(MoreScenes.Bosses.cutoutFor("Kalphite Queen kills", cutouts) == queen);
		assertNull(MoreScenes.Bosses.cutoutFor("Bryophytas", cutouts));
		assertNull(MoreScenes.Bosses.cutoutFor("Obor chest", cutouts));
	}

	@Test
	public void bustKeepsTheTopOfTallFigures()
	{
		BufferedImage tall = MoreScenes.Bosses.bust(new BufferedImage(40, 100, BufferedImage.TYPE_INT_ARGB));
		assertEquals(40, tall.getWidth());
		assertEquals(60, tall.getHeight());
		assertEquals(40, MoreScenes.Bosses.bust(new BufferedImage(100, 40, BufferedImage.TYPE_INT_ARGB)).getHeight());
		assertEquals(50, MoreScenes.Bosses.bust(new BufferedImage(50, 60, BufferedImage.TYPE_INT_ARGB)).getHeight());
	}

	@Test
	public void npcCutoutSizeComesFromThePngHeader() throws IOException
	{
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB), "png", out);
		assertEquals(600, ClipLibrary.pngArea(Arrays.copyOf(out.toByteArray(), 24)));
		assertEquals(0, ClipLibrary.pngArea(new byte[24]));
		assertEquals(0, ClipLibrary.pngArea(new byte[3]));
	}

	@Test
	public void npcCutoutsAvoidTheInterface()
	{
		java.awt.Rectangle view = new java.awt.Rectangle(0, 0, 1600, 900);
		List<java.awt.Rectangle> panels = Arrays.asList(new java.awt.Rectangle(1360, 560, 240, 340), new java.awt.Rectangle(0, 0, 1600, 900));
		assertTrue(Capture.clearOf(new java.awt.Rectangle(700, 300, 150, 200), panels, view));
		// Bryophyta standing behind the side panel.
		assertFalse(Capture.clearOf(new java.awt.Rectangle(1300, 600, 150, 160), panels, view));
	}

	@Test
	public void bossBubbleShowsTheCutout() throws IOException
	{
		// A red figure with a blue head: the bubble should show the head and chest, not the legs.
		BufferedImage figure = new BufferedImage(40, 100, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D fg = figure.createGraphics();
		fg.setColor(java.awt.Color.BLUE);
		fg.fillOval(10, 0, 20, 20);
		fg.setColor(java.awt.Color.RED);
		fg.fillRect(5, 20, 30, 50);
		fg.setColor(java.awt.Color.GREEN);
		fg.fillRect(8, 70, 24, 30);
		fg.dispose();
		java.util.Map<String, BufferedImage> cutouts = new java.util.HashMap<>();
		cutouts.put("bryophyta", figure);
		MoreScenes.Bosses scene = new MoreScenes.Bosses(Scenes.Look.of(new Config()), 4,
			Collections.singletonList(new Recap.Entry("Bryophyta chests opened", 3)), Collections.emptyList(), cutouts, 0);
		BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = img.createGraphics();
		scene.render(g, 1280, 720, 3.5);
		g.dispose();
		int blue = 0;
		int red = 0;
		int green = 0;
		for (int y = 0; y < 720; y++)
		{
			for (int x = 0; x < 1280; x++)
			{
				int p = img.getRGB(x, y) & 0xFFFFFF;
				blue += p == 0x0000FF ? 1 : 0;
				red += p == 0xFF0000 ? 1 : 0;
				green += p == 0x00FF00 ? 1 : 0;
			}
		}
		assertTrue(blue > 500);
		assertTrue(red > 2000);
		assertEquals(0, green);
		File dir = new File("build/features");
		dir.mkdirs();
		ImageIO.write(img, "png", new File(dir, "extra-bosses-bust.png"));
	}
}
