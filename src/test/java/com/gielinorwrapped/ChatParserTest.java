package com.gielinorwrapped;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ChatParserTest
{
	private static ChatParser.Result parse(String message, ChatParser.Kind kind)
	{
		ChatParser.Result r = ChatParser.parse(message);
		assertEquals(message, kind, r == null ? null : r.kind);
		return r;
	}

	@Test
	public void killCounts()
	{
		ChatParser.Result r = parse("Your Zulrah kill count is: 1,234.", ChatParser.Kind.KILL_COUNT);
		assertEquals("Zulrah", r.name);
		assertEquals(1234, r.number);

		r = parse("Your completed Chambers of Xeric count is: 12.", ChatParser.Kind.KILL_COUNT);
		assertEquals("Chambers of Xeric", r.name);
		assertEquals(12, r.number);

		r = parse("Your subdued Wintertodt count is: 500.", ChatParser.Kind.KILL_COUNT);
		assertEquals("Wintertodt", r.name);

		r = parse("Your Gnome Stronghold Agility Course lap count is: 30.", ChatParser.Kind.KILL_COUNT);
		assertEquals("Gnome Stronghold Agility Course", r.name);

		r = parse("Your Barrows chest count is: 7.", ChatParser.Kind.KILL_COUNT);
		assertEquals("Barrows chest", r.name);
	}

	@Test
	public void clues()
	{
		ChatParser.Result r = parse("You have completed 42 hard Treasure Trails.", ChatParser.Kind.CLUE);
		assertEquals("hard", r.name);
		assertEquals(42, r.number);
		r = parse("You have completed 1 master Treasure Trail.", ChatParser.Kind.CLUE);
		assertEquals("master", r.name);
	}

	@Test
	public void pets()
	{
		parse("You have a funny feeling like you're being followed.", ChatParser.Kind.PET);
		parse("You feel something weird sneaking into your backpack.", ChatParser.Kind.PET);
		parse("You have a funny feeling like you would have been followed...", ChatParser.Kind.PET);
	}

	@Test
	public void unlocks()
	{
		assertEquals("Dragon pickaxe", parse("New item added to your collection log: Dragon pickaxe", ChatParser.Kind.COLLECTION_LOG).name);
		assertEquals("Dragon Slayer II", parse("Congratulations, you've completed a quest: Dragon Slayer II", ChatParser.Kind.QUEST).name);

		ChatParser.Result r = parse("Congratulations, you've completed a hard combat task: Perfect Zulrah (3 points).", ChatParser.Kind.COMBAT_TASK);
		assertEquals("Perfect Zulrah", r.name);
		assertEquals("hard", r.detail);

		r = parse("Well done! You have completed an elite task in the Ardougne area. Your Achievement Diary has been updated.", ChatParser.Kind.DIARY);
		assertEquals("Ardougne", r.name);
		assertEquals("elite", r.detail);
	}

	@Test
	public void personalBests()
	{
		ChatParser.Result r = parse("Fight duration: 1:23.40 (new personal best)", ChatParser.Kind.PERSONAL_BEST);
		assertEquals(83, r.number);
		r = parse("Congratulations - your raid is complete! Team size: Solo Duration: 25:11 (new personal best)", ChatParser.Kind.PERSONAL_BEST);
		assertEquals(25 * 60 + 11, r.number);
	}

	@Test
	public void ignoresOtherMessages()
	{
		assertNull(ChatParser.parse("Welcome to Old School RuneScape."));
		// A kill that isn't a personal best still counts as a kill time.
		assertEquals(ChatParser.Kind.DURATION, ChatParser.parse("Fight duration: 1:30.00. Personal best: 1:23.40").kind);
		assertNull(ChatParser.parse(""));
		assertNull(ChatParser.parse(null));
	}

	@Test
	public void secondsParsing()
	{
		assertEquals(83, ChatParser.seconds("1:23.40"));
		assertEquals(3723, ChatParser.seconds("1:02:03"));
	}

	@Test
	public void dropImportance()
	{
		assertEquals(50, Tracker.dropImportance(1_000_000));
		assertEquals(67, Tracker.dropImportance(10_000_000));
		assertEquals(100, Tracker.dropImportance(10_000_000_000L));
	}
}
