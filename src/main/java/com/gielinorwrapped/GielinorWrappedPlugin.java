package com.gielinorwrapped;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import com.gielinorwrapped.music.TrackLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.MidiRequest;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Gielinor Wrapped",
	internalName = "gielinor-wrapped",
	description = "Your OSRS stats and highlight clips turned into share cards and recap videos",
	tags = {"wrapped", "recap", "stats", "video", "share", "clips"}
)
public class GielinorWrappedPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private DrawManager drawManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private GielinorWrappedConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	private ExecutorService io;
	private StatsStore store;
	private DeathLog deathLog;
	private ClipLibrary library;
	private Tracker tracker;
	private Capture capture;
	private Recaps recaps;
	private Shots shots;
	/** Account and day the recap schedule was last checked for. Client thread. */
	private String scheduleChecked;
	private WrappedPanel panel;
	private NavigationButton navButton;
	/** Account whose recaps the panel shows; the last one logged in. */
	private volatile long viewAccount = -1;
	private static final int MUSIC_INDEX = 6;
	/** Client thread only, like the two fields after it. */
	private Map<String, Integer> musicArchives;
	private List<String> musicNames = new ArrayList<>();
	private boolean tracksSent;
	private int lastSongArchive = -1;
	private final AtomicBoolean jobsDirty = new AtomicBoolean();
	/** Card thumbnails by recap directory and creation time. */
	private final Map<String, Image> thumbCache = new ConcurrentHashMap<>();

	private final HotkeyListener shotHotkey = new HotkeyListener(() -> config.shotHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::takeShot);
		}
	};

	private final HotkeyListener clipHotkey = new HotkeyListener(() -> config.clipHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(() ->
			{
				if (tracker != null && client.getGameState() == GameState.LOGGED_IN)
				{
					tracker.manualMoment();
				}
			});
		}
	};

	private final HotkeyListener footageHotkey = new HotkeyListener(() -> config.footageHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::toggleRecording);
		}
	};

	private final HotkeyListener emoteHotkey = new HotkeyListener(() -> config.emoteHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::captureEmote);
		}
	};

	@Provides
	GielinorWrappedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GielinorWrappedConfig.class);
	}

	@Override
	protected void startUp() throws IOException
	{
		io = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "gielinor-wrapped-io");
			t.setDaemon(true);
			return t;
		});
		store = new StatsStore(gson, getPluginDirectory());
		library = new ClipLibrary(store);
		tracker = new Tracker(client, clientThread, itemManager, config, store, io, this::onMoment);
		capture = new Capture(client, drawManager, config, library);
		capture.start();
		shots = new Shots(store);
		deathLog = new DeathLog(store);
		recaps = new Recaps(store, library, shots, deathLog, new GameArt(), config, this::onJob);

		panel = new WrappedPanel(new PanelActions(), config);
		capture.setListener(new CaptureEvents());
		navButton = NavigationButton.builder()
			.tooltip("Gielinor Wrapped")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		keyManager.registerKeyListener(clipHotkey);
		keyManager.registerKeyListener(shotHotkey);
		keyManager.registerKeyListener(footageHotkey);
		keyManager.registerKeyListener(emoteHotkey);
		clientThread.invokeLater(() ->
		{
			tracksSent = false;
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				tracker.loggedIn();
				sendTrackNames();
			}
		});
	}

	@Override
	protected void shutDown() throws InterruptedException
	{
		keyManager.unregisterKeyListener(clipHotkey);
		keyManager.unregisterKeyListener(shotHotkey);
		keyManager.unregisterKeyListener(footageHotkey);
		keyManager.unregisterKeyListener(emoteHotkey);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		viewAccount = -1;
		eventBus.post(new PluginMessage(DeathLog.NAMESPACE, "goodbye"));
		thumbCache.clear();
		capture.stop();
		capture = null;
		recaps.close();
		recaps = null;
		scheduleChecked = null;
		Tracker t = tracker;
		tracker = null;
		if (t != null)
		{
			// close() serializes on the calling thread; shutDown runs on the client thread
			t.close();
		}
		io.shutdown();
		if (!io.awaitTermination(5, TimeUnit.SECONDS))
		{
			log.warn("Gielinor Wrapped I/O did not finish in time");
		}
		io = null;
		store = null;
		deathLog = null;
		library = null;
		shots = null;
	}

	/** Takes a recap shot of the next frame. Client thread. */
	private void takeShot()
	{
		Tracker t = tracker;
		Capture c = capture;
		Shots s = shots;
		ExecutorService exec = io;
		long account = t == null ? -1 : t.getAccount();
		if (c == null || s == null || exec == null || account == -1 || client.getGameState() != GameState.LOGGED_IN)
		{
			WrappedPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(() -> p.showMessage("Log in to take recap shots.", true));
			}
			return;
		}
		long now = System.currentTimeMillis();
		c.shot(config.shotArea() == GielinorWrappedConfig.ShotArea.GAME_VIEW, exec, img ->
		{
			if (img == null)
			{
				return;
			}
			try
			{
				s.save(account, now, Shots.Role.GALLERY, img);
			}
			catch (IOException e)
			{
				log.warn("Unable to save the recap shot", e);
				return;
			}
			refreshShots();
			if (config.notifyReady())
			{
				clientThread.invokeLater(() -> chat("Recap shot saved.", " Set it as the cover or outro in the Gielinor Wrapped panel."));
			}
		});
	}

	/** Starts or stops a footage recording. Client thread. */
	private void toggleRecording()
	{
		Capture c = capture;
		Tracker t = tracker;
		if (c == null || t == null)
		{
			return;
		}
		if (c.isRecording())
		{
			c.stopFootage();
			return;
		}
		if (t.getAccount() == -1 || client.getGameState() != GameState.LOGGED_IN)
		{
			showRecordMessage("Log in to record.");
			return;
		}
		c.startFootage(t.getAccount());
	}

	/** Client thread. */
	private void captureEmote()
	{
		Capture c = capture;
		Tracker t = tracker;
		if (c != null && t != null)
		{
			c.armEmote(client.getGameState() == GameState.LOGGED_IN ? t.getAccount() : -1);
		}
	}

	private void showRecordMessage(String text)
	{
		WrappedPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.showRecordMessage(text));
		}
	}

	/** Recording state and capture news, shown in the panel and the chat box. Any thread. */
	private final class CaptureEvents implements Capture.Listener
	{
		@Override
		public void footage(boolean recording, long start)
		{
			WrappedPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(() -> p.setRecording(recording, start));
			}
			if (recording)
			{
				clientThread.invokeLater(() -> chat("Recording started.", " Stop it from the Wrapped panel or the hotkey."));
			}
		}

		@Override
		public void message(String text)
		{
			showRecordMessage(text);
			clientThread.invokeLater(() ->
			{
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					chat("Gielinor Wrapped: ", text);
				}
			});
		}

		@Override
		public void recordings(long account)
		{
			if (account == viewAccount)
			{
				refreshRecordings();
			}
		}
	}

	/** Reloads the panel's list of footage recordings off the Swing thread. */
	private void refreshRecordings()
	{
		long account = viewAccount;
		ClipLibrary lib = library;
		WrappedPanel p = panel;
		ExecutorService exec = io;
		if (lib == null || p == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			List<ClipLibrary.Recording> list = account == -1 ? java.util.Collections.emptyList() : lib.listRecordings(account);
			List<ClipLibrary.Recording> newest = new java.util.ArrayList<>(list);
			newest.sort((a, b) -> Long.compare(b.start, a.start));
			SwingUtilities.invokeLater(() -> p.showRecordings(newest));
		});
	}

	/** Reloads the panel's recap shots off the Swing thread. */
	private void refreshShots()
	{
		long account = viewAccount;
		Shots s = shots;
		WrappedPanel p = panel;
		ExecutorService exec = io;
		if (s == null || p == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			List<Shots.Shot> list = new java.util.ArrayList<>();
			Map<String, Image> thumbs = new HashMap<>();
			try
			{
				if (account != -1)
				{
					list = s.list(account);
				}
			}
			catch (IOException e)
			{
				log.debug("Unable to list recap shots", e);
			}
			for (Shots.Shot shot : list)
			{
				String key = "shot@" + shot.time;
				Image thumb = thumbCache.get(key);
				if (thumb == null)
				{
					BufferedImage img = readImage(shot.file);
					if (img == null)
					{
						continue;
					}
					thumb = ShareCard.scale(img, WrappedPanel.SHOT_THUMB_WIDTH);
					thumbCache.put(key, thumb);
				}
				thumbs.put(shot.file.toString(), thumb);
			}
			List<Shots.Shot> shown = list;
			SwingUtilities.invokeLater(() -> p.showShots(shown, thumbs));
		});
	}

	/** Game sprites for recaps. Called off the client thread; item icons load on it, so this waits briefly. */
	private final class GameArt implements Recaps.Art
	{
		@Override
		public BufferedImage item(int id)
		{
			AsyncBufferedImage img = itemManager.getImage(id);
			CountDownLatch loaded = new CountDownLatch(1);
			img.onLoaded(loaded::countDown);
			try
			{
				if (!loaded.await(2, TimeUnit.SECONDS))
				{
					return null;
				}
			}
			catch (InterruptedException e)
			{
				return null;
			}
			BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = copy.createGraphics();
			g.drawImage(img, 0, 0, null);
			g.dispose();
			return copy;
		}

		@Override
		public BufferedImage skill(String name)
		{
			for (Skill s : Skill.values())
			{
				if (s.getName().equalsIgnoreCase(name))
				{
					return skillIconManager.getSkillImage(s);
				}
			}
			return null;
		}

		@Override
		public BufferedImage badge(AccountType type)
		{
			if (type == null || !type.hasBadge())
			{
				return null;
			}
			CompletableFuture<BufferedImage> img = new CompletableFuture<>();
			spriteManager.getSpriteAsync(SpriteID.MOD_ICONS, type.icon, img::complete);
			try
			{
				return img.get(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException | ExecutionException | TimeoutException e)
			{
				return null;
			}
		}

		@Override
		public byte[] song(String track)
		{
			CompletableFuture<byte[]> data = new CompletableFuture<>();
			clientThread.invoke(() ->
			{
				try
				{
					data.complete(loadTrack(track));
				}
				catch (RuntimeException e)
				{
					data.completeExceptionally(e);
				}
			});
			try
			{
				byte[] raw = data.get(5, TimeUnit.SECONDS);
				return raw == null ? null : TrackLoader.toMidi(raw);
			}
			catch (InterruptedException e)
			{
				return null;
			}
			catch (ExecutionException | TimeoutException | RuntimeException e)
			{
				log.debug("Unable to load music track {}", track, e);
				return null;
			}
		}
	}

	/** Client thread. Display name to music archive, for every track in the game's music table. */
	private Map<String, Integer> musicTable()
	{
		if (musicArchives == null)
		{
			Map<String, Integer> archives = new HashMap<>();
			List<String> visible = new ArrayList<>();
			for (int row : client.getDBTableRows(DBTableID.Music.ID))
			{
				Object name = first(client.getDBTableField(row, DBTableID.Music.COL_DISPLAYNAME, 0));
				Object midi = first(client.getDBTableField(row, DBTableID.Music.COL_MIDI, 0));
				Object hidden = first(client.getDBTableField(row, DBTableID.Music.COL_HIDDEN, 0));
				if (!(name instanceof String) || !(midi instanceof Number))
				{
					continue;
				}
				String n = Text.removeTags((String) name).trim();
				if (n.isEmpty() || archives.containsKey(n))
				{
					continue;
				}
				archives.put(n, ((Number) midi).intValue());
				boolean isHidden = hidden instanceof Number ? ((Number) hidden).intValue() != 0 : Boolean.TRUE.equals(hidden);
				if (!isHidden)
				{
					visible.add(n);
				}
			}
			visible.sort(String.CASE_INSENSITIVE_ORDER);
			musicArchives = archives;
			musicNames = visible;
		}
		return musicArchives;
	}

	private static Object first(Object[] values)
	{
		return values == null || values.length == 0 ? null : values[0];
	}

	/** Client thread. The raw cache track for a name, "Random" or "Current area"; null if there is none. */
	private byte[] loadTrack(String track)
	{
		Map<String, Integer> table = musicTable();
		int archive = -1;
		String t = track == null ? "" : track.trim();
		if (t.equalsIgnoreCase("Current area"))
		{
			archive = lastSongArchive;
		}
		else if (!t.equalsIgnoreCase("Random"))
		{
			Integer a = table.get(t);
			if (a == null)
			{
				for (Map.Entry<String, Integer> e : table.entrySet())
				{
					if (e.getKey().equalsIgnoreCase(t))
					{
						a = e.getValue();
						break;
					}
				}
			}
			archive = a == null ? -1 : a;
		}
		if (archive < 0 && !musicNames.isEmpty())
		{
			// Random, or nothing has played yet, or an unknown name.
			String pick = musicNames.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(musicNames.size()));
			archive = table.get(pick);
		}
		return archive < 0 ? null : client.getIndex(MUSIC_INDEX).loadData(archive, 0);
	}

	/** Client thread. Remembers the area music that is playing, ignoring jingles. */
	private void pollMusic()
	{
		List<MidiRequest> playing = client.getActiveMidiRequests();
		if (playing == null)
		{
			return;
		}
		for (MidiRequest r : playing)
		{
			if (r != null && !r.isJingle())
			{
				lastSongArchive = r.getArchiveId();
				return;
			}
		}
	}

	/** Client thread. Sends the track names to the panel the first time the game is loaded. */
	private void sendTrackNames()
	{
		WrappedPanel p = panel;
		if (p == null || tracksSent)
		{
			return;
		}
		try
		{
			musicTable();
		}
		catch (RuntimeException e)
		{
			log.debug("Unable to read the music table", e);
			return;
		}
		tracksSent = true;
		List<String> names = new ArrayList<>(musicNames);
		SwingUtilities.invokeLater(() -> p.showTracks(names));
	}

	/** Render thread; called often while a video renders, so panel updates are coalesced. */
	private void onJob(Recaps.Job job)
	{
		if (job.status.finished())
		{
			log.debug("Recap {}: {} {}", job.period, job.status, job.message);
		}
		Recaps r = recaps;
		WrappedPanel p = panel;
		if (r == null || p == null)
		{
			return;
		}
		if (jobsDirty.compareAndSet(false, true))
		{
			SwingUtilities.invokeLater(() ->
			{
				jobsDirty.set(false);
				p.showJobs(r.jobs());
			});
		}
		if (job.status == Recaps.Status.DONE && job.account == viewAccount)
		{
			refreshSaved();
		}
	}

	/** Reloads the panel's list of saved recaps off the Swing thread. */
	private void refreshSaved()
	{
		long account = viewAccount;
		Recaps r = recaps;
		WrappedPanel p = panel;
		ExecutorService exec = io;
		if (r == null || p == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			List<Recaps.Saved> list = account == -1 ? java.util.Collections.emptyList() : r.saved(account);
			Map<String, Image> thumbs = new HashMap<>();
			for (Recaps.Saved s : list)
			{
				String key = s.dir + "@" + s.meta.created;
				Image thumb = thumbCache.get(key);
				if (thumb == null)
				{
					BufferedImage card = readImage(s.card());
					if (card == null)
					{
						continue;
					}
					thumb = ShareCard.scale(card, WrappedPanel.THUMB_WIDTH);
					thumbCache.put(key, thumb);
				}
				thumbs.put(s.dir.toString(), thumb);
			}
			SwingUtilities.invokeLater(() -> p.showSaved(list, thumbs));
		});
	}

	/** Reloads the panel's friend dropdown off the Swing thread; {@code select} is picked if present. */
	private void refreshFriends(String select)
	{
		long account = viewAccount;
		Recaps r = recaps;
		WrappedPanel p = panel;
		ExecutorService exec = io;
		if (r == null || p == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			List<String> names = account == -1 ? new java.util.ArrayList<>() : r.friends(account);
			SwingUtilities.invokeLater(() -> p.showFriends(names, select));
		});
	}

	/** Reloads the panel's death log off the Swing thread. */
	private void refreshDeaths()
	{
		long account = viewAccount;
		DeathLog deaths = deathLog;
		WrappedPanel p = panel;
		ExecutorService exec = io;
		if (deaths == null || p == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			List<DeathLog.Entry> list = account == -1 ? new java.util.ArrayList<>() : deaths.list(account);
			Map<String, Image> thumbs = new HashMap<>();
			for (int i = 0; i < Math.min(WrappedPanel.DEATHS_SHOWN, list.size()); i++)
			{
				DeathLog.Entry e = list.get(i);
				if (e.card == null)
				{
					continue;
				}
				String key = "death@" + account + "/" + e.card;
				Image thumb = thumbCache.get(key);
				if (thumb == null)
				{
					BufferedImage card = readImage(deaths.read(account, e.card));
					if (card == null)
					{
						continue;
					}
					thumb = ShareCard.scale(card, WrappedPanel.SHOT_THUMB_WIDTH);
					thumbCache.put(key, thumb);
				}
				thumbs.put(e.card, thumb);
			}
			SwingUtilities.invokeLater(() -> p.showDeaths(list, thumbs));
		});
	}

	private static BufferedImage readImage(byte[] data)
	{
		if (data == null)
		{
			return null;
		}
		try
		{
			return ImageIO.read(new java.io.ByteArrayInputStream(data));
		}
		catch (IOException e)
		{
			log.debug("Unable to read an image", e);
			return null;
		}
	}

	private static BufferedImage readImage(Filepath f)
	{
		try (InputStream in = f.openInputStream())
		{
			return ImageIO.read(in);
		}
		catch (IOException e)
		{
			log.debug("Unable to read {}", f, e);
			return null;
		}
	}

	/** Runs a task on the I/O thread and reports failures in the panel. */
	private void inBackground(String what, IoTask task)
	{
		ExecutorService exec = io;
		WrappedPanel p = panel;
		if (exec == null || p == null)
		{
			return;
		}
		exec.execute(() ->
		{
			try
			{
				task.run();
			}
			catch (IOException e)
			{
				log.warn("Unable to {}", what, e);
				SwingUtilities.invokeLater(() -> p.showMessage("Unable to " + what + ": " + e.getMessage(), true));
			}
			catch (RuntimeException e)
			{
				// A bug rather than a missing file, but the button still shouldn't just do nothing.
				log.warn("Unable to {}", what, e);
				SwingUtilities.invokeLater(() -> p.showMessage("Unable to " + what + ". Details are in the client log.", true));
			}
		});
	}

	/** Runs Swing work for a panel button, reporting a failure instead of doing nothing. */
	private static void onSwing(WrappedPanel p, String what, Runnable task)
	{
		SwingUtilities.invokeLater(() ->
		{
			try
			{
				task.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Unable to {}", what, e);
				p.showMessage("Unable to " + what + ". Details are in the client log.", true);
			}
		});
	}

	private interface IoTask
	{
		void run() throws IOException;
	}

	private final class PanelActions implements WrappedPanel.Actions
	{
		@Override
		public void make(Period period, boolean video, String friend)
		{
			long account = viewAccount;
			if (account == -1)
			{
				panel.showMessage("Log in first so Gielinor Wrapped knows whose recap to make.", true);
				return;
			}
			clientThread.invoke(() ->
			{
				Tracker t = tracker;
				Recaps r = recaps;
				if (t == null || r == null)
				{
					return;
				}
				String player = t.getPlayerName();
				if (t.getAccount() == account)
				{
					// Queue today's numbers first so a range ending today includes them.
					t.save();
				}
				io.execute(() -> r.request(account, player == null ? "Adventurer" : player, period, video, friend));
			});
		}

		@Override
		public void cancel(Recaps.Job job)
		{
			job.cancel();
		}

		@Override
		public void refresh()
		{
			refreshSaved();
			refreshShots();
			refreshRecordings();
			refreshFriends(null);
		}

		@Override
		public void copyUpload(Recaps.Saved saved)
		{
			WrappedPanel p = panel;
			inBackground("copy the upload text", () ->
			{
				String text = recaps.upload(saved);
				if (text == null)
				{
					throw new IOException("this recap has no upload text yet; make it again");
				}
				SwingUtilities.invokeLater(() ->
				{
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
					p.showMessage("Title, description and chapters copied. They're also in " + UploadText.FILE + " next to the video.", false);
				});
			});
		}

		@Override
		public void exportFriend(Recaps.Saved saved)
		{
			WrappedPanel p = panel;
			long account = viewAccount;
			if (account == -1)
			{
				p.showMessage("Log in first to save a friend file.", true);
				return;
			}
			Period period = saved.meta.period();
			String player = saved.meta.player;
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsSave()
				.setAcceptsFiles()
				.setDialogTitle("Save friend file")
				.addExtensionFilter("Gielinor Wrapped friend file", FriendFile.EXTENSION)
				.setDefaultExtension(FriendFile.EXTENSION)
				.setFileName(FriendFile.fileName(config.hideName() ? "Adventurer" : player, period))
				.showDialog(p);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}
			Filepath dest = chosen.get(0);
			inBackground("save the friend file", () ->
			{
				String json = recaps.exportFriend(account, player, period);
				if (json == null)
				{
					throw new IOException("nothing was tracked in that period");
				}
				dest.write(json);
				SwingUtilities.invokeLater(() -> p.showMessage("Saved " + dest.getFileName() + ". Send it to a friend; they import it under Make.", false));
			});
		}

		@Override
		public void importFriend()
		{
			WrappedPanel p = panel;
			long account = viewAccount;
			if (account == -1)
			{
				p.showMessage("Log in first so Gielinor Wrapped knows where to keep the friend file.", true);
				return;
			}
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsFiles()
				.setDialogTitle("Import friend file")
				.addExtensionFilter("Gielinor Wrapped friend file", FriendFile.EXTENSION)
				.showDialog(p);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}
			Filepath source = chosen.get(0);
			inBackground("import the friend file", () ->
			{
				if (source.size() > FriendFile.MAX_BYTES)
				{
					throw new IOException("that file is too big to be a friend file");
				}
				FriendFile f = recaps.importFriend(account, Recaps.readText(source));
				if (f == null)
				{
					throw new IOException("that isn't a Gielinor Wrapped friend file");
				}
				refreshFriends(Recaps.friendName(f));
				SwingUtilities.invokeLater(() -> p.showMessage("Imported " + f.player + " (" + f.period + "). Pick them under Make for a side-by-side scene.", false));
			});
		}

		@Override
		public void deleteFriend(String name)
		{
			long account = viewAccount;
			inBackground("forget the friend file", () ->
			{
				recaps.deleteFriend(account, name);
				refreshFriends(null);
			});
		}

		@Override
		public void takeShot()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::takeShot);
		}

		@Override
		public void setRole(Shots.Shot shot, Shots.Role role)
		{
			inBackground("change the shot", () ->
			{
				shots.setRole(shot, role);
				refreshShots();
			});
		}

		@Override
		public void deleteShot(Shots.Shot shot)
		{
			inBackground("delete the shot", () ->
			{
				shots.delete(shot);
				thumbCache.remove("shot@" + shot.time);
				refreshShots();
			});
		}

		@Override
		public void delete(Recaps.Saved saved)
		{
			inBackground("delete the recap", () ->
			{
				recaps.delete(saved);
				refreshSaved();
			});
		}

		@Override
		public void copyCard(Recaps.Saved saved)
		{
			WrappedPanel p = panel;
			inBackground("copy the card", () ->
			{
				BufferedImage card = readImage(saved.card());
				if (card == null)
				{
					throw new IOException("the card file is missing");
				}
				SwingUtilities.invokeLater(() ->
				{
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(card), null);
					p.showMessage("Card copied to the clipboard.", false);
				});
			});
		}

		@Override
		public void saveCard(Recaps.Saved saved)
		{
			export(saved.card(), "Save share card", "PNG image", "png", saved);
		}

		@Override
		public void saveVideo(Recaps.Saved saved)
		{
			Filepath video = saved.video();
			if (video == null)
			{
				return;
			}
			boolean gif = video.getFileName().endsWith(".gif");
			export(video, "Save recap video", gif ? "GIF animation" : "MP4 video", gif ? "gif" : "mp4", saved);
		}

		private void export(Filepath source, String title, String type, String ext, Recaps.Saved saved)
		{
			WrappedPanel p = panel;
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsSave()
				.setAcceptsFiles()
				.setDialogTitle(title)
				.addExtensionFilter(type, ext)
				.setDefaultExtension(ext)
				.setFileName("gielinor-wrapped-" + saved.meta.period().id() + "." + ext)
				.showDialog(p);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}
			Filepath dest = chosen.get(0);
			inBackground("save the file", () ->
			{
				source.copyTo(dest, StandardCopyOption.REPLACE_EXISTING);
				SwingUtilities.invokeLater(() -> p.showMessage("Saved " + dest.getFileName() + ".", false));
			});
		}

		@Override
		public void toggleRecording()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::toggleRecording);
		}

		@Override
		public void captureEmote()
		{
			clientThread.invoke(GielinorWrappedPlugin.this::captureEmote);
		}

		@Override
		public void deleteRecording(ClipLibrary.Recording recording)
		{
			long account = viewAccount;
			inBackground("delete the recording", () ->
			{
				library.deleteRecording(account, recording.name);
				refreshRecordings();
			});
		}

		@Override
		public void addStickers()
		{
			WrappedPanel p = panel;
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsFiles()
				.setMultiSelectionEnabled(true)
				.setDialogTitle("Add stickers")
				.addExtensionFilter("Images", "png", "jpg", "jpeg", "gif")
				.showDialog(p);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}
			inBackground("add the stickers", () ->
			{
				Filepath dir = library.stickersDir();
				dir.createDirectories();
				int added = 0;
				for (Filepath f : chosen)
				{
					String name = f.getFileName();
					String lower = name.toLowerCase();
					if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif"))
					{
						try
						{
							f.copyTo(dir.join(name), StandardCopyOption.REPLACE_EXISTING);
							added++;
						}
						catch (IllegalArgumentException e)
						{
							// A name Filepath won't take; copy it under a safe one.
							f.copyTo(dir.join(ClipLibrary.safeName(name.substring(0, name.lastIndexOf('.'))) + lower.substring(lower.lastIndexOf('.'))),
								StandardCopyOption.REPLACE_EXISTING);
							added++;
						}
					}
				}
				int n = added;
				SwingUtilities.invokeLater(() -> p.showMessage(n == 1 ? "Added 1 sticker." : "Added " + n + " stickers.", false));
			});
		}

		@Override
		public void copyStickersPath()
		{
			WrappedPanel p = panel;
			inBackground("find the stickers folder", () ->
			{
				Filepath dir = library.stickersDir();
				dir.createDirectories();
				String path = dir.toString();
				SwingUtilities.invokeLater(() ->
				{
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
					p.showMessage("Copied the stickers folder path.", false);
				});
			});
		}

		@Override
		public void openStickerBoard()
		{
			WrappedPanel p = panel;
			long account = viewAccount;
			if (account == -1)
			{
				p.showMessage("Log in to place stickers.", true);
				return;
			}
			inBackground("open the sticker board", () ->
			{
				List<Sticker> all = library.stickers(account, true, true);
				StickerBoard board = library.readBoard(account);
				onSwing(p, "open the sticker board", () -> new StickerBoardDialog(SwingUtilities.getWindowAncestor(p), config, all, board,
					saved -> inBackground("save the sticker board", () ->
					{
						library.writeBoard(account, saved);
						SwingUtilities.invokeLater(() -> p.showMessage("Saved the sticker board.", false));
					}),
					cutout -> inBackground("delete the cutout", () -> library.deleteNpc(account, cutout.name)))
					.setVisible(true));
			});
		}

		@Override
		public void setConfig(String key, Object value)
		{
			configManager.setConfiguration(GielinorWrappedConfig.GROUP, key, value);
		}

		@Override
		public void preview(Recaps.Saved saved)
		{
			WrappedPanel p = panel;
			inBackground("open the card", () ->
			{
				BufferedImage card = readImage(saved.card());
				if (card == null)
				{
					throw new IOException("the card file is missing");
				}
				onSwing(p, "open the card", () -> p.showPreview(saved, card));
			});
		}

		@Override
		public void previewDeath(DeathLog.Entry entry)
		{
			WrappedPanel p = panel;
			DeathLog deaths = deathLog;
			long account = viewAccount;
			if (deaths == null || account == -1)
			{
				return;
			}
			inBackground("open the death", () ->
			{
				BufferedImage img = readImage(deaths.read(account, entry.card != null ? entry.card : entry.shot));
				if (img == null)
				{
					throw new IOException("its picture is missing");
				}
				onSwing(p, "open the death", () -> p.showImage(WrappedPanel.describeDeath(entry), img));
			});
		}
	}

	/** An image on the clipboard. */
	private static final class ImageSelection implements Transferable
	{
		private final Image image;

		ImageSelection(Image image)
		{
			this.image = image;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors()
		{
			return new DataFlavor[]{DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor)
		{
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws java.awt.datatransfer.UnsupportedFlavorException
		{
			if (!isDataFlavorSupported(flavor))
			{
				throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}

	/** Queues any scheduled recaps that are due, once per account per day. Client thread. */
	private void checkSchedule()
	{
		long account = tracker.getAccount();
		String player = tracker.getPlayerName();
		if (account == -1 || player == null)
		{
			return;
		}
		LocalDate today = LocalDate.now();
		String key = account + "/" + today;
		if (key.equals(scheduleChecked))
		{
			return;
		}
		scheduleChecked = key;
		Recaps r = recaps;
		// Queued after the tracker's save of the day that just ended, so the recap sees all of it.
		io.execute(() -> r.checkSchedule(account, player, today, this::notifyReady));
	}

	private void notifyReady(String text)
	{
		chat(text, " Open the Gielinor Wrapped panel to share it.");
	}

	private void chat(String highlight, String rest)
	{
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(highlight)
			.append(ChatColorType.NORMAL)
			.append(rest)
			.build();
		chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.GAMEMESSAGE).runeLiteFormattedMessage(message).build());
	}

	/** Client thread. */
	private void onMoment(Moment moment)
	{
		log.debug("Moment: {} {} ({})", moment.type, moment.title, moment.importance);
		Tracker t = tracker;
		if (capture != null && t != null)
		{
			capture.onMoment(t.getAccount(), moment, (m, clip) -> clientThread.invokeLater(() -> t.clipSaved(m, clip)));
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (tracker == null)
		{
			return;
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			tracker.loggedIn();
			sendTrackNames();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			tracker.loggedOut();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (tracker != null)
		{
			tracker.onTick();
			pollMusic();
			checkSchedule();
			long account = tracker.getAccount();
			if (account != -1 && account != viewAccount)
			{
				viewAccount = account;
				capture.recover(account);
				refreshSaved();
				refreshShots();
				refreshRecordings();
				refreshDeaths();
				refreshFriends(null);
				sayHello(account);
			}
			capture.onTick(client.getGameState() == GameState.LOGGED_IN ? tracker.getAccount() : -1);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (tracker != null)
		{
			tracker.onStat(event.getSkill(), event.getXp());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (tracker != null && (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM))
		{
			tracker.onGameMessage(Text.removeTags(event.getMessage()));
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		if (tracker != null && offer != null && config.trackGe())
		{
			tracker.onGrandExchange(event.getSlot(), offer.getItemId(), offer.getState().name(), offer.getQuantitySold(), offer.getSpent());
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Capture c = capture;
		if (c != null)
		{
			c.onAnimation(event.getActor());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (tracker != null && event.getActor() == client.getLocalPlayer())
		{
			tracker.onDeath();
			long account = tracker.getAccount();
			long now = System.currentTimeMillis();
			int world = client.getWorld();
			DeathLog deaths = deathLog;
			if (account != -1 && deaths != null)
			{
				inBackground("log the death", () ->
				{
					deaths.died(account, now, world);
					refreshDeaths();
				});
			}
		}
	}

	/** Death Recap shares its deaths: {@code ready} when it has loaded an account, then a {@code death} for each one. */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!DeathLog.NAMESPACE.equals(event.getNamespace()))
		{
			return;
		}
		if ("ready".equals(event.getName()))
		{
			long account = viewAccount;
			if (account != -1)
			{
				sayHello(account);
			}
		}
		else if ("death".equals(event.getName()))
		{
			DeathLog.Recap r = DeathLog.Recap.from(event.getData());
			DeathLog deaths = deathLog;
			if (r != null && deaths != null)
			{
				boolean keepReplay = config.deathReplays();
				inBackground("save a Death Recap death", () ->
				{
					deaths.recap(r, keepReplay);
					if (r.account == viewAccount)
					{
						refreshDeaths();
					}
				});
			}
		}
	}

	/** Tells Death Recap this plugin is listening, and which of the account's deaths it already has. */
	private void sayHello(long account)
	{
		DeathLog deaths = deathLog;
		ExecutorService exec = io;
		if (deaths == null || exec == null)
		{
			return;
		}
		exec.execute(() ->
		{
			Map<String, Object> data = new HashMap<>();
			data.put("accountHash", account);
			data.put("have", deaths.recapTimes(account));
			eventBus.post(new PluginMessage(DeathLog.NAMESPACE, "hello", data));
		});
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (tracker != null)
		{
			tracker.onLoot(event.getNpc().getName(), event.getItems(), true);
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		if (tracker != null)
		{
			tracker.onLoot("PvP: " + event.getPlayer().getName(), event.getItems(), true);
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC and player loot already arrive through the events above.
		if (tracker == null)
		{
			return;
		}
		switch (event.getType())
		{
			case NPC:
			case PLAYER:
				return;
			default:
				tracker.onLoot(event.getName(), event.getItems(), false);
		}
	}
}
