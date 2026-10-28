package com.gielinorwrapped.music;

import java.util.HashMap;
import java.util.Map;

/**
 * A small General MIDI style synthesizer: band-limited wavetable voices picked by instrument family,
 * plucked and sustained envelopes, synthesized drums on channel 10 and a light stereo reverb. It is an
 * approximation of the game's own instruments, rendered offline to stereo floats at 44.1 kHz.
 */
public final class Synth
{
	public static final int RATE = 44100;
	private static final int TABLE = 2048;
	private static final int BLOCK = 64;
	private static final int MAX_VOICES = 48;
	private static final double LOOP_GAP = 1.0;

	private final Map<Long, float[]> tables = new HashMap<>();
	private final Voice[] voices = new Voice[MAX_VOICES];
	private final Channel[] channels = new Channel[16];
	private long noteCounter;

	private Synth()
	{
		for (int i = 0; i < voices.length; i++)
		{
			voices[i] = new Voice();
		}
		for (int i = 0; i < channels.length; i++)
		{
			channels[i] = new Channel();
		}
	}

	/**
	 * Renders the song for the given length, looping it if it is shorter.
	 *
	 * @param volume 0..1
	 * @param fade fade in over the first second and out over the last two
	 * @return left and right sample arrays in -1..1
	 */
	public static float[][] render(Midi song, double seconds, double volume, boolean fade)
	{
		return new Synth().run(song, seconds, volume, fade);
	}

	private float[][] run(Midi song, double seconds, double volume, boolean fade)
	{
		int total = (int) Math.ceil(seconds * RATE);
		float[] left = new float[total];
		float[] right = new float[total];
		double period = song.length + LOOP_GAP;
		int ev = 0;
		double offset = 0;
		float[] bl = new float[BLOCK];
		float[] br = new float[BLOCK];
		for (int start = 0; start < total; start += BLOCK)
		{
			double now = start / (double) RATE;
			while (song.size() > 0 && offset + song.time[ev] <= now)
			{
				event(song.status[ev], song.data1[ev], song.data2[ev]);
				if (++ev == song.size())
				{
					ev = 0;
					offset += period;
					allNotesOff();
				}
			}
			int n = Math.min(BLOCK, total - start);
			java.util.Arrays.fill(bl, 0);
			java.util.Arrays.fill(br, 0);
			for (Voice v : voices)
			{
				if (v.active)
				{
					v.render(bl, br, n);
				}
			}
			System.arraycopy(bl, 0, left, start, n);
			System.arraycopy(br, 0, right, start, n);
		}
		new Reverb().apply(left, right, 0.18f);
		master(left, right, volume, fade);
		return new float[][]{left, right};
	}

	/** Levels the mix to a steady loudness, soft-limits peaks, applies the volume and the fades. */
	private static void master(float[] left, float[] right, double volume, boolean fade)
	{
		double sum = 0;
		int counted = 0;
		for (int i = 0; i < left.length; i += 4)
		{
			sum += left[i] * left[i] + right[i] * right[i];
			counted += 2;
		}
		double rms = Math.sqrt(sum / Math.max(1, counted));
		double gain = rms < 1e-6 ? 0 : 0.17 / rms;
		int fadeIn = fade ? Math.min(left.length / 4, RATE) : 0;
		int fadeOut = fade ? Math.min(left.length / 3, 2 * RATE) : Math.min(left.length, RATE / 20);
		for (int i = 0; i < left.length; i++)
		{
			double g = gain;
			if (i < fadeIn)
			{
				g *= i / (double) fadeIn;
			}
			int fromEnd = left.length - i;
			if (fromEnd < fadeOut)
			{
				double f = fromEnd / (double) fadeOut;
				g *= f * f;
			}
			left[i] = (float) (limit(left[i] * g) * volume);
			right[i] = (float) (limit(right[i] * g) * volume);
		}
	}

