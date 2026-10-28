package com.gielinorwrapped;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The sticker board: the player picks stickers from their cutouts and own images and places them on the
 * recap's scenes by hand. Edits a copy of the board; Save hands it back.
 */
final class StickerBoardDialog extends JDialog
{
	private static final int PREVIEW_W = 640;
	private static final int THUMB = 64;

	private final StickerBoard board;
	private final Map<String, Sticker> byKey = new HashMap<>();
	private final List<Sticker> stickers = new ArrayList<>();
	private final Consumer<Sticker> deleteCutout;
	private final Scenes.Look look;
	private final Dimension frame;
	/** Made in the constructor, once {@link #frame} is known. */
	private final Preview preview;
	private final JPanel palette = new JPanel(new GridLayout(0, 4, 4, 4));
	private final JTextField search = new JTextField();
	private final JComboBox<String> scene = new JComboBox<>();
	private String sceneName = StickerBoard.EVERY;

	StickerBoardDialog(Window owner, GielinorWrappedConfig config, List<Sticker> all, StickerBoard saved,
		Consumer<StickerBoard> save, Consumer<Sticker> deleteCutout)
	{
		super(owner, "Sticker board", ModalityType.APPLICATION_MODAL);
		this.board = saved.copy();
		this.deleteCutout = deleteCutout;
		this.look = Scenes.Look.of(config);
		this.frame = Composer.size(config);
		this.preview = new Preview();
		for (Sticker s : all)
		{
			String key = StickerBoard.key(s);
			if (key != null && byKey.putIfAbsent(key, s) == null)
			{
				stickers.add(s);
			}
		}

		for (String[] s : StickerBoard.SCENES)
		{
			scene.addItem(s[1]);
		}
		scene.addActionListener(e ->
		{
			sceneName = StickerBoard.SCENES[Math.max(0, scene.getSelectedIndex())][0];
			preview.selected = null;
			preview.repaint();
		});
		JCheckBox auto = new JCheckBox("Auto stickers on scenes I leave empty", board.autoFill);
		auto.addActionListener(e -> board.autoFill = auto.isSelected());
		JButton clear = new JButton("Clear scene");
		clear.addActionListener(e ->
		{
			board.spots.removeIf(s -> sceneName.equals(s.scene));
			preview.selected = null;
			preview.repaint();
		});
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		top.add(new JLabel("Scene"));
		top.add(scene);
		top.add(clear);
		top.add(auto);

		JLabel hint = new JLabel("<html>Click a sticker to add it. Drag to move, scroll to resize, Shift+scroll to turn, "
			+ "right-click or Delete to remove. Placed stickers replace the automatic ones on that scene.</html>");
		hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		JPanel centre = new JPanel(new BorderLayout());
		centre.add(preview, BorderLayout.CENTER);
		centre.add(hint, BorderLayout.SOUTH);

		search.setToolTipText("Search stickers");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				fillPalette();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				fillPalette();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				fillPalette();
			}
		});
		JPanel paletteWrap = new JPanel(new BorderLayout());
		paletteWrap.add(palette, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(paletteWrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		JPanel side = new JPanel(new BorderLayout(0, 4));
		side.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 6));
		side.add(search, BorderLayout.NORTH);
		side.add(scroll, BorderLayout.CENTER);
		side.setPreferredSize(new Dimension(4 * (THUMB + 12) + 24, 0));
		fillPalette();

		JButton ok = new JButton("Save");
		ok.addActionListener(e ->
		{
			save.accept(board.clean());
			dispose();
		});
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
		buttons.add(cancel);
		buttons.add(ok);

		JPanel root = new JPanel(new BorderLayout(6, 0));
		root.add(top, BorderLayout.NORTH);
		root.add(centre, BorderLayout.CENTER);
		root.add(side, BorderLayout.EAST);
		root.add(buttons, BorderLayout.SOUTH);
		setContentPane(root);
		getRootPane().setDefaultButton(ok);
		pack();
		setLocationRelativeTo(owner);
	}

	private void fillPalette()
	{
		palette.removeAll();
		String q = search.getText().trim().toLowerCase(Locale.ROOT);
		for (Sticker s : stickers)
		{
			String name = displayName(s);
			if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q))
			{
				continue;
			}
			palette.add(tile(s, name));
		}
		if (palette.getComponentCount() == 0)
		{
			palette.add(new JLabel(stickers.isEmpty() ? "<html>No stickers yet. Fight something, or add your own.</html>" : "No match"));
		}
		palette.revalidate();
		palette.repaint();
	}

	private static String displayName(Sticker s)
	{
		String n = s.kind == Sticker.Kind.MINE && s.name.lastIndexOf('.') > 0 ? s.name.substring(0, s.name.lastIndexOf('.')) : s.name;
		n = n.replace('_', ' ');
		return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	private JComponent tile(Sticker s, String name)
	{
		JButton b = new JButton(name.length() > 12 ? name.substring(0, 11) + "…" : name, new ImageIcon(thumb(s)));
		b.setVerticalTextPosition(SwingConstants.BOTTOM);
		b.setHorizontalTextPosition(SwingConstants.CENTER);
		b.setToolTipText(name);
		b.setMargin(new java.awt.Insets(2, 2, 2, 2));
		b.setFont(b.getFont().deriveFont(10f));
		b.addActionListener(e -> add(s));
		if (s.kind == Sticker.Kind.NPC && deleteCutout != null)
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem del = new JMenuItem("Delete cutout");
			del.addActionListener(e ->
			{
				if (JOptionPane.showConfirmDialog(this, "Delete the " + name + " cutout? Fighting it again takes a new one.",
					"Delete cutout", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
				{
					deleteCutout.accept(s);
					stickers.remove(s);
					String key = StickerBoard.key(s);
					byKey.remove(key);
					board.spots.removeIf(sp -> key.equals(sp.sticker));
					preview.selected = null;
					fillPalette();
					preview.repaint();
				}
			});
			menu.add(del);
			b.setComponentPopupMenu(menu);
		}
		return b;
	}

	private static BufferedImage thumb(Sticker s)
	{
		BufferedImage out = new BufferedImage(THUMB, THUMB, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		s.draw(g, THUMB / 2.0, THUMB / 2.0, THUMB - 4, 0, 0, 1);
		g.dispose();
		return out;
	}

	/** Puts a sticker in the middle of the scene being edited, a little off so repeats don't stack exactly. */
	private void add(Sticker s)
	{
		int n = board.on(sceneName).size();
		double size = Math.max(StickerBoard.MIN_SIZE, Math.min(StickerBoard.MAX_SIZE, 0.25));
		StickerBoard.Spot spot = new StickerBoard.Spot(sceneName, StickerBoard.key(s),
			Captions.clamp(0.5 + 0.04 * (n % 5)), Captions.clamp(0.5 + 0.04 * (n % 5)), size, 0);
		board.spots.add(spot);
		preview.selected = spot;
		preview.repaint();
		preview.requestFocusInWindow();
	}

	/** A scene of the board as the editor shows it, at output size. */
	static BufferedImage render(GielinorWrappedConfig config, List<Sticker> all, StickerBoard board, String sceneName)
	{
		Map<String, Sticker> byKey = new HashMap<>();
		for (Sticker s : all)
		{
			String key = StickerBoard.key(s);
			if (key != null)
			{
				byKey.putIfAbsent(key, s);
			}
		}
		Dimension frame = Composer.size(config);
		BufferedImage img = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		paintScene(g, frame.width, frame.height, Scenes.Look.of(config), board, byKey, sceneName, null);
		g.dispose();
		return img;
	}

	private static void paintScene(Graphics2D g, int w, int h, Scenes.Look look, StickerBoard board, Map<String, Sticker> byKey,
		String name, StickerBoard.Spot selected)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		look.theme.background(g, w, h, 1.0);
		MoreScenes.heading(g, look, StickerBoard.label(name), w, h, 10);
		int u = Math.min(w, h);
		List<StickerBoard.Spot> spots = new ArrayList<>(board.on(name));
		if (!StickerBoard.EVERY.equals(name))
		{
			// Every-scene stickers show faded underneath, so the player sees what else lands here.
			for (StickerBoard.Spot s : board.on(StickerBoard.EVERY))
			{
				Sticker st = byKey.get(s.sticker);
				if (st != null)
				{
					st.draw(g, s.x * w, s.y * h, s.size * u, s.angle, 0, 0.45);
				}
			}
		}
		for (StickerBoard.Spot s : spots)
		{
			Sticker st = byKey.get(s.sticker);
			if (st == null)
			{
				continue;
			}
			st.draw(g, s.x * w, s.y * h, s.size * u, s.angle, 0, 1);
			if (s == selected)
			{
				double r = s.size * u * 0.55;
				g.setColor(new Color(255, 255, 255, 170));
				g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0));
				g.draw(new java.awt.geom.Rectangle2D.Double(s.x * w - r, s.y * h - r, r * 2, r * 2));
			}
		}
	}

	/** The scene preview, at the output's aspect ratio. */
	private final class Preview extends JComponent
	{
		StickerBoard.Spot selected;
		private StickerBoard.Spot dragging;
		private double grabX;
		private double grabY;

		Preview()
		{
			int h = (int) Math.round(PREVIEW_W * (double) frame.height / frame.width);
			if (h > 560)
			{
				setPreferredSize(new Dimension((int) Math.round(560.0 * frame.width / frame.height), 560));
			}
			else
			{
				setPreferredSize(new Dimension(PREVIEW_W, h));
			}
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter mouse = new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					requestFocusInWindow();
					java.awt.Rectangle r = area();
					StickerBoard.Spot hit = hit(e.getX(), e.getY(), r);
					selected = hit;
					dragging = hit;
					if (hit != null)
					{
						grabX = (e.getX() - r.x) / (double) r.width - hit.x;
						grabY = (e.getY() - r.y) / (double) r.height - hit.y;
						if (e.isPopupTrigger() || javax.swing.SwingUtilities.isRightMouseButton(e))
						{
							menu(hit, e);
							dragging = null;
						}
						else
						{
							// Bring to front.
							board.spots.remove(hit);
							board.spots.add(hit);
						}
					}
					repaint();
				}

				@Override
				public void mouseDragged(MouseEvent e)
				{
					if (dragging == null)
					{
						return;
					}
					java.awt.Rectangle r = area();
					dragging.x = Captions.clamp((e.getX() - r.x) / (double) r.width - grabX);
					dragging.y = Captions.clamp((e.getY() - r.y) / (double) r.height - grabY);
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					dragging = null;
				}

				@Override
				public void mouseWheelMoved(MouseWheelEvent e)
				{
					java.awt.Rectangle r = area();
					StickerBoard.Spot s = hit(e.getX(), e.getY(), r);
					if (s == null)
					{
						s = selected;
					}
					if (s == null)
					{
						return;
					}
					selected = s;
					double steps = e.getPreciseWheelRotation();
					if (e.isShiftDown())
					{
						s.angle -= steps * Math.toRadians(5);
					}
					else
					{
						s.size = Math.max(StickerBoard.MIN_SIZE, Math.min(StickerBoard.MAX_SIZE, s.size * Math.pow(0.92, steps)));
					}
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			addMouseWheelListener(mouse);
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "remove");
			getActionMap().put("remove", new AbstractAction()
			{
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e)
				{
					if (selected != null)
					{
						board.spots.remove(selected);
						selected = null;
						repaint();
					}
				}
			});
		}

		private void menu(StickerBoard.Spot s, MouseEvent e)
		{
			JPopupMenu m = new JPopupMenu();
			JMenuItem remove = new JMenuItem("Remove");
			remove.addActionListener(a ->
			{
				board.spots.remove(s);
				selected = null;
				repaint();
			});
			JMenuItem straighten = new JMenuItem("Straighten");
			straighten.addActionListener(a ->
			{
				s.angle = 0;
				repaint();
			});
			JMenuItem back = new JMenuItem("Send to back");
			back.addActionListener(a ->
			{
				board.spots.remove(s);
				board.spots.add(0, s);
				repaint();
			});
			m.add(remove);
			m.add(straighten);
			m.add(back);
			m.show(this, e.getX(), e.getY());
		}

		/** The frame's area inside the component, letterboxed to the output aspect. */
		private java.awt.Rectangle area()
		{
			int w = getWidth();
			int h = getHeight();
			double scale = Math.min(w / (double) frame.width, h / (double) frame.height);
			int fw = (int) Math.round(frame.width * scale);
			int fh = (int) Math.round(frame.height * scale);
			return new java.awt.Rectangle((w - fw) / 2, (h - fh) / 2, Math.max(1, fw), Math.max(1, fh));
		}

		/** The topmost sticker of the current scene under (x, y). */
		private StickerBoard.Spot hit(int x, int y, java.awt.Rectangle r)
		{
			int u = Math.min(r.width, r.height);
			List<StickerBoard.Spot> spots = board.on(sceneName);
			for (int i = spots.size() - 1; i >= 0; i--)
			{
				StickerBoard.Spot s = spots.get(i);
				double half = s.size * u / 2;
				if (Math.abs(x - (r.x + s.x * r.width)) <= half && Math.abs(y - (r.y + s.y * r.height)) <= half)
				{
					return s;
				}
			}
			return null;
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());
			java.awt.Rectangle r = area();
			// Paint at output size and scale down, so text and stickers sit exactly as in the video.
			g.translate(r.x, r.y);
			g.scale(r.width / (double) frame.width, r.height / (double) frame.height);
			g.clipRect(0, 0, frame.width, frame.height);
			paintScene(g, frame.width, frame.height, look, board, byKey, sceneName, selected);
			g.dispose();
		}
	}
}
