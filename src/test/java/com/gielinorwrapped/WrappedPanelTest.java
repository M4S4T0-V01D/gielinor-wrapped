package com.gielinorwrapped;

import static org.junit.Assert.assertTrue;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.client.util.Filepath;
import org.junit.Test;

/** Lays the panel out offscreen with sample recaps; the picture is kept in build/panel.png. */
public class WrappedPanelTest
{
	@Test
	public void laysOut() throws Exception
	{
		Filepath dir = new RecapsTest.DirPlugin().dir();
		WrappedPanel.Tab[] tabs = WrappedPanel.Tab.values();
		BufferedImage[] shot = new BufferedImage[tabs.length];
		SwingUtilities.invokeAndWait(() ->
		{
			WrappedPanel panel = new WrappedPanel(new NoActions(), new GielinorWrappedConfig() { });
			panel.setRecording(true, System.currentTimeMillis() - 83_000);
			panel.showRecordMessage("Emote captured (42 frames).");
			panel.showRecordings(Arrays.asList(
				new ClipLibrary.Recording("a", System.currentTimeMillis() - 3_600_000, 95_000, 40L << 20),
				new ClipLibrary.Recording("b", System.currentTimeMillis() - 86_400_000, 300_000, 120L << 20)));
			panel.showTracks(Arrays.asList("Harmony", "Newbie Melody", "Sea Shanty 2"));

			Recaps.Job job = new Recaps.Job(1, Period.containing(Period.Kind.MONTH, LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY), true, null);
			job.status = Recaps.Status.VIDEO;
			job.progress = 0.4;
			Recaps.Job failed = new Recaps.Job(1, Period.containing(Period.Kind.DAY, LocalDate.of(2026, 10, 21), DayOfWeek.MONDAY), false, null);
			failed.status = Recaps.Status.FAILED;
			failed.message = "Nothing was tracked in that period";
			panel.showJobs(Arrays.asList(job, failed));

			Recaps.Meta meta = new Recaps.Meta();
			meta.kind = "WEEK";
			meta.from = "2026-10-19";
			meta.to = "2026-10-25";
			meta.player = "Zezima";
			meta.video = "recap.mp4";
			Recaps.Saved s = new Recaps.Saved(meta, dir.join("week"));
			Map<String, Image> thumbs = new HashMap<>();
			BufferedImage card = ShareCard.render(new ComposerTest().recap(), new GielinorWrappedConfig() { }, null);
			thumbs.put(s.dir.toString(), ShareCard.scale(card, WrappedPanel.THUMB_WIDTH));
			panel.showSaved(Collections.singletonList(s), thumbs);

			for (int i = 0; i < tabs.length; i++)
			{
				panel.showTab(tabs[i]);
				panel.setSize(new Dimension(WrappedPanel.PANEL_WIDTH + 6, 800));
				layout(panel);
				Dimension pref = panel.getPreferredSize();
				panel.setSize(WrappedPanel.PANEL_WIDTH + 6, pref.height);
				layout(panel);
				layout(panel);
				shot[i] = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = shot[i].createGraphics();
				panel.paint(g);
				g.dispose();
			}
		});
		int w = 0;
		int h = 0;
		for (BufferedImage img : shot)
		{
			assertTrue(img.getHeight() > 300);
			w += img.getWidth() + 8;
			h = Math.max(h, img.getHeight());
		}
		BufferedImage all = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = all.createGraphics();
		for (int i = 0, x = 0; i < shot.length; x += shot[i].getWidth() + 8, i++)
		{
			g.drawImage(shot[i], x, 0, null);
		}
		g.dispose();
		ImageIO.write(all, "png", new File("build/panel.png"));
	}

	private static void layout(java.awt.Container c)
	{
		c.doLayout();
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof java.awt.Container)
			{
				layout((java.awt.Container) child);
			}
		}
	}

	private static class NoActions implements WrappedPanel.Actions
	{
		public void make(Period period, boolean video, String friend) { }
		public void copyUpload(Recaps.Saved saved) { }
		public void exportFriend(Recaps.Saved saved) { }
		public void importFriend() { }
		public void deleteFriend(String name) { }
		public void cancel(Recaps.Job job) { }
		public void refresh() { }
		public void delete(Recaps.Saved saved) { }
		public void copyCard(Recaps.Saved saved) { }
		public void saveCard(Recaps.Saved saved) { }
		public void saveVideo(Recaps.Saved saved) { }
		public void preview(Recaps.Saved saved) { }
		public void takeShot() { }
		public void setRole(Shots.Shot shot, Shots.Role role) { }
		public void deleteShot(Shots.Shot shot) { }
		public void toggleRecording() { }
		public void captureEmote() { }
		public void deleteRecording(ClipLibrary.Recording recording) { }
		public void addStickers() { }
		public void copyStickersPath() { }
		public void openStickerBoard() { }
		public void previewDeath(DeathLog.Entry entry) { }
		public void openStickers() { }
		public void setConfig(String key, Object value) { }
	}
}