	private static double limit(double x)
	{
		double a = Math.abs(x);
		if (a < 0.6)
		{
			return x;
		}
		double over = (a - 0.6) / 0.4;
		double y = 0.6 + 0.4 * over / (1 + over);
		return Math.copySign(Math.min(y, 0.98), x);
	}

	private void allNotesOff()
	{
		for (Voice v : voices)
		{
			if (v.active)
			{
				v.release();
			}
		}
		for (Channel c : channels)
		{
			c.sustain = false;
		}
	}

	private void event(int status, int d1, int d2)
	{
		int type = status & 0xF0;
		int ch = status & 0x0F;
		Channel c = channels[ch];
		switch (type)
		{
			case 0x90:
				if (d2 > 0)
				{
					noteOn(ch, d1, d2);
					break;
				}
				// velocity 0 is a note off
			case 0x80:
				for (Voice v : voices)
				{
					if (v.active && !v.released && v.channel == ch && v.note == d1)
					{
						if (c.sustain)
						{
							v.held = true;
						}
						else
						{
							v.release();
						}
					}
				}
				break;
			case 0xB0:
				control(c, ch, d1, d2);
				break;
			case 0xC0:
				c.program = d1;
				break;
			case 0xE0:
				c.bend = ((d2 << 7 | d1) - 8192) / 8192.0;
				break;
			default:
				break;
		}
	}

	private void control(Channel c, int ch, int cc, int value)
	{
		switch (cc)
		{
			case 6:
				if (c.rpn == 0)
				{
					c.bendRange = Math.max(1, value);
				}
				break;
			case 7:
				c.volume = value / 127.0;
				break;
			case 10:
				c.pan = value / 127.0;
				break;
			case 11:
				c.expression = value / 127.0;
				break;
			case 64:
				c.sustain = value >= 64;
				if (!c.sustain)
				{
					for (Voice v : voices)
					{
						if (v.active && v.held && v.channel == ch)
						{
							v.release();
						}
					}
				}
				break;
			case 100:
				c.rpn = (c.rpn & ~0x7F) | value;
				break;
			case 101:
				c.rpn = (c.rpn & 0x7F) | value << 7;
				break;
			case 120:
			case 123:
				for (Voice v : voices)
				{
					if (v.active && v.channel == ch)
					{
						v.release();
					}
				}
				break;
			case 121:
				c.reset();
				break;
			default:
				break;
		}
	}

	private void noteOn(int ch, int note, int velocity)
	{
		Channel c = channels[ch];
		Voice v = null;
		for (Voice x : voices)
		{
			if (x.active && x.channel == ch && x.note == note)
			{
				// Restriking a ringing note: reuse its voice so notes do not pile up.
				v = x;
				break;
			}
		}
		if (v == null)
		{
			for (Voice x : voices)
			{
				if (!x.active)
				{
					v = x;
					break;
				}
			}
		}
		if (v == null)
		{
			Voice quietest = null;
			for (Voice x : voices)
			{
				double level = x.env * (x.released ? 0.25 : 1);
				if (quietest == null || level < quietest.env * (quietest.released ? 0.25 : 1))
				{
					quietest = x;
				}
			}
			v = quietest;
		}
		v.start(c, ch, note, velocity, ch == 9 ? null : Patch.of(c.program), noteCounter++);
	}

	private float[] table(Patch p, int note)
	{
		int octave = Math.max(0, Math.min(10, note / 12));
		long key = (long) p.spectrum.hashCode() << 8 | octave;
		float[] t = tables.get(key);
		if (t == null)
		{
			double topFreq = 440 * Math.pow(2, ((octave + 1) * 12 + 2 - 69) / 12.0);
			int harmonics = Math.max(1, Math.min(p.spectrum.length, (int) (18000 / topFreq)));
			t = new float[TABLE + 1];
			double peak = 0;
			double[] raw = new double[TABLE];
			for (int k = 1; k <= harmonics; k++)
			{
				double a = p.spectrum[k - 1];
				if (a == 0)
				{
					continue;
				}
				for (int i = 0; i < TABLE; i++)
				{
					raw[i] += a * Math.sin(2 * Math.PI * k * i / TABLE);
				}
			}
			for (double r : raw)
			{
				peak = Math.max(peak, Math.abs(r));
			}
			for (int i = 0; i < TABLE; i++)
			{
				t[i] = (float) (raw[i] / Math.max(peak, 1e-9));
			}
			t[TABLE] = t[0];
			tables.put(key, t);
		}
		return t;
	}

