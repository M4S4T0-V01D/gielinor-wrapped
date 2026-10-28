package com.gielinorwrapped;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Footage cuts, green screen, stickers, emotes and the player's own captions. */
public class FeaturesTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final long T0 = 1_761_400_000_000L;
	private static final long PLAIN = T0 + 100_000;
	private static final long GREEN = T0 + 200_000;

	private static class Config implements GielinorWrappedConfig
	{
		boolean chroma = true;
		ChromaPlacement placement = ChromaPlacement.OVER_THEME;
		FootageMode mode = FootageMode.BOTH;
		String captions = "title: My week | grinding hard\nfootage: Look at this\nThanks for watching | see you\ncard: Big week";
		Transition transition = Transition.MIXED;
		int maxLength = 40;

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
			return maxLength;
		}

		@Override
		public int targetSize()
		{
			return 2;
		}

		@Override
		public Transition transition()
		{
			return transition;
		}

		@Override
		public TitleStyle titleStyle()
		{
			return TitleStyle.CLASSIC;
		}

		@Override
		public boolean chromaKey()
		{
			return chroma;
		}

		@Override
		public ChromaPlacement chromaPlacement()
		{
			return placement;
		}

		@Override
		public FootageMode footageMode()
		{
			return mode;
		}

		@Override
		public String customCaptions()
		{
			return captions;
		}

		@Override
		public boolean letterbox()
		{
			return true;
		}

		@Override
		public boolean progressBar()
		{
			return true;
		}
	}

	private static byte[] encode(BufferedImage img, String format) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, format, out);
		return out.toByteArray();
	}

	/** Gameplay-looking footage: a coloured field with a moving dot. */
	private static List<ClipFile.Frame> plain(long start, int n) throws IOException
	{
		List<ClipFile.Frame> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			BufferedImage img = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			g.setColor(new Color(0x4A6B3A));
			g.fillRect(0, 0, 160, 100);
			g.setColor(Color.ORANGE);
			g.fillOval(i * 4 % 140, 40, 20, 20);
			g.dispose();
			out.add(new ClipFile.Frame(start + i * 125L, encode(img, "jpg")));
		}
		return out;
	}

	/** Someone in front of a green screen: a red figure on pure green. */
	private static BufferedImage greenFrame(int i)
	{
		BufferedImage img = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0x10E020));
		g.fillRect(0, 0, 160, 100);
		g.setColor(new Color(0xC03030));
		g.fillRect(60 + i % 10, 20, 40, 80);
		g.dispose();
		return img;
	}

	private static List<ClipFile.Frame> green(long start, int n) throws IOException
	{
		List<ClipFile.Frame> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add(new ClipFile.Frame(start + i * 125L, encode(greenFrame(i), "png")));
		}
		return out;
	}

	/** A little figure on transparency, waving. */
	private static List<ClipFile.Frame> emote() throws IOException
	{
		List<ClipFile.Frame> out = new ArrayList<>();
		for (int i = 0; i < 6; i++)
		{
			BufferedImage img = new BufferedImage(80, 120, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			g.setColor(new Color(0x3050C0));
			g.fillRect(30, 40, 20, 50);
			g.setColor(new Color(0xF0C090));
			g.fillOval(30, 18, 20, 20);
			g.setColor(new Color(0x3050C0));
			g.fillRect(50, 40 - i * 4, 8, 30);
			g.fillRect(30, 90, 8, 25);
			g.fillRect(42, 90, 8, 25);
			g.dispose();
			out.add(new ClipFile.Frame(T0 + i * 150L, encode(img, "png")));
		}
		return out;
	}

	private static List<ClipFile.Frame> range(List<ClipFile.Frame> all, long from, long to)
	{
		List<ClipFile.Frame> out = new ArrayList<>();
		ClipFile.Frame before = null;
		for (ClipFile.Frame f : all)
		{
			if (f.time < from)
			{
				before = f;
			}
			else if (f.time <= to)
			{
				if (out.isEmpty() && before != null)
				{
					out.add(before);
				}
				out.add(f);
			}
		}
		if (out.isEmpty() && before != null)
		{
			out.add(before);
		}
		return out;
	}

	private Composer.Media media() throws IOException
	{
		Composer.Media base = new ComposerTest().media();
		List<ClipFile.Frame> plainFrames = plain(PLAIN, 160);
		List<ClipFile.Frame> greenFrames = green(GREEN, 80);
		List<ClipFile.Frame> emoteFrames = emote();
		List<Sticker> stickers = new ArrayList<>();
		stickers.add(Sticker.still("zulrah", Sticker.Kind.NPC, ComposerTest.sprite(new Color(0x30A060))));
		stickers.add(Sticker.still("my cat", Sticker.Kind.MINE, ComposerTest.sprite(new Color(0xE0A020))));
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
			public Emote emote()
			{
				return Emote.of(emoteFrames);
			}

			@Override
			public List<ClipLibrary.Recording> recordings()
			{
				return Arrays.asList(new ClipLibrary.Recording("p", PLAIN, 159 * 125L, 0), new ClipLibrary.Recording("g", GREEN, 79 * 125L, 0));
			}

			@Override
			public List<ClipFile.Frame> recording(String name, long from, long to)
			{
				return range("p".equals(name) ? plainFrames : greenFrames, from, to);
			}

			@Override
			public List<Sticker> stickers()
			{
				return stickers;
			}
		};
	}

	@Test
	public void parsesCaptions()
	{
		Composer.CustomCaptions c = Composer.CustomCaptions.parse("title: Hi | there\n# note\nTime: 5 hours\nfootage: A\nshare: S\ncard: B\n\n");
		assertEquals("Hi | there", c.title);
		assertEquals(Arrays.asList("Time: 5 hours", "B"), c.cards);
		assertEquals(Arrays.asList("A"), c.footage);
		assertEquals("S", c.share);
		assertArrayEquals(new String[]{"Hi", "there"}, Composer.CustomCaptions.split("Hi | there"));
		assertNull(Composer.CustomCaptions.split("Hi")[1]);
	}

	private static void assertArrayEquals(String[] a, String[] b)
	{
		assertEquals(Arrays.asList(a), Arrays.asList(b));
	}

	@Test
	public void cutsSpreadOverRecordings()
	{
		List<ClipLibrary.Recording> recs = Arrays.asList(new ClipLibrary.Recording("a", 1000, 10_000, 0), new ClipLibrary.Recording("b", 50_000, 2000, 0));
		long[] lengths = {3000, 3000, 3000, 3000};
		List<Composer.Cut> cuts = Composer.cuts(recs, lengths, 0.5);
		assertEquals(4, cuts.size());
		for (Composer.Cut c : cuts)
		{
			ClipLibrary.Recording r = "a".equals(c.name) ? recs.get(0) : recs.get(1);
			assertTrue(c.from >= r.start && c.to <= r.end());
		}
		// The short recording gets its whole length.
		Composer.Cut last = cuts.get(3);
		assertEquals("b", last.name);
		assertEquals(2000, last.to - last.from);
		assertEquals(0, Composer.count(Arrays.asList(new ClipLibrary.Recording("x", 0, 500, 0)), 6, 3000).length);
		assertEquals(4, Composer.count(recs, 6, 3000).length);
	}

	@Test
	public void spreadsBetweenScenes()
	{
		Scenes.Look look = Scenes.Look.of(new Config());
		List<Scenes.Scene> base = new ArrayList<>();
		for (int i = 0; i < 4; i++)
		{
			base.add(new Scenes.Card("b" + i, look, 1, null, "x", null));
		}
		List<Scenes.Scene> extra = Arrays.asList(new Scenes.Card("e0", look, 1, null, "x", null), new Scenes.Card("e1", look, 1, null, "x", null));
		List<String> names = new ArrayList<>();
		for (Scenes.Scene s : Composer.spread(base, extra))
		{
			names.add(s.name);
		}
		assertEquals(Arrays.asList("b0", "b1", "e0", "b2", "e1", "b3"), names);
	}

	@Test
	public void keysGreenScreen()
	{
		Chroma chroma = new Chroma(Color.GREEN, 30, 20, true);
		BufferedImage keyed = chroma.key(greenFrame(0));
		assertEquals(0, keyed.getRGB(5, 5) >>> 24);
		assertEquals(255, keyed.getRGB(80, 60) >>> 24);
		assertTrue(chroma.coverage(greenFrame(0)) > 0.6);
		BufferedImage field = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = field.createGraphics();
		g.setColor(new Color(0x4A6B3A));
		g.fillRect(0, 0, 20, 20);
		g.dispose();
		// Muted game grass is not the key colour.
		assertTrue(chroma.coverage(field) < 0.05);
	}

	@Test
	public void readsAnimatedGifSticker() throws IOException
	{
		File gif = tmp.newFile("a.gif");
		try (OutputStream out = new FileOutputStream(gif);
			com.gielinorwrapped.video.GifWriter w = new com.gielinorwrapped.video.GifWriter(out, 20, 20))
		{
			for (int i = 0; i < 3; i++)
			{
				int[] px = new int[400];
				Arrays.fill(px, i == 0 ? 0xFF0000 : i == 1 ? 0x00FF00 : 0x0000FF);
				w.add(px, 20);
			}
			w.finish();
		}
		Sticker s = Sticker.read("a", Sticker.Kind.MINE, java.nio.file.Files.readAllBytes(gif.toPath()));
		assertNotNull(s);
		assertEquals(3, s.frames.size());
		assertTrue(s.animated());
		assertEquals(200, s.delays[0]);
		assertTrue(s.frameAt(0) != s.frameAt(0.25));
	}

	@Test
	public void plansFootageStickersAndCaptions() throws IOException
	{
		Config c = new Config();
		Composer composer = new Composer(new ComposerTest().recap(), media(), c);
		assertTrue(composer.duration() <= c.maxLength + 1e-6);
		List<String> names = new ArrayList<>();
		int keyed = 0;
		boolean backdrop = false;
		for (Scenes.Scene s : composer.scenes)
		{
			names.add(s.name);
			if (s instanceof Scenes.Footage && ((Scenes.Footage) s).reel.keyed())
			{
				keyed++;
			}
			backdrop |= s.under != null;
		}
		assertTrue(names.toString(), names.contains("footage") && names.contains("custom"));
		assertTrue("green footage keyed", keyed > 0);
		assertTrue("footage behind scenes", backdrop);
		assertTrue(composer.scenes.get(0) instanceof Scenes.Title);
		Scenes.Scene kills = composer.scenes.stream().filter(s -> "kills".equals(s.name)).findFirst().orElse(null);
		assertNotNull(kills);
		assertTrue(kills.stickers.stream().anyMatch(p -> p.sticker.kind == Sticker.Kind.NPC));

		// Green footage over the stat scenes instead.
		c.placement = GielinorWrappedConfig.ChromaPlacement.OVER_STATS;
		composer = new Composer(new ComposerTest().recap(), media(), c);
		assertTrue(composer.scenes.stream().anyMatch(s -> s.over != null && s.over.keyed()));
		assertFalse(composer.scenes.stream().map(s -> s.name + (s instanceof Scenes.Footage && ((Scenes.Footage) s).reel.keyed() ? "*" : "")).collect(java.util.stream.Collectors.toList()).toString(), composer.scenes.stream().anyMatch(s -> s instanceof Scenes.Footage && ((Scenes.Footage) s).reel.keyed()));

		// Footage off, no intro or outro: still a valid plan.
		c.mode = GielinorWrappedConfig.FootageMode.OFF;
		composer = new Composer(new ComposerTest().recap(), media(), c);
		assertFalse(composer.scenes.stream().anyMatch(s -> s instanceof Scenes.Footage || s.under != null));
	}

	@Test
	public void cinemaBarsNeverCoverTheScene() throws Exception
	{
		Composer composer = new Composer(new ComposerTest().recap(), media(), new Config());
		int bar = composer.bar;
		assertEquals(Composer.barHeight(composer.height), bar);
		assertTrue(bar > 0);
		for (Scenes.Scene s : composer.scenes)
		{
			BufferedImage img = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
			s.openAll();
			try
			{
				composer.drawScene(img, s, Math.min(s.duration * 0.6, 1.2));
			}
			finally
			{
				s.closeAll();
			}
			// The bars stay plain black: everything the scene drew landed in the band between them.
			for (int y : new int[]{0, bar - 1, composer.height - bar, composer.height - 1})
			{
				for (int x = 0; x < composer.width; x += 7)
				{
					assertEquals(s.name + " drew under a bar at " + x + "," + y, 0, img.getRGB(x, y) & 0xFFFFFF);
				}
			}
		}
	}

	@Test
	public void rendersEverything() throws Exception
	{
		Config c = new Config();
		Composer composer = new Composer(new ComposerTest().recap(), media(), c);
		File dir = new File("build/features");
		dir.mkdirs();
		// One still per scene, half a second in, for eyeballing.
		for (int i = 0; i < composer.scenes.size(); i++)
		{
			Scenes.Scene s = composer.scenes.get(i);
			BufferedImage img = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
			s.openAll();
			try
			{
				composer.drawScene(img, s, Math.min(s.duration * 0.6, 1.2));
			}
			finally
			{
				s.closeAll();
			}
			ImageIO.write(img, "png", new File(dir, String.format("%02d-%s.png", i, s.name)));
		}
		File out = tmp.newFile("all.mp4");
		File scratch = tmp.newFile("scratch");
		composer.render(new Composer.Output()
		{
			@Override
			public OutputStream result() throws IOException
			{
				return new FileOutputStream(out);
			}

			@Override
			public OutputStream scratch() throws IOException
			{
				return new FileOutputStream(scratch);
			}

			@Override
			public InputStream readScratch() throws IOException
			{
				return new FileInputStream(scratch);
			}
		}, p -> { }, () -> false);
		assertTrue(out.length() > 1000);
		java.nio.file.Files.copy(out.toPath(), new File(dir, "all.mp4").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
