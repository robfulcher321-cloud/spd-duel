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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.Splash;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Reflection;

/**
 * Two halves that keep a match in step:
 *
 *  - DuelController is an actor in the game's turn queue. It lets my hero act during my
 *    turn, then sends my turn and holds the queue (so nothing moves) until it's my turn again.
 *  - Director runs on the screen every frame. When it isn't my turn it takes the next message
 *    (someone's turn, a knock-out, a player leaving) and plays it back step by step, then
 *    passes the turn on. When the turn reaches me, it wakes the controller.
 *
 * The director doesn't depend on my hero being alive, so a knocked-out player can keep watching.
 */
public class DuelController extends Actor {

	{
		actPriority = HERO_PRIO + 1; //look in before my hero whenever we're due at the same time
	}

	private volatile boolean waiting = false;

	@Override
	protected boolean act() {
		if (!DuelGame.active() || DuelGame.isOver()) {
			waiting = false;
			spend(TICK);
			return true;
		}

		if (DuelGame.myTurn()) {
			if (DuelGame.actionsThisTurn() == 0) {
				//my hero hasn't acted yet this turn; look in again after one tick
				spend(TICK);
				return true;
			}
			DuelGame.finishMyTurn();
		}

		//not my turn: hold the queue until the director hands the turn back
		waiting = true;
		return false;
	}

	/** My hero has used its turn: act right now, ahead of my hero. */
	public void bringForward() {
		timeToNow();
	}

	/** Director: it's my turn again. */
	void wake() {
		if (waiting) {
			waiting = false;
			timeToNow();
			next();
		}
	}

	// ======================================================================== director

	/** Added to the game screen when a match is on. */
	public static class Director extends Gizmo {

		private EventPlayer playing = null;

		@Override
		public void update() {
			super.update();
			if (!DuelGame.active()) return;

			if (playing != null) {
				if (playing.update()) return; //still animating
				int from = playing.from;
				playing = null;
				DuelGame.turnPlayedBack(from);
			}

			//anything waiting (other players' turns, knock-outs) is handled before my turn starts
			if (!DuelGame.myTurn()) {
				Bundle msg = DuelGame.pollIncoming();
				if (msg != null) {
					handle(msg);
					return;
				}
			}

			if (DuelGame.shouldStartMyTurn()) {
				DuelGame.startMyTurn();
				DuelTestDriver.progress("my turn starts at time " + Actor.now());
				DuelController c = DuelGame.controller();
				if (c != null) c.wake();
			}
		}

		private void handle(Bundle msg) {
			String type = msg.getString("type");
			if ("turn".equals(type)) {
				int from = msg.getInt("from");
				if (from != DuelGame.currentPlayer()) {
					DuelTestDriver.log("note: turn from " + from + " while expecting " + DuelGame.currentPlayer());
				}
				DuelTestDriver.progress("playing back turn " + msg.getInt("seq") + " from " + from + ": "
						+ DuelGame.describeEvents(java.util.Arrays.asList(msg.getBundleArray("events"))));
				playing = new EventPlayer(msg);
			} else if ("dead".equals(type)) {
				DuelGame.playerOut(msg.getInt("who"), false);
			} else if ("left".equals(type)) {
				DuelGame.playerOut(msg.getInt("who"), true);
			} else if ("end".equals(type)) {
				DuelGame.matchEnded(msg.getString("why"));
			}
		}
	}

	// ======================================================================== playback of one turn

	/** Plays one player's turn on my screen: walking, swings, hits and pushes, in order. */
	static class EventPlayer {

		final int from;
		private final Bundle msg;
		private final Bundle[] events;
		private int next = 0;
		private float wait = 0;
		private DuelOpponent mover;

		EventPlayer(Bundle msg) {
			this.msg = msg;
			this.from = msg.getInt("from");
			this.events = msg.getBundleArray("events");
			DuelGame.Player p = DuelGame.player(from);
			this.mover = p != null ? p.puppet : null;
		}

