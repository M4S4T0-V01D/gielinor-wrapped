package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Renders short recaps from synthetic footage; checks the result with ffprobe when it is installed. */
public class ComposerTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final long T0 = 1_761_400_000_000L;

	private static class Config implements GielinorWrappedConfig
	{
		VideoFormat format = VideoFormat.MP4;
		int maxLength = 12;
		int targetSize = 1;
		Transition transition = Transition.FADE;

		@Override
		public VideoFormat videoFormat()
		{
			return format;
		}

		@Override
		public Quality quality()
		{
			return Quality.SMALL;
		}

		@Override
		public int maxLength()
		{
			return maxLength;
		}

		@Override
		public int targetSize()
		{
			return targetSize;
		}

		@Override
		public int videoFps()
		{
			return 10;
		}

		@Override
		public Transition transition()
		{
			return transition;
		}
	}

	private static byte[] jpeg(int i) throws IOException
	{
		BufferedImage img = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.getHSBColor(i / 40f, 0.6f, 0.8f));
		g.fillRect(0, 0, 160, 100);
		g.setColor(Color.WHITE);
		g.fillOval(i * 3 % 140, 40, 20, 20);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	private static List<ClipFile.Frame> frames(long start, int n, long step) throws IOException
	{
		List<ClipFile.Frame> list = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			list.add(new ClipFile.Frame(start + i * step, jpeg(i)));
		}
		return list;
	}

	Recap recap()
	{
		Period week = Period.containing(Period.Kind.WEEK, LocalDate.of(2026, 10, 19), DayOfWeek.MONDAY);
		DayRecord d = new DayRecord("2026-10-20");
		d.playSeconds = 7200;
		d.hours[20] = 7200;
		d.xp.put("Mining", 250_000L);
		d.kills.put("Zulrah", 12);
		d.kills.put("Vorkath", 4);
		d.xp.put("Fishing", 40_000L);
		d.levels.put("Mining", 2);
		d.lootValue = 4_200_000;
		d.lootBySource.put("Zulrah", 3_600_000L);
		d.lootBySource.put("Vorkath", 600_000L);
		d.areas.put("Zul-Andra", 3000L);
		d.addDrop(new DayRecord.Drop(T0, 12922, "Tanzanite fang", 1, 3_000_000, "Zulrah"));
		Moment drop = new Moment(T0 + 4000, Moment.Type.DROP, "Tanzanite fang", "Zulrah", 3_000_000, 58);
		drop.itemId = 12922;
		drop.clip = "a";
		Moment level = new Moment(T0 + 60_000 + 3000, Moment.Type.LEVEL_UP, "80 Mining", null, 80, 50);
		level.clip = "b";
		d.addMoment(drop);
		d.addMoment(level);
		return Recap.build(week, "Zezima", Collections.singletonList(d), Collections.emptyList());
	}

	Composer.Media media() throws IOException
	{
		List<ClipFile.Frame> a = frames(T0, 60, 100);
		List<ClipFile.Frame> b = frames(T0 + 60_000, 60, 100);
		List<ClipFile.Frame> stills = frames(T0, 40, 60_000);
		return new Composer.Media()
		{
			@Override
			public List<ClipFile.Frame> clip(String name)
			{
				return "a".equals(name) ? a : "b".equals(name) ? b : Collections.emptyList();
			}

			@Override
			public List<ClipFile.Frame> timelapse(int max)
			{
				return stills.subList(0, Math.min(max, stills.size()));
			}

			@Override
			public BufferedImage item(int id)
			{
				return sprite(new Color(0x2E9E6B));
			}

			@Override
			public BufferedImage skill(String name)
			{
				return sprite(new Color(0x8A6D3B));
			}
		};
	}

	/** A stand-in for a 36x32 game sprite: a gem shape on a transparent background. */
	static BufferedImage sprite(Color color)
	{
		BufferedImage img = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(color);
		g.fillPolygon(new int[]{18, 30, 18, 6}, new int[]{3, 16, 29, 16}, 4);
		g.setColor(color.brighter());
		g.fillPolygon(new int[]{18, 24, 18, 12}, new int[]{8, 16, 24, 16}, 4);
		g.dispose();
		return img;
	}

	private Composer.Output output(File result, File scratch)
	{
		return new Composer.Output()
		{
			@Override
			public OutputStream result() throws IOException
			{
				return new FileOutputStream(result);
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
		};
	}

	@Test
	public void plansWithinLength() throws IOException
	{
		Config c = new Config();
		Composer composer = new Composer(recap(), media(), c);
		assertTrue("length " + composer.duration(), composer.duration() <= c.maxLength + 1e-6);
		assertEquals("title", composer.scenes.get(0).name);
		assertEquals("outro", composer.scenes.get(composer.scenes.size() - 1).name);

		c.maxLength = 60;
		composer = new Composer(recap(), media(), c);
		List<String> names = new ArrayList<>();
		for (Scenes.Scene s : composer.scenes)
		{
			names.add(s.name);
		}
		assertTrue(names.toString(), names.contains("clip") && names.contains("timelapse") && names.contains("xp"));
	}

	@Test
	public void rendersMp4() throws Exception
	{
		File out = tmp.newFile("recap.mp4");
		Config c = new Config();
		Composer composer = new Composer(recap(), media(), c);
		double[] last = {0};
		composer.render(output(out, tmp.newFile("scratch")), p -> last[0] = p, () -> false);
		assertEquals(1.0, last[0], 1e-9);
		assertTrue(out.length() > 1000 && out.length() < c.targetSize * 1_000_000L);
		// Kept for eyeballing: build/composer-sample.mp4
		Files.copy(out.toPath(), new File("build/composer-sample.mp4").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		String probe = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
			"-show_entries", "stream=width,height,nb_read_frames", "-of", "csv=p=0", out.getPath());
		Assume.assumeNotNull(probe);
		assertEquals(composer.width + "," + composer.height + "," + composer.frameCount(), probe.trim());
	}

	@Test
	public void addsMusicTrack() throws Exception
	{
		File out = tmp.newFile("music.mp4");
		Config c = new Config();
		Composer.Media base = media();
		byte[] song = com.gielinorwrapped.music.SynthTest.song();
		Composer.Media withSong = new Composer.Media()
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
			public byte[] song()
			{
				return song;
			}
		};
		Composer composer = new Composer(recap(), withSong, c);
		composer.render(output(out, tmp.newFile("scratch")), p -> { }, () -> false);
		assertTrue("size " + out.length(), out.length() < c.targetSize * 1_000_000L);
		Files.copy(out.toPath(), new File("build/composer-music-sample.mp4").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		String probe = run("ffprobe", "-v", "error", "-select_streams", "a:0",
			"-show_entries", "stream=codec_name,channels,duration", "-of", "csv=p=0", out.getPath());
		Assume.assumeNotNull(probe);
		String[] f = probe.trim().split(",");
		assertEquals(probe, "aac", f[0]);
		assertEquals(probe, "2", f[1]);
		assertEquals(composer.frameCount() / 10.0, Double.parseDouble(f[2]), 0.05);
	}

	@Test
	public void rendersGif() throws Exception
	{
		File out = tmp.newFile("recap.gif");
		Config c = new Config();
		c.format = GielinorWrappedConfig.VideoFormat.GIF;
		c.maxLength = 6;
		c.transition = GielinorWrappedConfig.Transition.SLIDE;
		Composer composer = new Composer(recap(), media(), c);
		assertTrue(Math.max(composer.width, composer.height) <= Composer.GIF_MAX_SIDE);
		composer.render(output(out, tmp.newFile("scratch")), p -> { }, () -> false);
		byte[] head = Arrays.copyOf(Files.readAllBytes(out.toPath()), 6);
		assertEquals("GIF89a", new String(head, StandardCharsets.US_ASCII));
	}

	@Test
	public void addsDeathsScene() throws IOException
	{
		Composer.Media base = media();
		List<DeathLog.Entry> deaths = new ArrayList<>();
		for (int i = 0; i < 8; i++)
		{
			DeathLog.Entry e = new DeathLog.Entry();
			e.time = T0 + i * 60_000;
			e.killer = "Killer " + i;
			e.gpLost = i == 2 ? 50_000_000 : i * 1000;
			e.card = i % 2 == 0 ? "card" : null;
			deaths.add(e);
		}
		Composer.Media withDeaths = new Composer.Media()
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
			public List<DeathLog.Entry> deaths()
			{
				return deaths;
			}

			@Override
			public BufferedImage deathImage(String name)
			{
				return new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
			}
		};
		Config c = new Config();
		c.maxLength = 60;
		Composer composer = new Composer(recap(), withDeaths, c);
		Scenes.Deaths scene = null;
		for (Scenes.Scene s : composer.scenes)
		{
			if ("deaths".equals(s.name))
			{
				scene = (Scenes.Deaths) s;
			}
		}
		assertTrue("no deaths scene", scene != null);
		assertEquals(6, scene.size());

		Scenes.Deaths picked = Composer.deathsScene(withDeaths, c, Scenes.Look.of(c), "Zezima");
		assertEquals("the costliest deaths are kept", 6, picked.size());
		assertEquals("Killer 2", Composer.deathTokens(deaths.get(2), "Zezima").get("killer"));

		scene.open();
		BufferedImage frame = new BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB);
		for (double t = 0; t < scene.duration; t += 0.25)
		{
			Graphics2D g = frame.createGraphics();
			scene.render(g, frame.getWidth(), frame.getHeight(), t);
			g.dispose();
		}
		scene.close();
	}

	@Test(expected = java.io.InterruptedIOException.class)
	public void cancels() throws Exception
	{
		Composer composer = new Composer(recap(), media(), new Config());
		composer.render(output(tmp.newFile("x.mp4"), tmp.newFile("s")), p -> { }, () -> true);
	}

	@Test
	public void rateControlStaysInRange()
	{
		int[] first = new int[300];
		Arrays.fill(first, 5000);
		Composer.RateControl rc = new Composer.RateControl(100_000, first, 30, 26);
		int qp = 26;
		for (int i = 0; i < 300; i++)
		{
			qp = rc.next(5000);
		}
		assertEquals(Composer.MAX_QP, qp);
		rc = new Composer.RateControl(100_000_000, first, 30, 26);
		for (int i = 0; i < 300; i++)
		{
			qp = rc.next(100);
		}
		assertEquals(Composer.MIN_QP, qp);
		assertEquals(Composer.FIRST_QP, Composer.RateControl.startQp(900, 1000, Composer.FIRST_QP));
		assertEquals(Composer.FIRST_QP + 6, Composer.RateControl.startQp(2000, 1000, Composer.FIRST_QP));
	}

	/** A second pass over a quiet start and a busy end lands near the budget without the qp swinging. */
	@Test
	public void secondPassKeepsQualityEven()
	{
		int[] first = new int[1800];
		long total = 0;
		for (int i = 0; i < first.length; i++)
		{
			first[i] = i < 900 ? 2000 : 12000;
			total += first[i];
		}
		long budget = total / 3;
		int start = Composer.RateControl.startQp(total, budget, Composer.FIRST_QP);
		Composer.RateControl rc = new Composer.RateControl(budget, first, 30, start);
		int qp = start;
		int lo = qp;
		int hi = qp;
		long spent = 0;
		for (int i = 0; i < first.length; i++)
		{
			// Sizes roughly halve every 6 qp, a little faster than the first pass predicts.
			int bytes = (int) (first[i] * Math.pow(2, -(qp - Composer.FIRST_QP) / 5.0));
			spent += bytes;
			qp = rc.next(bytes);
			lo = Math.min(lo, qp);
			hi = Math.max(hi, qp);
		}
		assertTrue("spent " + spent + " of " + budget, spent <= budget * 1.03 && spent >= budget * 0.8);
		assertTrue("qp " + lo + ".." + hi, hi - lo <= 3);
	}

	/** Output of the command, or null when the tool isn't installed. */
	private static String run(String... cmd) throws InterruptedException
	{
		try
		{
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor();
			return s;
		}
		catch (IOException e)
		{
			return null;
		}
	}
}
