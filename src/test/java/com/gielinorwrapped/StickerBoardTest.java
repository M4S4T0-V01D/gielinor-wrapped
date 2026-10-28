package com.gielinorwrapped;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Stickers the player places by hand on the sticker board. */
public class StickerBoardTest
{
	private static final GielinorWrappedConfig CONFIG = new GielinorWrappedConfig()
	{
	};

	private static final Sticker ZULRAH = Sticker.still("Zulrah", Sticker.Kind.NPC, ComposerTest.sprite(new Color(0x30A060)));
	private static final Sticker CAT = Sticker.still("cat.png", Sticker.Kind.MINE, ComposerTest.sprite(new Color(0xE0A020)));

	private static Composer.Media media(StickerBoard board)
	{
		return new Composer.Media()
		{
			@Override
			public List<ClipFile.Frame> clip(String name)
			{
				return new ArrayList<>();
			}

			@Override
			public List<ClipFile.Frame> timelapse(int max)
			{
				return new ArrayList<>();
			}

			@Override
			public List<Sticker> stickers()
			{
				return new ArrayList<>(Arrays.asList(ZULRAH, CAT));
			}

			@Override
			public StickerBoard stickerBoard()
			{
				return board;
			}
		};
	}

	private static List<Scenes.Scene> scenes(String... names)
	{
		Scenes.Look look = Scenes.Look.of(CONFIG);
		List<Scenes.Scene> out = new ArrayList<>();
		for (String n : names)
		{
			out.add(new Scenes.Scene(n, look, 3)
			{
				@Override
				void render(java.awt.Graphics2D g, int w, int h, double t)
				{
				}
			});
		}
		return out;
	}

	@Test
	public void keysAndCleaning()
	{
		assertEquals("npc:zulrah", StickerBoard.key(ZULRAH));
		assertEquals("mine:cat.png", StickerBoard.key(CAT));
		assertNull(StickerBoard.key(Sticker.still("coins", Sticker.Kind.GAME, ComposerTest.sprite(Color.YELLOW))));

		StickerBoard b = new StickerBoard();
		b.spots.add(new StickerBoard.Spot("kills", "npc:zulrah", 1.7, -0.2, 5, 0.3));
		b.spots.add(new StickerBoard.Spot(null, "npc:zulrah", 0.5, 0.5, 0.2, 0));
		b.spots.add(null);
		StickerBoard back = new Gson().fromJson(new Gson().toJson(b), StickerBoard.class).clean();
		assertEquals(1, back.spots.size());
		StickerBoard.Spot s = back.spots.get(0);
		assertTrue(s.x <= 1 && s.y >= 0);
		assertEquals(StickerBoard.MAX_SIZE, s.size, 1e-9);
		assertEquals(0.3, s.angle, 1e-9);
		assertTrue(back.arranged("kills"));
		assertFalse(back.arranged("loot"));
		assertEquals("Kills", StickerBoard.label("kills"));
	}

	@Test
	public void placedStickersReplaceAutomaticOnes()
	{
		StickerBoard board = new StickerBoard();
		board.spots.add(new StickerBoard.Spot("loot", "mine:cat.png", 0.2, 0.3, 0.4, 0.1));
		board.spots.add(new StickerBoard.Spot(StickerBoard.EVERY, "npc:zulrah", 0.9, 0.9, 0.1, 0));
		board.spots.add(new StickerBoard.Spot("loot", "npc:gone", 0.5, 0.5, 0.2, 0));
		List<Scenes.Scene> scenes = scenes("kills", "loot");
		Recap r = new ComposerTest().recap();
		Composer.placeStickers(scenes, r, media(board), CONFIG, Scenes.Look.of(CONFIG));

		Scenes.Scene loot = scenes.get(1);
		assertEquals("cat placed, zulrah on every scene, missing cutout skipped", 2, loot.stickers.size());
		assertEquals(CAT, loot.stickers.get(0).sticker);
		assertEquals(0.2, loot.stickers.get(0).x, 1e-9);
		assertEquals(0.4, loot.stickers.get(0).size, 1e-9);
		assertEquals(ZULRAH, loot.stickers.get(1).sticker);

		// Kills wasn't arranged, so it still gets automatic picks on top of the every-scene one.
		int withAuto = scenes.get(0).stickers.size();
		assertTrue(withAuto >= 1);

		board.autoFill = false;
		scenes = scenes("kills", "loot");
		Composer.placeStickers(scenes, r, media(board), CONFIG, Scenes.Look.of(CONFIG));
		assertEquals(1, scenes.get(0).stickers.size());
		assertEquals(ZULRAH, scenes.get(0).stickers.get(0).sticker);
	}

	@Test
	public void boardPreviewDrawsPlacedStickers() throws IOException
	{
		StickerBoard board = new StickerBoard();
		board.spots.add(new StickerBoard.Spot("kills", "npc:zulrah", 0.3, 0.6, 0.35, -0.2));
		board.spots.add(new StickerBoard.Spot(StickerBoard.EVERY, "mine:cat.png", 0.85, 0.2, 0.2, 0.3));
		List<Sticker> all = Arrays.asList(ZULRAH, CAT);
		BufferedImage img = StickerBoardDialog.render(CONFIG, all, board, "kills");
		assertEquals(Composer.size(CONFIG).width, img.getWidth());
		BufferedImage empty = StickerBoardDialog.render(CONFIG, all, new StickerBoard(), "kills");
		int x = (int) (0.3 * img.getWidth());
		int y = (int) (0.6 * img.getHeight());
		assertNotEquals(empty.getRGB(x, y), img.getRGB(x, y));
		File dir = new File("build/features");
		dir.mkdirs();
		ImageIO.write(img, "png", new File(dir, "sticker-board.png"));
	}

	@Test
	public void boardOpens() throws Exception
	{
		org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
		StickerBoard board = new StickerBoard();
		board.spots.add(new StickerBoard.Spot("kills", "npc:zulrah", 0.3, 0.6, 0.35, -0.2));
		javax.swing.SwingUtilities.invokeAndWait(() ->
			new StickerBoardDialog(null, CONFIG, Arrays.asList(ZULRAH, CAT), board, b -> { }, c -> { }).dispose());
	}
}
