/*
 * SPD Duel - a PvP mod of Shattered Pixel Dungeon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.duel;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.effects.Beam;
import com.shatteredpixel.shatteredpixeldungeon.effects.Flare;
import com.shatteredpixel.shatteredpixeldungeon.effects.Lightning;
import com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.effects.SpellSprite;
import com.shatteredpixel.shatteredpixeldungeon.effects.Splash;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.noosa.Group;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.Bundle;
import com.watabou.utils.PointF;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * Records every visual and sound effect my phone makes during my turn (sounds, particle bursts,
 * bolts, beams, lightning, flares, splashes, spell icons, screen flashes and shakes, and my
 * hero's and creatures' animations) so the other phones can play the same thing back, in the
 * same order and at the same pace.
 *
 * This works at the level of the effect building blocks rather than spell by spell, so any
 * spell, ability, wand, potion or trap made from them is covered.
 */
public class DuelFx {

	/** True while playing back someone else's effects (so they aren't recorded again). */
	static volatile boolean replaying = false;

	public static boolean recording() {
		return !replaying && DuelGame.recordingFx();
	}

	// ------------------------------------------------------------------ install / uninstall

	static void install() {
		Sample.hook = new Sample.PlayHook() {
			@Override
			public void played(Object id, float l, float r, float pitch) {
				sound(id, l, r, pitch);
			}
		};
		Emitter.hook = new Emitter.StartHook() {
			@Override
			public void started(Emitter e, Emitter.Factory f, float interval, int quantity, float x, float y, float w, float h) {
				emitter(e, f, interval, quantity, x, y, w, h);
			}
		};
	}

	static void uninstall() {
		Sample.hook = null;
		Emitter.hook = null;
	}

	// ------------------------------------------------------------------ recording

	private static Bundle fx(String kind) {
		Bundle e = new Bundle();
		e.put("e", "fx");
		e.put("k", kind);
		return e;
	}

	private static void send(Bundle e) {
		DuelGame.addFxEvent(e);
	}

	private static final HashSet<String> SILENT = new HashSet<>();
	static {
		SILENT.add(Assets.Sounds.CLICK);
	}

	static void sound(Object id, float l, float r, float pitch) {
		if (!(id instanceof String) || SILENT.contains(id) || !recording()) return;
		Bundle e = fx("snd");
		e.put("id", (String) id);
		e.put("l", l);
		e.put("r", r);
		e.put("pt", pitch);
		send(e);
	}

	static void emitter(Emitter em, Emitter.Factory f, float interval, int quantity, float x, float y, float w, float h) {
		//only plain one-off emitters: missiles, blobs and buff auras are synced their own way
		if (em.getClass() != Emitter.class || !recording()) return;
		String key = keyOf(f);
		if (key == null) {
			noteUnknown(f);
			return;
		}
		Bundle e = fx("emit");
		e.put("f", key);
		e.put("i", interval);
		e.put("q", quantity);
		e.put("x", x);
		e.put("y", y);
		e.put("w", w);
		e.put("h", h);
		send(e);
	}

	public static void missile(int type, PointF from, PointF to) {
		if (!recording()) return;
		Bundle e = fx("mm");
		e.put("t", type);
		e.put("a", new float[]{from.x, from.y, to.x, to.y});
		send(e);
	}

	public static void beam(Beam b, PointF s, PointF end) {
		if (!recording()) return;
		Bundle e = fx("beam");
		e.put("c", b.getClass().getSimpleName());
		e.put("a", new float[]{s.x, s.y, end.x, end.y});
		send(e);
	}

	public static void lightning(float[] pts) {
		if (!recording()) return;
		Bundle e = fx("lt");
		e.put("a", pts);
		send(e);
	}

	public static void flare(int rays, float radius, int color, boolean light, float x, float y, float duration, float spin) {
		if (duration <= 0 || !recording()) return; //lasting flares belong to items/buffs
		Bundle e = fx("flare");
		e.put("n", rays);
		e.put("rad", radius);
		e.put("col", color);
		e.put("lm", light);
		e.put("a", new float[]{x, y, duration, spin});
		send(e);
	}

	public static void splash(PointF p, float dir, float cone, int color, int n, float interval) {
		if (!recording()) return;
		Bundle e = fx("spl");
		e.put("a", new float[]{p.x, p.y, dir, cone, interval});
		e.put("col", color);
		e.put("n", n);
		send(e);
	}

	public static void spell(Char ch, int index, float r, float g, float b) {
		if (!recording()) return;
		Bundle e = fx("spell");
		if (!putChar(e, ch, false)) return;
		e.put("idx", index);
		e.put("a", new float[]{r, g, b});
		send(e);
	}

