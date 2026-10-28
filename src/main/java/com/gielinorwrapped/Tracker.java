package com.gielinorwrapped;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.item.ItemPrice;

/**
 * Counts the logged-in account's play into today's {@link DayRecord}. Every method runs on the client
 * thread; records are written on the I/O executor.
 */
@Slf4j
class Tracker
{
	/** Save about once a minute while playing. */
	private static final int SAVE_TICKS = 100;
	/** Mouse or keyboard input within this many client cycles (20 ms) counts as active play. */
	private static final int ACTIVE_CYCLES = 3000;
	/** Drops worth less than this aren't kept in the day's drop list. */
	private static final long MIN_LISTED_DROP = 10_000;
	/** A personal-best message within this many ticks of a kill-count message is credited to that boss. */
	private static final int PB_KC_TICKS = 5;
	private static final int GE_BASELINE_TICKS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final GielinorWrappedConfig config;
	private final StatsStore store;
	private final ExecutorService io;
	private final Consumer<Moment> onMoment;

	private long account = -1;
	private LocalDate date;
	private DayRecord today;
	/** False until the stored record for today has been read and merged; saving would overwrite it. */
	private boolean loaded;
	private int ticksSinceSave;
	private int tick;
	private int[] lastXp;
	private boolean baselinePending;
	private boolean loggedOut = true;
	private long carryMillis;
	private String lastKcName;
	private int lastKcTick = -100;
	private Moment lastPet;
	private int lastPetTick = -100;
	/** A kill time seen just before its kill-count message, waiting to be credited. */
	private long pendingTime;
	private int pendingTimeTick = -100;
	/** Per Grand Exchange slot: the item, the gp already counted and whether its trade is recorded. */
	private final int[] geItem = new int[8];
	private final long[] geSpent = new long[8];
	private final boolean[] geDone = new boolean[8];
	private final boolean[] geSeen = new boolean[8];
	/** Offers reported up to this tick are the state at login, not new trades. */
	private int geBaselineUntil = -100;
	private volatile String playerName;

