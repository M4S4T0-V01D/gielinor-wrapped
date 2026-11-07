package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Test;

/** Renders every layout in every aspect; the PNGs land in build/cards for eyeballing. */
public class ShareCardTest
{
	@Test
	public void rendersAllLayouts() throws Exception
	{
		ComposerTest helper = new ComposerTest();
		Recap recap = helper.recap();
		Composer.Media media = helper.media();
		File dir = new File("build/cards");
		dir.mkdirs();
		for (GielinorWrappedConfig.Aspect aspect : GielinorWrappedConfig.Aspect.values())
		{
			for (GielinorWrappedConfig.CardLayout layout : GielinorWrappedConfig.CardLayout.values())
			{
				GielinorWrappedConfig config = new GielinorWrappedConfig()
				{
					@Override
					public Aspect aspect()
					{
						return aspect;
					}

					@Override
					public CardLayout cardLayout()
					{
						return layout;
					}
				};
				BufferedImage img = ShareCard.render(recap, config, media);
				assertEquals(ShareCard.size(aspect).width, img.getWidth());
				ImageIO.write(img, "png", new File(dir, aspect.name().toLowerCase() + "-" + layout.name().toLowerCase() + ".png"));
			}
		}
	}

	private static GielinorWrappedConfig config(GielinorWrappedConfig.Aspect aspect, GielinorWrappedConfig.CardLayout layout, GielinorWrappedConfig.ThemeName theme)
	{
		return new GielinorWrappedConfig()
		{
			@Override
			public Aspect aspect()
			{
				return aspect;
			}

			@Override
			public CardLayout cardLayout()
			{
				return layout;
			}

			@Override
			public ThemeName theme()
			{
				return theme;
			}
		};
	}

	static Recap busyMonth()
	{
		Period month = Period.containing(Period.Kind.MONTH, LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY);
		List<DayRecord> days = new ArrayList<>();
		for (int i = 1; i <= 31; i++)
		{
			if (i % 4 == 3 || i == 30)
			{
				continue;
			}
			long secs = 600 + (long) ((i * 37) % 11) * 1500;
			days.add(ExtrasTest.day(String.format("2026-10-%02d", i), secs, secs * 60));
		}
		return Recap.build(month, "Zezima", days, Collections.emptyList());
	}

	@Test
	public void trophyCabinetAndCalendar() throws Exception
	{
		Composer.Media media = new ComposerTest().media();
		File dir = new File("build/cards");
		dir.mkdirs();
		Recap year = ExtrasTest.richRecap();
		Recap month = busyMonth();
		assertTrue(ShareCard.calendarFits(month));
		assertFalse("a year has no daily series", ShareCard.calendarFits(year));
		GielinorWrappedConfig.ThemeName[] themes = {GielinorWrappedConfig.ThemeName.VOLCANIC, GielinorWrappedConfig.ThemeName.HOLIDAY, GielinorWrappedConfig.ThemeName.LUMBRIDGE};
		int n = 0;
		for (GielinorWrappedConfig.Aspect aspect : GielinorWrappedConfig.Aspect.values())
		{
			GielinorWrappedConfig.ThemeName theme = themes[n++ % themes.length];
			BufferedImage t = ShareCard.render(year, config(aspect, GielinorWrappedConfig.CardLayout.TROPHIES, theme), media);
			ImageIO.write(t, "png", new File(dir, "rich-" + aspect.name().toLowerCase() + "-trophies.png"));
			BufferedImage c = ShareCard.render(month, config(aspect, GielinorWrappedConfig.CardLayout.CALENDAR, theme), media);
			ImageIO.write(c, "png", new File(dir, "rich-" + aspect.name().toLowerCase() + "-calendar.png"));
		}
		// Too little for either: both fall back to the summary card.
		Recap thin = Recap.build(Period.containing(Period.Kind.DAY, LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY), "Zezima",
			Collections.singletonList(ExtrasTest.day("2026-10-20", 600, 100)), Collections.emptyList());
		BufferedImage summary = ShareCard.render(thin, config(GielinorWrappedConfig.Aspect.values()[0], GielinorWrappedConfig.CardLayout.SUMMARY, GielinorWrappedConfig.ThemeName.GIELINOR), null);
		for (GielinorWrappedConfig.CardLayout l : new GielinorWrappedConfig.CardLayout[]{GielinorWrappedConfig.CardLayout.TROPHIES, GielinorWrappedConfig.CardLayout.CALENDAR})
		{
			BufferedImage img = ShareCard.render(thin, config(GielinorWrappedConfig.Aspect.values()[0], l, GielinorWrappedConfig.ThemeName.GIELINOR), null);
			assertTrue(l.name(), same(summary, img));
		}
	}

	private static boolean same(BufferedImage a, BufferedImage b)
	{
		for (int y = 0; y < a.getHeight(); y += 7)
		{
			for (int x = 0; x < a.getWidth(); x += 7)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}
}
