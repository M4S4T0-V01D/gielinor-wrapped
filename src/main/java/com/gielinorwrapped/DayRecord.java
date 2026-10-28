package com.gielinorwrapped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything tracked for one account on one local calendar day. Stored as JSON. */
public class DayRecord
{
	public static final int MAX_MOMENTS = 200;
	public static final int MAX_DROPS = 25;

	/** ISO date, yyyy-MM-dd. */
	public String date;
	/** XP gained per skill, keyed by skill name. */
	public Map<String, Long> xp = new LinkedHashMap<>();
	/** Levels gained per skill. */
	public Map<String, Integer> levels = new LinkedHashMap<>();
	/** Seconds logged in. */
	public long playSeconds;
	/** Seconds logged in with mouse or keyboard input in the last minute. */
	public long activeSeconds;
	/** Seconds logged in, by hour of day. */
	public long[] hours = new long[24];
	public int logins;
	/** Seconds spent per area name. */
	public Map<String, Long> areas = new LinkedHashMap<>();
	/** Loot value per source (NPC, chest or event name). */
	public Map<String, Long> lootBySource = new LinkedHashMap<>();
	/** Kills per NPC name, counted from loot and kill-count messages. */
	public Map<String, Integer> kills = new LinkedHashMap<>();
	/** Highest kill count seen per boss or activity today. */
	public Map<String, Integer> killCounts = new LinkedHashMap<>();
	/** Clues completed per tier. */
	public Map<String, Integer> clues = new LinkedHashMap<>();
	public long lootValue;
	public int deaths;
	public List<Drop> drops = new ArrayList<>();
	public List<Moment> moments = new ArrayList<>();
	/** Seconds spent per map region id, for the "where you played" map. */
	public Map<String, Long> regions = new LinkedHashMap<>();
	/** The latest total XP seen per skill (not the gain), for goal pace. */
	public Map<String, Long> skillXp = new LinkedHashMap<>();
	/** Fastest kill seen per boss, in seconds. */
	public Map<String, Long> fastest = new LinkedHashMap<>();
	public int slayerTasks;
	/** Boss or activity kills, one per kill-count message. */
	public Map<String, Integer> bossKills = new LinkedHashMap<>();
	/** Grand Exchange: gp spent on buys and received from sales, and offers finished. */
	public long geBought;
	public long geSold;
	public int geTrades;
	/** The biggest Grand Exchange trades. */
	public List<Trade> trades = new ArrayList<>();
	/** The account type varbit as last seen, or -1 before it was tracked. */
	public int accountType = -1;

	public static class Drop
	{
		public long time;
		public int itemId;
		public String name;
		public int quantity;
		public long value;
		public String source;

		public Drop()
		{
		}

		public Drop(long time, int itemId, String name, int quantity, long value, String source)
		{
			this.time = time;
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
			this.source = source;
		}
	}

	public static class Trade
	{
		public long time;
		public int itemId;
		public String name;
		public int quantity;
		/** gp spent or received. */
		public long value;
		public boolean buy;

		public Trade()
		{
		}

		public Trade(long time, int itemId, String name, int quantity, long value, boolean buy)
		{
			this.time = time;
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
			this.buy = buy;
		}
	}

	public DayRecord()
	{
	}

	public DayRecord(String date)
	{
		this.date = date;
	}

	public long totalXp()
	{
		long t = 0;
		for (long v : xp.values())
		{
			t += v;
		}
		return t;
	}

	public int totalLevels()
	{
		int t = 0;
		for (int v : levels.values())
		{
			t += v;
		}
		return t;
	}

	/** Keeps the most valuable drops, newest first among equals. */
	void addDrop(Drop drop)
	{
		drops.add(drop);
		drops.sort((a, b) -> Long.compare(b.value, a.value));
		while (drops.size() > MAX_DROPS)
		{
			drops.remove(drops.size() - 1);
		}
	}

