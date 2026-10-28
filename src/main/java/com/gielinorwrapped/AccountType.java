package com.gielinorwrapped;

/** The account type the game reports, with the chat badge it shows next to the name. */
enum AccountType
{
	NORMAL(0, "", -1),
	IRONMAN(1, "Ironman", 2),
	ULTIMATE(2, "Ultimate Ironman", 3),
	HARDCORE(3, "Hardcore Ironman", 10),
	GROUP(4, "Group Ironman", 41),
	HARDCORE_GROUP(5, "Hardcore Group Ironman", 42),
	UNRANKED_GROUP(6, "Unranked Group Ironman", 43);

	/** The value of the account type varbit. */
	final int value;
	final String title;
	/** The badge's frame in the chat icon sprite (net.runelite.api.IconID), or -1 for none. */
	final int icon;

	AccountType(int value, String title, int icon)
	{
		this.value = value;
		this.title = title;
		this.icon = icon;
	}

	/** The type for a varbit value; unknown and unseen values are normal. */
	static AccountType of(int value)
	{
		for (AccountType t : values())
		{
			if (t.value == value)
			{
				return t;
			}
		}
		return NORMAL;
	}

	boolean hasBadge()
	{
		return icon >= 0;
	}
}
