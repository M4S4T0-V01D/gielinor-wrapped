package com.gielinorwrapped;

/** Something worth remembering: a level up, a big drop, a pet. Recaps are built around these. */
public class Moment
{
	public enum Type
	{
		LEVEL_UP,
		MAX_LEVEL,
		DROP,
		PET,
		COLLECTION_LOG,
		QUEST,
		COMBAT_TASK,
		DIARY,
		CLUE,
		PERSONAL_BEST,
		KILL_COUNT,
		DEATH,
		MANUAL
	}

	public long time;
	public Type type;
	/** Short headline, e.g. "99 Fishing" or "Twisted bow". */
	public String title;
	/** Optional second line, e.g. "from Chambers of Xeric". */
	public String detail;
	/** Coin value for drops, the new level for level ups, the count for kill counts. */
	public long value;
	/** 0-100; higher is more likely to make the recap. */
	public int importance;
	/** Item shown with drops, pets and collection log slots; 0 when unknown. */
	public int itemId;
	/** Clip file name in the clip library, or null if no clip was recorded. */
	public String clip;

	public Moment()
	{
	}

	public Moment(long time, Type type, String title, String detail, long value, int importance)
	{
		this.time = time;
		this.type = type;
		this.title = title;
		this.detail = detail;
		this.value = value;
		this.importance = importance;
	}
}
