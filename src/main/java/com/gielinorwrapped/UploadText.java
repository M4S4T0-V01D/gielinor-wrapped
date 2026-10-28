package com.gielinorwrapped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A ready-to-paste title, description and chapter list for uploading a recap video. Chapters follow
 * YouTube's rules: the first at 0:00, at least three, each at least ten seconds long.
 */
final class UploadText
{
	static final String FILE = "upload.txt";
	static final int MIN_CHAPTER_SECONDS = 10;
	static final int MIN_CHAPTERS = 3;

	private static final Map<String, String> TITLES = new HashMap<>();

	static
	{
		TITLES.put("title", "Intro");
		TITLES.put("xp", "Experience");
		TITLES.put("loot", "Loot");
		TITLES.put("kills", "Kills");
		TITLES.put("time", "Time played");
		TITLES.put("clip", "Highlights");
		TITLES.put("footage", "Highlights");
		TITLES.put("custom", "Highlights");
		TITLES.put("timelapse", "Timelapse");
		TITLES.put("map", "Where I played");
		TITLES.put("graphs", "Graphs");
		TITLES.put("bosses", "Bosses and Slayer");
		TITLES.put("collection", "Collection log");
		TITLES.put("ge", "Grand Exchange");
		TITLES.put("montage", "Month by month");
		TITLES.put("deaths", "Deaths");
		TITLES.put("gallery", "Gallery");
		TITLES.put("compare", "Vs last time");
		TITLES.put("versus", "Vs a friend");
		TITLES.put("pace", "Goals");
		TITLES.put("persona", "Persona");
		TITLES.put("outro", "Outro");
	}

	static final class Chapter
	{
		final double start;
		final String title;

		Chapter(double start, String title)
		{
			this.start = start;
			this.title = title;
		}

		String line()
		{
			return MoreScenes.time((long) Math.floor(start)) + " " + title;
		}
	}

	private UploadText()
	{
	}

	static String title(Recap r, boolean hideName)
	{
		String who = hideName || r.player == null ? "My" : r.player + "'s";
		return who + " Gielinor Wrapped: " + r.period.describe();
	}

	static String description(Recap r)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("My Old School RuneScape ").append(r.period.noun()).append(", wrapped.\n\n");
		DayRecord t = r.total;
		if (t.totalXp() > 0)
		{
			sb.append("XP gained: ").append(Recap.number(t.totalXp())).append('\n');
		}
		if (t.totalLevels() > 0)
		{
			sb.append("Levels gained: ").append(t.totalLevels()).append('\n');
		}
		if (t.lootValue > 0)
		{
			sb.append("Loot: ").append(Recap.gp(t.lootValue)).append('\n');
		}
		if (r.totalBossKills() > 0)
		{
			sb.append("Boss kills: ").append(Recap.number(r.totalBossKills())).append('\n');
		}
		if (t.playSeconds > 0)
		{
			sb.append("Time played: ").append(Recap.duration(t.playSeconds)).append('\n');
		}
		if (!r.skills.isEmpty())
		{
			sb.append("Top skill: ").append(r.skills.get(0).name).append('\n');
		}
		if (r.persona != null && r.persona.title != null)
		{
			sb.append("Persona: ").append(r.persona.title).append('\n');
		}
		sb.append("\nMade with the Gielinor Wrapped RuneLite plugin.");
		return sb.toString();
	}

	/** Chapters from the scene list, or an empty list when the video is too short for them. */
	static List<Chapter> chapters(List<Scenes.Scene> scenes, int fps)
	{
		List<Chapter> raw = new ArrayList<>();
		long frames = 0;
		for (Scenes.Scene s : scenes)
		{
			String title = TITLES.getOrDefault(s.name, "Highlights");
			if (raw.isEmpty() || !raw.get(raw.size() - 1).title.equals(title))
			{
				raw.add(new Chapter(frames / (double) fps, title));
			}
			frames += Math.max(1, Math.round(s.duration * fps));
		}
		double end = frames / (double) fps;
		// Group short chapters with the ones after them until each lasts long enough; a short tail joins the last.
		List<Double> starts = new ArrayList<>();
		List<List<String>> titles = new ArrayList<>();
		for (Chapter c : raw)
		{
			if (starts.isEmpty() || c.start - starts.get(starts.size() - 1) >= MIN_CHAPTER_SECONDS)
			{
				starts.add(c.start);
				titles.add(new ArrayList<>());
			}
			List<String> t = titles.get(titles.size() - 1);
			if (!t.contains(c.title))
			{
				t.add(c.title);
			}
		}
		int n = starts.size();
		if (n > 1 && end - starts.get(n - 1) < MIN_CHAPTER_SECONDS)
		{
			for (String t : titles.remove(n - 1))
			{
				if (!titles.get(n - 2).contains(t))
				{
					titles.get(n - 2).add(t);
				}
			}
			starts.remove(n - 1);
		}
		List<Chapter> out = new ArrayList<>();
		for (int i = 0; i < starts.size(); i++)
		{
			out.add(new Chapter(i == 0 ? 0 : starts.get(i), join(titles.get(i))));
		}
		return out.size() >= MIN_CHAPTERS ? out : new ArrayList<>();
	}

	/** "A", "A & B", "A, B & C", "A, B & more". */
	private static String join(List<String> t)
	{
		if (t.size() == 1)
		{
			return t.get(0);
		}
		if (t.size() == 2)
		{
			return t.get(0) + " & " + t.get(1);
		}
		return t.get(0) + ", " + t.get(1) + " & " + (t.size() == 3 ? t.get(2) : "more");
	}

	static String text(Recap r, boolean hideName, List<Scenes.Scene> scenes, int fps)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(title(r, hideName)).append("\n\n").append(description(r));
		List<Chapter> chapters = scenes == null ? new ArrayList<>() : chapters(scenes, fps);
		if (!chapters.isEmpty())
		{
			sb.append("\n\nChapters\n");
			for (Chapter c : chapters)
			{
				sb.append(c.line()).append('\n');
			}
		}
		return sb.toString().trim() + "\n";
	}
}