	Tracker(Client client, ClientThread clientThread, ItemManager itemManager, GielinorWrappedConfig config,
		StatsStore store, ExecutorService io, Consumer<Moment> onMoment)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.config = config;
		this.store = store;
		this.io = io;
		this.onMoment = onMoment;
	}

	long getAccount()
	{
		return account;
	}

	String getPlayerName()
	{
		return playerName;
	}

	/** Today's record as JSON, for recaps that include today. Client thread only. */
	String snapshot()
	{
		return today == null ? null : store.gson().toJson(today);
	}

	LocalDate getDate()
	{
		return date;
	}

	void loggedIn()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return;
		}
		if (hash != account)
		{
			save();
			account = hash;
			playerName = null;
			startDay(LocalDate.now());
		}
		if (loggedOut)
		{
			today.logins++;
			loggedOut = false;
		}
		// Every skill is reported again after a login or world hop; take a fresh baseline on the next tick.
		baselinePending = true;
		// So are Grand Exchange offers, sometimes as empty first and then as they really are.
		geBaselineUntil = tick + GE_BASELINE_TICKS;
	}

	void loggedOut()
	{
		save();
		loggedOut = true;
		baselinePending = true;
		lastXp = null;
		// Offers are reported again on the next login; treat that as a fresh baseline.
		Arrays.fill(geSeen, false);
	}

	/** Saves and forgets the account; used on shutdown. */
	void close()
	{
		save();
		account = -1;
		today = null;
		date = null;
		lastXp = null;
		loggedOut = true;
	}

	private void startDay(LocalDate day)
	{
		date = day;
		today = new DayRecord(day.toString());
		loaded = false;
		long acc = account;
		execute(() ->
		{
			DayRecord stored = store.readDay(acc, day);
			clientThread.invokeLater(() ->
			{
				if (acc != account || !day.equals(date))
				{
					return;
				}
				if (stored != null)
				{
					stored.merge(today);
					today = stored;
				}
				loaded = true;
			});
		});
	}

	void save()
	{
		if (today == null || !loaded || account == -1)
		{
			return;
		}
		ticksSinceSave = 0;
		String json = store.gson().toJson(today);
		long acc = account;
		String day = today.date;
		execute(() ->
		{
			try
			{
				store.writeDay(acc, day, json);
			}
			catch (Exception e)
			{
				log.warn("Unable to save Wrapped stats", e);
			}
		});
	}

	void onTick()
	{
		tick++;
		if (account == -1 || today == null)
		{
			return;
		}
		LocalDate now = LocalDate.now();
		if (!now.equals(date))
		{
			save();
			startDay(now);
		}
		Player player = client.getLocalPlayer();
		if (playerName == null && player != null && player.getName() != null)
		{
			playerName = player.getName();
		}
		if (baselinePending)
		{
			takeBaseline();
		}
		today.accountType = client.getVarbitValue(VarbitID.IRONMAN);

		carryMillis += 600;
		if (carryMillis >= 1000)
		{
			long secs = carryMillis / 1000;
			carryMillis %= 1000;
			today.playSeconds += secs;
			today.hours[LocalTime.now().getHour()] += secs;
			if (Math.min(client.getMouseIdleTicks(), client.getKeyboardIdleTicks()) < ACTIVE_CYCLES)
			{
				today.activeSeconds += secs;
			}
			WorldPoint wp = config.trackAreas() ? location(player) : null;
			if (wp != null)
			{
				String area = Areas.name(wp.getRegionID());
				if (area != null)
				{
					today.areas.merge(area, secs, Long::sum);
				}
				today.regions.merge(Integer.toString(wp.getRegionID()), secs, Long::sum);
			}
		}

		if (++ticksSinceSave >= SAVE_TICKS)
		{
			save();
		}
	}

	private WorldPoint location(Player player)
	{
		if (player == null)
		{
			return null;
		}
		LocalPoint lp = player.getLocalLocation();
		if (lp == null)
		{
			return null;
		}
		return WorldPoint.fromLocalInstance(client, lp);
	}

	private void takeBaseline()
	{
		Skill[] skills = Skill.values();
		lastXp = new int[skills.length];
		for (Skill s : skills)
		{
			lastXp[s.ordinal()] = isOverall(s) ? -1 : client.getSkillExperience(s);
			if (!isOverall(s) && lastXp[s.ordinal()] > 0)
			{
				today.skillXp.put(s.getName(), (long) lastXp[s.ordinal()]);
			}
		}
		baselinePending = false;
	}

	@SuppressWarnings("deprecation")
	private static boolean isOverall(Skill s)
	{
		return s == Skill.OVERALL;
	}

	void onStat(Skill skill, int xp)
	{
		if (today == null || lastXp == null || baselinePending || isOverall(skill))
		{
			return;
		}
		int i = skill.ordinal();
		int prev = lastXp[i];
		lastXp[i] = xp;
		int gained = xp - prev;
		if (prev < 0 || gained <= 0 || gained > 50_000_000)
		{
			return;
		}
		String name = skill.getName();
		today.xp.merge(name, (long) gained, Long::sum);
		today.skillXp.merge(name, (long) xp, Math::max);
		int before = Experience.getLevelForXp(prev);
		int after = Experience.getLevelForXp(xp);
		if (after > before)
		{
			today.levels.merge(name, after - before, Integer::sum);
			if (after >= config.levelMoments() || after == Experience.MAX_REAL_LEVEL)
			{
				boolean max = after == Experience.MAX_REAL_LEVEL;
				int importance = max ? 100 : after >= 90 ? 70 : after % 10 == 0 ? 50 : 25 + after / 4;
				moment(max ? Moment.Type.MAX_LEVEL : Moment.Type.LEVEL_UP, after + " " + name, max ? "Maxed!" : "Level up", after, importance);
			}
		}
	}

	/** Records loot; {@code kill} counts one kill of the source. */
	void onLoot(String source, Collection<ItemStack> items, boolean kill)
	{
		if (today == null || source == null)
		{
			return;
		}
		long total = 0;
		long now = System.currentTimeMillis();
		for (ItemStack stack : items)
		{
			int id = itemManager.canonicalize(stack.getId());
			long value = (long) itemManager.getItemPrice(id) * stack.getQuantity();
			total += value;
			if (value >= MIN_LISTED_DROP)
			{
				String name = itemManager.getItemComposition(id).getName();
				today.addDrop(new DayRecord.Drop(now, id, name, stack.getQuantity(), value, source));
				if (value >= config.dropThreshold())
				{
					String title = stack.getQuantity() > 1 ? stack.getQuantity() + " x " + name : name;
					moment(Moment.Type.DROP, title, "from " + source, value, dropImportance(value)).itemId = id;
				}
			}
		}
		today.lootValue += total;
		today.lootBySource.merge(source, total, Long::sum);
		if (kill)
		{
			today.kills.merge(source, 1, Integer::sum);
		}
	}

	/** 1M is 50, each tenfold adds 17, capped at 100. */
	static int dropImportance(long value)
	{
		if (value <= 0)
		{
			return 0;
		}
		double tens = Math.log10(value) - 6;
		return (int) Math.max(20, Math.min(100, Math.round(50 + tens * 17)));
	}

	void onDeath()
	{
		if (today == null)
		{
			return;
		}
		today.deaths++;
		moment(Moment.Type.DEATH, "Oh dear, you are dead!", null, today.deaths, 40);
	}

	void onGameMessage(String message)
	{
		if (today == null)
		{
			return;
		}
		ChatParser.Result r = ChatParser.parse(message);
		if (r == null)
		{
			return;
		}
		switch (r.kind)
		{
			case KILL_COUNT:
			{
				int count = (int) Math.min(Integer.MAX_VALUE, r.number);
				today.killCounts.merge(r.name, count, Math::max);
				today.bossKills.merge(r.name, 1, Integer::sum);
				lastKcName = r.name;
				lastKcTick = tick;
				if (tick - pendingTimeTick <= PB_KC_TICKS && pendingTime > 0)
				{
					today.fastest.merge(r.name, pendingTime, Math::min);
					pendingTime = 0;
				}
				if (count == 1)
				{
					moment(Moment.Type.KILL_COUNT, "First " + r.name + " kill", "Kill count 1", count, 60);
				}
				else if (count % 100 == 0)
				{
					moment(Moment.Type.KILL_COUNT, count + " " + r.name, "Kill count milestone", count, count % 1000 == 0 ? 65 : 45);
				}
				break;
			}
			case CLUE:
			{
				today.clues.merge(r.name, 1, Integer::sum);
				int importance = clueImportance(r.name);
				moment(Moment.Type.CLUE, capitalize(r.name) + " clue", r.number + " completed", r.number, importance);
				break;
			}
			case PET:
				lastPet = moment(Moment.Type.PET, "Pet drop!", "You have a funny feeling…", 0, 100);
				lastPetTick = tick;
				break;
			case COLLECTION_LOG:
			{
				int id = itemIdByName(r.name);
				moment(Moment.Type.COLLECTION_LOG, r.name, "New collection log slot", 0, 55).itemId = id;
				// The log message right after a pet message names the pet.
				if (lastPet != null && tick - lastPetTick <= PB_KC_TICKS && lastPet.itemId == 0)
				{
					lastPet.itemId = id;
					lastPet.detail = r.name;
				}
				break;
			}
			case QUEST:
				moment(Moment.Type.QUEST, r.name, "Quest complete", 0, 50);
				break;
			case COMBAT_TASK:
				moment(Moment.Type.COMBAT_TASK, r.name, capitalize(r.detail) + " combat task", 0, 30 + tierIndex(r.detail) * 8);
				break;
			case DIARY:
				moment(Moment.Type.DIARY, r.name + " diary task", capitalize(r.detail) + " task", 0, 15 + tierIndex(r.detail) * 8);
				break;
			case PERSONAL_BEST:
			{
				String boss = tick - lastKcTick <= PB_KC_TICKS ? lastKcName : null;
				moment(Moment.Type.PERSONAL_BEST, boss == null ? "New personal best" : boss + " PB", r.detail, r.number, 60);
				killTime(boss, r.number);
				break;
			}
			case DURATION:
				killTime(tick - lastKcTick <= PB_KC_TICKS ? lastKcName : null, r.number);
				break;
			case SLAYER_TASK:
				today.slayerTasks++;
				if (r.name != null && r.number >= 100)
				{
					moment(Moment.Type.KILL_COUNT, r.number + " " + r.name, "Slayer task done", r.number, 25);
				}
				break;
		}
	}

	/** Credits a kill time to the boss, or holds it for a kill-count message that comes right after. */
	private void killTime(String boss, long seconds)
	{
		if (seconds <= 0)
		{
			return;
		}
		if (boss != null)
		{
			today.fastest.merge(boss, seconds, Math::min);
		}
		else
		{
			pendingTime = seconds;
			pendingTimeTick = tick;
		}
	}

	/**
	 * Counts gp moved by a Grand Exchange offer. The first report of each slot after login is a baseline;
	 * after that, gp spent (or received) since the last report is added, and the trade is listed once when
	 * the offer finishes or is cancelled part-way.
	 *
	 * @param state the offer's state name, e.g. BUYING, BOUGHT, CANCELLED_SELL, EMPTY
	 */
	void onGrandExchange(int slot, int itemId, String state, int quantitySold, long spent)
	{
		if (today == null || slot < 0 || slot >= geItem.length || state == null)
		{
			return;
		}
		boolean empty = "EMPTY".equals(state) || itemId <= 0;
		if (!geSeen[slot] || tick <= geBaselineUntil)
		{
			geSeen[slot] = true;
			geItem[slot] = empty ? 0 : itemId;
			geSpent[slot] = empty ? 0 : spent;
			geDone[slot] = !empty && isFinished(state);
			return;
		}
		if (empty)
		{
			geItem[slot] = 0;
			geSpent[slot] = 0;
			geDone[slot] = false;
			return;
		}
		if (itemId != geItem[slot] || spent < geSpent[slot])
		{
			// A new offer in this slot.
			geItem[slot] = itemId;
			geSpent[slot] = 0;
			geDone[slot] = false;
		}
		boolean buy = state.contains("BUY") || "BOUGHT".equals(state);
		long delta = spent - geSpent[slot];
		geSpent[slot] = spent;
		if (delta > 0)
		{
			if (buy)
			{
				today.geBought += delta;
			}
			else
			{
				today.geSold += delta;
			}
		}
		if (isFinished(state) && !geDone[slot] && spent > 0)
		{
			geDone[slot] = true;
			today.geTrades++;
			String name = itemManager.getItemComposition(itemId).getName();
			today.addTrade(new DayRecord.Trade(System.currentTimeMillis(), itemId, name, quantitySold, spent, buy));
		}
	}

	private static boolean isFinished(String state)
	{
		return "BOUGHT".equals(state) || "SOLD".equals(state) || state.startsWith("CANCELLED");
	}

	/** Today's drop with that name, else a tradeable item with exactly that name; 0 if neither. */
	private int itemIdByName(String name)
	{
		for (DayRecord.Drop d : today.drops)
		{
			if (d.itemId > 0 && name.equalsIgnoreCase(d.name))
			{
				return d.itemId;
			}
		}
		for (ItemPrice p : itemManager.search(name))
		{
			if (name.equalsIgnoreCase(p.getName()))
			{
				return p.getId();
			}
		}
		return 0;
	}

	private static int clueImportance(String tier)
	{
		switch (tier)
		{
			case "master":
				return 60;
			case "elite":
				return 50;
			case "hard":
				return 35;
			case "medium":
				return 25;
			case "easy":
				return 20;
			default:
				return 15;
		}
	}

	private static int tierIndex(String tier)
	{
		switch (tier == null ? "" : tier)
		{
			case "grandmaster":
				return 5;
			case "master":
				return 4;
			case "elite":
				return 3;
			case "hard":
				return 2;
			case "medium":
				return 1;
			default:
				return 0;
		}
	}

	static String capitalize(String s)
	{
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Saves a "Clip that" moment so the clip shows up with the day's highlights. */
	Moment manualMoment()
	{
		if (today == null)
		{
			return null;
		}
		return moment(Moment.Type.MANUAL, "Clip", null, 0, 45);
	}

	private Moment moment(Moment.Type type, String title, String detail, long value, int importance)
	{
		Moment m = new Moment(System.currentTimeMillis(), type, title, detail, value, importance);
		today.addMoment(m);
		onMoment.accept(m);
		return m;
	}

	/** Called on the client thread once a moment's clip is on disk. */
	void clipSaved(Moment m, String clip)
	{
		m.clip = clip;
	}

	private void execute(Runnable r)
	{
		try
		{
			io.execute(r);
		}
		catch (RejectedExecutionException e)
		{
			log.debug("I/O executor is shut down");
		}
	}
}