	private static final class Channel
	{
		int program;
		double volume;
		double expression;
		double pan;
		double bend;
		int bendRange;
		int rpn;
		boolean sustain;

		Channel()
		{
			reset();
		}

		void reset()
		{
			volume = 100 / 127.0;
			expression = 1;
			pan = 0.5;
			bend = 0;
			bendRange = 2;
			rpn = 0x3FFF;
			sustain = false;
		}
	}

	private final class Voice
	{
		boolean active;
		boolean released;
		boolean held;
		boolean attacking;
		int channel;
		int note;
		long order;
		Channel ch;
		Patch patch;
		Drum drum;
		float[] wave;
		double phase;
		double phase2;
		double env;
		double gain;
		double age;
		double attackStep;
		double decayCoef;
		double releaseCoef;
		double lp1;
		double lp2;
		double baseFreq;
		int noise = 0x2545F491;
		double drumLp;

		void start(Channel c, int chIndex, int note, int velocity, Patch p, long order)
		{
			boolean restrike = active && this.note == note && channel == chIndex;
			active = true;
			released = false;
			held = false;
			channel = chIndex;
			this.note = note;
			this.order = order;
			ch = c;
			patch = p;
			age = 0;
			double vel = velocity / 127.0;
			if (p == null)
			{
				drum = Drum.of(note);
				wave = null;
				gain = vel * vel * drum.gain * 0.5;
				env = 1;
				noise ^= (int) (order * 0x9E3779B9L);
				if (noise == 0)
				{
					noise = 0x2545F491;
				}
				drumLp = 0;
				phase = 0;
				return;
			}
			drum = null;
			wave = table(p, note);
			baseFreq = 440 * Math.pow(2, (note - 69) / 12.0);
			gain = vel * vel * p.gain * 0.3;
			if (!restrike)
			{
				env = 0;
				phase = 0;
				phase2 = 0.37;
				lp1 = 0;
				lp2 = 0;
			}
			attackStep = 1.0 / Math.max(1, p.attack * RATE);
			attacking = true;
			// Plucked notes ring shorter the higher they are.
			double t60 = p.sustainLevel > 0 ? p.decay : p.decay * Math.pow(2, -(note - 60) / 24.0);
			decayCoef = Math.pow(10, -3 / (Math.max(0.02, t60) * RATE));
			releaseCoef = Math.pow(10, -3 / (Math.max(0.02, p.release) * RATE));
		}

		void release()
		{
			released = true;
			held = false;
		}

