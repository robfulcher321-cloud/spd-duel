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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Copies game state between phones:
 *  - a hero's buffs (shields, burning, frozen, haste, invisible...) so other phones draw them;
 *  - a buff one player inflicts on another, so it really happens to the victim's hero;
 *  - the arena itself (fire, gas, frost clouds, webs, plants, items on the floor, terrain),
 *    sent by whoever just took their turn, since only they changed it.
 */
public class DuelSync {

	// ------------------------------------------------------------------ bundling helpers

	static Bundle pack(Bundlable b) {
		Bundle w = new Bundle();
		w.put("x", b);
		return w;
	}

	static Bundlable unpack(Bundle w) {
		try {
			return w.get("x");
		} catch (Exception e) {
			DuelTestDriver.log("couldn't unpack: " + e);
			return null;
		}
	}

	static Bundle packBuff(Buff b) {
		Bundle w = pack(b);
		w.put("dur", b.cooldown());
		return w;
	}

	static Buff unpackBuff(Bundle w) {
		Bundlable b = unpack(w);
		return b instanceof Buff ? (Buff) b : null;
	}

	// ------------------------------------------------------------------ buffs

	/** Buffs worth showing on my figure on other phones: anything with an icon, plus shields. */
	static boolean shareable(Buff b) {
		if (b instanceof ShieldBuff) return true;
		try {
			return b.icon() != BuffIndicator.NONE;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Whether a buff my hero put on someone's figure should really happen to that player.
	 * Hidden bookkeeping (talent trackers and the like) stays on my phone, and corruption is
	 * left out: on a hero it would only slowly drain them, which isn't what the wand means.
	 */
	static boolean worthSending(Buff b) {
		if (b instanceof com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corruption) return false;
		return shareable(b);
	}

	static Bundle[] myBuffs(Hero h) {
		ArrayList<Bundle> list = new ArrayList<>();
		for (Buff b : h.buffs()) {
			if (!shareable(b)) continue;
			try {
				list.add(packBuff(b));
			} catch (Exception e) {
				DuelTestDriver.log("couldn't share buff " + b.getClass().getSimpleName() + ": " + e);
			}
		}
		return list.toArray(new Bundle[0]);
	}

	/** Replaces a figure's buffs with its owner's real ones (for looks only; they don't tick here). */
	static void applyBuffsToPuppet(DuelOpponent puppet, Bundle[] buffs) {
		if (puppet == null || buffs == null) return;
		puppet.applyingSnapshot = true;
		try {
			for (Buff b : puppet.buffs()) {
				try { b.detach(); } catch (Exception ignored) {}
			}
			for (Bundle w : buffs) {
				Buff b = unpackBuff(w);
				if (b == null) continue;
				try {
					b.attachTo(puppet);
				} catch (Exception e) {
					DuelTestDriver.log("couldn't show buff " + b.getClass().getSimpleName() + " on figure: " + e);
				}
			}
		} finally {
			puppet.applyingSnapshot = false;
		}
	}

	/** Another player inflicted this on my hero: it really happens to me. */
	static void applyInflictedToMe(Bundle w) {
		Hero me = Dungeon.hero;
		if (me == null || !me.isAlive()) return;
		Buff b = unpackBuff(w);
		if (b == null) return;
		try {
			Buff existing = me.buff(b.getClass());
			if (existing != null) existing.detach();
			if (b.attachTo(me)) {
				b.duelSetCooldown(w.getFloat("dur"));
				DuelTestDriver.progress("inflicted on me: " + b.getClass().getSimpleName());
			}
		} catch (Exception e) {
			DuelTestDriver.log("couldn't apply " + b.getClass().getSimpleName() + " to my hero: " + e);
		}
	}

	/** Another player inflicted this on one of my creatures: it really happens to it. */
	static void applyInflictedToMine(Char c, Bundle w) {
		Buff b = unpackBuff(w);
		if (b == null || c == null || !c.isAlive()) return;
		try {
			Buff existing = c.buff(b.getClass());
			if (existing != null) existing.detach();
			if (b.attachTo(c)) b.duelSetCooldown(w.getFloat("dur"));
		} catch (Exception e) {
			DuelTestDriver.log("couldn't apply " + b.getClass().getSimpleName() + " to my creature: " + e);
		}
	}

	/** Another player inflicted this on a copy of someone else's creature: show it. */
	static void applyInflictedToCopy(Char c, Bundle w) {
		Buff b = unpackBuff(w);
		if (b == null || c == null) return;
		DuelMirror.applying = true;
		try {
			Buff existing = c.buff(b.getClass());
			if (existing != null) existing.detach();
			b.attachTo(c);
		} catch (Exception ignored) {
		} finally {
			DuelMirror.applying = false;
		}
	}

	/** Another player inflicted this on someone else's figure: show it until that player's next turn. */
	static void applyInflictedToPuppet(DuelOpponent puppet, Bundle w) {
		if (puppet == null) return;
		Buff b = unpackBuff(w);
		if (b == null) return;
		puppet.applyingSnapshot = true;
		try {
			Buff existing = puppet.buff(b.getClass());
			if (existing != null) existing.detach();
			b.attachTo(puppet);
		} catch (Exception ignored) {
		} finally {
			puppet.applyingSnapshot = false;
		}
	}

	// ------------------------------------------------------------------ the arena

	static Bundle world() {
		Level level = Dungeon.level;
		Bundle w = new Bundle();

		ArrayList<Bundle> blobs = new ArrayList<>();
		for (Blob b : level.blobs.values()) {
			if (b.volume > 0) {
				try { blobs.add(pack(b)); } catch (Exception ignored) {}
			}
		}
		w.put("blobs", blobs.toArray(new Bundle[0]));

		ArrayList<Bundle> heaps = new ArrayList<>();
		for (Heap h : level.heaps.valueList()) {
			try { heaps.add(pack(h)); } catch (Exception ignored) {}
		}
		w.put("heaps", heaps.toArray(new Bundle[0]));

		ArrayList<Bundle> plants = new ArrayList<>();
		for (Plant p : level.plants.valueList()) {
			try { plants.add(pack(p)); } catch (Exception ignored) {}
		}
		w.put("plants", plants.toArray(new Bundle[0]));

		w.put("map", level.map.clone());
		try {
			w.put("mobs", DuelMirror.myMobs());
		} catch (Exception e) {
			DuelTestDriver.log("couldn't pack my creatures: " + e);
		}
		return w;
	}

	static void applyWorld(Bundle w, int from) {
		if (w == null || w.isNull()) return;
		Level level = Dungeon.level;
		try {
			applyMap(level, w.getIntArray("map"));
		} catch (Exception e) { DuelTestDriver.log("map sync failed: " + e); }
		try {
			applyBlobs(level, w.getBundleArray("blobs"));
		} catch (Exception e) { DuelTestDriver.log("blob sync failed: " + e); }
		try {
			applyHeaps(level, w.getBundleArray("heaps"));
		} catch (Exception e) { DuelTestDriver.log("heap sync failed: " + e); }
		try {
			applyPlants(level, w.getBundleArray("plants"));
		} catch (Exception e) { DuelTestDriver.log("plant sync failed: " + e); }
		try {
			if (w.contains("mobs")) DuelMirror.reconcile(from, w.getBundleArray("mobs"));
		} catch (Exception e) { DuelTestDriver.log("creature sync failed: " + e); }

		level.buildFlagMaps();
		GameScene.updateMap();
		Dungeon.observe();
		GameScene.updateFog();
	}

	private static void applyMap(Level level, int[] map) {
		if (map == null || map.length != level.length()) return;
		for (int i = 0; i < map.length; i++) {
			if (level.map[i] != map[i]) {
				Level.set(i, map[i], level);
				GameScene.updateMap(i);
			}
		}
	}

	private static void applyBlobs(Level level, Bundle[] blobs) {
		HashMap<Class<?>, Blob> incoming = new HashMap<>();
		if (blobs != null) {
			for (Bundle w : blobs) {
				Bundlable b = unpack(w);
				if (b instanceof Blob) incoming.put(b.getClass(), (Blob) b);
			}
		}
		//gone on the sender's side: clear ours
		for (Blob local : new ArrayList<>(level.blobs.values())) {
			if (!incoming.containsKey(local.getClass())) local.fullyClear();
		}
		for (Blob nb : incoming.values()) {
			if (nb.cur == null) continue;
			Blob local = level.blobs.get(nb.getClass());
			if (local == null) {
				level.blobs.put(nb.getClass(), nb);
				GameScene.add(nb);
				nb.timeToNow();
				nb.setupArea();
			} else {
				local.fullyClear();
				int n = Math.min(local.cur.length, nb.cur.length);
				int vol = 0;
				for (int i = 0; i < n; i++) {
					local.cur[i] = nb.cur[i];
					vol += nb.cur[i];
				}
				local.volume = vol;
				local.setupArea();
			}
		}
		if (!incoming.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (Blob b : incoming.values()) sb.append(b.getClass().getSimpleName()).append("(").append(b.volume).append(") ");
			DuelTestDriver.log("arena now has " + sb.toString().trim());
		}
	}

	private static void applyHeaps(Level level, Bundle[] heaps) {
		HashMap<Integer, Bundle> incoming = new HashMap<>();
		HashMap<Integer, Heap> incomingHeaps = new HashMap<>();
		if (heaps != null) {
			for (Bundle w : heaps) {
				Bundlable b = unpack(w);
				if (b instanceof Heap) {
					incoming.put(((Heap) b).pos, w);
					incomingHeaps.put(((Heap) b).pos, (Heap) b);
				}
			}
		}
		for (Heap local : new ArrayList<>(level.heaps.valueList())) {
			Bundle theirs = incoming.get(local.pos);
			if (theirs != null && pack(local).toString().equals(theirs.toString())) {
				incomingHeaps.remove(local.pos); //unchanged
			} else {
				local.destroy();
			}
		}
		for (Heap h : incomingHeaps.values()) {
			level.heaps.put(h.pos, h);
			GameScene.add(h);
		}
	}

	private static void applyPlants(Level level, Bundle[] plants) {
		HashMap<Integer, Plant> incoming = new HashMap<>();
		if (plants != null) {
			for (Bundle w : plants) {
				Bundlable b = unpack(w);
				if (b instanceof Plant) incoming.put(((Plant) b).pos, (Plant) b);
			}
		}
		for (Plant local : new ArrayList<>(level.plants.valueList())) {
			Plant theirs = incoming.get(local.pos);
			if (theirs != null && theirs.getClass() == local.getClass()) {
				incoming.remove(local.pos);
			} else {
				level.uproot(local.pos);
			}
		}
		for (Plant p : incoming.values()) {
			level.plants.put(p.pos, p);
			GameScene.plantSeed(p.pos);
		}
	}

	// ------------------------------------------------------------------ helpers for playback

	static Char charFor(int id) {
		if (id == DuelGame.myId()) return Dungeon.hero;
		DuelGame.Player p = DuelGame.player(id);
		return p != null ? p.puppet : null;
	}

	static String names(Char c) {
		StringBuilder sb = new StringBuilder();
		for (Buff b : c.buffs()) {
			if (sb.length() > 0) sb.append(",");
			sb.append(b.getClass().getSimpleName());
		}
		return sb.toString();
	}
}
