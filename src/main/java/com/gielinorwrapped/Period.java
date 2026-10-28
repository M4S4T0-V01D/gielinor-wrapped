package com.gielinorwrapped;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** A span of whole days a recap covers. */
final class Period
{
	enum Kind
	{
		DAY("Daily", "day"),
		WEEK("Weekly", "week"),
		MONTH("Monthly", "month"),
		YEAR("Yearly", "year"),
		CUSTOM("", "time");

		final String label;
		final String noun;

		Kind(String label, String noun)
		{
			this.label = label;
			this.noun = noun;
		}
	}

	private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	final Kind kind;
	final LocalDate from;
	final LocalDate to;

	Period(Kind kind, LocalDate from, LocalDate to)
	{
		if (to.isBefore(from))
		{
			throw new IllegalArgumentException("Period ends before it starts");
		}
		this.kind = kind;
		this.from = from;
		this.to = to;
	}

	/** The period of this kind containing {@code day}. */
	static Period containing(Kind kind, LocalDate day, DayOfWeek weekStart)
	{
		switch (kind)
		{
			case WEEK:
			{
				LocalDate start = day.with(TemporalAdjusters.previousOrSame(weekStart));
				return new Period(kind, start, start.plusDays(6));
			}
			case MONTH:
				return new Period(kind, day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()));
			case YEAR:
				return new Period(kind, day.withDayOfYear(1), day.with(TemporalAdjusters.lastDayOfYear()));
			default:
				return new Period(Kind.DAY, day, day);
		}
	}

	/** The last complete period of this kind before {@code today}. */
	static Period previous(Kind kind, LocalDate today, DayOfWeek weekStart)
	{
		Period current = containing(kind, today, weekStart);
		return containing(kind, current.from.minusDays(1), weekStart);
	}

	/** The period of the same length just before this one, for comparisons. */
	Period before()
	{
		if (kind == Kind.CUSTOM)
		{
			long len = days();
			return new Period(kind, from.minusDays(len), from.minusDays(1));
		}
		return containing(kind, from.minusDays(1), DayOfWeek.from(from));
	}

	long days()
	{
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	/** "Weekly", or "" for custom ranges. */
	String label()
	{
		return kind.label;
	}

	String noun()
	{
		return kind.noun;
	}

	/** "26 Oct 2026", "19 Oct – 25 Oct 2026", "October 2026", "2026". */
	String describe()
	{
		switch (kind)
		{
			case DAY:
				return from.format(DAY_FORMAT);
			case MONTH:
				return from.format(MONTH_FORMAT);
			case YEAR:
				return Integer.toString(from.getYear());
			default:
				if (from.equals(to))
				{
					return from.format(DAY_FORMAT);
				}
				return (from.getYear() == to.getYear() ? from.format(SHORT_FORMAT) : from.format(DAY_FORMAT)) + " – " + to.format(DAY_FORMAT);
		}
	}

	/** A file-name-safe id such as {@code week-2026-10-19}. */
	String id()
	{
		return kind.name().toLowerCase(Locale.ROOT) + "-" + from + (kind == Kind.CUSTOM ? "-" + to : "");
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof Period))
		{
			return false;
		}
		Period p = (Period) o;
		return kind == p.kind && from.equals(p.from) && to.equals(p.to);
	}

	@Override
	public int hashCode()
	{
		return (kind.hashCode() * 31 + from.hashCode()) * 31 + to.hashCode();
	}

	@Override
	public String toString()
	{
		return id();
	}
}
