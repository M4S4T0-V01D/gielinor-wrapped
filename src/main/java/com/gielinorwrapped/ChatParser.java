package com.gielinorwrapped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognises the game messages Wrapped counts. Input is a game message with colour tags already removed. */
final class ChatParser
{
	enum Kind
	{
		KILL_COUNT,
		CLUE,
		PET,
		COLLECTION_LOG,
		QUEST,
		COMBAT_TASK,
		DIARY,
		PERSONAL_BEST,
		/** A kill time that isn't a personal best. */
		DURATION,
		SLAYER_TASK
	}

	static final class Result
	{
		final Kind kind;
		/** Boss, clue tier, item, quest, task, area or Slayer monster name. */
		final String name;
		/** Kill count, clue count, kill time in seconds or Slayer task size; 0 when the message has none. */
		final long number;
		/** Extra detail: the task tier for combat tasks and diaries, the duration text for personal bests. */
		final String detail;

		Result(Kind kind, String name, long number, String detail)
		{
			this.kind = kind;
			this.name = name;
			this.number = number;
			this.detail = detail;
		}
	}

	private static final Pattern KILL_COUNT = Pattern.compile("Your (?:completed |subdued )?(.+?) (?:kill |success |completion |lap )?count is: ?([\\d,]+)\\.?");
	private static final Pattern CLUE = Pattern.compile("You have completed ([\\d,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.?");
	private static final Pattern PET = Pattern.compile("You (?:have a funny feeling like you(?:'re being| would have been) followed|feel something weird sneaking into your backpack).*");
	private static final Pattern COLLECTION_LOG = Pattern.compile("New item added to your collection log: (.+)");
	private static final Pattern QUEST = Pattern.compile("Congratulations, you've completed a quest: (.+?)\\.?");
	private static final Pattern COMBAT_TASK = Pattern.compile("Congratulations, you've completed an? (easy|medium|hard|elite|master|grandmaster) combat task: (.+?)(?: \\(\\d+ points?\\))?\\.?");
	private static final Pattern DIARY = Pattern.compile("Well done! You have completed an? (easy|medium|hard|elite) task in the (.+?) area\\..*");
	private static final Pattern PERSONAL_BEST = Pattern.compile(".*?[Dd]uration: ([\\d:.]+) \\(new personal best\\).*");
	private static final Pattern DURATION = Pattern.compile(".*?[Dd]uration: (\\d+(?::\\d+)+(?:\\.\\d+)?).*");
	private static final Pattern SLAYER_TASK = Pattern.compile("You(?:'ve| have) completed your (?:boss )?task! You killed ([\\d,]+) (.+?)\\..*|You(?:'ve| have) completed your (?:boss )?task!.*");

	private ChatParser()
	{
	}

	static Result parse(String message)
	{
		if (message == null || message.isEmpty())
		{
			return null;
		}
		Matcher m = KILL_COUNT.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.KILL_COUNT, m.group(1), number(m.group(2)), null);
		}
		m = CLUE.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.CLUE, m.group(2), number(m.group(1)), null);
		}
		if (PET.matcher(message).matches())
		{
			return new Result(Kind.PET, "Pet", 0, null);
		}
		m = COLLECTION_LOG.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.COLLECTION_LOG, m.group(1).trim(), 0, null);
		}
		m = QUEST.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.QUEST, m.group(1).trim(), 0, null);
		}
		m = COMBAT_TASK.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.COMBAT_TASK, m.group(2).trim(), 0, m.group(1));
		}
		m = DIARY.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.DIARY, m.group(2).trim(), 0, m.group(1));
		}
		m = PERSONAL_BEST.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.PERSONAL_BEST, null, seconds(m.group(1)), m.group(1));
		}
		m = DURATION.matcher(message);
		if (m.matches())
		{
			return new Result(Kind.DURATION, null, seconds(m.group(1)), m.group(1));
		}
		m = SLAYER_TASK.matcher(message);
		if (m.matches())
		{
			return m.group(2) != null
				? new Result(Kind.SLAYER_TASK, m.group(2).trim(), number(m.group(1)), null)
				: new Result(Kind.SLAYER_TASK, null, 0, null);
		}
		return null;
	}

	private static long number(String s)
	{
		try
		{
			return Long.parseLong(s.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** "1:23.40" or "1:02:03" to whole seconds. */
	static long seconds(String s)
	{
		long total = 0;
		for (String part : s.split(":"))
		{
			int dot = part.indexOf('.');
			total = total * 60 + number(dot >= 0 ? part.substring(0, dot) : part);
		}
		return total;
	}
}