	/** Keeps the biggest trades. */
	void addTrade(Trade trade)
	{
		trades.add(trade);
		trades.sort((a, b) -> Long.compare(b.value, a.value));
		while (trades.size() > MAX_DROPS)
		{
			trades.remove(trades.size() - 1);
		}
	}

	/** Gp received minus gp spent on the Grand Exchange. */
	public long geNet()
	{
		return geSold - geBought;
	}

	/** Adds a moment, dropping the least important one when full. */
	void addMoment(Moment m)
	{
		moments.add(m);
		if (moments.size() > MAX_MOMENTS)
		{
			int worst = 0;
			for (int i = 1; i < moments.size(); i++)
			{
				if (moments.get(i).importance < moments.get(worst).importance)
				{
					worst = i;
				}
			}
			moments.remove(worst);
		}
	}

	/** Adds another record's counts to this one (used to sum days into a period and to merge a reload). */
	public void merge(DayRecord o)
	{
		o.xp.forEach((k, v) -> xp.merge(k, v, Long::sum));
		o.levels.forEach((k, v) -> levels.merge(k, v, Integer::sum));
		playSeconds += o.playSeconds;
		activeSeconds += o.activeSeconds;
		for (int i = 0; i < 24; i++)
		{
			hours[i] += o.hours[i];
		}
		logins += o.logins;
		o.areas.forEach((k, v) -> areas.merge(k, v, Long::sum));
		o.lootBySource.forEach((k, v) -> lootBySource.merge(k, v, Long::sum));
		o.kills.forEach((k, v) -> kills.merge(k, v, Integer::sum));
		o.killCounts.forEach((k, v) -> killCounts.merge(k, v, Math::max));
		o.clues.forEach((k, v) -> clues.merge(k, v, Integer::sum));
		lootValue += o.lootValue;
		deaths += o.deaths;
		for (Drop d : o.drops)
		{
			addDrop(d);
		}
		for (Moment m : o.moments)
		{
			addMoment(m);
		}
		moments.sort((a, b) -> Long.compare(a.time, b.time));
		o.regions.forEach((k, v) -> regions.merge(k, v, Long::sum));
		o.skillXp.forEach((k, v) -> skillXp.merge(k, v, Math::max));
		o.fastest.forEach((k, v) -> fastest.merge(k, v, Math::min));
		slayerTasks += o.slayerTasks;
		if (o.accountType >= 0)
		{
			accountType = o.accountType;
		}
		o.bossKills.forEach((k, v) -> bossKills.merge(k, v, Integer::sum));
		geBought += o.geBought;
		geSold += o.geSold;
		geTrades += o.geTrades;
		for (Trade t : o.trades)
		{
			addTrade(t);
		}
	}

	/** Fills fields missing from older or hand-edited files. */
	DayRecord normalize()
	{
		if (xp == null)
		{
			xp = new LinkedHashMap<>();
		}
		if (levels == null)
		{
			levels = new LinkedHashMap<>();
		}
		if (hours == null || hours.length != 24)
		{
			hours = new long[24];
		}
		if (areas == null)
		{
			areas = new LinkedHashMap<>();
		}
		if (lootBySource == null)
		{
			lootBySource = new LinkedHashMap<>();
		}
		if (kills == null)
		{
			kills = new LinkedHashMap<>();
		}
		if (killCounts == null)
		{
			killCounts = new LinkedHashMap<>();
		}
		if (clues == null)
		{
			clues = new LinkedHashMap<>();
		}
		if (drops == null)
		{
			drops = new ArrayList<>();
		}
		if (moments == null)
		{
			moments = new ArrayList<>();
		}
		moments.removeIf(m -> m == null || m.type == null);
		if (regions == null)
		{
			regions = new LinkedHashMap<>();
		}
		if (skillXp == null)
		{
			skillXp = new LinkedHashMap<>();
		}
		if (fastest == null)
		{
			fastest = new LinkedHashMap<>();
		}
		if (bossKills == null)
		{
			bossKills = new LinkedHashMap<>();
		}
		if (trades == null)
		{
			trades = new ArrayList<>();
		}
		trades.removeIf(t -> t == null);
		return this;
	}
}