		void render(float[] left, float[] right, int n)
		{
			double level = gain * ch.volume * ch.volume * ch.expression;
			double pan = ch.pan;
			float l = (float) (level * Math.cos(pan * Math.PI / 2) * 1.41);
			float r = (float) (level * Math.sin(pan * Math.PI / 2) * 1.41);
			if (drum != null)
			{
				renderDrum(left, right, n, l, r);
				return;
			}
			Patch p = patch;
			double cents = p.vibrato * Math.min(1, Math.max(0, (age - 0.25) * 2)) * Math.sin(2 * Math.PI * 5.5 * age);
			double freq = baseFreq * Math.pow(2, (ch.bend * ch.bendRange + cents / 100) / 12);
			double inc = freq * TABLE / RATE;
			double inc2 = p.detune == 0 ? 0 : inc * Math.pow(2, p.detune / 1200);
			double cutoff = freq * (p.filterEnd + (p.filterStart - p.filterEnd) * Math.exp(-age / p.filterTime));
			double a = cutoff >= RATE * 0.45 ? 1 : 1 - Math.exp(-2 * Math.PI * cutoff / RATE);
			float[] w = wave;
			for (int i = 0; i < n; i++)
			{
				if (released)
				{
					env *= releaseCoef;
				}
				else if (attacking)
				{
					env += attackStep;
					if (env >= 1)
					{
						env = 1;
						attacking = false;
					}
				}
				else if (p.sustainLevel > 0)
				{
					env += (p.sustainLevel - env) * (1 - decayCoef) * 4;
				}
				else
				{
					env *= decayCoef;
				}
				int idx = (int) phase;
				double frac = phase - idx;
				double s = w[idx] + (w[idx + 1] - w[idx]) * frac;
				phase += inc;
				if (phase >= TABLE)
				{
					phase -= TABLE;
				}
				if (inc2 != 0)
				{
					int i2 = (int) phase2;
					double f2 = phase2 - i2;
					s = (s + w[i2] + (w[i2 + 1] - w[i2]) * f2) * 0.6;
					phase2 += inc2;
					if (phase2 >= TABLE)
					{
						phase2 -= TABLE;
					}
				}
				lp1 += a * (s - lp1);
				lp2 += a * (lp1 - lp2);
				float out = (float) (lp2 * env);
				left[i] += out * l;
				right[i] += out * r;
			}
			age += n / (double) RATE;
			if (env < 1e-4 && (released || p.sustainLevel == 0) && age > p.attack)
			{
				active = false;
			}
		}

		private void renderDrum(float[] left, float[] right, int n, float l, float r)
		{
			Drum d = drum;
			double toneCoef = Math.pow(10, -3 / (d.toneDecay * RATE));
			double noiseCoef = Math.pow(10, -3 / (d.noiseDecay * RATE));
			double hp = 1 - Math.exp(-2 * Math.PI * d.highpass / RATE);
			double toneEnv = Math.pow(toneCoef, age * RATE);
			double noiseEnv = Math.pow(noiseCoef, age * RATE);
			for (int i = 0; i < n; i++)
			{
				double t = age + i / (double) RATE;
				double f = d.toneEnd + (d.toneStart - d.toneEnd) * Math.exp(-t / d.sweep);
				phase += f / RATE;
				if (phase >= 1)
				{
					phase -= 1;
				}
				double tone = Math.sin(2 * Math.PI * phase);
				if (d.tone2 > 0)
				{
					tone = 0.6 * tone + 0.4 * Math.sin(2 * Math.PI * phase * d.tone2);
				}
				noise ^= noise << 13;
				noise ^= noise >>> 17;
				noise ^= noise << 5;
				double nz = noise / 2147483648.0;
				drumLp += hp * (nz - drumLp);
				nz -= drumLp;
				double s = tone * toneEnv * d.toneLevel + nz * noiseEnv * d.noiseLevel;
				toneEnv *= toneCoef;
				noiseEnv *= noiseCoef;
				float out = (float) s;
				left[i] += out * l;
				right[i] += out * r;
			}
			age += n / (double) RATE;
			if (toneEnv * d.toneLevel + noiseEnv * d.noiseLevel < 1e-4)
			{
				active = false;
			}
		}
	}

	/** An instrument: harmonic spectrum, envelope and tone filter. */
	static final class Patch
	{
		private static final double[] SINE = {1};
		private static final double[] SAW = series(32, 1, 1, false);
		private static final double[] SQUARE = series(32, 1, 1, true);
		private static final double[] SOFT_SQUARE = series(24, 1, 1.4, true);
		private static final double[] TRIANGLE = series(16, 1, 2, true);
		private static final double[] PIANO = series(28, 1, 1.35, false);
		private static final double[] BRASS = series(28, 1, 0.85, false);
		private static final double[] STRINGS = series(32, 1, 1.1, false);
		private static final double[] ORGAN = {1, 0.65, 0.45, 0.35, 0, 0.22, 0, 0.16};
		private static final double[] BELL = {1, 0.15, 0, 0.4, 0, 0.12, 0, 0, 0, 0.18};
		private static final double[] MARIMBA = {1, 0, 0, 0.3, 0, 0, 0, 0, 0, 0.08};
		private static final double[] FLUTE = {1, 0.32, 0.12, 0.05};
		private static final double[] CLARINET = {1, 0.04, 0.55, 0.03, 0.32, 0.02, 0.2, 0, 0.12, 0, 0.07};
		private static final double[] OBOE = {0.7, 1, 0.85, 0.5, 0.42, 0.25, 0.18, 0.1, 0.06};
		private static final double[] VOICE = {1, 0.55, 0.65, 0.35, 0.12, 0.1, 0.05};
		private static final Patch[] CACHE = new Patch[128];

