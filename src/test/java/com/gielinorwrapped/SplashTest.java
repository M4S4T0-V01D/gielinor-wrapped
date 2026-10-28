package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.Test;

/** Sprites, the splash title and the splash card; the PNGs land in build/splash for eyeballing. */
public class SplashTest
{
	@Test
	public void upscalesByWholeFactors()
	{
		BufferedImage s = ComposerTest.sprite(Color.RED);
		BufferedImage big = Sprites.upscale(s, 100);
		// 36 px wide fits twice into 100, not 2.78 times: pixels stay square.
		assertEquals(72, big.getWidth());
		assertEquals(64, big.getHeight());
		assertEquals(18, Sprites.upscale(s, 18).getWidth());
	}

	@Test
	public void trimsAndGlows()
	{
		BufferedImage s = ComposerTest.sprite(Color.RED);
		BufferedImage t = Sprites.trim(s);
		assertTrue(t.getWidth() < s.getWidth() && t.getHeight() < s.getHeight());
		// No empty border left: the gem touches all four edges.
		assertTrue((t.getRGB(t.getWidth() / 2, 0) >>> 24) > 16);
		assertTrue((t.getRGB(0, t.getHeight() / 2) >>> 24) > 16);
		assertTrue((t.getRGB(t.getWidth() - 1, t.getHeight() / 2) >>> 24) > 16);
		assertTrue((t.getRGB(t.getWidth() / 2, t.getHeight() - 1) >>> 24) > 16);
		assertNull(Sprites.trim(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)));

		BufferedImage g = Sprites.glow(t, Color.YELLOW, 6);
		assertEquals(t.getWidth() + 12, g.getWidth());
		// Halo next to the sprite, nothing in the far corner.
		int halo = g.getRGB(6 + t.getWidth() / 2, 4);
		assertTrue((halo >>> 24) > 0);
		assertEquals(0xFFFF00, halo & 0xFFFFFF);
		assertEquals(0, g.getRGB(0, 0) >>> 24);

		BufferedImage splash = Sprites.splash(s, 120, Color.YELLOW);
		assertTrue(splash.getWidth() <= 120 && splash.getHeight() <= 120);
		assertNull(Sprites.splash(null, 120, Color.YELLOW));
	}

	@Test
	public void featuresPetsBeforeDrops()
	{
		Recap r = new ComposerTest().recap();
		Moment pet = new Moment(1, Moment.Type.PET, "Snakeling", null, 0, 90);
		pet.itemId = 12921;
		r.highlights.add(pet);
		assertEquals(Arrays.asList(12921, 12922), Splash.featuredItems(r, 6));
		assertEquals(Arrays.asList(12921), Splash.featuredItems(r, 1));
	}

	@Test
	public void rendersSplashTitleAndCard() throws Exception
	{
		ComposerTest helper = new ComposerTest();
		Recap recap = helper.recap();
		Composer.Media media = helper.media();
		File dir = new File("build/splash");
		dir.mkdirs();
		for (GielinorWrappedConfig.ThemeName theme : new GielinorWrappedConfig.ThemeName[]{GielinorWrappedConfig.ThemeName.GIELINOR, GielinorWrappedConfig.ThemeName.CLEAN})
		{
			GielinorWrappedConfig config = new GielinorWrappedConfig()
			{
				@Override
				public CardLayout cardLayout()
				{
					return CardLayout.SPLASH;
				}

				@Override
				public ThemeName theme()
				{
					return theme;
				}
			};
			Splash splash = Splash.of(recap, media, recap.tokens(false), config);
			assertTrue(splash.hasArt());
			for (double t : new double[]{0.3, 3})
			{
				BufferedImage frame = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = frame.createGraphics();
				splash.draw(g, Theme.of(config), 1280, 720, t);
				g.dispose();
				ImageIO.write(frame, "png", new File(dir, "title-" + theme.name().toLowerCase() + "-" + t + ".png"));
			}
			BufferedImage card = ShareCard.render(recap, config, media);
			assertNotNull(card);
			ImageIO.write(card, "png", new File(dir, "card-" + theme.name().toLowerCase() + ".png"));
		}
	}
}