		/** True while there's still something to play. */
		boolean update() {
			if (wait > 0) {
				wait -= Game.elapsed;
				return true;
			}
			if (mover != null && mover.sprite != null && mover.sprite.isMoving) {
				return true; //let the step finish
			}
			if (next < events.length) {
				try {
					play(events[next++]);
				} catch (Exception e) {
					DuelTestDriver.log("ERROR playing event: " + e);
				}
				return true;
			}
			finish();
			return false;
		}

		private void play(Bundle e) {
			String type = e.getString("e");
			Hero me = Dungeon.hero;

			if ("move".equals(type)) {
				if (mover != null) {
					slide(mover, e.getInt("p"));
					wait = 0.02f;
				}

			} else if ("swing".equals(type)) {
				int t = e.getInt("t");
				Char target = charFor(t);
				//thrown/shot attacks were already shown flying by the throw event
				if (mover != null && mover.sprite != null && target != null && !e.getBoolean("r")) {
					mover.sprite.attack(target.pos);
					wait = 0.3f;
				}
				if (t == DuelGame.myId() && !e.getBoolean("h") && me != null && me.isAlive()) {
					me.sprite.showStatus(CharSprite.NEUTRAL, me.defenseVerb());
					Sample.INSTANCE.play(Assets.Sounds.MISS);
				}

			} else if ("dmg".equals(type)) {
				int t = e.getInt("t");
				int amount = e.getInt("a");
				if (t == DuelGame.myId()) {
					if (me != null && me.isAlive()) {
						Sample.INSTANCE.play(Assets.Sounds.HIT);
						me.damage(amount, mover != null ? mover : me);
					}
				} else {
					//someone else was hit: their own phone has the real number, show it for feedback
					DuelGame.Player p = DuelGame.player(t);
					if (p != null && p.puppet != null && p.alive) {
						p.puppet.showHit(amount);
					}
				}
				wait = 0.15f;

			} else if ("push".equals(type)) {
				int t = e.getInt("t");
				int to = e.getInt("p");
				if (t == DuelGame.myId()) {
					pushMyHero(to);
				} else {
					DuelGame.Player p = DuelGame.player(t);
					if (p != null && p.puppet != null) slide(p.puppet, to);
				}
				wait = 0.2f;

			} else if ("throw".equals(type)) {
				showThrow(e.getString("c"), e.getInt("p"));
				wait = 0.35f;

			} else if ("zap".equals(type)) {
				showZap(e.getString("c"), e.getInt("p"));
				wait = 0.35f;

			} else if ("buff".equals(type)) {
				int t = e.getInt("t");
				if (t == DuelGame.myId()) {
					DuelSync.applyInflictedToMe(e.getBundle("b"));
				} else {
					DuelGame.Player p = DuelGame.player(t);
					if (p != null && p.puppet != null && p.alive) DuelSync.applyInflictedToPuppet(p.puppet, e.getBundle("b"));
				}
				wait = 0.1f;
			}
		}

		private Item itemFor(String className) {
			try {
				if (className.endsWith("SpiritBow$SpiritArrow")) return new SpiritBow().knockArrow();
				Class<?> c = Reflection.forName(className);
				Object o = c == null ? null : Reflection.newInstance(c);
				return o instanceof Item ? (Item) o : null;
			} catch (Exception ex) {
				return null;
			}
		}

		/** Another player's thrown item flying to where it lands (and breaking, for potions). */
		private void showThrow(String className, final int cell) {
			if (mover == null || mover.sprite == null || mover.sprite.parent == null) return;
			final Item item = itemFor(className);
			if (item == null) return;
			mover.sprite.zap(cell);
			((MissileSprite) mover.sprite.parent.recycle(MissileSprite.class)).reset(mover.sprite, cell, item, new Callback() {
				@Override
				public void call() {
					if (item instanceof Potion && cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.heroFOV[cell]) {
						Sample.INSTANCE.play(Assets.Sounds.SHATTER);
						Splash.at(cell, ((Potion) item).duelSplashColor(), 5);
					}
				}
			});
		}

