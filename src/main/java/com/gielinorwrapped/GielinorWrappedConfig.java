package com.gielinorwrapped;

import java.awt.Color;
import java.time.DayOfWeek;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(GielinorWrappedConfig.GROUP)
public interface GielinorWrappedConfig extends Config
{
	String GROUP = "gielinorwrapped";

	enum VideoFormat
	{
		MP4,
		GIF
	}

	enum Aspect
	{
		LANDSCAPE("Landscape 16:9", 1280, 720),
		SQUARE("Square 1:1", 1080, 1080),
		VERTICAL("Vertical 9:16", 720, 1280);

		private final String label;
		final int width;
		final int height;

		Aspect(String label, int width, int height)
		{
			this.label = label;
			this.width = width;
			this.height = height;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Quality
	{
		SMALL("Small", 0.5),
		MEDIUM("Medium", 0.75),
		FULL("Full", 1.0);

		private final String label;
		final double scale;

		Quality(String label, double scale)
		{
			this.label = label;
			this.scale = scale;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum ThemeName
	{
		GIELINOR("Gielinor (parchment & gold)"),
		MIDNIGHT("Midnight"),
		NEON("Neon"),
		WILDERNESS("Wilderness"),
		CLEAN("Clean light"),
		RUNE("Rune (blue steel)"),
		DRAGON("Dragon (red & black)"),
		BARROWS("Barrows (crypt fog)"),
		ANCIENT("Ancient (Zaros purple)"),
		DESERT("Desert (Kharidian sand)"),
		FROST("Fremennik frost"),
		VAPORWAVE("Vaporwave"),
		NOIR("Noir"),
		RETRO("Retro 2007"),
		TOXIC("Toxic (Zulrah green)"),
		CUSTOM("Custom colours");

		private final String label;

		ThemeName(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Effect
	{
		AUTO("Theme default"),
		NONE("None"),
		MOTES("Drifting motes"),
		STARS("Stars"),
		EMBERS("Embers"),
		GRID("Synth grid"),
		BLOBS("Soft blobs"),
		SNOW("Snow"),
		RAIN("Rain"),
		BUBBLES("Bubbles"),
		SPARKLES("Sparkles"),
		AURORA("Aurora"),
		FOG("Fog"),
		RAYS("Light rays");

		private final String label;

		Effect(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Grade
	{
		NONE("None"),
		WARM("Warm"),
		COOL("Cool"),
		VINTAGE("Vintage"),
		SEPIA("Sepia"),
		MONO("Black & white"),
		VIVID("Vivid"),
		DREAMY("Dreamy"),
		TEAL_ORANGE("Teal & orange");

		private final String label;

		Grade(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum FontChoice
	{
		RUNESCAPE("RuneScape"),
		SANS("Sans"),
		SERIF("Serif"),
		MONO("Monospace");

		private final String label;

		FontChoice(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Border
	{
		NONE("None"),
		LINE("Line"),
		DOUBLE("Double line"),
		CORNERS("Corner brackets"),
		INTERFACE("Game interface"),
		POLAROID("Polaroid");

		private final String label;

		Border(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Pace
	{
		RELAXED("Relaxed", 1.25),
		NORMAL("Normal", 1.0),
		SNAPPY("Snappy", 0.75);

		private final String label;
		final double scale;

		Pace(String label, double scale)
		{
			this.label = label;
			this.scale = scale;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum FootageMode
	{
		BETWEEN("Between scenes"),
		BACKDROP("Behind stat scenes"),
		BOTH("Both"),
		OFF("Don't use");

		private final String label;

		FootageMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum CaptionStyle
	{
		BOXED("Boxed"),
		OUTLINE("Outlined"),
		SHADOW("Drop shadow"),
		KARAOKE("Word by word");

		private final String label;

		CaptionStyle(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum CaptionPosition
	{
		TOP,
		CENTER,
		BOTTOM;

		@Override
		public String toString()
		{
			return name().charAt(0) + name().substring(1).toLowerCase();
		}
	}

	enum Transition
	{
		CUT("Cut"),
		FADE("Fade"),
		SLIDE("Slide"),
		ZOOM("Zoom"),
		WIPE("Wipe"),
		PIXELATE("Pixelate"),
		FLASH("Flash"),
		IRIS("Iris"),
		SPIN("Spin"),
		MIXED("Mixed");

		private final String label;

		Transition(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum CardLayout
	{
		SUMMARY("Summary"),
		TOP_FIVE("Top fives"),
		HIGHLIGHT("Highlight moment"),
		MINIMAL("Minimal"),
		SPLASH("Splash"),
		POSTER("Poster"),
		HISCORES("Hiscores table"),
		SCROLL("Quest scroll"),
		RECEIPT("GE receipt"),
		VERSUS_LAST("Vs last time");

		private final String label;

		CardLayout(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum TitleStyle
	{
		SPLASH("Splash"),
		CLASSIC("Classic");

		private final String label;

		TitleStyle(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum ShotArea
	{
		GAME_VIEW("Game view"),
		WHOLE_WINDOW("Whole window");

		private final String label;

		ShotArea(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum ChromaPlacement
	{
		OVER_THEME("Over the theme background"),
		OVER_FOOTAGE("Over my other recordings"),
		OVER_STATS("Over stat scenes");

		private final String label;

		ChromaPlacement(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(name = "Tracking", description = "What Wrapped counts", position = 0)
	String tracking = "tracking";

	@ConfigSection(name = "Clips", description = "Recording gameplay for recap videos", position = 1)
	String clips = "clips";

	@ConfigSection(name = "Video", description = "Recap video output", position = 2)
	String video = "video";

	@ConfigSection(name = "Captions", description = "Caption text and style; see the README for {tokens}", position = 3)
	String captions = "captions";

	@ConfigSection(name = "Look", description = "Theme and colours for videos and share cards", position = 4)
	String look = "look";

	@ConfigSection(name = "Recap shots", description = "Screenshots you set up in game for your recaps", position = 5)
	String shots = "shots";

	@ConfigSection(name = "Schedule", description = "Make recaps automatically", position = 6)
	String schedule = "schedule";

	@ConfigSection(name = "Music", description = "Game music under recap videos (MP4 only)", position = 7)
	String music = "music";

	@ConfigSection(name = "Footage & emote", description = "Longer recordings you start and stop yourself, and your emote", position = 8)
	String footage = "footage";

	@ConfigSection(name = "Stickers", description = "Boss cutouts, game icons and your own pictures slapped on scenes", position = 9)
	String stickers = "stickers";

	@ConfigSection(name = "Death log", description = "Every death, with the Death Recap plugin's cards and replays when it's installed", position = 10)
	String deaths = "deaths";

	@ConfigSection(name = "Extra scenes", description = "Map, graphs, comparisons, goal pace, bosses, collection log, Grand Exchange, year montage and friend duels", position = 11)
	String extras = "extras";

	// Tracking

	@ConfigItem(keyName = "dropThreshold", name = "Big drop value", description = "Drops worth at least this many coins become highlight moments", section = tracking, position = 0)
	@Units(" gp")
	default int dropThreshold()
	{
		return 1_000_000;
	}

	@ConfigItem(keyName = "trackAreas", name = "Track areas", description = "Count time spent in named areas", section = tracking, position = 1)
	default boolean trackAreas()
	{
		return true;
	}

	@ConfigItem(keyName = "levelMoments", name = "Level-up moments from", description = "Level ups at or above this level become highlight moments", section = tracking, position = 2)
	@Range(min = 2, max = 99)
	default int levelMoments()
	{
		return 50;
	}

	@ConfigItem(keyName = "trackGe", name = "Track Grand Exchange", description = "Count gp spent and received on the Grand Exchange, and your biggest trades", section = tracking, position = 3)
	default boolean trackGe()
	{
		return true;
	}

	// Clips

	@ConfigItem(keyName = "recordClips", name = "Record clips", description = "Keep a short rolling recording of the game window and save it when something happens. Nothing leaves your computer.", section = clips, position = 0)
	default boolean recordClips()
	{
		return true;
	}

	@ConfigItem(keyName = "clipFps", name = "Clip frame rate", description = "Frames per second recorded", section = clips, position = 1)
	@Range(min = 5, max = 30)
	default int clipFps()
	{
		return 12;
	}

	@ConfigItem(keyName = "clipBefore", name = "Seconds before", description = "How much is kept from before the moment", section = clips, position = 2)
	@Range(min = 2, max = 20)
	@Units(Units.SECONDS)
	default int clipBefore()
	{
		return 6;
	}

	@ConfigItem(keyName = "clipAfter", name = "Seconds after", description = "How long recording continues after the moment", section = clips, position = 3)
	@Range(min = 1, max = 10)
	@Units(Units.SECONDS)
	default int clipAfter()
	{
		return 3;
	}

	@ConfigItem(keyName = "clipWidth", name = "Clip width", description = "Clips are scaled down to at most this width", section = clips, position = 4)
	@Range(min = 320, max = 1920)
	@Units(Units.PIXELS)
	default int clipWidth()
	{
		return 1280;
	}

	@ConfigItem(keyName = "clipHotkey", name = "Clip that hotkey", description = "Save the last few seconds as a clip", section = clips, position = 5)
	default Keybind clipHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "clipLevels", name = "Clip level ups", description = "Record level ups that are highlight moments", section = clips, position = 6)
	default boolean clipLevels()
	{
		return true;
	}

	@ConfigItem(keyName = "clipDrops", name = "Clip big drops", description = "Record drops above the big drop value", section = clips, position = 7)
	default boolean clipDrops()
	{
		return true;
	}

	@ConfigItem(keyName = "clipPets", name = "Clip pets & collection log", description = "Record pet drops and new collection log slots", section = clips, position = 8)
	default boolean clipPets()
	{
		return true;
	}

	@ConfigItem(keyName = "clipBosses", name = "Clip PBs & milestones", description = "Record personal bests, quests, combat tasks and kill count milestones", section = clips, position = 9)
	default boolean clipBosses()
	{
		return true;
	}

	@ConfigItem(keyName = "clipDeaths", name = "Clip deaths", description = "Record your deaths", section = clips, position = 10)
	default boolean clipDeaths()
	{
		return true;
	}

	@ConfigItem(keyName = "timelapse", name = "Timelapse", description = "Save a still every few seconds for a sped-up montage of your session", section = clips, position = 11)
	default boolean timelapse()
	{
		return true;
	}

	@ConfigItem(keyName = "timelapseInterval", name = "Timelapse interval", description = "Seconds between timelapse stills", section = clips, position = 12)
	@Range(min = 2, max = 120)
	@Units(Units.SECONDS)
	default int timelapseInterval()
	{
		return 15;
	}

	@ConfigItem(keyName = "hideChat", name = "Hide chat in clips", description = "Blur the chatbox and private messages in recordings", section = clips, position = 13)
	default boolean hideChat()
	{
		return true;
	}

	@ConfigItem(keyName = "diskBudget", name = "Disk space", description = "Clips are pruned, least important first, to stay under this size", section = clips, position = 14)
	@Range(min = 100, max = 50_000)
	@Units(" MB")
	default int diskBudget()
	{
		return 2048;
	}

	// Video

	@ConfigItem(keyName = "videoFormat", name = "Format", description = "MP4 is smaller and sharper; GIF plays anywhere", section = video, position = 0)
	default VideoFormat videoFormat()
	{
		return VideoFormat.MP4;
	}

	@ConfigItem(keyName = "aspect", name = "Shape", description = "Landscape for YouTube and Discord, vertical for TikTok, Shorts and Reels", section = video, position = 1)
	default Aspect aspect()
	{
		return Aspect.LANDSCAPE;
	}

	@ConfigItem(keyName = "quality", name = "Resolution", description = "Smaller videos render faster", section = video, position = 2)
	default Quality quality()
	{
		return Quality.FULL;
	}

	@ConfigItem(keyName = "videoFps", name = "Frame rate", description = "Frames per second in the recap video (GIFs use at most 15)", section = video, position = 3)
	@Range(min = 10, max = 30)
	default int videoFps()
	{
		return 30;
	}

	@ConfigItem(keyName = "maxLength", name = "Max length", description = "Longest recap video", section = video, position = 4)
	@Range(min = 10, max = 300)
	@Units(Units.SECONDS)
	default int maxLength()
	{
		return 60;
	}

	@ConfigItem(keyName = "targetSize", name = "Size limit", description = "Keep MP4s under this size (0 for no limit). Set 10 to post on Discord without Nitro.", section = video, position = 5)
	@Range(min = 0, max = 500)
	@Units(" MB")
	default int targetSize()
	{
		return 50;
	}

	@ConfigItem(keyName = "maxClips", name = "Clips per recap", description = "Most highlight clips in one recap", section = video, position = 6)
	@Range(min = 0, max = 30)
	default int maxClips()
	{
		return 6;
	}

	@ConfigItem(keyName = "transition", name = "Transition", description = "How scenes change", section = video, position = 7)
	default Transition transition()
	{
		return Transition.FADE;
	}

	@ConfigItem(keyName = "statScenes", name = "Stat scenes", description = "Include XP, loot, kills and time scenes", section = video, position = 8)
	default boolean statScenes()
	{
		return true;
	}

	@ConfigItem(keyName = "timelapseScene", name = "Timelapse scene", description = "Include a sped-up montage of your sessions", section = video, position = 9)
	default boolean timelapseScene()
	{
		return true;
	}

	@ConfigItem(keyName = "personaScene", name = "Persona scene", description = "End with a playful title based on how you played", section = video, position = 10)
	default boolean personaScene()
	{
		return true;
	}

	@ConfigItem(keyName = "pace", name = "Pace", description = "How long each scene stays on screen", section = video, position = 11)
	default Pace pace()
	{
		return Pace.NORMAL;
	}

	@ConfigItem(keyName = "transitionLength", name = "Transition length", description = "How long a transition takes", section = video, position = 12)
	@Range(min = 100, max = 1500)
	@Units(Units.MILLISECONDS)
	default int transitionLength()
	{
		return 500;
	}

	@ConfigItem(keyName = "introScene", name = "Title scene", description = "Open with the title", section = video, position = 13)
	default boolean introScene()
	{
		return true;
	}

	@ConfigItem(keyName = "outroScene", name = "Outro scene", description = "End with the closing card", section = video, position = 14)
	default boolean outroScene()
	{
		return true;
	}

	@ConfigItem(keyName = "progressBar", name = "Progress bar", description = "A thin bar along the bottom showing how far the video is", section = video, position = 15)
	default boolean progressBar()
	{
		return false;
	}

	@ConfigItem(keyName = "letterbox", name = "Cinema bars", description = "Black bars top and bottom for a film look", section = video, position = 16)
	default boolean letterbox()
	{
		return false;
	}

	@ConfigItem(keyName = "footageMode", name = "Recorded footage", description = "Where cuts from your own recordings go", section = video, position = 17)
	default FootageMode footageMode()
	{
		return FootageMode.BOTH;
	}

	@ConfigItem(keyName = "footageSegment", name = "Footage cut length", description = "Length of each cut taken from your recordings", section = video, position = 18)
	@Range(min = 1, max = 15)
	@Units(Units.SECONDS)
	default int footageSegment()
	{
		return 3;
	}

	@ConfigItem(keyName = "footageCuts", name = "Footage cuts", description = "Most cuts from your recordings in one recap", section = video, position = 19)
	@Range(max = 20)
	default int footageCuts()
	{
		return 6;
	}

	@ConfigItem(keyName = "emoteScene", name = "Show my emote", description = "Your captured emote dances on the title and outro", section = video, position = 20)
	default boolean emoteScene()
	{
		return true;
	}

	// Captions

	@ConfigItem(keyName = "captionsEnabled", name = "Captions", description = "Show captions on clips and scenes", section = captions, position = 0)
	default boolean captionsEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "captionStyle", name = "Caption style", description = "How captions are drawn", section = captions, position = 1)
	default CaptionStyle captionStyle()
	{
		return CaptionStyle.BOXED;
	}

	@ConfigItem(keyName = "captionPosition", name = "Caption position", description = "Where clip captions sit", section = captions, position = 2)
	default CaptionPosition captionPosition()
	{
		return CaptionPosition.BOTTOM;
	}

	@ConfigItem(keyName = "captionSize", name = "Caption size", description = "Caption text size as a percentage of the default", section = captions, position = 3)
	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	default int captionSize()
	{
		return 100;
	}

	@ConfigItem(keyName = "titleText", name = "Title", description = "Opening title. Tokens: {player} {period} {range}", section = captions, position = 4)
	default String titleText()
	{
		return "{player}'s {period} Wrapped";
	}

	@ConfigItem(keyName = "xpText", name = "XP scene", description = "Tokens: {xp} {topSkill} {topSkillXp} {levels}", section = captions, position = 5)
	default String xpText()
	{
		return "You gained {xp} XP|Mostly {topSkill}: {topSkillXp}";
	}

	@ConfigItem(keyName = "lootText", name = "Loot scene", description = "Tokens: {loot} {topDrop} {topDropValue} {topSource}", section = captions, position = 6)
	default String lootText()
	{
		return "{loot} in loot|Best drop: {topDrop}";
	}

	@ConfigItem(keyName = "killsText", name = "Kills scene", description = "Tokens: {kills} {topKill} {topKillCount} {deaths}", section = captions, position = 7)
	default String killsText()
	{
		return "{kills} kills|Favourite target: {topKill} ×{topKillCount}";
	}

	@ConfigItem(keyName = "timeText", name = "Time scene", description = "Tokens: {hours} {days} {topArea} {streak} {peakHour}", section = captions, position = 8)
	default String timeText()
	{
		return "{hours} played over {days}|Home away from home: {topArea}";
	}

	@ConfigItem(keyName = "clipText", name = "Clip captions", description = "Shown on each highlight clip. Tokens: {title} {detail} {value} {date} {time}", section = captions, position = 9)
	default String clipText()
	{
		return "{title}|{detail}";
	}

	@ConfigItem(keyName = "timelapseText", name = "Timelapse", description = "Tokens: {hours} {days} {periodNoun}", section = captions, position = 10)
	default String timelapseText()
	{
		return "Your {periodNoun} in fast-forward";
	}

	@ConfigItem(keyName = "personaText", name = "Persona", description = "Tokens: {persona} {personaBlurb}", section = captions, position = 11)
	default String personaText()
	{
		return "{persona}|{personaBlurb}";
	}

	@ConfigItem(keyName = "outroText", name = "Outro", description = "Closing card. Tokens: {player} {persona} {period}", section = captions, position = 12)
	default String outroText()
	{
		return "See you next {periodNoun}, {player}";
	}

	@ConfigItem(keyName = "watermark", name = "Watermark", description = "Small text in the corner of videos and cards (empty for none)", section = captions, position = 13)
	default String watermark()
	{
		return "Gielinor Wrapped";
	}

	@ConfigItem(keyName = "hideName", name = "Hide my name", description = "Use \"Adventurer\" instead of your display name", section = captions, position = 14)
	default boolean hideName()
	{
		return false;
	}

	@ConfigItem(keyName = "splashText", name = "Splash headline", description = "Big headline on the splash title and card. Tokens: {player} {period} {topSkill} {topDrop}", section = captions, position = 15)
	default String splashText()
	{
		return "{period} Wrapped";
	}

	@ConfigItem(keyName = "galleryText", name = "Gallery", description = "Caption on your recap shots. Tokens: {date} {time} {player}", section = captions, position = 16)
	default String galleryText()
	{
		return "{date}";
	}

	@ConfigItem(keyName = "customCaptions", name = "My captions", description = "Your own captions, one per line. Start a line with card:, title:, outro:, footage: or share: to pick where it goes (card: is the default). Use | for a smaller second line.", section = captions, position = 17)
	default String customCaptions()
	{
		return "";
	}

	@ConfigItem(keyName = "customCardSeconds", name = "Caption card length", description = "How long each of your caption cards stays up", section = captions, position = 18)
	@Range(min = 1, max = 10)
	@Units(Units.SECONDS)
	default int customCardSeconds()
	{
		return 3;
	}

	// Look

	@ConfigItem(keyName = "theme", name = "Theme", description = "Colours and backgrounds", section = look, position = 0)
	default ThemeName theme()
	{
		return ThemeName.GIELINOR;
	}

	@ConfigItem(keyName = "useAccent", name = "Custom accent", description = "Use the accent colour below instead of the theme's", section = look, position = 1)
	default boolean useAccent()
	{
		return false;
	}

	@Alpha
	@ConfigItem(keyName = "accent", name = "Accent colour", description = "Highlight colour for numbers and titles", section = look, position = 2)
	default Color accent()
	{
		return new Color(0xFFC83C);
	}

	@ConfigItem(keyName = "cardLayout", name = "Share card", description = "Share card layout", section = look, position = 3)
	default CardLayout cardLayout()
	{
		return CardLayout.SUMMARY;
	}

	@ConfigItem(keyName = "titleStyle", name = "Title style", description = "Splash: big outlined headline with your best items. Classic: plain text", section = look, position = 4)
	default TitleStyle titleStyle()
	{
		return TitleStyle.SPLASH;
	}

	@ConfigItem(keyName = "icons", name = "Item and skill icons", description = "Show game icons on cards and scenes", section = look, position = 5)
	default boolean icons()
	{
		return true;
	}

	@ConfigItem(keyName = "effect", name = "Background effect", description = "Animated decoration behind scenes and cards", section = look, position = 6)
	default Effect effect()
	{
		return Effect.AUTO;
	}

	@ConfigItem(keyName = "grade", name = "Colour grade", description = "A colour look applied to the whole video and card", section = look, position = 7)
	default Grade grade()
	{
		return Grade.NONE;
	}

	@ConfigItem(keyName = "font", name = "Font", description = "Typeface for titles, numbers and captions", section = look, position = 8)
	default FontChoice font()
	{
		return FontChoice.RUNESCAPE;
	}

	@ConfigItem(keyName = "vignette", name = "Vignette", description = "Darken the corners", section = look, position = 9)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int vignette()
	{
		return 0;
	}

	@ConfigItem(keyName = "filmGrain", name = "Film grain", description = "Moving grain over the picture (makes MP4s a little bigger)", section = look, position = 10)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int filmGrain()
	{
		return 0;
	}

	@ConfigItem(keyName = "scanlines", name = "Scanlines", description = "Old CRT monitor lines", section = look, position = 11)
	default boolean scanlines()
	{
		return false;
	}

	@ConfigItem(keyName = "border", name = "Frame border", description = "A frame around videos and cards", section = look, position = 12)
	default Border border()
	{
		return Border.NONE;
	}

	@ConfigItem(keyName = "customTop", name = "Custom: background top", description = "Used by the Custom colours theme", section = look, position = 13)
	default Color customTop()
	{
		return new Color(0x1E2A3A);
	}

	@ConfigItem(keyName = "customBottom", name = "Custom: background bottom", description = "Used by the Custom colours theme", section = look, position = 14)
	default Color customBottom()
	{
		return new Color(0x3A1E3A);
	}

	@ConfigItem(keyName = "customText", name = "Custom: text", description = "Used by the Custom colours theme", section = look, position = 15)
	default Color customText()
	{
		return new Color(0xFFFFFF);
	}

	@ConfigItem(keyName = "customAccent", name = "Custom: accent", description = "Used by the Custom colours theme", section = look, position = 16)
	default Color customAccent()
	{
		return new Color(0xFFD23F);
	}

	@ConfigItem(keyName = "customAccent2", name = "Custom: second accent", description = "Used by the Custom colours theme", section = look, position = 17)
	default Color customAccent2()
	{
		return new Color(0x3FD2FF);
	}

	// Recap shots

	@ConfigItem(keyName = "shotHotkey", name = "Shot hotkey", description = "Take a recap shot of the game. Pick its role (cover, outro or gallery) in the Wrapped panel", section = shots, position = 0)
	default Keybind shotHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "shotArea", name = "Shot area", description = "Game view leaves out the side panels and inventory", section = shots, position = 1)
	default ShotArea shotArea()
	{
		return ShotArea.GAME_VIEW;
	}

	@ConfigItem(keyName = "galleryScene", name = "Gallery scene", description = "Show your gallery shots in recap videos", section = shots, position = 2)
	default boolean galleryScene()
	{
		return true;
	}

	@ConfigItem(keyName = "shotsAsBackdrop", name = "Cover as backdrop", description = "Use your cover shot behind the title and share card", section = shots, position = 3)
	default boolean shotsAsBackdrop()
	{
		return true;
	}

	// Schedule

	@ConfigItem(keyName = "autoDaily", name = "Daily recap", description = "Make a recap of yesterday the first time you log in each day", section = schedule, position = 0)
	default boolean autoDaily()
	{
		return false;
	}

	@ConfigItem(keyName = "autoWeekly", name = "Weekly recap", description = "Make a recap of last week the first time you log in each week", section = schedule, position = 1)
	default boolean autoWeekly()
	{
		return true;
	}

	@ConfigItem(keyName = "autoMonthly", name = "Monthly recap", description = "Make a recap of last month", section = schedule, position = 2)
	default boolean autoMonthly()
	{
		return true;
	}

	@ConfigItem(keyName = "autoYearly", name = "Yearly recap", description = "Make a recap of last year", section = schedule, position = 3)
	default boolean autoYearly()
	{
		return true;
	}

	@ConfigItem(keyName = "weekStart", name = "Week starts on", description = "First day of a week", section = schedule, position = 4)
	default DayOfWeek weekStart()
	{
		return DayOfWeek.MONDAY;
	}

	@ConfigItem(keyName = "autoVideo", name = "Render video", description = "Scheduled recaps render a video as well as a share card", section = schedule, position = 5)
	default boolean autoVideo()
	{
		return true;
	}

	@ConfigItem(keyName = "makeVideo", name = "Video for my recaps", description = "The Video box under Make in the panel, remembered", hidden = true)
	default boolean makeVideo()
	{
		return true;
	}

	@ConfigItem(keyName = "notify", name = "Chat notification", description = "Say in chat when a recap is ready", section = schedule, position = 6)
	default boolean notifyReady()
	{
		return true;
	}

	// Music

	@ConfigItem(keyName = "music", name = "Music", description = "Play game music under MP4 recaps", section = music, position = 0)
	default boolean musicEnabled()
	{
		return true;
	}

	@ConfigItem(keyName = "musicTrack", name = "Track", description = "Name of a music track as in the music player, \"Current area\" for what was playing, or \"Random\". Pick from the list in the Wrapped panel.", section = music, position = 1)
	default String musicTrack()
	{
		return "Harmony";
	}

	@ConfigItem(keyName = "musicVolume", name = "Volume", description = "Music volume in the video", section = music, position = 2)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int musicVolume()
	{
		return 70;
	}

	@ConfigItem(keyName = "musicFade", name = "Fade in and out", description = "Fade the music at the start and end", section = music, position = 3)
	default boolean musicFade()
	{
		return true;
	}

	// Footage & emote

	@ConfigItem(keyName = "footageMax", name = "Longest recording", description = "Recordings stop by themselves after this long", section = footage, position = 0)
	@Range(min = 10, max = 900)
	@Units(Units.SECONDS)
	default int footageMax()
	{
		return 180;
	}

	@ConfigItem(keyName = "footageFps", name = "Recording frame rate", description = "Frames per second in your recordings", section = footage, position = 1)
	@Range(min = 2, max = 30)
	default int footageFps()
	{
		return 12;
	}

	@ConfigItem(keyName = "footageHotkey", name = "Start/stop hotkey", description = "Start or stop a recording", section = footage, position = 2)
	default Keybind footageHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "footageBudget", name = "Recording disk space", description = "Oldest recordings are deleted to stay under this size", section = footage, position = 3)
	@Range(min = 50, max = 20000)
	@Units(" MB")
	default int footageBudget()
	{
		return 1500;
	}

	@ConfigItem(keyName = "emoteHotkey", name = "Capture emote hotkey", description = "Get ready to capture your next emote; then do an emote in game", section = footage, position = 4)
	default Keybind emoteHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(keyName = "chromaKey", name = "Green screen (chroma key)", description = "Cut a colour out of your recordings, e.g. with the Green Screen plugin on, and put what's left over something else", section = footage, position = 5)
	default boolean chromaKey()
	{
		return false;
	}

	@ConfigItem(keyName = "chromaColor", name = "Key colour", description = "The colour to cut out", section = footage, position = 6)
	default Color chromaColor()
	{
		return new Color(0x00FF00);
	}

	@ConfigItem(keyName = "chromaTolerance", name = "Key tolerance", description = "How far from the key colour still gets cut", section = footage, position = 7)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int chromaTolerance()
	{
		return 30;
	}

	@ConfigItem(keyName = "chromaSoftness", name = "Key softness", description = "Feathered edge around what's kept", section = footage, position = 8)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int chromaSoftness()
	{
		return 20;
	}

	@ConfigItem(keyName = "chromaSpill", name = "Remove colour spill", description = "Take the green tint off the edges", section = footage, position = 9)
	default boolean chromaSpill()
	{
		return true;
	}

	@ConfigItem(keyName = "chromaPlacement", name = "Keyed footage goes", description = "What shows through where the colour was cut out", section = footage, position = 10)
	default ChromaPlacement chromaPlacement()
	{
		return ChromaPlacement.OVER_THEME;
	}

	// Stickers

	@ConfigItem(keyName = "bossStickers", name = "Boss & NPC cutouts", description = "Cut the NPC out of kill and drop clips and use it as a sticker", section = stickers, position = 0)
	default boolean bossStickers()
	{
		return true;
	}

	@ConfigItem(keyName = "iconStickers", name = "Game icon stickers", description = "Skill, prayer and other game icons on stat scenes", section = stickers, position = 1)
	default boolean iconStickers()
	{
		return true;
	}

	@ConfigItem(keyName = "myStickers", name = "My pictures", description = "PNG, JPG or GIF files from the stickers folder (open it from the Wrapped panel)", section = stickers, position = 2)
	default boolean myStickers()
	{
		return true;
	}

	@ConfigItem(keyName = "stickerSize", name = "Sticker size", description = "Sticker size as a percentage of the default", section = stickers, position = 3)
	@Range(min = 25, max = 300)
	@Units(Units.PERCENT)
	default int stickerSize()
	{
		return 100;
	}

	@ConfigItem(keyName = "stickerWobble", name = "Wobble", description = "Stickers bob and tilt a little", section = stickers, position = 4)
	default boolean stickerWobble()
	{
		return true;
	}

	@ConfigItem(keyName = "accountBadge", name = "Ironman badge", description = "Your Ironman, Ultimate, Hardcore or Group Ironman chat helmet on the title, outro, share card and friend duel", section = stickers, position = 5)
	default boolean accountBadge()
	{
		return true;
	}

	// Death log

	@ConfigItem(keyName = "deathScene", name = "Deaths scene", description = "Show your deaths in recap videos, using the Death Recap plugin's share cards when it's installed", section = deaths, position = 0)
	default boolean deathScene()
	{
		return true;
	}

	@ConfigItem(keyName = "deathMax", name = "Deaths shown", description = "Most deaths in the deaths scene; the costliest are picked", section = deaths, position = 1)
	@Range(min = 1, max = 20)
	default int deathMax()
	{
		return 6;
	}

	@ConfigItem(keyName = "deathText", name = "Death captions", description = "Caption under each death. Tokens: {killer} {place} {gpLost} {damage} {world} {date} {time}", section = deaths, position = 2)
	default String deathText()
	{
		return "{killer} · {date}";
	}

	@ConfigItem(keyName = "deathReplays", name = "Use replays", description = "Keep Death Recap's replay GIFs and use them as the clip for deaths that weren't recorded", section = deaths, position = 3)
	default boolean deathReplays()
	{
		return true;
	}

	@ConfigItem(keyName = "mapScene", name = "Where you played", description = "A glowing map of where you spent your time, with your top three places", section = extras, position = 0)
	default boolean mapScene()
	{
		return true;
	}

	@ConfigItem(keyName = "graphScene", name = "Graphs", description = "Bar charts of XP, time played and loot by day, week or month (by hour for one-day recaps)", section = extras, position = 1)
	default boolean graphScene()
	{
		return true;
	}

	@ConfigItem(keyName = "compareScene", name = "Vs last time", description = "This period's numbers next to the last one's", section = extras, position = 2)
	default boolean compareScene()
	{
		return true;
	}

	@ConfigItem(keyName = "paceScene", name = "Goal pace", description = "How long until your next big goal at this rate, e.g. 99 Mining in 41 days", section = extras, position = 3)
	default boolean paceScene()
	{
		return true;
	}

	@ConfigItem(keyName = "bossScene", name = "Bosses & Slayer", description = "Top bosses with their cutouts, kill counts, fastest kills and Slayer tasks", section = extras, position = 4)
	default boolean bossScene()
	{
		return true;
	}

	@ConfigItem(keyName = "collectionScene", name = "Collection log", description = "New collection log slots as an icon grid, with combat and diary tasks", section = extras, position = 5)
	default boolean collectionScene()
	{
		return true;
	}

	@ConfigItem(keyName = "geScene", name = "Grand Exchange", description = "gp bought and sold, your net and biggest trades", section = extras, position = 6)
	default boolean geScene()
	{
		return true;
	}

	@ConfigItem(keyName = "montageScene", name = "Year in review", description = "For recaps over two months: the best clip from each month", section = extras, position = 7)
	default boolean montageScene()
	{
		return true;
	}

	@ConfigItem(keyName = "versusScene", name = "Friend duel", description = "You vs the friend picked in the panel, from their friend file", section = extras, position = 8)
	default boolean versusScene()
	{
		return true;
	}
}
