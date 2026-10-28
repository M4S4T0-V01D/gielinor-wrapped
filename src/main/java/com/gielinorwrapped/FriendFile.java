package com.gielinorwrapped;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A small summary of one recap that friends swap as a file, for a side-by-side "you vs them" scene.
 * Holds totals only: no chat, no locations finer than an area name, nothing that identifies the account
 * beyond the name the player chose to share.
 */
final class FriendFile
{
	static final int VERSION = 1;
	static final String EXTENSION = "gwfriend";
	/** Friend files are tiny; anything bigger isn't one. */
	static final int MAX_BYTES = 64 * 1024;
	private static final int MAX_TEXT = 40;
	private static final int MAX_SKILLS = 5;

	static final class Line
	{
		String name;
		long value;

		Line()
		{
		}

		Line(String name, long value)
		{
			this.name = name;
			this.value = value;
		}
	}

	int version = VERSION;
	String player;
	/** "October 2026", "19 Oct – 25 Oct 2026". */
	String period;
	String kind;
	String from;
	String to;
	long xp;
	long loot;
	long seconds;
	long kills;
	long bossKills;
	long levels;
	long deaths;
	long clues;
	long geNet;
	int days;
	int streak;
	int collection;
	int slayerTasks;
	String topSkill;
	String topBoss;
	String topArea;
	String persona;
	/** "Hardcore Ironman" and so on; empty or missing for a normal account. */
	String accountType;
	List<Line> skills = new ArrayList<>();

	static FriendFile of(Recap r, boolean hideName)
	{
		FriendFile f = new FriendFile();
		f.player = hideName ? "Adventurer" : r.player;
		f.period = r.period.describe();
		f.kind = r.period.kind.name();
		f.from = r.period.from.toString();
		f.to = r.period.to.toString();
		f.xp = r.total.totalXp();
		f.loot = r.total.lootValue;
		f.seconds = r.total.playSeconds;
		f.kills = r.totalKills;
		f.bossKills = r.totalBossKills();
		f.levels = r.total.totalLevels();
		f.deaths = r.total.deaths;
		f.clues = r.totalClues;
		f.geNet = r.total.geNet();
		f.days = r.daysPlayed;
		f.streak = r.longestStreak;
		f.collection = r.collection.size();
		f.slayerTasks = r.total.slayerTasks;
		f.topSkill = r.skills.isEmpty() ? null : r.skills.get(0).name;
		f.topBoss = r.bosses.isEmpty() ? null : r.bosses.get(0).name;
		f.topArea = r.areas.isEmpty() ? null : r.areas.get(0).name;
		f.persona = r.persona.title;
		f.accountType = r.accountType.name();
		for (Recap.Entry e : Recap.top(r.skills, MAX_SKILLS))
		{
			f.skills.add(new Line(e.name, e.value));
		}
		return f;
	}

	String toJson(Gson gson)
	{
		return gson.toJson(this);
	}

	/** Reads a friend file, or returns null if the text isn't one. Text fields are trimmed and numbers kept sane. */
	static FriendFile parse(Gson gson, String json)
	{
		if (json == null || json.length() > MAX_BYTES)
		{
			return null;
		}
		FriendFile f;
		try
		{
			f = gson.fromJson(json, FriendFile.class);
		}
		catch (JsonParseException | IllegalStateException | NumberFormatException e)
		{
			return null;
		}
		if (f == null || f.version < 1 || f.player == null || f.player.trim().isEmpty())
		{
			return null;
		}
		f.player = text(f.player);
		f.period = text(f.period);
		f.kind = text(f.kind);
		f.from = text(f.from);
		f.to = text(f.to);
		f.topSkill = text(f.topSkill);
		f.topBoss = text(f.topBoss);
		f.topArea = text(f.topArea);
		f.persona = text(f.persona);
		f.accountType = text(f.accountType);
		f.xp = sane(f.xp);
		f.loot = sane(f.loot);
		f.seconds = sane(f.seconds);
		f.kills = sane(f.kills);
		f.bossKills = sane(f.bossKills);
		f.levels = sane(f.levels);
		f.deaths = sane(f.deaths);
		f.clues = sane(f.clues);
		f.geNet = Math.max(-Long.MAX_VALUE / 4, Math.min(Long.MAX_VALUE / 4, f.geNet));
		f.days = (int) Math.min(100_000, Math.max(0, f.days));
		f.streak = (int) Math.min(100_000, Math.max(0, f.streak));
		f.collection = (int) Math.min(100_000, Math.max(0, f.collection));
		f.slayerTasks = (int) Math.min(100_000, Math.max(0, f.slayerTasks));
		List<Line> skills = new ArrayList<>();
		if (f.skills != null)
		{
			for (Line l : f.skills)
			{
				if (l != null && l.name != null && skills.size() < MAX_SKILLS)
				{
					skills.add(new Line(text(l.name), sane(l.value)));
				}
			}
		}
		f.skills = skills;
		return f;
	}

	/** The friend's account type; anything unknown is normal. */
	AccountType type()
	{
		for (AccountType t : AccountType.values())
		{
			if (t.name().equals(accountType))
			{
				return t;
			}
		}
		return AccountType.NORMAL;
	}

	private static String text(String s)
	{
		if (s == null)
		{
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length() && out.length() < MAX_TEXT; i++)
		{
			char c = s.charAt(i);
			if (!Character.isISOControl(c))
			{
				out.append(c);
			}
		}
		return out.toString().trim();
	}

	private static long sane(long v)
	{
		return Math.max(0, Math.min(Long.MAX_VALUE / 4, v));
	}

	/** A file name such as {@code Zezima-week-2026-10-19.gwfriend}. */
	static String fileName(String player, Period period)
	{
		return ClipLibrary.safeName(player == null ? "friend" : player) + "-" + period.id() + "." + EXTENSION;
	}
}
