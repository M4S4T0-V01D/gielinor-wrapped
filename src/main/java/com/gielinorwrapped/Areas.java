package com.gielinorwrapped;

import java.util.HashMap;
import java.util.Map;

/** Names for well-known map regions (64x64 tile squares). Regions without a name aren't counted as areas. */
final class Areas
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		add("Lumbridge", 12850, 12849, 12851);
		add("Varrock", 12853, 12854, 12597);
		add("Grand Exchange", 12598);
		add("Falador", 11828, 11827, 12084);
		add("Edgeville", 12342);
		add("Draynor Village", 12338);
		add("Al Kharid", 13105, 13361);
		add("Seers' Village", 10806);
		add("Catherby", 11061, 11317);
		add("East Ardougne", 10547, 10548);
		add("Yanille", 10288);
		add("Rellekka", 10553);
		add("Prifddinas", 12894, 12895, 13150, 13151);
		add("Zulrah", 9007);
		add("Vorkath", 9023);
		add("Fight Caves", 9551);
		add("Inferno", 9043);
		add("Wintertodt", 6462);
		add("Tempoross", 12078, 12588);
		add("Barrows", 14131, 14231);
		add("Motherlode Mine", 14936);
		add("Castle Wars", 9520, 9776);
		add("Gnome Stronghold", 9781, 9782);
		add("Zeah: Hosidius", 6710, 6711);
		add("Zeah: Arceuus", 6458, 6459);
		add("Chambers of Xeric", 12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396, 13397, 13398, 13399, 13400, 13401);
		add("Theatre of Blood", 12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 14642);
		add("Gauntlet", 7512, 7768);
		add("Nightmare", 15515);
		add("God Wars Dungeon", 11346, 11347, 11601, 11602, 11603);
		add("Corporeal Beast", 11842, 11844);
		add("Kraken Cove", 9116);
		add("Cerberus", 4883, 5140, 5395);
		add("Slayer Tower", 13623, 13723);
		add("Catacombs of Kourend", 6556, 6557, 6812, 6813);
		add("Wilderness", 12090, 12091, 12092, 12093, 12346, 12347, 12348, 12349, 12602, 12603, 12604, 12605, 12858, 12859, 12860, 12861, 13114, 13115, 13116, 13117, 11834, 11835, 11836, 11837, 12603);
	}

	private Areas()
	{
	}

	private static void add(String name, int... regions)
	{
		for (int r : regions)
		{
			NAMES.putIfAbsent(r, name);
		}
	}

	static String name(int region)
	{
		return NAMES.get(region);
	}
}
