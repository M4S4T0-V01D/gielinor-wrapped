package com.gielinorwrapped.music;

import java.io.ByteArrayOutputStream;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Builds a small song with javax.sound.midi, then checks the parser's timing and the synth's output. */
public class SynthTest
{
	public static byte[] song() throws Exception
	{
		Sequence seq = new Sequence(Sequence.PPQ, 96);
		Track tempo = seq.createTrack();
		// 120 bpm for the first bar, then 60 bpm.
		tempo.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte) 0xA1, 0x20}, 3), 0));
		tempo.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x0F, 0x42, 0x40}, 3), 384));
		Track melody = seq.createTrack();
		int[] programs = {0, 48, 73, 33};
		for (int ch = 0; ch < programs.length; ch++)
		{
			melody.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, programs[ch], 0), 0));
			melody.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 10, 20 + ch * 30), 0));
		}
		int[] notes = {60, 64, 67, 72, 67, 64, 60, 55};
		for (int i = 0; i < notes.length; i++)
		{
			int ch = i % programs.length;
			melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, notes[i], 100), i * 96));
			melody.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, ch, notes[i], 0), i * 96 + 90));
		}
		melody.add(new MidiEvent(new ShortMessage(ShortMessage.PITCH_BEND, 2, 0, 96), 400));
		Track drums = seq.createTrack();
		for (int i = 0; i < 8; i++)
		{
			int note = i % 2 == 0 ? 36 : 38;
			drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, note, 110), i * 96));
			drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 9, note, 0), i * 96 + 10));
			drums.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 9, 42, 70), i * 96 + 48));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		MidiSystem.write(seq, 1, out);
		return out.toByteArray();
	}

	@Test
	public void parsesTempoChanges() throws Exception
	{
		Midi m = Midi.parse(song());
		assertTrue(m.size() > 30);
		for (int i = 1; i < m.size(); i++)
		{
			assertTrue(m.time[i] >= m.time[i - 1]);
		}
		// Notes 0-3 at 0.5 s per beat, then 1 s per beat after tick 384: the last note-off is at tick 762.
		assertEquals(2 + (762 - 384) / 96.0, m.length, 1e-6);
	}

	@Test
	public void rendersAudibleLoopingStereo() throws Exception
	{
		Midi m = Midi.parse(song());
		double seconds = 15;
		float[][] pcm = Synth.render(m, seconds, 0.8, true);
		assertEquals(2, pcm.length);
		assertEquals((int) Math.ceil(seconds * Synth.RATE), pcm[0].length);
		double peak = 0;
		double diff = 0;
		for (int c = 0; c < 2; c++)
		{
			for (float s : pcm[c])
			{
				assertTrue(!Float.isNaN(s) && !Float.isInfinite(s));
				peak = Math.max(peak, Math.abs(s));
			}
		}
		for (int i = 0; i < pcm[0].length; i++)
		{
			diff += Math.abs(pcm[0][i] - pcm[1][i]);
		}
		assertTrue("peak " + peak, peak > 0.1 && peak <= 1);
		assertTrue("panned channels differ", diff > 100);
		// The song is about 6 s long, so the second pass (from about 7 s) must also make sound.
		assertTrue("loops", rms(pcm[0], 8 * Synth.RATE, 10 * Synth.RATE) > 0.02);
		// Fades: quiet at both ends.
		assertTrue(rms(pcm[0], 0, 2000) < rms(pcm[0], Synth.RATE * 2, Synth.RATE * 3));
		assertTrue(Math.abs(pcm[0][pcm[0].length - 1]) < 0.01);
	}

	@Test
	public void emptySongIsSilent()
	{
		byte[] header = {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 0, 0, 96};
		float[][] pcm = Synth.render(Midi.parse(header), 1, 1, false);
		for (float s : pcm[0])
		{
			assertEquals(0, s, 0);
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsGarbage()
	{
		Midi.parse(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
	}

	@Test(expected = IllegalArgumentException.class)
	public void trackLoaderRejectsGarbage()
	{
		TrackLoader.toMidi(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
	}

	private static double rms(float[] a, int from, int to)
	{
		double s = 0;
		for (int i = from; i < to; i++)
		{
			s += a[i] * a[i];
		}
		return Math.sqrt(s / (to - from));
	}
}
