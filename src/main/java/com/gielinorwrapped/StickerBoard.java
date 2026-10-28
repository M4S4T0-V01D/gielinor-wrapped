package com.gielinorwrapped;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the player put their stickers: per scene, by hand on the sticker board. Scenes the player hasn't
 * arranged get stickers picked automatically, unless that is switched off. Saved as JSON per account.
 */
final class StickerBoard
{
	/** Placements under this scene name show on every scene. */
	static final String EVERY = "every";

	/** Scenes a sticker can go on, in recap order: the scene name and the label the board shows. */
	static final String[][] SCENES = {
		{EVERY, "Every scene"},
		{"title", "Title"},
		{"xp", "Experience"},
		{"loot", "Loot"},
		{"kills", "Kills"},
		{"time", "Time played"},
		{"bosses", "Bosses"},
		{"map", "Map"},
		{"graphs", "Graphs"},
		{"pace", "Pace"},
		{"collection", "Collection log"},
		{"ge", "Grand Exchange"},
		{"clip", "Clips"},
		{"footage", "Footage"},
		{"gallery", "Screenshots"},
		{"deaths", "Deaths"},
		{"persona", "Persona"},
		{"outro", "Outro"},
	};

	static final double MIN_SIZE = 0.05;
	static final double MAX_SIZE = 0.9;

	/** One sticker on one scene. Position and size are fractions of the frame, as in {@link Scenes.Placement}. */
	static final class Spot
	{
		String scene;
		/** {@link #key} of the sticker. */
		String sticker;
		double x;
		double y;
		double size;
		double angle;

		Spot()
		{
		}

		Spot(String scene, String sticker, double x, double y, double size, double angle)
		{
			this.scene = scene;
			this.sticker = sticker;
			this.x = x;
			this.y = y;
			this.size = size;
			this.angle = angle;
		}
	}

	List<Spot> spots = new ArrayList<>();
	/** Stickers picked automatically for the scenes the player left alone. */
	boolean autoFill = true;

	/** The name a sticker is saved under, or null for game sprites, which the board doesn't offer. */
	static String key(Sticker s)
	{
		switch (s.kind)
		{
			case NPC:
				return "npc:" + ClipLibrary.safeName(s.name);
			case MINE:
				return "mine:" + s.name;
			default:
				return null;
		}
	}

	/** The label shown for a scene name, or the name itself. */
	static String label(String scene)
	{
		for (String[] s : SCENES)
		{
			if (s[0].equals(scene))
			{
				return s[1];
			}
		}
		return scene;
	}

	List<Spot> on(String scene)
	{
		List<Spot> out = new ArrayList<>();
		for (Spot s : spots)
		{
			if (scene.equals(s.scene))
			{
				out.add(s);
			}
		}
		return out;
	}

	/** True when the player placed stickers on this very scene, so it gets no automatic ones. */
	boolean arranged(String scene)
	{
		for (Spot s : spots)
		{
			if (scene.equals(s.scene))
			{
				return true;
			}
		}
		return false;
	}

	/** Drops spots with missing fields and pulls the rest back into the frame, after reading a hand-edited file. */
	StickerBoard clean()
	{
		if (spots == null)
		{
			spots = new ArrayList<>();
		}
		spots.removeIf(s -> s == null || s.scene == null || s.sticker == null);
		for (Spot s : spots)
		{
			s.x = Captions.clamp(s.x);
			s.y = Captions.clamp(s.y);
			s.size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, s.size));
		}
		return this;
	}

	StickerBoard copy()
	{
		StickerBoard b = new StickerBoard();
		b.autoFill = autoFill;
		for (Spot s : spots)
		{
			b.spots.add(new Spot(s.scene, s.sticker, s.x, s.y, s.size, s.angle));
		}
		return b;
	}
}
