package com.gielinorwrapped;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel with four tabs: Record (footage, shots, emote, stickers), Make (recaps and the saved ones),
 * Style (look and video options) and Captions. Swing thread.
 */
final class WrappedPanel extends PluginPanel
{
	/** Panel width less the panel and section borders. */
	static final int THUMB_WIDTH = PANEL_WIDTH - 24;
	/** Recap shot thumbnails sit beside their controls. */
	static final int SHOT_THUMB_WIDTH = 96;
	/** Shots listed at once; older ones stay on disk and still count toward recaps. */
	private static final int SHOTS_SHOWN = 12;
	private static final int RECORDINGS_SHOWN = 15;
	/** Deaths listed at once; older ones stay in the log and still go into recaps. */
	static final int DEATHS_SHOWN = 10;
	private static final String CAPTION_HELP = "One caption per line, under a heading:<br>"
		+ "<b>title:</b> replaces the title<br><b>outro:</b> replaces the closing card<br>"
		+ "<b>card:</b> extra caption cards<br><b>footage:</b> captions over your recordings, in order<br>"
		+ "<b>share:</b> a line on the share card<br>"
		+ "Write <b>big | small</b> for a headline with a line under it. Lines starting with # are notes. "
		+ "Tokens: {player} {period} {range}";

	/** What the panel asks of the plugin. Calls come from the Swing thread. */
	interface Actions
	{
		/** @param friend an imported friend file name for the "you vs them" scene, or null */
		void make(Period period, boolean video, String friend);

		/** Copies the recap's upload title, description and chapters to the clipboard. */
		void copyUpload(Recaps.Saved saved);

		/** Saves a small friend file of the recap's totals for a friend to import. */
		void exportFriend(Recaps.Saved saved);

		/** Picks a friend file and keeps a copy for the friend dropdown. */
		void importFriend();

		void deleteFriend(String name);

		void cancel(Recaps.Job job);

		void refresh();

		void delete(Recaps.Saved saved);

		void copyCard(Recaps.Saved saved);

		void saveCard(Recaps.Saved saved);

		void saveVideo(Recaps.Saved saved);

		void preview(Recaps.Saved saved);

		/** Opens a logged death's Death Recap card, or its screenshot. */
		void previewDeath(DeathLog.Entry entry);

		void takeShot();

		void setRole(Shots.Shot shot, Shots.Role role);

		void deleteShot(Shots.Shot shot);

		/** Starts a footage recording, or stops the one running. */
		void toggleRecording();

		/** Gets ready to capture the next emote the player does. */
		void captureEmote();

		void deleteRecording(ClipLibrary.Recording recording);

		/** Copies sticker images the player picks into the stickers folder. */
		void addStickers();

		void copyStickersPath();

		/** Opens the sticker board, where the player places stickers on the recap's scenes. */
		void openStickerBoard();

		/** Writes one config value; enums, colours, numbers, booleans and strings. */
		void setConfig(String key, Object value);
	}

	private enum Range
	{
		TODAY("Today"),
		YESTERDAY("Yesterday"),
		THIS_WEEK("This week"),
		LAST_WEEK("Last week"),
		THIS_MONTH("This month"),
		LAST_MONTH("Last month"),
		THIS_YEAR("This year"),
		LAST_YEAR("Last year"),
		CUSTOM("Custom range");

		final String label;