	public static void flash(int color, boolean light) {
		if (!recording()) return;
		Bundle e = fx("flash");
		e.put("col", color);
		e.put("lm", light);
		send(e);
	}

	public static void shake(float magnitude, float duration) {
		if (!recording()) return;
		Bundle e = fx("shake");
		e.put("a", new float[]{magnitude, duration});
		send(e);
	}

	public static void anim(Char ch, String kind, int cell) {
		if (ch == null || !recording()) return;
		Bundle e = fx("anim");
		if (!putChar(e, ch, true)) return;
		e.put("an", kind);
		e.put("c", cell);
		send(e);
	}

	public static void jump(Char ch, int from, int to, float height, float duration) {
		if (ch == null || !recording()) return;
		Bundle e = fx("jump");
		if (!putChar(e, ch, true)) return;
		e.put("fr", from);
		e.put("to", to);
		e.put("a", new float[]{height, duration});
		send(e);
	}

	// ------------------------------------------------------------------ who's who

	/** Names a character so every phone finds the same one. With mineOnly, only my hero/creatures. */
	static boolean putChar(Bundle e, Char ch, boolean mineOnly) {
		if (ch == null) return false;
		if (ch == Dungeon.hero) {
			e.put("ct", 0);
			e.put("co", DuelGame.myId());
			return true;
		}
		if (ch instanceof DuelOpponent) {
			if (mineOnly) return false;
			e.put("ct", 0);
			e.put("co", ((DuelOpponent) ch).ownerId());
			return true;
		}
		if (ch.duelOwner >= 0) {
			if (mineOnly) return false;
			e.put("ct", 1);
			e.put("co", ch.duelOwner);
			e.put("ci", ch.duelOrigId);
			return true;
		}
		if (ch instanceof Mob) {
			e.put("ct", 1);
			e.put("co", DuelGame.myId());
			e.put("ci", ch.id());
			return true;
		}
		return false;
	}

	static Char charFrom(Bundle e) {
		if (!e.contains("ct")) return null;
		int owner = e.getInt("co");
		if (e.getInt("ct") == 0) return DuelSync.charFor(owner);
		int id = e.getInt("ci");
		if (owner == DuelGame.myId()) {
			Actor a = Actor.findById(id);
			return a instanceof Char ? (Char) a : null;
		}
		return DuelMirror.find(owner, id);
	}

	// ------------------------------------------------------------------ particle factories

	private static final String P = "com.shatteredpixel.shatteredpixeldungeon.effects.particles.";
	private static final String[] FACTORY_CLASSES = {
			P + "BlastParticle", P + "BloodParticle", P + "ChallengeParticle", P + "CorrosionParticle",
			P + "EarthParticle", P + "ElmoParticle", P + "EnergyParticle", P + "FlameParticle",
			P + "FlowParticle", P + "LeafParticle", P + "PitfallParticle", P + "PoisonParticle",
			P + "PurpleParticle", P + "RainbowParticle", P + "SacrificialParticle", P + "ShadowParticle",
			P + "ShaftParticle", P + "SmokeParticle", P + "SnowParticle", P + "SparkParticle",
			P + "SpectralWallParticle", P + "WebParticle", P + "WindParticle", P + "WoolParticle",
			"com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile",
	};

	private static IdentityHashMap<Emitter.Factory, String> keys;
	private static HashMap<String, Emitter.Factory> factories;

	private static synchronized void buildRegistry() {
		if (keys != null) return;
		keys = new IdentityHashMap<>();
		factories = new HashMap<>();
		for (String name : FACTORY_CLASSES) {
			try {
				Class<?> c = Class.forName(name);
				scan(c);
				for (Class<?> inner : c.getClasses()) scan(inner);
			} catch (Throwable t) {
				DuelTestDriver.log("fx: couldn't scan " + name + ": " + t);
			}
		}
	}

	private static void scan(Class<?> c) {
		for (Field f : c.getFields()) {
			if (!Modifier.isStatic(f.getModifiers()) || !Emitter.Factory.class.isAssignableFrom(f.getType())) continue;
			try {
				Emitter.Factory fac = (Emitter.Factory) f.get(null);
				if (fac == null) continue;
				String key = c.getName() + "#" + f.getName();
				if (!keys.containsKey(fac)) keys.put(fac, key);
				factories.put(key, fac);
			} catch (Throwable ignored) {}
		}
	}

	static String keyOf(Emitter.Factory f) {
		int speck = Speck.duelTypeOf(f);
		if (speck >= 0) return "speck:" + speck + (f.lightMode() ? ":l" : "");
		buildRegistry();
		return keys.get(f);
	}