		final double[] spectrum;
		double attack = 0.005;
		/** Plucked: seconds to fade by 60 dB at middle C. Sustained: seconds to settle to the sustain level. */
		double decay = 1.5;
		double sustainLevel;
		double release = 0.25;
		double filterStart = 40;
		double filterEnd = 40;
		double filterTime = 0.3;
		double vibrato;
		double detune;
		double gain = 1;

		private Patch(double[] spectrum)
		{
			this.spectrum = spectrum;
		}

		private static double[] series(int n, double first, double power, boolean oddOnly)
		{
			double[] s = new double[n];
			for (int k = 1; k <= n; k++)
			{
				s[k - 1] = oddOnly && k % 2 == 0 ? 0 : first / Math.pow(k, power);
			}
			return s;
		}

		private Patch plucked(double t60, double release, double filterStart, double filterEnd, double filterTime)
		{
			this.decay = t60;
			this.sustainLevel = 0;
			this.release = release;
			this.filterStart = filterStart;
			this.filterEnd = filterEnd;
			this.filterTime = filterTime;
			return this;
		}

		private Patch sustained(double attack, double level, double release)
		{
			this.attack = attack;
			this.sustainLevel = level;
			this.decay = 0.3;
			this.release = release;
			return this;
		}

		private Patch tone(double filter, double vibrato, double detune, double gain)
		{
			this.filterStart = filter;
			this.filterEnd = filter;
			this.vibrato = vibrato;
			this.detune = detune;
			this.gain = gain;
			return this;
		}

		static Patch of(int program)
		{
			program &= 127;
			Patch p = CACHE[program];
			if (p == null)
			{
				p = make(program);
				CACHE[program] = p;
			}
			return p;
		}

