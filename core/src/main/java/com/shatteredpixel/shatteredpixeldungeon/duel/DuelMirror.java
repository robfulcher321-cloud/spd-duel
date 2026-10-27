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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Creatures belonging to other players (summons, allies, wards, mirror images, spirit hawks,
 * shadow clones, earth guardians...) shown on my phone as copies.
 *
 * A copy never acts here; it moves, animates and attacks as its owner's turn is played back.
 * Damage and debuffs I put on a copy are sent to the owner's phone, where the real creature
 * takes them. Its owner sends its full state at the end of every turn.
 */
public class DuelMirror {

	private static final HashMap<String, Mob> mirrors = new HashMap<>();

	/** True while I'm changing a copy myself (so it isn't reported as something I did to it). */
	static boolean applying = false;

	private static String key(int owner, int id) {
		return owner + ":" + id;
	}

	static void reset() {
		synchronized (mirrors) {
			mirrors.clear();
		}
		synchronized (lastPos) {
			lastPos.clear();
		}
	}

	static Mob find(int owner, int id) {
		synchronized (mirrors) {
			return mirrors.get(key(owner, id));
		}
	}

	/** Actor thread: copies and their buffs never take turns on this phone. */
	public static boolean frozen(Actor a) {
		if (a instanceof Char) return ((Char) a).duelOwner >= 0;
		if (a instanceof Buff) {
			Char t = ((Buff) a).target;
			return t != null && t.duelOwner >= 0;
		}
		return false;
	}

	/** A creature on my phone that's really mine (not another player's hero or creature). */
	static boolean isMine(Char c) {
		return c instanceof Mob && !(c instanceof DuelOpponent) && c.duelOwner < 0;
	}

	// ------------------------------------------------------------------ my creatures, while recording

	private static final HashMap<Integer, Integer> lastPos = new HashMap<>();

	/** Turn start: what my creatures look like now, so changes show up as events. */
	static void beginRecording() {
		synchronized (lastPos) {
			lastPos.clear();
			for (Mob m : Dungeon.level.mobs.toArray(new Mob[0])) {
				if (isMine(m)) lastPos.put(m.id(), m.pos);
			}
		}
	}

	/** My creature started walking somewhere: send the step now, so it walks on every screen. */
	public static void mineMoved(Char c, int to) {
		if (!isMine(c) || !DuelFx.recording()) return;
		synchronized (lastPos) {
			Integer last = lastPos.get(c.id());
			if (last == null) {
				spawned((Mob) c);
				return;
			}
			if (last == to) return;
			lastPos.put(c.id(), to);
		}
		Bundle e = new Bundle();
		e.put("e", "mmove");
		e.put("ci", c.id());
		e.put("p", to);
		DuelGame.addFxEvent(e);
	}

	/** Catch-all: new creatures, ones that are gone, and ones that teleported. */
	static void sampleMine() {
		if (!DuelFx.recording()) return;
		HashSet<Integer> seen = new HashSet<>();
		for (Mob m : Dungeon.level.mobs.toArray(new Mob[0])) {
			if (!isMine(m) || !m.isAlive()) continue;
			seen.add(m.id());
			Integer last;
			synchronized (lastPos) {
				last = lastPos.get(m.id());
			}
			if (last == null) {
				spawned(m);
			} else if (last != m.pos) {
				synchronized (lastPos) {
					lastPos.put(m.id(), m.pos);
				}
				Bundle e = new Bundle();
				e.put("e", "mmove");
				e.put("ci", m.id());
				e.put("p", m.pos);
				DuelGame.addFxEvent(e);
			}
		}
		ArrayList<Integer> gone = new ArrayList<>();
		synchronized (lastPos) {
			for (Integer id : lastPos.keySet()) if (!seen.contains(id)) gone.add(id);
			for (Integer id : gone) lastPos.remove(id);
		}
		for (Integer id : gone) {
			Bundle e = new Bundle();
			e.put("e", "mgone");
			e.put("ci", id);
			DuelGame.addFxEvent(e);
		}
	}