	static Emitter.Factory factoryFor(String key) {
		if (key.startsWith("speck:")) {
			String[] parts = key.split(":");
			return Speck.factory(Integer.parseInt(parts[1]), parts.length > 2);
		}
		buildRegistry();
		return factories.get(key);
	}

	private static final HashSet<String> unknownSeen = new HashSet<>();

	private static void noteUnknown(Emitter.Factory f) {
		String n = f.getClass().getName();
		synchronized (unknownSeen) {
			if (unknownSeen.add(n)) DuelTestDriver.log("fx: particle effect not shared yet: " + n);
		}
	}

	// ------------------------------------------------------------------ playback

	private static Group layer() {
		return GameScene.duelFxLayer();
	}

	private static PointF pt(float[] a, int i) {
		return new PointF(a[i], a[i + 1]);
	}

	/** Plays one recorded effect on this phone. Render thread. */
	static void play(Bundle e) {
		String k = e.getString("k");
		replaying = true;
		try {
			switch (k) {
				case "snd":
					Sample.INSTANCE.play(e.getString("id"), e.getFloat("l"), e.getFloat("r"), e.getFloat("pt"));
					break;
				case "emit": {
					Emitter.Factory f = factoryFor(e.getString("f"));
					Emitter em = GameScene.emitter();
					if (f == null || em == null) break;
					em.pos(e.getFloat("x"), e.getFloat("y"), e.getFloat("w"), e.getFloat("h"));
					em.start(f, e.getFloat("i"), e.getInt("q"));
					break;
				}
				case "mm": {
					Group g = layer();
					float[] a = e.getFloatArray("a");
					if (g == null) break;
					((MagicMissile) g.recycle(MagicMissile.class)).reset(e.getInt("t"), pt(a, 0), pt(a, 2), null);
					break;
				}
				case "beam": {
					Group g = layer();
					float[] a = e.getFloatArray("a");
					if (g == null) break;
					String c = e.getString("c");
					PointF s = pt(a, 0), end = pt(a, 2);
					Beam b = "DeathRay".equals(c) ? new Beam.DeathRay(s, end)
							: "SunRay".equals(c) ? new Beam.SunRay(s, end)
							: "HealthRay".equals(c) ? new Beam.HealthRay(s, end)
							: new Beam.LightRay(s, end);
					g.add(b);
					break;
				}
				case "lt": {
					Group g = layer();
					float[] a = e.getFloatArray("a");
					if (g == null) break;
					ArrayList<Lightning.Arc> arcs = new ArrayList<>();
					for (int i = 0; i + 3 < a.length; i += 4) arcs.add(new Lightning.Arc(pt(a, i), pt(a, i + 2)));
					g.add(new Lightning(arcs, null));
					break;
				}
				case "flare": {
					Group g = layer();
					float[] a = e.getFloatArray("a");
					if (g == null) break;
					Flare f = new Flare(e.getInt("n"), e.getFloat("rad")).color(e.getInt("col"), e.getBoolean("lm"));
					f.angularSpeed = a[3];
					f.show(g, new PointF(a[0], a[1]), a[2]);
					break;
				}
				case "spl": {
					float[] a = e.getFloatArray("a");
					Splash.at(new PointF(a[0], a[1]), a[2], a[3], e.getInt("col"), e.getInt("n"), a[4]);
					break;
				}
				case "spell": {
					Char c = charFrom(e);
					float[] a = e.getFloatArray("a");
					if (c != null && c.sprite != null) SpellSprite.show(c, e.getInt("idx"), a[0], a[1], a[2]);
					break;
				}
				case "flash":
					GameScene.flash(e.getInt("col"), e.getBoolean("lm"));
					break;
				case "shake": {
					float[] a = e.getFloatArray("a");
					PixelScene.shake(a[0], a[1]);
					break;
				}
				case "anim": {
					Char c = charFrom(e);
					if (c != null && c.sprite != null && c != Dungeon.hero) c.sprite.duelShow(e.getString("an"), e.getInt("c"));
					break;
				}
				case "jump": {
					Char c = charFrom(e);
					if (c == null || c.sprite == null || c == Dungeon.hero) break;
					int to = e.getInt("to");
					float[] a = e.getFloatArray("a");
					int from = c.pos;
					if (c instanceof DuelOpponent) {
						int free = DuelController.EventPlayer.freeCellNear(to, c);
						if (free >= 0) {
							to = free;
							((DuelOpponent) c).placeAt(to);
						}
					} else if (c.duelOwner >= 0) {
						DuelMirror.placeAt(c, to);
					}
					c.sprite.jump(from, to, a[0], a[1], null);
					break;
				}
			}
		} catch (Exception ex) {
			DuelTestDriver.log("fx playback failed (" + k + "): " + ex);
		} finally {
			replaying = false;
		}
	}
}
