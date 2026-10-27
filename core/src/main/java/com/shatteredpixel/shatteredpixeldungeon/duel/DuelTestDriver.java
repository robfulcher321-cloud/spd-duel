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

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;

/**
 * Automated two-player duel for GitHub's test machine (SPD_DUELTEST=host or join).
 * Each copy of the game builds a fighter, connects over localhost, and plays by itself:
 * walk towards the opponent, attack when adjacent. Every turn exchange is logged; if no
 * turn goes through for a while, it prints what every thread is doing and quits.
 */
public class DuelTestDriver {

	public static String mode() {
		try {
			return System.getenv("SPD_DUELTEST");
		} catch (Exception e) {
			return null;
		}
	}

	public static boolean enabled() {
		return mode() != null;
	}

	static void log(String s) {
		if (enabled()) {
			System.out.println("DUELTEST[" + mode() + "] " + s);
			System.out.flush();
		}
	}

	private static boolean started = false;
	private static volatile long lastProgress = System.currentTimeMillis();
	private static volatile int actions = 0;
	private static final int MAX_ACTIONS = 40;

	/** Something happened in the duel (a turn was sent, received, or my hero acted). */
	static void progress(String what) {
		lastProgress = System.currentTimeMillis();
		log(what);
	}

	public static void startIfEnabled() {
		if (!enabled() || started) return;
		started = true;
		final boolean host = "host".equals(mode());
		log("starting as " + (host ? "host" : "joiner"));

		Thread t = new Thread("Duel test driver") {
			@Override
			public void run() {
				try {
					Thread.sleep(host ? 1000 : 4000); //give the host time to start listening
				} catch (InterruptedException ignored) {}

				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						if (host) {
							DuelGame.prepareEndgameHero(HeroClass.WARRIOR, HeroSubClass.BERSERKER,
									HeroClass.WARRIOR.armorAbilities()[0], new DuelLoadout());
						} else {
							DuelGame.prepareTestHero(HeroClass.WARRIOR, new DuelLoadout());
						}
						DuelGame.LobbyListener l = new DuelGame.LobbyListener() {
							public void onStatus(String text) { log("lobby: " + text); }
							public void onFailed(String reason) { log("lobby FAILED: " + reason); exit(3); }
						};
						if (host) DuelGame.hostLobby(l);
						else DuelGame.joinLobby("127.0.0.1", l);
					}
				});

				lastProgress = System.currentTimeMillis();
				long start = System.currentTimeMillis();
				while (true) {
					try {
						Thread.sleep(400);
					} catch (InterruptedException e) {
						return;
					}
					Game.runOnRenderThread(new Callback() {
						@Override
						public void call() {
							step();
						}
					});

					long idle = System.currentTimeMillis() - lastProgress;
					if (idle > 25000) {
						log("STALL: nothing happened for 25s");
						dumpThreads();
						exit(2);
					}
					if (System.currentTimeMillis() - start > 240000) {
						log("TIMEOUT after 4 minutes");
						exit(4);
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	/** Runs on the render thread a few times a second: take my hero's turn if it's waiting for input. */
	private static void step() {
		if (DuelGame.isOver()) {
			log("RESULT duel over; my HP " + (Dungeon.hero == null ? "?" : Dungeon.hero.HP) + "; actions " + actions);
			log("PASS");
			exit(0);
			return;
		}
		if (!(Game.scene() instanceof GameScene) || !DuelGame.active()) return;
		Hero h = Dungeon.hero;
		if (h == null || !h.isAlive() || !h.ready || Actor.processing() && !h.ready) return;

		Char opp = null;
		for (Char c : Actor.chars()) {
			if (c instanceof DuelOpponent) opp = c;
		}
		if (opp == null) return;

		int target;
		if (Dungeon.level.adjacent(h.pos, opp.pos)) {
			target = opp.pos; //attack
		} else {
			//step to the free neighbouring cell closest to the opponent
			target = -1;
			int best = Integer.MAX_VALUE;
			for (int n : PathFinder.NEIGHBOURS8) {
				int c = h.pos + n;
				if (c < 0 || c >= Dungeon.level.length() || !Dungeon.level.passable[c] || Actor.findChar(c) != null) continue;
				int d = Dungeon.level.distance(c, opp.pos);
				if (d < best) { best = d; target = c; }
			}
			if (target == -1) target = h.pos; //nowhere to go: wait
		}

		actions++;
		progress("my action " + actions + ": " + (target == opp.pos ? "attack" : "move")
				+ " | me HP " + h.HP + "/" + h.HT + " at " + h.pos
				+ " | them HP " + opp.HP + "/" + opp.HT + " at " + opp.pos
				+ " | time " + Actor.now());

		if (h.handle(target)) {
			h.next();
		}

		if (actions >= MAX_ACTIONS) {
			log("RESULT reached " + MAX_ACTIONS + " actions; my HP " + h.HP + ", them " + opp.HP);
			log("PASS");
			exit(0);
		}
	}

	private static void dumpThreads() {
		for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
			StringBuilder sb = new StringBuilder("THREAD " + e.getKey().getName() + " (" + e.getKey().getState() + ")\n");
			for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append("\n");
			System.out.println(sb);
		}
		System.out.flush();
	}

	private static void exit(final int code) {
		new Thread() {
			@Override
			public void run() {
				try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
				System.exit(code);
			}
		}.start();
	}
}
