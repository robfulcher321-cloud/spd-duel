/*
 * SPD Duel - a 1v1 PvP mod of Shattered Pixel Dungeon
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
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.SparseArray;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything about the duel that isn't networking or drawing.
 *
 * How a fight stays in sync: each phone is in charge of its own hero. When it's your
 * opponent's turn, your phone sends a summary of what your hero just did (where you are,
 * your HP, how much damage you dealt and whether you swung), then waits for their summary
 * and replays it: their figure walks, swings, and you take the damage their phone rolled.
 * The host always moves first, then turns alternate.
 */
public class DuelGame {

	//bump this whenever the message format changes, so mismatched builds refuse to fight
	public static final int PROTOCOL = 1;

	private static volatile boolean active = false;   //a fight is on screen
	private static volatile boolean inLobby = false;  //hosting/joining, not fighting yet
	private static volatile boolean over = false;
	private static boolean host;

	private static Bundle myHello;
	private static Bundle theirHello;
	private static Hero ghost; //the opponent's hero, used only for their defence stats
	private static HeroClass oppClass = HeroClass.WARRIOR;
	private static int oppTier = 0;
	private static String oppName = "Opponent";

	private static DuelOpponent opponent;
	private static final ConcurrentLinkedQueue<Bundle> incomingTurns = new ConcurrentLinkedQueue<>();

	//what my hero did since I last reported
	private static int dealt = 0;
	private static boolean attacked = false;
	private static boolean hitSomething = false;

	public interface LobbyListener {
		void onStatus(String text);
		void onFailed(String reason);
	}
	private static LobbyListener lobby;

	public static boolean active() { return active; }
	public static boolean isHost() { return host; }
	public static boolean isOver() { return over; }
	public static Hero ghost() { return ghost; }
	public static HeroClass opponentClass() { return oppClass; }
	public static int opponentTier() { return oppTier; }
	public static String opponentName() { return oppName; }

	// ------------------------------------------------------------------ lobby

	/**
	 * Loads a saved run's hero into memory to fight with. Only reads the save file;
	 * nothing about the duel is ever written back to it.
	 */
	public static void prepareHero(int slot) throws Exception {
		Dungeon.loadGame(slot, true);
		Hero h = Dungeon.hero;
		if (h == null) throw new Exception("That save has no hero");

		h.HP = h.HT; //everyone starts the fight at full health

		myHello = new Bundle();
		myHello.put("type", "hello");
		myHello.put("protocol", PROTOCOL);
		myHello.put("hero", h);
		myHello.put("cls", h.heroClass);
		myHello.put("tier", h.tier());
		myHello.put("name", h.heroClass.title() + " (lvl " + h.lvl + ")");
	}

	public static String myLabel() {
		Hero h = Dungeon.hero;
		if (h == null) return "Hero";
		return h.heroClass.title() + " lvl " + h.lvl;
	}

	public static void hostLobby(LobbyListener l) {
		host = true;
		startLobby(l);
		DuelNet.host(myLabel(), netListener);
	}

	public static void joinLobby(String ip, LobbyListener l) {
		host = false;
		startLobby(l);
		DuelNet.join(ip, netListener);
	}

	private static void startLobby(LobbyListener l) {
		lobby = l;
		inLobby = true;
		active = false;
		over = false;
		theirHello = null;
		ghost = null;
		incomingTurns.clear();
		resetTurnTracking();
	}

	public static void cancelLobby() {
		inLobby = false;
		lobby = null;
		DuelNet.close();
	}

