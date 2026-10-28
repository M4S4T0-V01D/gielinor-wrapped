package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import java.awt.image.BufferedImage;
import java.io.File;
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
}