	private static void spawned(Mob m) {
		synchronized (lastPos) {
			lastPos.put(m.id(), m.pos);
		}
		try {
			Bundle e = new Bundle();
			e.put("e", "mspawn");
			e.put("ci", m.id());
			e.put("b", DuelSync.pack(m));
			e.put("n", m.getClass().getSimpleName());
			DuelGame.addFxEvent(e);
		} catch (Exception ex) {
			DuelTestDriver.log("couldn't share creature " + m.getClass().getSimpleName() + ": " + ex);
		}
	}

	/** End of my turn: every creature of mine, for the other phones to line up with. */
	static Bundle[] myMobs() {
		ArrayList<Bundle> list = new ArrayList<>();
		for (Mob m : Dungeon.level.mobs.toArray(new Mob[0])) {
			if (!isMine(m) || !m.isAlive()) continue;
			try {
				Bundle w = DuelSync.pack(m);
				w.put("id", m.id());
				list.add(w);
			} catch (Exception e) {
				DuelTestDriver.log("couldn't share creature " + m.getClass().getSimpleName() + ": " + e);
			}
		}
		return list.toArray(new Bundle[0]);
	}

	// ------------------------------------------------------------------ hits and debuffs on copies

	/** Something hit another player's creature on my phone. */
	public static void damaged(Char c, int dmg, Object src) {
		if (dmg <= 0) return;
		//show it now; the real number lands on the owner's phone
		c.HP = Math.max(1, c.HP - dmg);
		if (c.sprite != null && c.sprite.visible) {
			c.sprite.showStatus(CharSprite.NEGATIVE, Integer.toString(dmg));
			c.sprite.flash();
		}
		if (src instanceof Blob || src instanceof Buff) return; //lingering harm happens on the owner's phone
		if (!DuelFx.recording()) return;
		Bundle e = new Bundle();
		e.put("e", "mdmg");
		e.put("co", c.duelOwner);
		e.put("ci", c.duelOrigId);
		e.put("a", dmg);
		DuelGame.addFxEvent(e);
	}

	public static void buffAdded(Char c, Buff b) {
		if (applying || DuelFx.replaying) return;
		//buffs on copies never tick here
		Actor.remove(b);
		DuelGame.buffInflicted(c, b);
	}

	// ------------------------------------------------------------------ playback

	static void placeAt(Char c, int to) {
		if (to < 0 || to >= Dungeon.level.length()) return;
		c.pos = to;
	}

	static void slide(Char c, int to) {
		if (c == null || to == c.pos) return;
		int free = DuelController.EventPlayer.freeCellNear(to, c);
		if (free < 0) return;
		int from = c.pos;
		placeAt(c, free);
		if (c.sprite != null) {
			c.sprite.visible = Dungeon.level.heroFOV[free] || Dungeon.level.heroFOV[from];
			applying = true;
			try {
				c.sprite.move(from, free);
			} finally {
				applying = false;
			}
		}
	}

	/** Event from another player's turn. */
	static void play(int owner, Bundle e) {
		String t = e.getString("e");
		int id = e.getInt("ci");
		if ("mmove".equals(t)) {
			slide(find(owner, id), e.getInt("p"));
		} else if ("mspawn".equals(t)) {
			if (find(owner, id) == null) spawn(owner, id, e.getBundle("b"));
		} else if ("mgone".equals(t)) {
			remove(owner, id, true);
		} else if ("mdmg".equals(t)) {
			int co = e.getInt("co");
			int amount = e.getInt("a");
			if (co == DuelGame.myId()) {
				//someone hit my creature: it really takes the hit
				Actor a = Actor.findById(e.getInt("ci"));
				if (a instanceof Char && ((Char) a).isAlive()) {
					Char src = DuelSync.charFor(owner);
					((Char) a).damage(amount, src != null ? src : Dungeon.hero);
					DuelTestDriver.progress("my creature " + a.getClass().getSimpleName() + " took " + amount);
				}
			} else {
				Mob m = find(co, e.getInt("ci"));
				if (m != null && m.sprite != null) {
					m.HP = Math.max(1, m.HP - amount);
					if (m.sprite.visible) {
						m.sprite.showStatus(CharSprite.NEGATIVE, Integer.toString(amount));
						m.sprite.flash();
					}
				}
			}
		}
	}