	private static final DuelNet.Listener netListener = new DuelNet.Listener() {
		@Override
		public void onConnected() {
			status("Connected! Swapping heroes...");
			DuelNet.send(myHello);
		}

		@Override
		public void onMessage(final Bundle msg) {
			String type = msg.getString("type");
			if ("hello".equals(type)) {
				if (msg.getInt("protocol") != PROTOCOL) {
					lobbyFail("Your opponent has a different version of SPD Duel. Both phones need the same build.");
					DuelNet.close();
					return;
				}
				theirHello = msg;
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						startFight();
					}
				});
			} else if ("turn".equals(type)) {
				incomingTurns.add(msg);
				wakeOpponent();
			} else if ("defeat".equals(type)) {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						onOpponentDefeated(false);
					}
				});
			} else if ("leave".equals(type)) {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						onOpponentDefeated(true);
					}
				});
			}
		}

		@Override
		public void onDisconnected(final String reason) {
			if (inLobby && !active) {
				lobbyFail(reason);
			} else if (active && !over) {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						onOpponentDefeated(true);
					}
				});
			}
		}
	};

	private static void status(final String text) {
		final LobbyListener l = lobby;
		if (l == null) return;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				l.onStatus(text);
			}
		});
	}

	private static void lobbyFail(final String reason) {
		final LobbyListener l = lobby;
		inLobby = false;
		if (l == null) return;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				l.onFailed(reason);
			}
		});
	}

	// ------------------------------------------------------------------ starting the fight

	private static void startFight() {
		if (!inLobby || theirHello == null) return;

		try {
			oppClass = theirHello.getEnum("cls", HeroClass.class);
			oppTier = theirHello.getInt("tier");
			oppName = theirHello.getString("name");
			ghost = (Hero) theirHello.get("hero");
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
			ghost = null;
		}
		if (ghost == null) {
			lobbyFail("Couldn't read your opponent's hero.");
			DuelNet.close();
			return;
		}

		inLobby = false;
		lobby = null;
		active = true;
		over = false;
		resetTurnTracking();

		Mob.clearHeldAllies();
		GameLog.wipe();
		Dungeon.droppedItems = new SparseArray<>();
		InterlevelScene.mode = InterlevelScene.Mode.NONE;

		DuelArenaLevel arena = new DuelArenaLevel();
		arena.create();

		int mySpawn = host ? DuelArenaLevel.hostSpawn() : DuelArenaLevel.joinerSpawn();
		int theirSpawn = host ? DuelArenaLevel.joinerSpawn() : DuelArenaLevel.hostSpawn();

		opponent = new DuelOpponent();
		opponent.setup(ghost, theirSpawn, !host);
		arena.mobs.add(opponent);

		//saved runs store small leftover times; line my hero up with the start of the fight
		Hero me = Dungeon.hero;
		me.HP = me.HT;
		me.curAction = me.lastAction = null;

		Dungeon.switchLevel(arena, mySpawn);
		Actor.delayChar(me, -me.cooldown());

		GLog.h("The duel begins! " + (host ? "You move first." : "Your opponent moves first."));
		Game.switchScene(GameScene.class);
	}

	// ------------------------------------------------------------------ turns

	private static void resetTurnTracking() {
		dealt = 0;
		attacked = false;
		hitSomething = false;
	}

	/** My hero damaged the opponent's figure (any source: weapons, wands, burning...). */
	public static void recordDamageDealt(int dmg) {
		if (dmg > 0) dealt += dmg;
	}

	/** My hero swung or shot at the opponent. */
	public static void recordAttack(boolean hit) {
		attacked = true;
		if (hit) hitSomething = true;
	}

	public static void sendMyTurn() {
		Hero me = Dungeon.hero;
		Bundle b = new Bundle();
		b.put("type", "turn");
		b.put("pos", me.pos);
		b.put("hp", Math.max(me.HP, 0));
		b.put("ht", me.HT);
		b.put("dmg", dealt);
		b.put("attacked", attacked);
		b.put("hit", hitSomething);
		resetTurnTracking();
		DuelNet.send(b);
	}

	public static Bundle pollTurn() {
		return incomingTurns.poll();
	}

	private static void wakeOpponent() {
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				if (opponent != null && opponent.isWaiting()) {
					opponent.next();
				}
			}
		});
	}

	// ------------------------------------------------------------------ ending

	/** Called from Hero.die while a duel is active, instead of the normal game-over. */
	public static void onLocalDeath() {
		if (over) return;
		over = true;
		Bundle b = new Bundle();
		b.put("type", "defeat");
		DuelNet.send(b);
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.show(new WndDuelResult(false, false));
			}
		});
	}

	private static void onOpponentDefeated(boolean forfeit) {
		if (!active || over) return;
		over = true;
		if (opponent != null && opponent.sprite != null && !forfeit) {
			opponent.sprite.die();
		}
		GameScene.show(new WndDuelResult(true, forfeit));
	}

	/**
	 * Leaves the duel (back to the title screen, or the app is leaving the arena some other
	 * way). If the fight wasn't finished, the opponent is told they won by forfeit.
	 */
	public static void leave() {
		final boolean wasFighting = active && !over;
		active = false;
		inLobby = false;
		over = true;
		opponent = null;
		ghost = null;
		lobby = null;
		incomingTurns.clear();

		Thread t = new Thread("Duel leave") {
			@Override
			public void run() {
				if (wasFighting) {
					Bundle b = new Bundle();
					b.put("type", "leave");
					DuelNet.sendAndClose(b);
				} else {
					DuelNet.close();
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	public static void returnToTitle() {
		leave();
		ShatteredPixelDungeon.switchNoFade(TitleScene.class);
	}
}