		Range(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Tab
	{
		RECORD("Record"),
		MAKE("Make"),
		STYLE("Style"),
		CAPTIONS("Captions");

		final String label;

		Tab(String label)
		{
			this.label = label;
		}
	}

	private final Actions actions;
	private final GielinorWrappedConfig config;
	private final JPanel content = new JPanel();
	private final Map<Tab, JButton> tabButtons = new HashMap<>();
	private final Map<Tab, JPanel> tabs = new HashMap<>();
	private Tab tab;

	private final JComboBox<Range> range = new JComboBox<>(Range.values());
	private final JTextField from = new JTextField();
	private final JTextField to = new JTextField();
	private final JPanel custom = new JPanel(new GridLayout(2, 2, 4, 4));
	private final JCheckBox video = new JCheckBox("Include video", true);
	/** Size limit presets in MB, 0 for none; each sets the Size limit option. */
	private static final int[] SIZES = {0, 10, 50, 100};
	/** Style options that show or hide others, so the tab is rebuilt when they change. */
	private static final Set<String> REBUILDS_STYLE = Set.of("theme", "useAccent", "chromaKey", "deathScene");
	private final Map<Integer, JButton> sizeButtons = new LinkedHashMap<>();
	private final JPanel sizeRow = new JPanel(new BorderLayout(0, 2));
	private static final String NO_FRIEND = "No friend";
	private final JComboBox<String> friend = new JComboBox<>(new String[]{NO_FRIEND});
	private final JLabel status = new JLabel(" ");
	private final JPanel jobsBox = box();
	private final JPanel savedBox = box();
	private final JPanel shotsBox = box();
	private final JPanel recordingsBox = box();
	private final JPanel deathsBox = box();
	private final Map<String, Image> deathThumbs = new HashMap<>();
	private final Map<String, Image> shotThumbs = new HashMap<>();
	private final Map<String, Image> thumbs = new HashMap<>();
	private List<Recaps.Saved> saved = Collections.emptyList();

	private final JButton recordButton = new JButton();
	private final JLabel recordStatus = new JLabel(" ");
	private final JLabel recordMessage = new JLabel(" ");
	private final Timer recordTimer;
	private boolean recording;
	private long recordStart;

	private final JTextArea captionText = new JTextArea(12, 10);
	/** The captions last loaded into or saved from the editor. */
	private String shownCaptions = "";
	private List<String> tracks = new ArrayList<>();

	WrappedPanel(Actions actions, GielinorWrappedConfig config)
	{
		this.actions = actions;
		this.config = config;
		setBorder(new EmptyBorder(8, 6, 8, 6));
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Gielinor Wrapped");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		add(left(title));
		add(Box.createVerticalStrut(6));

		JPanel strip = new JPanel(new GridLayout(1, Tab.values().length, 2, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		strip.setOpaque(false);
		strip.setAlignmentX(LEFT_ALIGNMENT);
		for (Tab t : Tab.values())
		{
			JButton b = new JButton(t.label);
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setMargin(new Insets(3, 1, 3, 1));
			b.setFocusPainted(false);
			b.addActionListener(e -> showTab(t));
			tabButtons.put(t, b);
			strip.add(b);
		}
		add(strip);
		add(Box.createVerticalStrut(8));

		status.setFont(FontManager.getRunescapeSmallFont());
		add(left(status));
		add(Box.createVerticalStrut(4));

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.setAlignmentX(LEFT_ALIGNMENT);
		add(content);

		recordTimer = new Timer(500, e -> updateRecordButton());
		tabs.put(Tab.RECORD, buildRecord());
		tabs.put(Tab.MAKE, buildMake());
		tabs.put(Tab.CAPTIONS, buildCaptions());
		showSaved(Collections.emptyList(), Collections.emptyMap());
		setRecording(false, 0);
		showTab(Tab.RECORD);
	}

	void showTab(Tab t)
	{
		if (t == Tab.STYLE)
		{
			// Rebuilt from config each time, so changes made in the settings panel show here too.
			tabs.put(Tab.STYLE, buildStyle());
		}
		if (t == Tab.CAPTIONS && captionText.getText().equals(shownCaptions))
		{
			// Only when there are no unsaved edits.
			shownCaptions = config.customCaptions();
			captionText.setText(shownCaptions);
		}
		tab = t;
		if (t == Tab.MAKE)
		{
			// The option may have been changed in the settings meanwhile.
			showSize(config.targetSize());
		}
		for (Map.Entry<Tab, JButton> e : tabButtons.entrySet())
		{
			boolean on = e.getKey() == t;
			e.getValue().setBackground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
			e.getValue().setForeground(on ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
		}
		content.removeAll();
		content.add(tabs.get(t));
		revalidate();
		repaint();
	}

	// ---------------------------------------------------------------- Record

	private JPanel buildRecord()
	{
		JPanel page = box();

		page.add(heading("Recording"));
		JPanel rec = section();
		recordButton.setFont(FontManager.getRunescapeBoldFont());
		recordButton.setFocusPainted(false);
		recordButton.addActionListener(e -> actions.toggleRecording());
		recordButton.setPreferredSize(new Dimension(THUMB_WIDTH - 12, 30));
		rec.add(wide(recordButton));
		rec.add(Box.createVerticalStrut(4));
		recordStatus.setFont(FontManager.getRunescapeSmallFont());
		recordStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rec.add(left(recordStatus));
		rec.add(left(help("Record as much as you like; recaps cut the best bits from it, between the stat scenes. "
			+ "Record in front of a green screen and turn on the chroma key in Style.")));
		rec.add(Box.createVerticalStrut(4));
		rec.add(left(spinner("Stop after (s)", "footageMax", config.footageMax(), 10, 900, 10)));
		rec.add(left(spinner("Frame rate", "footageFps", config.footageFps(), 2, 30, 1)));
		page.add(rec);

		page.add(heading("Snapshots"));
		JPanel snap = section();
		JPanel two = new JPanel(new GridLayout(1, 2, 4, 0));
		two.setOpaque(false);
		two.add(button("Take screenshot", "Screenshot the game for your next recap", actions::takeShot));
		two.add(button("Capture emote", "Get ready, then do any emote in game: it dances on your title and outro", actions::captureEmote));
		snap.add(wide(two));
		recordMessage.setFont(FontManager.getRunescapeSmallFont());
		recordMessage.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		snap.add(left(recordMessage));
		page.add(snap);

		page.add(heading("Recordings"));
		JPanel recs = section();
		recs.add(left(recordingsBox));
		page.add(recs);

		page.add(heading("Stickers"));
		JPanel st = section();
		st.add(left(help("Every boss and monster you fight is cut out of the game view as you play. Add your own PNG, JPG or GIF stickers too, then place them on the sticker board.")));
		JPanel sb = new JPanel(new GridLayout(1, 2, 4, 0));
		sb.setOpaque(false);
		sb.add(button("Add stickers…", "Copy images into the stickers folder", actions::addStickers));
		sb.add(button("Copy folder path", "Copy the stickers folder path to the clipboard", actions::copyStickersPath));
		st.add(wide(sb));
		st.add(wide(button("Sticker board…", "Choose stickers and place them on the recap's scenes", actions::openStickerBoard)));
		st.add(left(small("Which stickers go in automatically: Style tab.")));
		page.add(st);

		page.add(heading("Recap shots"));
		JPanel shots = section();
		shots.add(left(help("Set up a scene in game and take a screenshot. Recaps use the shots from their range: "
			+ "the cover, the outro and a gallery.")));
		shots.add(left(shotsBox));
		page.add(shots);
		return page;
	}

	/** Shows whether footage is recording; {@code start} is when it began, epoch milliseconds. */
	void setRecording(boolean on, long start)
	{
		recording = on;
		recordStart = start;
		if (on)
		{
			recordTimer.start();
		}
		else
		{
			recordTimer.stop();
		}
		updateRecordButton();
	}

	private void updateRecordButton()
	{
		if (recording)
		{
			long s = Math.max(0, (System.currentTimeMillis() - recordStart) / 1000);
			recordButton.setText("■  Stop  " + s / 60 + ":" + String.format("%02d", s % 60));
			recordButton.setBackground(new Color(0xB0, 0x24, 0x24));
			recordButton.setForeground(Color.WHITE);
			recordStatus.setText("Recording · stops by itself at " + fmtLength(config.footageMax() * 1000L));
		}
		else
		{
			recordButton.setText("●  Start recording");
			recordButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
			recordButton.setForeground(new Color(0xFF, 0x60, 0x60));
			recordStatus.setText("Not recording");
		}
	}

	/** A short note under the snapshot buttons, such as "Emote saved." */
	void showRecordMessage(String text)
	{
		recordMessage.setText(text == null ? " " : "<html>" + text + "</html>");
		revalidate();
	}

	void showRecordings(List<ClipLibrary.Recording> list)
	{
		recordingsBox.removeAll();
		if (list.isEmpty())
		{
			recordingsBox.add(left(small("No recordings yet.")));
		}
		DateTimeFormatter when = DateTimeFormatter.ofPattern("d MMM HH:mm");
		long total = 0;
		for (int i = 0; i < list.size(); i++)
		{
			ClipLibrary.Recording r = list.get(i);
			total += r.size;
			if (i >= RECORDINGS_SHOWN)
			{
				continue;
			}
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setOpaque(false);
			JLabel name = small(when.format(Instant.ofEpochMilli(r.start).atZone(ZoneId.systemDefault()))
				+ " · " + fmtLength(r.length) + " · " + r.size / (1024 * 1024) + " MB");
			row.add(name, BorderLayout.CENTER);
			row.add(button("✕", "Delete this recording", () ->
			{
				if (JOptionPane.showConfirmDialog(this, "Delete this recording?", "Gielinor Wrapped",
					JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
				{
					actions.deleteRecording(r);
				}
			}), BorderLayout.EAST);
			recordingsBox.add(wide(row));
			recordingsBox.add(Box.createVerticalStrut(2));
		}
		if (!list.isEmpty())
		{
			recordingsBox.add(left(small(list.size() + " recordings · " + total / (1024 * 1024) + " of "
				+ config.footageBudget() + " MB")));
		}
		revalidate();
		repaint();
	}

	private static String fmtLength(long ms)
	{
		long s = ms / 1000;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	// ---------------------------------------------------------------- Make

	private JPanel buildMake()
	{
		JPanel page = box();
		JPanel make = section();
		// This week always includes today, so a first recap has something in it.
		range.setSelectedItem(Range.THIS_WEEK);
		range.addActionListener(e -> custom.setVisible(range.getSelectedItem() == Range.CUSTOM));
		make.add(wide(range));

		LocalDate today = LocalDate.now();
		from.setText(today.minusDays(6).toString());
		to.setText(today.toString());
		from.setToolTipText("First day, YYYY-MM-DD");
		to.setToolTipText("Last day, YYYY-MM-DD");
		custom.setOpaque(false);
		custom.add(small("From"));
		custom.add(small("To"));
		custom.add(from);
		custom.add(to);
		custom.setVisible(false);
		make.add(Box.createVerticalStrut(4));
		make.add(left(custom));

		video.setSelected(config.makeVideo());
		video.setOpaque(false);
		video.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		video.setToolTipText("Also render the recap video (takes a minute or two); the card is always made");
		make.add(left(video));

		JPanel sizes = new JPanel(new GridLayout(1, SIZES.length, 2, 0));
		sizes.setOpaque(false);
		for (int mb : SIZES)
		{
			JButton b = button(mb == 0 ? "No limit" : mb + " MB", sizeTip(mb), () ->
			{
				actions.setConfig("targetSize", mb);
				showSize(mb);
			});
			sizeButtons.put(mb, b);
			sizes.add(b);
		}
		sizeRow.setOpaque(false);
		sizeRow.add(small("Video size"), BorderLayout.NORTH);
		sizeRow.add(sizes, BorderLayout.CENTER);
		sizeRow.setVisible(video.isSelected());
		video.addActionListener(e ->
		{
			sizeRow.setVisible(video.isSelected());
			actions.setConfig("makeVideo", video.isSelected());
		});
		showSize(config.targetSize());
		make.add(Box.createVerticalStrut(2));
		make.add(wide(sizeRow));

		friend.setToolTipText("Add a side-by-side \"you vs them\" scene from a friend file they sent you");
		JPanel friendRow = new JPanel(new BorderLayout(3, 0));
		friendRow.setOpaque(false);
		friendRow.add(friend, BorderLayout.CENTER);
		JPanel friendButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		friendButtons.setOpaque(false);
		friendButtons.add(button("Import", "Import a .gwfriend file a friend sent you", () -> actions.importFriend()));
		friendButtons.add(button("✕", "Forget the selected friend file", () ->
		{
			String name = selectedFriend();
			if (name != null)
			{
				actions.deleteFriend(name);
			}
		}));
		friendRow.add(friendButtons, BorderLayout.EAST);
		make.add(Box.createVerticalStrut(4));
		make.add(wide(friendRow));

		JButton go = new JButton("Make recap");
		go.addActionListener(e -> make());
		make.add(Box.createVerticalStrut(4));
		make.add(wide(go));
		page.add(make);
		page.add(left(jobsBox));

		page.add(heading("Your recaps"));
		page.add(left(savedBox));

		page.add(heading("Death log"));
		page.add(left(deathsBox));
		showDeaths(Collections.emptyList(), Collections.emptyMap());
		return page;
	}

	private static String sizeTip(int mb)
	{
		switch (mb)
		{
			case 0:
				return "Full quality; the file can be large";
			case 10:
				return "Fits Discord without Nitro; lowest quality";
			default:
				return "Keep the video under " + mb + " MB; bigger looks better";
		}
	}

	/** Highlights the preset matching the Size limit option; none if it was set to something else in the settings. */
	private void showSize(int mb)
	{
		for (Map.Entry<Integer, JButton> e : sizeButtons.entrySet())
		{
			boolean on = e.getKey() == mb;
			e.getValue().setBackground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
			e.getValue().setForeground(on ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
		}
		sizeRow.setToolTipText(sizeButtons.containsKey(mb) ? null : "Size limit is " + mb + " MB in the settings");
	}

	private void make()
	{
		showMessage(null, false);
		Range r = (Range) range.getSelectedItem();
		LocalDate today = LocalDate.now();
		DayOfWeek ws = config.weekStart();
		Period p;
		switch (r)
		{
			case TODAY:
				p = Period.containing(Period.Kind.DAY, today, ws);
				break;
			case YESTERDAY:
				p = Period.previous(Period.Kind.DAY, today, ws);
				break;
			case THIS_WEEK:
				p = Period.containing(Period.Kind.WEEK, today, ws);
				break;
			case LAST_WEEK:
				p = Period.previous(Period.Kind.WEEK, today, ws);
				break;
			case THIS_MONTH:
				p = Period.containing(Period.Kind.MONTH, today, ws);
				break;
			case LAST_MONTH:
				p = Period.previous(Period.Kind.MONTH, today, ws);
				break;
			case THIS_YEAR:
				p = Period.containing(Period.Kind.YEAR, today, ws);
				break;
			case LAST_YEAR:
				p = Period.previous(Period.Kind.YEAR, today, ws);
				break;
			default:
				try
				{
					LocalDate a = LocalDate.parse(from.getText().trim());
					LocalDate b = LocalDate.parse(to.getText().trim());
					if (b.isBefore(a))
					{
						showMessage("The last day is before the first.", true);
						return;
					}
					if (a.plusYears(1).isBefore(b))
					{
						showMessage("Custom ranges go up to a year.", true);
						return;
					}
					p = new Period(Period.Kind.CUSTOM, a, b);
				}
				catch (DateTimeParseException e)
				{
					showMessage("Dates look like 2026-10-19.", true);
					return;
				}
		}
		actions.make(p, video.isSelected(), selectedFriend());
	}

	private String selectedFriend()
	{
		Object f = friend.getSelectedItem();
		return f == null || NO_FRIEND.equals(f) ? null : f.toString();
	}

	/** Shows the imported friend files; {@code select} is picked if present. */
	void showFriends(List<String> names, String select)
	{
		Object was = select != null ? select : friend.getSelectedItem();
		friend.removeAllItems();
		friend.addItem(NO_FRIEND);
		for (String n : names)
		{
			friend.addItem(n);
		}
		friend.setSelectedItem(was != null && names.contains(was.toString()) ? was : NO_FRIEND);
	}

	/** Shows the given jobs (unfinished ones and recent failures). */
	void showJobs(List<Recaps.Job> jobs)
	{
		jobsBox.removeAll();
		for (Recaps.Job job : jobs)
		{
			if (job.status == Recaps.Status.DONE || job.status == Recaps.Status.CANCELLED)
			{
				continue;
			}
			JPanel row = section();
			JLabel name = small(job.period.describe() + " · " + job.status.label);
			name.setForeground(job.status == Recaps.Status.FAILED ? ColorScheme.PROGRESS_ERROR_COLOR : Color.WHITE);
			row.add(left(name));
			if (job.status == Recaps.Status.FAILED)
			{
				if (job.message != null)
				{
					row.add(left(small(job.message)));
				}
			}
			else
			{
				JProgressBar bar = new JProgressBar(0, 1000);
				bar.setValue(job.status == Recaps.Status.VIDEO ? (int) (job.progress * 1000) : 0);
				bar.setIndeterminate(job.status != Recaps.Status.VIDEO);
				bar.setForeground(ColorScheme.BRAND_ORANGE);
				bar.setPreferredSize(new Dimension(THUMB_WIDTH - 70, 14));
				JButton cancel = new JButton("Cancel");
				cancel.setFont(FontManager.getRunescapeSmallFont());
				cancel.addActionListener(e -> actions.cancel(job));
				JPanel line = new JPanel(new BorderLayout(4, 0));
				line.setOpaque(false);
				line.add(bar, BorderLayout.CENTER);
				line.add(cancel, BorderLayout.EAST);
				row.add(left(line));
			}
			jobsBox.add(Box.createVerticalStrut(6));
			jobsBox.add(row);
		}
		revalidate();
		repaint();
	}

	/** @param newThumbs card thumbnails keyed by directory, already scaled to {@link #THUMB_WIDTH} */
	void showSaved(List<Recaps.Saved> list, Map<String, Image> newThumbs)
	{
		saved = list;
		thumbs.clear();
		thumbs.putAll(newThumbs);
		savedBox.removeAll();
		if (saved.isEmpty())
		{
			savedBox.add(left(help("Nothing yet. Play a while, then make a recap above. Scheduled recaps appear here by themselves.")));
		}
		for (Recaps.Saved s : saved)
		{
			savedBox.add(card(s));
			savedBox.add(Box.createVerticalStrut(8));
		}
		revalidate();
		repaint();
	}

	/** @param newThumbs card thumbnails keyed by card file name, already scaled to {@link #SHOT_THUMB_WIDTH} */
	void showDeaths(List<DeathLog.Entry> list, Map<String, Image> newThumbs)
	{
		deathThumbs.clear();
		deathThumbs.putAll(newThumbs);
		deathsBox.removeAll();
		if (list.isEmpty())
		{
			deathsBox.add(left(help("No deaths yet. Every death is logged here; with the Death Recap plugin installed, "
				+ "its cards and replays are kept too and go into your recaps.")));
		}
		else
		{
			long gp = list.stream().mapToLong(e -> Math.max(0, e.gpLost)).sum();
			deathsBox.add(left(small(list.size() + (list.size() == 1 ? " death" : " deaths")
				+ (gp > 0 ? " · " + QuantityFormatter.quantityToStackSize(gp) + " gp lost" : ""))));
		}
		for (int i = 0; i < Math.min(DEATHS_SHOWN, list.size()); i++)
		{
			deathsBox.add(Box.createVerticalStrut(6));
			deathsBox.add(left(deathRow(list.get(i))));
		}
		if (list.size() > DEATHS_SHOWN)
		{
			deathsBox.add(Box.createVerticalStrut(4));
			deathsBox.add(left(small((list.size() - DEATHS_SHOWN) + " older deaths not shown")));
		}
		revalidate();
		repaint();
	}

	private JPanel deathRow(DeathLog.Entry e)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		Image thumb = e.card == null ? null : deathThumbs.get(e.card);
		if (thumb != null)
		{
			JLabel img = new JLabel(new ImageIcon(thumb));
			img.setPreferredSize(new Dimension(SHOT_THUMB_WIDTH, thumb.getHeight(null)));
			row.add(img, BorderLayout.WEST);
		}
		JPanel side = box();
		JLabel killer = small(e.killer != null ? e.killer : "Unknown");
		killer.setForeground(Color.WHITE);
		side.add(left(killer));
		side.add(left(small(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
			.format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault())))));
		if (e.gpLost > 0)
		{
			side.add(left(small(QuantityFormatter.quantityToStackSize(e.gpLost) + " gp lost")));
		}
		if (e.card != null || e.shot != null)
		{
			side.add(left(button("View", "Open this death's card", () -> actions.previewDeath(e))));
		}
		row.add(side, BorderLayout.CENTER);
		return row;
	}

	/** A one-line title for a logged death. */
	static String describeDeath(DeathLog.Entry e)
	{
		String when = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()));
		return (e.killer != null ? e.killer : "Death") + " · " + when;
	}

	/** @param newThumbs shot thumbnails keyed by file, already scaled to {@link #SHOT_THUMB_WIDTH} */
	void showShots(List<Shots.Shot> list, Map<String, Image> newThumbs)
	{
		shotThumbs.clear();
		shotThumbs.putAll(newThumbs);
		shotsBox.removeAll();
		for (int i = 0; i < Math.min(SHOTS_SHOWN, list.size()); i++)
		{
			shotsBox.add(Box.createVerticalStrut(6));
			shotsBox.add(left(shotRow(list.get(i))));
		}
		if (list.size() > SHOTS_SHOWN)
		{
			shotsBox.add(Box.createVerticalStrut(4));
			shotsBox.add(left(small((list.size() - SHOTS_SHOWN) + " older shots not shown")));
		}
		revalidate();
		repaint();
	}

	private JPanel shotRow(Shots.Shot shot)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		Image thumb = shotThumbs.get(shot.file.toString());
		JLabel img = thumb != null ? new JLabel(new ImageIcon(thumb)) : small("(missing)");
		img.setPreferredSize(new Dimension(SHOT_THUMB_WIDTH, thumb != null ? thumb.getHeight(null) : 54));
		row.add(img, BorderLayout.WEST);

		JPanel side = box();
		side.add(left(small(DateTimeFormatter.ofPattern("d MMM HH:mm")
			.format(Instant.ofEpochMilli(shot.time).atZone(ZoneId.systemDefault())))));
		JComboBox<Shots.Role> role = new JComboBox<>(Shots.Role.values());
		role.setSelectedItem(shot.role);
		role.setFont(FontManager.getRunescapeSmallFont());
		role.setToolTipText("Cover: the title backdrop. Outro: behind the closing card. Gallery: a slideshow scene.");
		role.addActionListener(e ->
		{
			Shots.Role chosen = (Shots.Role) role.getSelectedItem();
			if (chosen != null && chosen != shot.role)
			{
				actions.setRole(shot, chosen);
			}
		});
		side.add(left(role));
		side.add(left(button("✕", "Delete this shot", () -> actions.deleteShot(shot))));
		row.add(side, BorderLayout.CENTER);
		return row;
	}

	private JPanel card(Recaps.Saved s)
	{
		JPanel box = section();
		Image thumb = thumbs.get(s.dir.toString());
		if (thumb != null)
		{
			JLabel img = new JLabel(new ImageIcon(thumb));
			img.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			img.setToolTipText("Preview");
			img.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					actions.preview(s);
				}
			});
			box.add(left(img));
		}
		Period p = s.meta.period();
		String kind = p.kind == Period.Kind.CUSTOM ? "Custom" : p.label();
		JLabel name = new JLabel(p.describe());
		name.setForeground(Color.WHITE);
		box.add(left(name));
		box.add(left(small(kind + (s.meta.player == null ? "" : " · " + s.meta.player) + (s.meta.video == null ? "" : " · video"))));

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
		buttons.setOpaque(false);
		buttons.add(button("Copy", "Copy the card image to the clipboard", () -> actions.copyCard(s)));
		buttons.add(button("Card", "Save the card as a PNG", () -> actions.saveCard(s)));
		if (s.meta.video != null)
		{
			buttons.add(button("Video", "Save the video", () -> actions.saveVideo(s)));
		}
		else
		{
			buttons.add(button("+Video", "Render the video for this recap", () -> actions.make(p, true, s.meta.friend)));
		}
		buttons.add(button("Upload", "Copy a title, description and chapters for uploading the video", () -> actions.copyUpload(s)));
		buttons.add(button("Friend", "Save a small friend file of these totals to send to a friend", () -> actions.exportFriend(s)));
		buttons.add(button("✕", "Delete this recap", () ->
		{
			if (JOptionPane.showConfirmDialog(this, "Delete the " + p.describe() + " recap?", "Gielinor Wrapped",
				JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
			{
				actions.delete(s);
			}
		}));
		box.add(left(buttons));
		return box;
	}

	// ---------------------------------------------------------------- Style

	private JPanel buildStyle()
	{
		JPanel page = box();

		page.add(heading("Look"));
		JPanel look = section();
		look.add(left(combo("Theme", "theme", GielinorWrappedConfig.ThemeName.values(), config.theme())));
		if (config.theme() == GielinorWrappedConfig.ThemeName.CUSTOM)
		{
			look.add(left(color("Background top", "customTop", config.customTop())));
			look.add(left(color("Background bottom", "customBottom", config.customBottom())));
			look.add(left(color("Text", "customText", config.customText())));
			look.add(left(color("Accent", "customAccent", config.customAccent())));
		}
		look.add(left(check("Own accent colour", "useAccent", config.useAccent())));
		if (config.useAccent())
		{
			look.add(left(color("Accent", "accent", config.accent())));
		}
		look.add(left(combo("Card layout", "cardLayout", GielinorWrappedConfig.CardLayout.values(), config.cardLayout())));
		look.add(left(combo("Title", "titleStyle", GielinorWrappedConfig.TitleStyle.values(), config.titleStyle())));
		look.add(left(combo("Font", "font", GielinorWrappedConfig.FontChoice.values(), config.font())));
		look.add(left(combo("Effect", "effect", GielinorWrappedConfig.Effect.values(), config.effect())));
		look.add(left(combo("Colour grade", "grade", GielinorWrappedConfig.Grade.values(), config.grade())));
		look.add(left(combo("Border", "border", GielinorWrappedConfig.Border.values(), config.border())));
		look.add(left(spinner("Vignette %", "vignette", config.vignette(), 0, 100, 5)));
		look.add(left(spinner("Film grain %", "filmGrain", config.filmGrain(), 0, 100, 5)));
		look.add(left(check("Scanlines", "scanlines", config.scanlines())));
		look.add(left(check("Item & skill icons", "icons", config.icons())));
		page.add(look);

		page.add(heading("Video"));
		JPanel vid = section();
		vid.add(left(combo("Shape", "aspect", GielinorWrappedConfig.Aspect.values(), config.aspect())));
		vid.add(left(combo("Format", "videoFormat", GielinorWrappedConfig.VideoFormat.values(), config.videoFormat())));
		vid.add(left(combo("Quality", "quality", GielinorWrappedConfig.Quality.values(), config.quality())));
		vid.add(left(combo("Pace", "pace", GielinorWrappedConfig.Pace.values(), config.pace())));
		vid.add(left(spinner("Max length (s)", "maxLength", config.maxLength(), 10, 300, 5)));
		vid.add(left(combo("Transition", "transition", GielinorWrappedConfig.Transition.values(), config.transition())));
		vid.add(left(spinner("Transition (ms)", "transitionLength", config.transitionLength(), 100, 1500, 50)));
		vid.add(left(check("Title scene", "introScene", config.introScene())));
		vid.add(left(check("Stat scenes", "statScenes", config.statScenes())));
		vid.add(left(check("Persona scene", "personaScene", config.personaScene())));
		vid.add(left(check("Timelapse", "timelapseScene", config.timelapseScene())));
		vid.add(left(check("Screenshot gallery", "galleryScene", config.galleryScene())));
		vid.add(left(check("My emote", "emoteScene", config.emoteScene())));
		vid.add(left(check("Outro", "outroScene", config.outroScene())));
		vid.add(left(check("Progress bar", "progressBar", config.progressBar())));
		vid.add(left(check("Cinema bars", "letterbox", config.letterbox())));
		page.add(vid);

		page.add(heading("More scenes"));
		JPanel more = section();
		more.add(left(check("Where you played", "mapScene", config.mapScene())));
		more.add(left(check("Graphs", "graphScene", config.graphScene())));
		more.add(left(check("Vs last time", "compareScene", config.compareScene())));
		more.add(left(check("Goal pace", "paceScene", config.paceScene())));
		more.add(left(check("Bosses & Slayer", "bossScene", config.bossScene())));
		more.add(left(check("Collection log", "collectionScene", config.collectionScene())));
		more.add(left(check("Grand Exchange", "geScene", config.geScene())));
		more.add(left(check("Year in review", "montageScene", config.montageScene())));
		more.add(left(check("Friend duel", "versusScene", config.versusScene())));
		more.add(left(check("Deaths", "deathScene", config.deathScene())));
		if (config.deathScene())
		{
			more.add(left(spinner("Deaths shown", "deathMax", config.deathMax(), 1, 20, 1)));
			more.add(left(check("Use Death Recap replays", "deathReplays", config.deathReplays())));
		}
		page.add(more);

		page.add(heading("Footage"));
		JPanel foot = section();
		foot.add(left(combo("Use footage", "footageMode", GielinorWrappedConfig.FootageMode.values(), config.footageMode())));
		foot.add(left(spinner("Cut length (s)", "footageSegment", config.footageSegment(), 1, 15, 1)));
		foot.add(left(spinner("Most cuts", "footageCuts", config.footageCuts(), 0, 20, 1)));
		foot.add(left(check("Chroma key (green screen)", "chromaKey", config.chromaKey())));
		if (config.chromaKey())
		{
			foot.add(left(color("Key colour", "chromaColor", config.chromaColor())));
			foot.add(left(spinner("Tolerance %", "chromaTolerance", config.chromaTolerance(), 0, 100, 2)));
			foot.add(left(spinner("Softness %", "chromaSoftness", config.chromaSoftness(), 0, 100, 2)));
			foot.add(left(check("Remove colour spill", "chromaSpill", config.chromaSpill())));
			foot.add(left(combo("Keyed over", "chromaPlacement", GielinorWrappedConfig.ChromaPlacement.values(), config.chromaPlacement())));
		}
		page.add(foot);

		page.add(heading("Stickers"));
		JPanel st = section();
		st.add(wide(button("Sticker board…", "Choose stickers and place them on the recap's scenes", actions::openStickerBoard)));
		st.add(left(check("Auto: boss & NPC cutouts", "bossStickers", config.bossStickers())));
		st.add(left(check("Auto: game icon stickers", "iconStickers", config.iconStickers())));
		st.add(left(check("Auto: my stickers", "myStickers", config.myStickers())));
		st.add(left(spinner("Size %", "stickerSize", config.stickerSize(), 25, 300, 5)));
		st.add(left(check("Wobble", "stickerWobble", config.stickerWobble())));
		st.add(left(check("Ironman badge", "accountBadge", config.accountBadge())));
		page.add(st);

		page.add(heading("Music"));
		JPanel mus = section();
		mus.add(left(check("Game music in MP4s", "music", config.musicEnabled())));
		List<String> names = new ArrayList<>();
		names.add("Random");
		names.add("Current area");
		names.addAll(tracks);
		if (!names.contains(config.musicTrack()))
		{
			names.add(2, config.musicTrack());
		}
		JComboBox<String> track = new JComboBox<>(names.toArray(new String[0]));
		track.setEditable(true);
		track.setSelectedItem(config.musicTrack());
		track.setToolTipText("Any track from the music player; type to search");
		track.addActionListener(e ->
		{
			Object v = track.getSelectedItem();
			if (v != null && !v.toString().trim().isEmpty() && !v.toString().equals(config.musicTrack()))
			{
				actions.setConfig("musicTrack", v.toString().trim());
			}
		});
		mus.add(left(row("Track", track)));
		mus.add(left(spinner("Volume %", "musicVolume", config.musicVolume(), 0, 100, 5)));
		mus.add(left(check("Fade in and out", "musicFade", config.musicFade())));
		page.add(mus);
		return page;
	}

	/** The game's music track names for the picker, sorted. */
	void showTracks(List<String> names)
	{
		tracks = new ArrayList<>(names);
		if (tab == Tab.STYLE)
		{
			showTab(Tab.STYLE);
		}
	}

	// ---------------------------------------------------------------- Captions

	private JPanel buildCaptions()
	{
		JPanel page = box();
		page.add(heading("Your captions"));
		JPanel cap = section();
		captionText.setLineWrap(true);
		captionText.setWrapStyleWord(true);
		captionText.setFont(FontManager.getRunescapeSmallFont());
		shownCaptions = config.customCaptions();
		captionText.setText(shownCaptions);
		JScrollPane scroll = new JScrollPane(captionText);
		scroll.setPreferredSize(new Dimension(THUMB_WIDTH, 200));
		cap.add(wide(scroll));
		cap.add(Box.createVerticalStrut(4));
		JPanel buttons = new JPanel(new GridLayout(1, 3, 4, 0));
		buttons.setOpaque(false);
		buttons.add(button("Save", "Use these captions in your next recaps", () ->
		{
			shownCaptions = captionText.getText();
			actions.setConfig("customCaptions", shownCaptions);
			showMessage("Captions saved.", false);
		}));
		buttons.add(button("Example", "Fill in an example to edit", () -> captionText.setText(
			"title:\n{player}'s week | {range}\n\ncard:\nGrind never stops | 99 here I come\n\n"
				+ "footage:\nThe big fight\nFinally got it!\n\nshare:\nBest week yet\n\noutro:\nSee you next week | Like & subscribe")));
		buttons.add(button("Undo", "Go back to the saved captions", () -> captionText.setText(shownCaptions)));
		cap.add(wide(buttons));
		cap.add(Box.createVerticalStrut(4));
		cap.add(left(help(CAPTION_HELP)));
		cap.add(left(spinner("Card length (s)", "customCardSeconds", config.customCardSeconds(), 1, 10, 1)));
		page.add(cap);

		page.add(heading("Caption style"));
		JPanel style = section();
		style.add(left(check("Captions on clips", "captionsEnabled", config.captionsEnabled())));
		style.add(left(combo("Style", "captionStyle", GielinorWrappedConfig.CaptionStyle.values(), config.captionStyle())));
		style.add(left(combo("Position", "captionPosition", GielinorWrappedConfig.CaptionPosition.values(), config.captionPosition())));
		style.add(left(spinner("Size %", "captionSize", config.captionSize(), 50, 200, 10)));
		style.add(left(text("Watermark", "watermark", config.watermark())));
		style.add(left(check("Hide my name", "hideName", config.hideName())));
		page.add(style);
		return page;
	}

	// ---------------------------------------------------------------- Shared

	/** Shows a card at a readable size in a small window. */
	void showPreview(Recaps.Saved s, BufferedImage card)
	{
		showImage(s.meta.period().describe(), card);
	}

	void showImage(String title, BufferedImage card)
	{
		JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title);
		double scale = Math.min(1, Math.min(900.0 / card.getWidth(), 700.0 / card.getHeight()));
		Image img = scale < 1 ? card.getScaledInstance((int) (card.getWidth() * scale), (int) (card.getHeight() * scale), Image.SCALE_SMOOTH) : card;
		dialog.add(new JLabel(new ImageIcon(img)));
		dialog.pack();
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	void showMessage(String text, boolean error)
	{
		status.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
		status.setText(text == null ? " " : "<html>" + text + "</html>");
	}

	private <E extends Enum<E>> JPanel combo(String label, String key, E[] values, E current)
	{
		JComboBox<E> c = new JComboBox<>(values);
		c.setSelectedItem(current);
		c.setFont(FontManager.getRunescapeSmallFont());
		c.addActionListener(e ->
		{
			Object v = c.getSelectedItem();
			if (v != null)
			{
				actions.setConfig(key, v);
				refreshStyle(key);
			}
		});
		return row(label, c);
	}

	private JCheckBox check(String label, String key, boolean current)
	{
		JCheckBox c = new JCheckBox(label, current);
		c.setOpaque(false);
		c.setFont(FontManager.getRunescapeSmallFont());
		c.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		c.addActionListener(e ->
		{
			actions.setConfig(key, c.isSelected());
			refreshStyle(key);
		});
		return c;
	}

	private JPanel spinner(String label, String key, int current, int min, int max, int step)
	{
		JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, current)), min, max, step));
		s.setPreferredSize(new Dimension(64, 22));
		s.addChangeListener(e -> actions.setConfig(key, s.getValue()));
		return row(label, s);
	}

	private JPanel color(String label, String key, Color current)
	{
		JButton b = new JButton(" ");
		b.setBackground(current);
		b.setPreferredSize(new Dimension(64, 20));
		b.setToolTipText("Pick a colour");
		b.addActionListener(e ->
		{
			Color c = JColorChooser.showDialog(this, label, b.getBackground());
			if (c != null)
			{
				b.setBackground(c);
				actions.setConfig(key, c);
			}
		});
		return row(label, b);
	}

	private JPanel text(String label, String key, String current)
	{
		JTextField f = new JTextField(current);
		f.setPreferredSize(new Dimension(110, 22));
		String[] saved = {current};
		Consumer<Object> save = x ->
		{
			if (!f.getText().equals(saved[0]))
			{
				saved[0] = f.getText();
				actions.setConfig(key, saved[0]);
			}
		};
		f.addActionListener(save::accept);
		f.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				save.accept(e);
			}
		});
		return row(label, f);
	}

	/** Rebuilds the style tab after a change that shows or hides options; other changes leave it be. */
	private void refreshStyle(String key)
	{
		if (tab == Tab.STYLE && REBUILDS_STYLE.contains(key))
		{
			SwingUtilities.invokeLater(() -> showTab(Tab.STYLE));
		}
	}

	private static JPanel row(String label, JComponent control)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		row.add(small(label), BorderLayout.WEST);
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.setOpaque(false);
		if (control instanceof JComboBox)
		{
			control.setPreferredSize(new Dimension(118, 22));
		}
		east.add(control);
		row.add(east, BorderLayout.CENTER);
		return row;
	}

	private JButton button(String text, String tip, Runnable action)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setToolTipText(tip);
		b.setMargin(new Insets(2, 5, 2, 5));
		b.addActionListener(e -> action.run());
		return b;
	}

	private static JLabel heading(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		l.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
		return left(l);
	}

	private static JLabel help(String text)
	{
		// A fixed body width makes Swing wrap the text and size the label to fit it.
		return small("<html><body style='width:" + (THUMB_WIDTH - 44) + "px'>" + text + "</body></html>");
	}

	private static JPanel box()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private static JPanel section()
	{
		JPanel p = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				// Full width in the panel's BoxLayout, not just as wide as the widest child.
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private static JLabel small(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	/** Stretches a control across the section. */
	private static <T extends JComponent> T wide(T c)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}

	private static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		return c;
	}
}
