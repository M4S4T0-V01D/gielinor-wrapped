package com.gielinorwrapped;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AccountTypeTest
{
	private static class Config implements GielinorWrappedConfig
	{
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
	}

	/** A 13 by 13 helmet-ish badge with a transparent edge, like the game's chat icons. */
	static BufferedImage helmet(Color color)
	{
		BufferedImage img = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
		for (int y = 2; y < 13; y++)
		{
			for (int x = 2; x < 13; x++)
			{
				if (y > 5 || Math.abs(x - 7) < y)
				{
					img.setRGB(x, y, (y == 8 && x > 3 && x < 11 ? Color.BLACK : color).getRGB());
				}
			}
		}
		return img;
	}

	private static Composer.Media media(FriendFile friend, boolean badges) throws IOException
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
			public BufferedImage badge(AccountType type)
			{
				if (!badges || type == null || !type.hasBadge())
				{
					return null;
				}
				return helmet(type == AccountType.HARDCORE ? new Color(0xB02020) : new Color(0x9A9A9A));
			}

			@Override
			public FriendFile friend()
			{
				return friend;
			}
		};
	}

	private static Recap recap(int... types)
	{
		List<DayRecord> days = new ArrayList<>();
		for (int i = 0; i < types.length; i++)
		{
			DayRecord d = new DayRecord("2026-10-" + (10 + i));
			d.playSeconds = 3600;
			d.xp.put("Mining", 50_000L);
			d.accountType = types[i];
			days.add(d);
		}
		return Recap.build(Period.containing(Period.Kind.YEAR, java.time.LocalDate.of(2026, 10, 20), java.time.DayOfWeek.MONDAY),
			"Zezima", days, Collections.emptyList());
	}

	@Test
	public void mapsTheVarbit()
	{
		assertEquals(AccountType.NORMAL, AccountType.of(0));
		assertEquals(AccountType.IRONMAN, AccountType.of(1));
		assertEquals(AccountType.HARDCORE, AccountType.of(3));
		assertEquals(AccountType.UNRANKED_GROUP, AccountType.of(6));
		assertEquals(AccountType.NORMAL, AccountType.of(-1));
		assertEquals(AccountType.NORMAL, AccountType.of(42));
		assertFalse(AccountType.NORMAL.hasBadge());
		assertEquals(10, AccountType.HARDCORE.icon);
	}

	@Test
	public void theLatestSeenTypeWins()
	{
		DayRecord stored = new DayRecord("2026-10-20");
		DayRecord today = new DayRecord("2026-10-20");
		today.accountType = 3;
		stored.merge(today);
		assertEquals(3, stored.accountType);
		stored.merge(new DayRecord("2026-10-20"));
		assertEquals("an unseen type keeps the stored one", 3, stored.accountType);

		// A Hardcore who died is a plain Ironman from then on; days from before the type was tracked don't count.
		Recap r = recap(3, 3, 1, -1);
		assertEquals(AccountType.IRONMAN, r.accountType);
		assertEquals("Ironman", r.tokens(false).get("accountType"));
		assertEquals(AccountType.NORMAL, recap(-1, -1).accountType);
		assertEquals("", recap(0).tokens(false).get("accountType"));
	}

	@Test
	public void friendFilesCarryTheType()
	{
		Gson gson = new Gson();
		FriendFile f = FriendFile.parse(gson, FriendFile.of(recap(3), false).toJson(gson));
		assertNotNull(f);
		assertEquals(AccountType.HARDCORE, f.type());
		FriendFile old = FriendFile.parse(gson, "{\"version\":1,\"player\":\"A\",\"xp\":5}");
		assertNotNull(old);
		assertEquals(AccountType.NORMAL, old.type());
		old.accountType = "NOT_A_TYPE";
		assertEquals(AccountType.NORMAL, old.type());
	}

	@Test
	public void theHelmetGoesOnTheTitleOutroCardAndDuel() throws Exception
	{
		Recap r = recap(3, 3);
		FriendFile friend = FriendFile.of(recap(2), false);
		friend.player = "Lynx Titan";
		Config c = new Config();
		Composer composer = new Composer(r, media(friend, true), c);
		List<String> badged = composer.scenes.stream()
			.filter(s -> s.stickers.stream().anyMatch(p -> "badge".equals(p.sticker.name)))
			.map(s -> s.name).collect(Collectors.toList());
		assertTrue(badged.toString(), badged.contains("title"));
		assertTrue(badged.toString(), badged.contains("outro"));
		assertEquals(badged.toString(), 2, badged.size());

		File dir = new File("build/features");
		dir.mkdirs();
		for (Scenes.Scene s : composer.scenes)
		{
			if (s instanceof MoreScenes.Duel && "versus".equals(s.name))
			{
				assertNotNull(((MoreScenes.Duel) s).leftBadge);
				assertNotNull(((MoreScenes.Duel) s).rightBadge);
			}
			if (!"title".equals(s.name) && !"versus".equals(s.name))
			{
				continue;
			}
			BufferedImage img = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
			s.openAll();
			try
			{
				composer.drawScene(img, s, s.duration * 0.8);
			}
			finally
			{
				s.closeAll();
			}
			ImageIO.write(img, "png", new File(dir, "badge-" + s.name + ".png"));
		}

		BufferedImage card = ShareCard.render(r, c, media(null, true));
		ImageIO.write(card, "png", new File(dir, "badge-card.png"));
		BufferedImage plain = ShareCard.render(r, c, media(null, false));
		int u = Math.min(card.getWidth(), card.getHeight());
		int x = u / 40 + u / 50;
		int y = card.getHeight() - u / 40 - u / 50;
		assertTrue("the helmet's red shows bottom-left", (card.getRGB(x, y) & 0xFFFFFF) != (plain.getRGB(x, y) & 0xFFFFFF));
	}

	@Test
	public void normalAccountsAndBadgesOffGetNone() throws Exception
	{
		Config c = new Config();
		for (Composer composer : new Composer[]{
			new Composer(recap(0), media(null, true), c),
			new Composer(recap(3), media(null, false), c)})
		{
			assertTrue(composer.scenes.stream().flatMap(s -> s.stickers.stream()).noneMatch(p -> "badge".equals(p.sticker.name)));
		}
		assertNull(new Composer.Media()
		{
			@Override
			public List<ClipFile.Frame> clip(String name)
			{
				return null;
			}

			@Override
			public List<ClipFile.Frame> timelapse(int max)
			{
				return null;
			}
		}.badge(AccountType.HARDCORE));
	}
}