		private static Patch make(int program)
		{
			switch (program)
			{
				case 12:
				case 13:
					return new Patch(MARIMBA).plucked(0.5, 0.3, 40, 40, 1);
				case 14:
					return new Patch(BELL).plucked(4, 3, 40, 40, 1);
				case 21:
				case 22:
				case 23:
					return new Patch(SOFT_SQUARE).sustained(0.03, 0.85, 0.1).tone(8, 6, 0, 0.7);
				case 29:
				case 30:
					return new Patch(SAW).sustained(0.01, 0.8, 0.15).tone(6, 0, 6, 0.65);
				case 38:
				case 39:
					return new Patch(SAW).plucked(1.2, 0.1, 8, 2, 0.25);
				case 45:
					return new Patch(STRINGS).plucked(0.35, 0.15, 6, 2, 0.1);
				case 46:
					return new Patch(TRIANGLE).plucked(1.8, 1.2, 8, 3, 0.5);
				case 47:
					return new Patch(SINE).plucked(1.0, 0.6, 40, 40, 1).tone(40, 0, 0, 1.3);
				case 55:
					return new Patch(SAW).plucked(0.45, 0.2, 10, 3, 0.15);
				case 68:
				case 69:
				case 111:
					return new Patch(OBOE).sustained(0.03, 0.9, 0.08).tone(40, 8, 0, 0.75);
				case 70:
					return new Patch(OBOE).sustained(0.03, 0.9, 0.1).tone(5, 5, 0, 0.8);
				case 71:
				case 109:
					return new Patch(CLARINET).sustained(0.03, 0.9, 0.08).tone(40, 5, 0, 0.8);
				case 80:
					return new Patch(SQUARE).sustained(0.01, 0.85, 0.08).tone(10, 8, 0, 0.55);
				case 108:
				case 112:
				case 113:
				case 114:
					return new Patch(BELL).plucked(program == 113 ? 0.5 : 1.2, 0.6, 40, 40, 1);
				case 110:
					return new Patch(STRINGS).sustained(0.06, 0.9, 0.2).tone(6, 12, 0, 0.75);
				case 104:
				case 105:
				case 106:
				case 107:
					return new Patch(SAW).plucked(1.0, 0.3, 10, 3, 0.25);
				default:
					break;
			}
			switch (program / 8)
			{
				case 0:
					return new Patch(PIANO).plucked(3, 0.35, 12, 3, 0.6);
				case 1:
					return new Patch(BELL).plucked(1.5, 0.8, 40, 40, 1);
				case 2:
					return new Patch(ORGAN).sustained(0.01, 1, 0.08).tone(40, 0, 0, 0.6);
				case 3:
					return new Patch(SAW).plucked(1.6, 0.25, 7, 1.6, 0.35);
				case 4:
					return new Patch(SAW).plucked(1.6, 0.15, 5, 1.5, 0.3).tone(5, 0, 0, 1.2);
				case 5:
					return new Patch(STRINGS).sustained(0.09, 0.9, 0.3).tone(6, 12, 0, 0.7);
				case 6:
					if (program >= 52)
					{
						return new Patch(VOICE).sustained(0.15, 0.9, 0.35).tone(40, 10, 9, 0.75);
					}
					return new Patch(STRINGS).sustained(0.15, 0.9, 0.4).tone(5, 8, 8, 0.65);
				case 7:
					return new Patch(BRASS).sustained(0.04, 0.85, 0.12).tone(6, 5, 0, 0.6);
				case 8:
					return new Patch(SOFT_SQUARE).sustained(0.03, 0.9, 0.1).tone(6, 8, 0, 0.7);
				case 9:
					return new Patch(FLUTE).sustained(0.05, 0.9, 0.12).tone(40, 10, 0, 0.85);
				case 10:
					return new Patch(SAW).sustained(0.01, 0.85, 0.1).tone(8, 6, 0, 0.5);
				case 11:
					return new Patch(SAW).sustained(0.35, 0.9, 0.6).tone(3, 0, 10, 0.6);
				case 14:
					return new Patch(SINE).plucked(0.3, 0.2, 40, 40, 1);
				case 15:
					return new Patch(SINE).sustained(0.2, 0.5, 0.3).tone(40, 0, 0, 0.3);
				default:
					return new Patch(TRIANGLE).sustained(0.2, 0.8, 0.5).tone(40, 0, 6, 0.6);
			}
		}
	}

	/** A synthesized percussion sound: a swept tone plus filtered noise. */
	static final class Drum
	{
		double toneStart = 200;
		double toneEnd = 200;
		double sweep = 0.05;
		double toneDecay = 0.2;
		double toneLevel;
		double tone2;
		double noiseDecay = 0.1;
		double noiseLevel;
		double highpass = 20;
		double gain = 1;
		private static final Drum[] CACHE = new Drum[128];

		private Drum tone(double start, double end, double sweep, double decay, double level)
		{
			toneStart = start;
			toneEnd = end;
			this.sweep = sweep;
			toneDecay = decay;
			toneLevel = level;
			return this;
		}

		private Drum noise(double decay, double level, double highpass)
		{
			noiseDecay = decay;
			noiseLevel = level;
			this.highpass = highpass;
			return this;
		}

		static Drum of(int note)
		{
			note &= 127;
			Drum d = CACHE[note];
			if (d == null)
			{
				d = make(note);
				CACHE[note] = d;
			}
			return d;
		}

