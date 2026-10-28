package com.gielinorwrapped;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.Experience;

/** A period's stats summed up, ranked and turned into caption tokens. Built off the client thread. */
final class Recap
{
	static final class Entry
	{
		final String name;
		final long value;

		Entry(String name, long value)
		{
			this.name = name;
			this.value = value;
		}
	}

	static final class Persona
	{
		final String title;
		final String blurb;

		Persona(String title, String blurb)
		{
			this.title = title;
			this.blurb = blurb;
		}
	}

	/** One "this time vs last time" line. */
	static final class Comparison
	{
		enum Unit
		{
			NUMBER, GP, TIME, DAYS
		}

		final String label;
		final long now;
		final long before;
		final Unit unit;

		Comparison(String label, long now, long before, Unit unit)
		{
			this.label = label;
			this.now = now;
			this.before = before;
			this.unit = unit;
		}

		String show(long v)
		{
			switch (unit)
			{
				case GP:
					return gp(v);
				case TIME:
					return duration(v);
				case DAYS:
					return v == 1 ? "1 day" : v + " days";
				default:
					return number(v);
			}
		}

		/** "+23%", "-8%", "new" or "same". */
		String change()
		{
			if (before == 0)
			{
				return now == 0 ? "same" : "new";
			}
			long pct = Math.round((now - before) * 100.0 / Math.abs(before));
			return pct == 0 ? "same" : (pct > 0 ? "+" : "") + pct + "%";
		}

		boolean up()
		{
			return now >= before;
		}
	}

	/** How long until a skill goal at this period's rate. */
	static final class Pace
	{
		final String skill;
		/** "99 Mining" or "200M Mining XP". */
		final String goal;
		final long days;
		final long xpLeft;
		final long xpPerDay;

		Pace(String skill, String goal, long days, long xpLeft, long xpPerDay)
		{
			this.skill = skill;
			this.goal = goal;
			this.days = days;
			this.xpLeft = xpLeft;
			this.xpPerDay = xpPerDay;
		}

		String when()
		{
			if (days <= 1)
			{
				return "tomorrow";
			}
			return days < 60 ? "in " + days + " days" : days < 730 ? "in " + Math.round(days / 30.4) + " months" : "in " + Math.round(days / 365.0) + " years";
		}

		/** "99 Mining in 41 days". */
		String line()
		{
			return goal + " " + when();
		}
	}

	/** One month of a long recap: its best moment and totals. */
	static final class Month
	{
		final YearMonth month;
		/** The most important moment, preferring ones with a clip; may be null. */
		final Moment best;
		final long xp;
		final long loot;
		final long seconds;

		Month(YearMonth month, Moment best, long xp, long loot, long seconds)
		{
			this.month = month;
			this.best = best;
			this.xp = xp;
			this.loot = loot;
			this.seconds = seconds;
		}

		String name()
		{
			return month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
		}
	}

	/** One bar on the graphs: a day, a week or a month. */
	static final class Bucket
	{
		final LocalDate start;
		final long xp;
		final long seconds;
		final long loot;

		Bucket(LocalDate start, long xp, long seconds, long loot)
		{
			this.start = start;
			this.xp = xp;
			this.seconds = seconds;
			this.loot = loot;
		}
	}

	/** How long one bar on the graphs spans. */
	enum Step
	{
		DAY, WEEK, MONTH
	}

	static final int MAX_PACES = 3;
	static final int MAX_MONTHS = 12;
	/** Recaps up to this many days get a bar per day, up to {@link #WEEKLY_DAYS} a bar per week, longer a bar per month. */
	static final int DAILY_DAYS = 31;
	static final int WEEKLY_DAYS = 183;
	/** Paces further off than this aren't worth showing. */
	static final long PACE_HORIZON_DAYS = 365;
	static final long MAX_XP = 200_000_000L;