		/** Another player's wand bolt, using that wand's own effect. */
		private void showZap(String className, int cell) {
			if (mover == null || mover.sprite == null || mover.sprite.parent == null) return;
			Item item = itemFor(className);
			if (!(item instanceof Wand)) return;
			DuelGame.Player owner = DuelGame.player(from);
			Hero stand = owner != null ? owner.ghost : null;
			if (stand == null) return;
			//the wand effects draw from "the current user": stand the owner's hero in for the figure
			stand.pos = mover.pos;
			stand.sprite = mover.sprite;
			Hero saved = Item.duelSetUser(stand);
			try {
				mover.sprite.zap(cell);
				Wand w = (Wand) item;
				w.duelFx(new Ballistica(mover.pos, cell, w.collisionProperties(cell)));
			} catch (Exception ex) {
				DuelTestDriver.log("couldn't show zap: " + ex);
			} finally {
				Item.duelSetUser(saved);
				stand.sprite = null;
			}
		}

		/** Final position/health for the player who just moved, straight from their phone, plus the arena and their buffs. */
		private void finish() {
			//the arena as the mover left it: fire, gas, plants, items, terrain
			if (msg.contains("world")) {
				try {
					DuelSync.applyWorld(msg.getBundle("world"));
				} catch (Exception e) {
					DuelTestDriver.log("ERROR applying arena: " + e);
				}
			}
			if (mover == null) return;
			mover.syncFrom(msg.getInt("pos"), msg.getInt("hp"), msg.getInt("ht"));
			if (msg.contains("buffs")) {
				DuelSync.applyBuffsToPuppet(mover, msg.getBundleArray("buffs"));
				DuelTestDriver.log("figure " + from + " shows buffs: [" + DuelSync.names(mover) + "]");
			}
		}

		private static Char charFor(int id) {
			if (id == DuelGame.myId()) return Dungeon.hero;
			DuelGame.Player p = DuelGame.player(id);
			return p != null ? p.puppet : null;
		}

		static void slide(DuelOpponent puppet, int to) {
			to = freeCellNear(to, puppet);
			if (to < 0 || to == puppet.pos) return;
			int from = puppet.pos;
			puppet.placeAt(to);
			if (puppet.sprite != null) {
				puppet.sprite.visible = Dungeon.level.heroFOV[to] || Dungeon.level.heroFOV[from];
				puppet.sprite.move(from, to);
			}
		}

		/** Another player knocked my hero somewhere: that really happens to my hero. */
		static void pushMyHero(int to) {
			Hero me = Dungeon.hero;
			if (me == null || !me.isAlive()) return;
			to = freeCellNear(to, me);
			if (to < 0 || to == me.pos) return;
			int from = me.pos;
			DuelTestDriver.progress("I was pushed from " + from + " to " + to);
			me.interrupt();
			me.pos = to;
			Dungeon.level.occupyCell(me);
			if (me.sprite != null) me.sprite.move(from, to);
			Dungeon.observe();
			GameScene.updateFog();
			GLog.w("You were knocked back!");
		}

		/** The cell itself if it's free, otherwise the nearest free walkable cell. */
		static int freeCellNear(int cell, Char who) {
			if (cell < 0 || cell >= Dungeon.level.length()) return -1;
			Char there = Actor.findChar(cell);
			if ((there == null || there == who) && Dungeon.level.passable[cell]) return cell;
			for (int n : PathFinder.NEIGHBOURS8) {
				int c = cell + n;
				if (c < 0 || c >= Dungeon.level.length()) continue;
				Char o = Actor.findChar(c);
				if ((o == null || o == who) && Dungeon.level.passable[c]) return c;
			}
			return -1;
		}
	}
}