	private static Mob spawn(int owner, int id, Bundle w) {
		Bundlable b = DuelSync.unpack(w);
		if (!(b instanceof Mob)) return null;
		Mob m = (Mob) b;
		m.duelOwner = owner;
		m.duelOrigId = id;
		DuelGame.Player p = DuelGame.player(owner);
		m.alignment = (p != null && !DuelGame.isEnemy(p)) ? Char.Alignment.ALLY : Char.Alignment.ENEMY;
		int cell = DuelController.EventPlayer.freeCellNear(m.pos, m);
		if (cell < 0) return null;
		m.pos = cell;
		applying = true;
		try {
			GameScene.add(m);
		} finally {
			applying = false;
		}
		synchronized (mirrors) {
			mirrors.put(key(owner, id), m);
		}
		DuelTestDriver.progress("copy of " + m.getClass().getSimpleName() + " from player " + owner + " appeared at " + m.pos);
		return m;
	}

	private static void remove(int owner, int id, boolean dies) {
		Mob m;
		synchronized (mirrors) {
			m = mirrors.remove(key(owner, id));
		}
		if (m == null) return;
		for (Buff b : m.buffs()) Actor.remove(b);
		Actor.remove(m);
		Dungeon.level.mobs.remove(m);
		if (m.sprite != null) {
			if (dies && m.sprite.visible) m.sprite.die();
			else m.sprite.killAndErase();
		}
		DuelTestDriver.progress("copy of " + m.getClass().getSimpleName() + " from player " + owner + " is gone");
	}

	/** End of a player's turn: line their creatures up with what their phone says. */
	static void reconcile(int owner, Bundle[] mobs) {
		HashSet<String> keep = new HashSet<>();
		if (mobs != null) {
			for (Bundle w : mobs) {
				int id = w.getInt("id");
				keep.add(key(owner, id));
				Mob m = find(owner, id);
				if (m == null) {
					spawn(owner, id, w);
				} else {
					update(m, w);
				}
			}
		}
		ArrayList<Integer> gone = new ArrayList<>();
		synchronized (mirrors) {
			for (String k : mirrors.keySet()) {
				if (k.startsWith(owner + ":") && !keep.contains(k)) {
					gone.add(Integer.parseInt(k.substring(k.indexOf(':') + 1)));
				}
			}
		}
		for (Integer id : gone) remove(owner, id, true);
	}

	private static void update(Mob m, Bundle w) {
		Bundlable b = DuelSync.unpack(w);
		if (!(b instanceof Mob) || b.getClass() != m.getClass()) return;
		Mob fresh = (Mob) b;
		m.HT = fresh.HT;
		m.HP = Math.max(1, fresh.HP);
		if (fresh.pos != m.pos) {
			int to = DuelController.EventPlayer.freeCellNear(fresh.pos, m);
			if (to >= 0) {
				placeAt(m, to);
				if (m.sprite != null) m.sprite.place(to);
			}
		}
		applying = true;
		try {
			for (Buff old : m.buffs()) {
				try { old.detach(); } catch (Exception ignored) {}
			}
			for (Buff nb : new ArrayList<>(fresh.buffs())) {
				try {
					fresh.remove(nb);
					nb.attachTo(m);
				} catch (Exception ignored) {}
			}
		} finally {
			applying = false;
		}
	}

	static int count() {
		synchronized (mirrors) {
			return mirrors.size();
		}
	}
}
