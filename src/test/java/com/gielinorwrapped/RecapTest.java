package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class RecapTest
{
	private static DayRecord day(String date, long playSecs, String skill, long xp)
	{
		DayRecord d = new DayRecord(date);
		d.playSeconds = playSecs;
		d.hours[20] = playSecs;
		d.xp.put(skill, xp);
		return d;
	}

	@Test
	public void periods()
	{
		LocalDate sun = LocalDate.of(2026, 10, 25);
		Period week = Period.containing(Period.Kind.WEEK, sun, DayOfWeek.MONDAY);
		assertEquals(LocalDate.of(2026, 10, 19), week.from);
		assertEquals(LocalDate.of(2026, 10, 25), week.to);
		assertEquals("19 Oct – 25 Oct 2026", week.describe());
		assertEquals(Period.containing(Period.Kind.WEEK, LocalDate.of(2026, 10, 12), DayOfWeek.MONDAY), week.before());

		Period lastMonth = Period.previous(Period.Kind.MONTH, LocalDate.of(2026, 10, 26), DayOfWeek.MONDAY);
		assertEquals(LocalDate.of(2026, 9, 1), lastMonth.from);
		assertEquals(LocalDate.of(2026, 9, 30), lastMonth.to);
		assertEquals("September 2026", lastMonth.describe());
		assertEquals(LocalDate.of(2025, 1, 1), Period.previous(Period.Kind.YEAR, LocalDate.of(2026, 1, 1), DayOfWeek.MONDAY).from);

		Period custom = new Period(Period.Kind.CUSTOM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10));
		assertEquals(LocalDate.of(2026, 9, 21), custom.before().from);
		assertEquals("custom-2026-10-01-2026-10-10", custom.id());
	}

	@Test
	public void summarises()
	{
		Period week = Period.containing(Period.Kind.WEEK, LocalDate.of(2026, 10, 19), DayOfWeek.MONDAY);
		DayRecord mon = day("2026-10-19", 3600, "Mining", 200_000);
		DayRecord tue = day("2026-10-20", 7200, "Mining", 300_000);
		tue.xp.put("Fishing", 50_000L);
		tue.kills.put("Zulrah", 12);
		tue.addDrop(new DayRecord.Drop(1, 12922, "Tanzanite fang", 1, 3_000_000, "Zulrah"));
		tue.addMoment(new Moment(5, Moment.Type.DROP, "Tanzanite fang", null, 3_000_000, 58));
		tue.addMoment(new Moment(2, Moment.Type.LEVEL_UP, "80 Mining", null, 80, 50));
		DayRecord thu = day("2026-10-22", 1800, "Mining", 10_000);

		Recap r = Recap.build(week, "Zezima", Arrays.asList(mon, tue, thu), Collections.singletonList(day("2026-10-12", 60, "Mining", 280_000)));
		assertEquals(3, r.daysPlayed);
		assertEquals(2, r.longestStreak);
		assertEquals("Mining", r.skills.get(0).name);
		assertEquals(LocalDate.of(2026, 10, 20), r.busiestDay);
		assertEquals(Integer.valueOf(100), r.xpChange);
		assertEquals("Tanzanite fang", r.highlights.get(0).title);
		assertEquals("The Gatherer", r.persona.title);

		Map<String, String> t = r.tokens(false);
		assertEquals("Zezima's Weekly Wrapped", Recap.fill("{player}'s {period} Wrapped", t));
		assertEquals("You gained 560,000 XP|Mostly Mining: 510,000 XP {nope}", Recap.fill("You gained {xp} XP|Mostly {topSkill}: {topSkillXp} {nope}", t));
		assertEquals("3h 30m played over 3 days", Recap.fill("{hours} played over {days}", t));
		assertEquals("Adventurer", r.tokens(true).get("player"));
		assertEquals("8pm", t.get("peakHour"));
		assertEquals("+100%", t.get("xpChange"));
	}

	@Test
	public void noComparisonWithoutHistory()
	{
		Period day = Period.containing(Period.Kind.DAY, LocalDate.of(2026, 10, 26), DayOfWeek.MONDAY);
		Recap r = Recap.build(day, null, Collections.emptyList(), Collections.emptyList());
		assertNull(r.xpChange);
		assertEquals("The Explorer", r.persona.title);
		assertEquals("nothing yet", r.tokens(false).get("topSkill"));
	}

	@Test
	public void formatting()
	{
		assertEquals("999", Recap.number(999));
		assertEquals("1.23M", Recap.number(1_234_567));
		assertEquals("12.3M", Recap.number(12_345_678));
		assertEquals("2.1B", Recap.number(2_147_483_647));
		assertEquals("54,321 gp", Recap.gp(54_321));
		assertEquals("250K gp", Recap.gp(250_000));
		assertEquals("2.5M gp", Recap.gp(2_500_000));
		assertEquals("-3.1M gp", Recap.gp(-3_100_000));
		assertEquals("1.2B gp", Recap.gp(1_250_000_000L));
		assertEquals("-2.1B gp", Recap.gp(-2_147_000_000L));
		assertEquals("45.6M gp", Recap.gp(45_600_000));
		assertEquals("45m", Recap.duration(2700));
		assertEquals("2h", Recap.duration(7200));
		assertEquals("12am", Recap.hour(0));
		assertEquals("12pm", Recap.hour(12));
	}
}