	private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);
	private static final Set<String> GATHERING = new HashSet<>(Arrays.asList("Mining", "Fishing", "Woodcutting", "Hunter", "Farming"));
	private static final Set<String> ARTISAN = new HashSet<>(Arrays.asList("Smithing", "Crafting", "Fletching", "Herblore", "Cooking", "Firemaking", "Construction", "Runecraft"));
	private static final Set<String> COMBAT = new HashSet<>(Arrays.asList("Attack", "Strength", "Defence", "Ranged", "Magic", "Hitpoints", "Prayer", "Slayer"));
	private static final Set<String> ROGUE = new HashSet<>(Arrays.asList("Agility", "Thieving"));

	final Period period;
	final String player;
	final DayRecord total;
	final int daysPlayed;
	final List<Entry> skills;
	final List<Entry> levels;
	final List<Entry> lootSources;
	final List<Entry> kills;
	final List<Entry> areas;
	final List<DayRecord.Drop> drops;
	/** Highlight moments, most important first. */
	final List<Moment> highlights;
	final int longestStreak;
	final LocalDate busiestDay;
	final long busiestDaySeconds;
	final LocalDate bestXpDay;
	final long bestXpDayXp;
	final int peakHour;
	final int totalClues;
	final int totalKills;
	/** Percent change in XP against the period before, or null when there's nothing to compare. */
	final Integer xpChange;
	final Persona persona;
	/** The period before, summed; empty when nothing was tracked then. */
	final DayRecord previousTotal;
	final int previousDaysPlayed;
	final int previousStreak;
	final List<Comparison> comparisons;
	final List<Pace> paces;
	final List<Entry> bosses;
	/** Fastest kill per boss in seconds, quickest first. */
	final List<Entry> fastest;
	final List<Entry> regions;
	/** Collection log slots and pets, oldest first. */
	final List<Moment> collection;
	final int combatTasks;
	final int diaryTasks;
	final List<DayRecord.Trade> trades;
	/** Months with anything in them, for recaps longer than two months; otherwise empty. */
	final List<Month> months;
	/** The account type on the latest day it was seen. */
	final AccountType accountType;
	final Step step;
	/** XP, time and loot per {@link #step} from the start of the period to the last tracked day; empty for one-day recaps. */
	final List<Bucket> series;

	private Recap(Period period, String player, List<DayRecord> days, List<DayRecord> previous)
	{
		this.period = period;
		this.player = player == null ? "Adventurer" : player;
		accountType = accountType(days);
		total = new DayRecord(period.from.toString());
		int played = 0;
		LocalDate busiest = null;
		long busiestSecs = 0;
		LocalDate bestXp = null;
		long bestXpValue = 0;
		Set<LocalDate> playedDays = new HashSet<>();
		for (DayRecord d : days)
		{
			total.merge(d);
			LocalDate date = LocalDate.parse(d.date);
			if (d.playSeconds > 0 || d.totalXp() > 0)
			{
				played++;
				playedDays.add(date);
			}
			if (d.playSeconds > busiestSecs)
			{
				busiestSecs = d.playSeconds;
				busiest = date;
			}
			long xp = d.totalXp();
			if (xp > bestXpValue)
			{
				bestXpValue = xp;
				bestXp = date;
			}
		}
		daysPlayed = played;
		busiestDay = busiest;
		busiestDaySeconds = busiestSecs;
		bestXpDay = bestXp;
		bestXpDayXp = bestXpValue;
		longestStreak = streak(playedDays, period);

		skills = rank(total.xp);
		levels = rank(total.levels);
		lootSources = rank(total.lootBySource);
		kills = rank(total.kills);
		areas = rank(total.areas);
		drops = new ArrayList<>(total.drops);
		highlights = new ArrayList<>(total.moments);
		highlights.sort((a, b) -> a.importance != b.importance ? Integer.compare(b.importance, a.importance) : Long.compare(a.time, b.time));

		int peak = 0;
		for (int h = 1; h < 24; h++)
		{
			if (total.hours[h] > total.hours[peak])
			{
				peak = h;
			}
		}
		peakHour = peak;
		int clues = 0;
		for (int c : total.clues.values())
		{
			clues += c;
		}
		totalClues = clues;
		int k = 0;
		for (int c : total.kills.values())
		{
			k += c;
		}
		totalKills = k;

		long prevXp = 0;
		for (DayRecord d : previous)
		{
			prevXp += d.totalXp();
		}
		xpChange = prevXp > 0 ? (int) Math.round((total.totalXp() - prevXp) * 100.0 / prevXp) : null;
		persona = persona();

		previousTotal = new DayRecord(period.before().from.toString());
		Set<LocalDate> prevPlayed = new HashSet<>();
		for (DayRecord d : previous)
		{
			previousTotal.merge(d);
			if (d.playSeconds > 0 || d.totalXp() > 0)
			{
				prevPlayed.add(LocalDate.parse(d.date));
			}
		}
		previousDaysPlayed = prevPlayed.size();
		previousStreak = streak(prevPlayed, period.before());
		comparisons = compare();

		bosses = rank(total.bossKills);
		fastest = rank(total.fastest);
		Collections.reverse(fastest);
		regions = rank(total.regions);
		List<Moment> slots = new ArrayList<>();
		int tasks = 0;
		int diaries = 0;
		for (Moment m : total.moments)
		{
			if (m.type == Moment.Type.COLLECTION_LOG || m.type == Moment.Type.PET)
			{
				slots.add(m);
			}
			else if (m.type == Moment.Type.COMBAT_TASK)
			{
				tasks++;
			}
			else if (m.type == Moment.Type.DIARY)
			{
				diaries++;
			}
		}
		slots.sort((a, b) -> Long.compare(a.time, b.time));
		collection = slots;
		combatTasks = tasks;
		diaryTasks = diaries;
		trades = new ArrayList<>(total.trades);
		paces = paces(days);
		months = months(days);
		step = period.days() <= DAILY_DAYS ? Step.DAY : period.days() <= WEEKLY_DAYS ? Step.WEEK : Step.MONTH;
		series = series(days, period, step);
	}

	/** The first day of the bar {@code date} falls in. */
	static LocalDate bucketStart(LocalDate date, LocalDate from, Step step)
	{
		switch (step)
		{
			case WEEK:
				return from.plusWeeks(ChronoUnit.WEEKS.between(from, date));
			case MONTH:
				LocalDate first = date.withDayOfMonth(1);
				return first.isBefore(from) ? from : first;
			default:
				return date;
		}
	}

	static List<Bucket> series(List<DayRecord> days, Period period, Step step)
	{
		List<Bucket> out = new ArrayList<>();
		if (period.days() <= 1)
		{
			return out;
		}
		LocalDate last = null;
		Map<LocalDate, DayRecord> sums = new TreeMap<>();
		for (DayRecord d : days)
		{
			LocalDate date = LocalDate.parse(d.date);
			if (date.isBefore(period.from) || date.isAfter(period.to))
			{
				continue;
			}
			last = last == null || date.isAfter(last) ? date : last;
			sums.computeIfAbsent(bucketStart(date, period.from, step), k -> new DayRecord(k.toString())).merge(d);
		}
		if (last == null)
		{
			return out;
		}
		// Every bar from the start to the last tracked day, quiet ones included, so gaps show.
		for (LocalDate b = period.from; !b.isAfter(last); )
		{
			DayRecord d = sums.get(b);
			out.add(d == null ? new Bucket(b, 0, 0, 0) : new Bucket(b, d.totalXp(), d.playSeconds, d.lootValue));
			b = step == Step.DAY ? b.plusDays(1) : step == Step.WEEK ? b.plusWeeks(1) : b.withDayOfMonth(1).plusMonths(1);
		}
		return out;
	}

	private List<Comparison> compare()
	{
		List<Comparison> out = new ArrayList<>();
		if (previousDaysPlayed == 0 && previousTotal.totalXp() == 0 && previousTotal.lootValue == 0)
		{
			return out;
		}
		add(out, "XP gained", total.totalXp(), previousTotal.totalXp(), Comparison.Unit.NUMBER);
		add(out, "Time played", total.playSeconds, previousTotal.playSeconds, Comparison.Unit.TIME);
		add(out, "Loot", total.lootValue, previousTotal.lootValue, Comparison.Unit.GP);
		add(out, "Levels", total.totalLevels(), previousTotal.totalLevels(), Comparison.Unit.NUMBER);
		long prevKills = 0;
		for (int c : previousTotal.kills.values())
		{
			prevKills += c;
		}
		add(out, "Kills", totalKills, prevKills, Comparison.Unit.NUMBER);
		add(out, "Days played", daysPlayed, previousDaysPlayed, Comparison.Unit.DAYS);
		add(out, "Longest streak", longestStreak, previousStreak, Comparison.Unit.DAYS);
		add(out, "Deaths", total.deaths, previousTotal.deaths, Comparison.Unit.NUMBER);
		return out;
	}

	private static void add(List<Comparison> out, String label, long now, long before, Comparison.Unit unit)
	{
		if (now > 0 || before > 0)
		{
			out.add(new Comparison(label, now, before, unit));
		}
	}

	/** Goals for the most-trained skills, at the rate this period was played. */
	private List<Pace> paces(List<DayRecord> days)
	{
		List<Pace> out = new ArrayList<>();
		LocalDate last = null;
		for (DayRecord d : days)
		{
			LocalDate date = LocalDate.parse(d.date);
			if ((d.playSeconds > 0 || d.totalXp() > 0) && (last == null || date.isAfter(last)))
			{
				last = date;
			}
		}
		if (last == null)
		{
			return out;
		}
		long span = Math.max(1, ChronoUnit.DAYS.between(period.from, last) + 1);
		for (Entry e : skills)
		{
			Long now = total.skillXp.get(e.name);
			if (now == null || now <= 0)
			{
				continue;
			}
			Pace p = pace(e.name, now, Math.max(1, e.value / span));
			if (p != null)
			{
				out.add(p);
				if (out.size() >= MAX_PACES)
				{
					break;
				}
			}
		}
		return out;
	}

	/** The biggest goal within a year at {@code perDay}, or null if even the next level is further off. */
	static Pace pace(String skill, long xp, long perDay)
	{
		if (xp >= MAX_XP || perDay <= 0)
		{
			return null;
		}
		int level = Experience.getLevelForXp((int) Math.min(Integer.MAX_VALUE, xp));
		if (level >= Experience.MAX_REAL_LEVEL)
		{
			long left = MAX_XP - xp;
			long days = (left + perDay - 1) / perDay;
			return days <= PACE_HORIZON_DAYS ? new Pace(skill, "200M " + skill + " XP", days, left, perDay) : null;
		}
		int[] targets = {Experience.MAX_REAL_LEVEL, Math.min(Experience.MAX_REAL_LEVEL, (level / 10 + 1) * 10), level + 1};
		for (int target : targets)
		{
			long left = Experience.getXpForLevel(target) - xp;
			long days = (left + perDay - 1) / perDay;
			if (left > 0 && days <= PACE_HORIZON_DAYS)
			{
				return new Pace(skill, target + " " + skill, days, left, perDay);
			}
		}
		return null;
	}

	private List<Month> months(List<DayRecord> days)
	{
		List<Month> out = new ArrayList<>();
		if (period.days() <= 62)
		{
			return out;
		}
		Map<YearMonth, DayRecord> byMonth = new TreeMap<>();
		for (DayRecord d : days)
		{
			YearMonth ym = YearMonth.from(LocalDate.parse(d.date));
			byMonth.computeIfAbsent(ym, k -> new DayRecord(k.atDay(1).toString())).merge(d);
		}
		for (Map.Entry<YearMonth, DayRecord> e : byMonth.entrySet())
		{
			DayRecord m = e.getValue();
			if (m.playSeconds == 0 && m.totalXp() == 0 && m.moments.isEmpty())
			{
				continue;
			}
			Moment best = null;
			for (Moment mo : m.moments)
			{
				if (mo.type == Moment.Type.DEATH)
				{
					continue;
				}
				boolean better;
				if (best == null)
				{
					better = true;
				}
				else if ((mo.clip != null) != (best.clip != null))
				{
					better = mo.clip != null;
				}
				else
				{
					better = mo.importance > best.importance;
				}
				if (better)
				{
					best = mo;
				}
			}
			out.add(new Month(e.getKey(), best, m.totalXp(), m.lootValue, m.playSeconds));
		}
		while (out.size() > MAX_MONTHS)
		{
			out.remove(0);
		}
		return out;
	}

	boolean hasGe()
	{
		return total.geBought > 0 || total.geSold > 0;
	}

	int totalBossKills()
	{
		int t = 0;
		for (Entry e : bosses)
		{
			t += (int) e.value;
		}
		return t;
	}

	/**
	 * @param days stored records within the period, with today's live record in place of its stored copy
	 * @param previous records of the period before, for comparisons; may be empty
	 */
	static Recap build(Period period, String player, List<DayRecord> days, List<DayRecord> previous)
	{
		return new Recap(period, player, days, previous);
	}

	static <N extends Number> List<Entry> rank(Map<String, N> map)
	{
		List<Entry> out = new ArrayList<>();
		map.forEach((k, v) ->
		{
			if (k != null && v.longValue() > 0)
			{
				out.add(new Entry(k, v.longValue()));
			}
		});
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
	}

	static int streak(Set<LocalDate> played, Period period)
	{
		int best = 0;
		int run = 0;
		for (LocalDate d = period.from; !d.isAfter(period.to); d = d.plusDays(1))
		{
			run = played.contains(d) ? run + 1 : 0;
			best = Math.max(best, run);
		}
		return best;
	}

	boolean isEmpty()
	{
		return daysPlayed == 0 && total.lootValue == 0 && total.moments.isEmpty();
	}

	/** Picks the archetype the period's play fits best. */
	private Persona persona()
	{
		long xp = Math.max(1, total.totalXp());
		Map<String, Double> scores = new LinkedHashMap<>();
		scores.put("gather", share(GATHERING) / (double) xp);
		scores.put("artisan", share(ARTISAN) / (double) xp);
		scores.put("combat", share(COMBAT) / (double) xp);
		scores.put("rogue", share(ROGUE) / (double) xp);
		scores.put("boss", Math.min(1.0, totalKills / 300.0) + (total.killCounts.isEmpty() ? 0 : 0.2));
		scores.put("clues", Math.min(1.2, totalClues / 15.0));
		scores.put("death", Math.min(1.0, total.deaths / 15.0));
		boolean pet = total.moments.stream().anyMatch(m -> m.type == Moment.Type.PET);
		long quests = total.moments.stream().filter(m -> m.type == Moment.Type.QUEST).count();
		scores.put("quest", Math.min(1.1, quests / 4.0));
		long night = 0;
		for (int h = 0; h < 5; h++)
		{
			night += total.hours[h];
		}
		boolean owl = total.playSeconds > 3600 && night * 100 / total.playSeconds >= 40;

		if (pet)
		{
			return new Persona("The Lucky One", "A pet came home with you. RNG smiled, and everyone else is jealous.");
		}
		String best = null;
		double score = 0.35;
		for (Map.Entry<String, Double> e : scores.entrySet())
		{
			if (e.getValue() > score)
			{
				score = e.getValue();
				best = e.getKey();
			}
		}
		Persona p;
		if (best == null)
		{
			p = new Persona("The Explorer", "A bit of everything, everywhere. Gielinor is your playground.");
		}
		else
		{
			switch (best)
			{
				case "gather":
					p = new Persona("The Gatherer", "Rocks, logs and fish fear you. The bank is overflowing.");
					break;
				case "artisan":
					p = new Persona("The Artisan", "If it can be crafted, cooked or smithed, you made a thousand of them.");
					break;
				case "combat":
					p = new Persona("The Warrior", "Every problem looks like a monster, and you have the answer.");
					break;
				case "rogue":
					p = new Persona("The Rogue", "Rooftops and pockets. Light on your feet and light-fingered.");
					break;
				case "boss":
					p = new Persona("The Boss Hunter", "Bosses had a rough " + period.noun() + ". You kept coming back.");
					break;
				case "clues":
					p = new Persona("The Treasure Hunter", "Caskets, emotes and riddles. X marks your spot.");
					break;
				case "death":
					p = new Persona("The Daredevil", "Death knows you by name. You keep the gravestone business alive.");
					break;
				default:
					p = new Persona("The Adventurer", "Quest log ticking green, one story at a time.");
					break;
			}
		}
		if (owl)
		{
			p = new Persona(p.title + " (Night Owl)", p.blurb + " Mostly after midnight.");
		}
		return p;
	}

	private long share(Set<String> skillNames)
	{
		long t = 0;
		for (Map.Entry<String, Long> e : total.xp.entrySet())
		{
			if (skillNames.contains(e.getKey()))
			{
				t += e.getValue();
			}
		}
		return t;
	}

	private static AccountType accountType(List<DayRecord> days)
	{
		String latest = null;
		int value = 0;
		for (DayRecord d : days)
		{
			if (d.accountType >= 0 && (latest == null || d.date.compareTo(latest) >= 0))
			{
				latest = d.date;
				value = d.accountType;
			}
		}
		return AccountType.of(value);
	}

	/** Values for the caption tokens, e.g. {@code {xp}}. */
	Map<String, String> tokens(boolean hideName)
	{
		Map<String, String> t = new HashMap<>();
		String name = hideName ? "Adventurer" : player;
		t.put("player", name);
		t.put("accountType", accountType.title);
		t.put("period", period.label().isEmpty() ? period.describe() : period.label());
		t.put("periodNoun", period.noun());
		t.put("dates", period.describe());
		t.put("range", period.describe());
		t.put("xp", number(total.totalXp()));
		t.put("topSkill", skills.isEmpty() ? "nothing yet" : skills.get(0).name);
		t.put("topSkillXp", skills.isEmpty() ? "0" : number(skills.get(0).value) + " XP");
		t.put("levels", Long.toString(total.totalLevels()));
		t.put("loot", gp(total.lootValue));
		t.put("topDrop", drops.isEmpty() ? "none yet" : drops.get(0).name);
		t.put("topDropValue", drops.isEmpty() ? "0 gp" : gp(drops.get(0).value));
		t.put("topSource", lootSources.isEmpty() ? "nobody" : lootSources.get(0).name);
		t.put("kills", number(totalKills));
		t.put("topKill", kills.isEmpty() ? "nobody" : kills.get(0).name);
		t.put("topKillCount", kills.isEmpty() ? "0" : number(kills.get(0).value));
		t.put("hours", duration(total.playSeconds));
		t.put("active", duration(total.activeSeconds));
		t.put("days", daysPlayed == 1 ? "1 day" : daysPlayed + " days");
		t.put("topArea", areas.isEmpty() ? "everywhere" : areas.get(0).name);
		t.put("deaths", Integer.toString(total.deaths));
		t.put("clues", Integer.toString(totalClues));
		t.put("streak", longestStreak == 1 ? "1 day" : longestStreak + " days");
		t.put("peakHour", hour(peakHour));
		t.put("busiestDay", busiestDay == null ? "-" : busiestDay.format(DAY_NAME));
		t.put("xpChange", xpChange == null ? "" : (xpChange >= 0 ? "+" : "") + xpChange + "%");
		t.put("persona", persona.title);
		t.put("personaBlurb", persona.blurb);
		t.put("highlights", Integer.toString(highlights.size()));
		t.put("bossKills", number(totalBossKills()));
		t.put("topBoss", bosses.isEmpty() ? "nobody" : bosses.get(0).name);
		t.put("slayerTasks", Integer.toString(total.slayerTasks));
		t.put("geBought", gp(total.geBought));
		t.put("geSold", gp(total.geSold));
		t.put("geNet", (total.geNet() > 0 ? "+" : "") + gp(total.geNet()));
		t.put("topTrade", trades.isEmpty() ? "none yet" : trades.get(0).name);
		t.put("collectionSlots", Integer.toString(collection.size()));
		t.put("combatTasks", Integer.toString(combatTasks));
		t.put("diaryTasks", Integer.toString(diaryTasks));
		t.put("pace", paces.isEmpty() ? "" : paces.get(0).line());
		t.put("lastXp", number(previousTotal.totalXp()));
		t.put("lastLoot", gp(previousTotal.lootValue));
		t.put("lastHours", duration(previousTotal.playSeconds));
		return t;
	}

	/** Fills {@code {token}}s; unknown tokens are left as typed. */
	static String fill(String template, Map<String, String> tokens)
	{
		if (template == null)
		{
			return "";
		}
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < template.length())
		{
			int open = template.indexOf('{', i);
			int close = open < 0 ? -1 : template.indexOf('}', open);
			if (open < 0 || close < 0)
			{
				out.append(template, i, template.length());
				break;
			}
			out.append(template, i, open);
			String value = tokens.get(template.substring(open + 1, close));
			out.append(value != null ? value : template.substring(open, close + 1));
			i = close + 1;
		}
		return out.toString();
	}

	/** 1234 → "1,234", 1234567 → "1.23M". */
	static String number(long n)
	{
		long a = Math.abs(n);
		if (a >= 1_000_000_000L)
		{
			return trim(n / 1e9) + "B";
		}
		if (a >= 10_000_000L)
		{
			return trim(n / 1e6) + "M";
		}
		if (a >= 1_000_000L)
		{
			return String.format(Locale.ENGLISH, "%.2fM", n / 1e6);
		}
		return String.format(Locale.ENGLISH, "%,d", n);
	}

	private static String trim(double v)
	{
		return v >= 100 ? String.format(Locale.ENGLISH, "%.0f", Math.floor(v)) : String.format(Locale.ENGLISH, "%.1f", Math.floor(v * 10) / 10);
	}

	static String gp(long n)
	{
		long a = Math.abs(n);
		if (a < 100_000)
		{
			return String.format(Locale.ENGLISH, "%,d gp", n);
		}
		String sign = n < 0 ? "-" : "";
		if (a < 1_000_000)
		{
			return sign + a / 1000 + "K gp";
		}
		if (a < 1_000_000_000L)
		{
			return sign + trim(a / 1e6) + "M gp";
		}
		return sign + trim(a / 1e9) + "B gp";
	}

	static String duration(long seconds)
	{
		long h = seconds / 3600;
		long m = seconds % 3600 / 60;
		if (h == 0)
		{
			return m + "m";
		}
		return m == 0 ? h + "h" : h + "h " + m + "m";
	}

	static String hour(int h)
	{
		int twelve = h % 12 == 0 ? 12 : h % 12;
		return twelve + (h < 12 ? "am" : "pm");
	}

	static List<Entry> top(List<Entry> list, int n)
	{
		return list.size() <= n ? list : Collections.unmodifiableList(list.subList(0, n));
	}
}