		private static Drum make(int note)
		{
			Drum d = new Drum();
			switch (note)
			{
				case 35:
				case 36:
					return d.tone(130, 45, 0.04, 0.4, 1.2).noise(0.02, 0.15, 800);
				case 37:
					return d.tone(820, 820, 1, 0.05, 0.4).noise(0.04, 0.4, 1500);
				case 38:
				case 40:
					return d.tone(200, 165, 0.03, 0.15, 0.55).noise(0.22, 0.75, 1200);
				case 39:
					return d.noise(0.15, 0.9, 1000);
				case 41:
				case 43:
				case 45:
				case 47:
				case 48:
				case 50:
				{
					double f = 70 + (note - 41) * 14;
					return d.tone(f * 1.4, f, 0.08, 0.45, 1).noise(0.05, 0.12, 600);
				}
				case 42:
					return d.noise(0.05, 0.45, 7000);
				case 44:
					return d.noise(0.08, 0.35, 7000);
				case 46:
					return d.noise(0.45, 0.4, 6500);
				case 49:
				case 52:
				case 55:
				case 57:
					return d.noise(1.6, 0.42, 4500);
				case 51:
				case 59:
					return d.tone(3100, 3100, 1, 0.8, 0.08).noise(1.0, 0.25, 6000);
				case 53:
					d.tone2 = 1.48;
					return d.tone(2300, 2300, 1, 0.9, 0.3);
				case 54:
					return d.noise(0.18, 0.45, 5500);
				case 56:
					d.tone2 = 1.51;
					return d.tone(560, 560, 1, 0.3, 0.45);
				case 75:
				case 76:
				case 77:
					return d.tone(1800 + (77 - note) * 400, 1800 + (77 - note) * 400, 1, 0.07, 0.5);
				case 81:
					d.tone2 = 1.33;
					return d.tone(4200, 4200, 1, 1.0, 0.18);
				default:
					if (note < 35)
					{
						return d.tone(150, 60, 0.05, 0.25, 0.8).noise(0.05, 0.2, 800);
					}
					return d.tone(400 + note * 6, 380 + note * 6, 0.05, 0.12, 0.4).noise(0.08, 0.35, 2500);
			}
		}
	}

	/** A small Schroeder/Freeverb style reverb: four damped combs and two allpasses per side. */
	private static final class Reverb
	{
		private static final int[] COMBS = {1116, 1188, 1277, 1356};
		private static final int[] ALLPASS = {556, 441};

		void apply(float[] left, float[] right, float wet)
		{
			process(left, 0, wet);
			process(right, 23, wet);
		}

		private static void process(float[] x, int spread, float wet)
		{
			float[][] comb = new float[COMBS.length][];
			int[] cp = new int[COMBS.length];
			float[] store = new float[COMBS.length];
			for (int i = 0; i < COMBS.length; i++)
			{
				comb[i] = new float[COMBS[i] + spread];
			}
			float[][] ap = new float[ALLPASS.length][];
			int[] app = new int[ALLPASS.length];
			for (int i = 0; i < ALLPASS.length; i++)
			{
				ap[i] = new float[ALLPASS[i] + spread];
			}
			float feedback = 0.8f;
			float damp = 0.25f;
			for (int n = 0; n < x.length; n++)
			{
				float in = x[n] * 0.12f;
				float out = 0;
				for (int i = 0; i < COMBS.length; i++)
				{
					float[] buf = comb[i];
					float y = buf[cp[i]];
					store[i] = y * (1 - damp) + store[i] * damp;
					buf[cp[i]] = in + store[i] * feedback;
					if (++cp[i] == buf.length)
					{
						cp[i] = 0;
					}
					out += y;
				}
				for (int i = 0; i < ALLPASS.length; i++)
				{
					float[] buf = ap[i];
					float b = buf[app[i]];
					buf[app[i]] = out + b * 0.5f;
					out = b - out;
					if (++app[i] == buf.length)
					{
						app[i] = 0;
					}
				}
				x[n] = x[n] + out * wet * 4;
			}
		}
	}
}
