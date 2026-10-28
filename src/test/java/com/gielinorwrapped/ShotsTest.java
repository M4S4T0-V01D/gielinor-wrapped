package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Recap shots on disk, which ones a recap picks, and how they show up in the video. */
public class ShotsTest
{
	private static final long ACCOUNT = 7;
	private static final long T0 = 1_761_400_000_000L;

	private Filepath root;
	private Shots shots;

	@Before
	public void setUp() throws IOException
	{
		Filepath data = new RecapsTest.DirPlugin().dir();
		data.createDirectories();
		root = data.createTempDir("shots");
		shots = new Shots(new StatsStore(new Gson(), root));
	}

	@After
	public void tearDown() throws IOException
	{
		if (root != null)
		{
			root.deleteRecursively();
		}
	}

	private static BufferedImage scene(int i)
	{
		BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.getHSBColor(i / 10f, 0.5f, 0.7f));
		g.fillRect(0, 0, 320, 200);
		g.dispose();
		return img;
	}

	@Test
	public void hiddenChatNeverCoversTheGame()
	{
		List<Rectangle> raw = List.of(
			new Rectangle(0, 0, 519, 1000),     // a full-height container, as in resizable mode
			new Rectangle(0, 0, 1145, 754),     // a full-viewport PM container
			new Rectangle(7, 600, 506, 130),    // the chat messages
			new Rectangle(10, 740, 500, 40));   // the input line, partly off the frame
		List<Rectangle> fit = Capture.fitAreas(raw, 1145, 754, 1, 1);
		assertEquals(List.of(new Rectangle(7, 600, 506, 130), new Rectangle(10, 740, 500, 14)), fit);

		List<Rectangle> stretched = Capture.fitAreas(List.of(new Rectangle(7, 300, 506, 65)), 1145, 754, 1, 2);
		assertEquals(List.of(new Rectangle(7, 600, 506, 130)), stretched);
		assertTrue(Capture.fitAreas(List.of(new Rectangle(2000, 0, 50, 50)), 1145, 754, 1, 1).isEmpty());
	}

	@Test
	public void savesListsAndRenames() throws IOException
	{
		assertTrue(shots.list(ACCOUNT).isEmpty());
		shots.save(ACCOUNT, T0, Shots.Role.GALLERY, scene(1));
		Shots.Shot second = shots.save(ACCOUNT, T0 + 1000, Shots.Role.GALLERY, scene(2));

		List<Shots.Shot> list = shots.list(ACCOUNT);
		assertEquals(2, list.size());
		assertEquals(T0 + 1000, list.get(0).time);
		BufferedImage back = list.get(0).read();
		assertEquals(320, back.getWidth());

		Shots.Shot cover = shots.setRole(second, Shots.Role.COVER);
		assertEquals(Shots.Role.COVER, shots.list(ACCOUNT).get(0).role);
		assertEquals(2, shots.list(ACCOUNT).size());

		shots.delete(cover);
		assertEquals(1, shots.list(ACCOUNT).size());
		assertNull(Shots.parse(ACCOUNT, root.join("shot-abc-cover.jpg")));
	}

	@Test
	public void picksShotsInRange()
	{
		List<Shots.Shot> list = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			list.add(new Shots.Shot(ACCOUNT, null, T0 + i, Shots.Role.GALLERY));
		}
		list.add(new Shots.Shot(ACCOUNT, null, T0 + 3, Shots.Role.COVER));
		list.add(new Shots.Shot(ACCOUNT, null, T0 + 9, Shots.Role.COVER));
		list.add(new Shots.Shot(ACCOUNT, null, T0 - 5, Shots.Role.OUTRO));

		Shots.Pick p = Shots.pick(list, T0, T0 + 19);
		assertEquals(T0 + 9, p.cover.time);
		assertNull("taken before the range", p.outro);
		assertEquals(Shots.MAX_GALLERY, p.gallery.size());
		assertEquals(T0, p.gallery.get(0).time);
		assertEquals(T0 + 19, p.gallery.get(Shots.MAX_GALLERY - 1).time);
		assertEquals(p.cover, p.backdrop());

		p = Shots.pick(list, T0 + 10, T0 + 12);
		assertEquals(3, p.gallery.size());
		assertEquals(T0 + 12, p.backdrop().time);
	}

	@Test
	public void addsGalleryToVideo() throws IOException
	{
		for (int i = 0; i < 4; i++)
		{
			shots.save(ACCOUNT, T0 + i * 1000, Shots.Role.GALLERY, scene(i));
		}
		shots.save(ACCOUNT, T0 + 9000, Shots.Role.OUTRO, scene(9));
		Shots.Pick pick = Shots.pick(shots.list(ACCOUNT), T0, T0 + 10_000);
		ComposerTest helper = new ComposerTest();
		Composer.Media base = helper.media();
		Composer.Media media = new Composer.Media()
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
			public Shots.Pick shots()
			{
				return pick;
			}
		};
		GielinorWrappedConfig roomy = new GielinorWrappedConfig()
		{
			@Override
			public int maxLength()
			{
				return 60;
			}
		};
		List<String> names = names(Composer.plan(helper.recap(), media, roomy));
		assertTrue(names.toString(), names.contains("gallery"));
		assertTrue(names.indexOf("gallery") < names.indexOf("outro"));

		GielinorWrappedConfig off = new GielinorWrappedConfig()
		{
			@Override
			public boolean galleryScene()
			{
				return false;
			}
		};
		assertTrue(!names(Composer.plan(helper.recap(), media, off)).contains("gallery"));
		assertNotNull(Composer.read(pick.outro));
		assertNull(Composer.read(null));
	}

	private static List<String> names(List<Scenes.Scene> scenes)
	{
		List<String> names = new ArrayList<>();
		for (Scenes.Scene s : scenes)
		{
			names.add(s.name);
		}
		return names;
	}

	@Test
	public void cropsToGameViewAndBlursChat()
	{
		BufferedImage full = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = full.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, 800, 600);
		g.setColor(Color.WHITE);
		for (int y = 450; y < 600; y += 2)
		{
			g.drawLine(0, y, 500, y);
		}
		g.dispose();

		BufferedImage shot = Capture.shotImage(full, new Rectangle(4, 4, 512, 334), Collections.singletonList(new Rectangle(0, 450, 500, 150)));
		assertEquals(512, shot.getWidth());
		assertEquals(334, shot.getHeight());

		BufferedImage whole = Capture.shotImage(full, null, Collections.singletonList(new Rectangle(0, 450, 500, 150)));
		assertEquals(800, whole.getWidth());
		// The striped chat is averaged out, so neighbouring rows match.
		assertEquals(whole.getRGB(20, 500), whole.getRGB(20, 501));

		// A crop too small to be a real viewport is ignored.
		assertEquals(800, Capture.shotImage(full, new Rectangle(0, 0, 8, 8), Collections.emptyList()).getWidth());
	}
}
