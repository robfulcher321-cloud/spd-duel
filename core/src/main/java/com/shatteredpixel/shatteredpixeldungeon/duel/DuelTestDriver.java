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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicalSleep;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;

/**
 * Automated match for GitHub's test machine. Each copy of the game is one player:
 *   SPD_DUELTEST  = host | join
 *   SPD_DUELPLAYERS = total players (host waits for this many, default 2)
 *   SPD_DUELMODE  = ffa | 2v1 (teams: host alone against everyone else)
 *   SPD_DUELSEAT  = joiner number, used to stagger joining
 * Players walk to the nearest enemy and attack. The host also knocks its target back a
 * tile, and one joiner puts its target to sleep/slows/paralyses it, to exercise pushes and
 * local effects. Every turn is logged; a stall prints what every thread is doing.
 */
public class DuelTestDriver {

	public static String mode() {
		try {
			return System.getenv("SPD_DUELTEST");
		} catch (Exception e) {
			return null;
		}
	}

	private static String env(String key, String def) {
		try {
			String v = System.getenv(key);
			return v == null ? def : v;
		} catch (Exception e) {
			return def;
		}
	}

	public static boolean enabled() {
		return mode() != null;
	}

	static void log(String s) {
		if (enabled()) {
			System.out.println("DUELTEST[" + mode() + env("SPD_DUELSEAT", "") + "] " + s);
			System.out.flush();
		}
	}

	private static boolean started = false;
	private static volatile long lastProgress = System.currentTimeMillis();
	private static volatile int actions = 0;
	private static boolean configured = false;
	private static boolean pushed = false;
	private static final int MAX_ACTIONS = 60;

	static void progress(String what) {
		lastProgress = System.currentTimeMillis();
		log(what);
	}

	public static void startIfEnabled() {
		if (!enabled() || started) return;
		started = true;
		final boolean host = "host".equals(mode());
		final int seat = Integer.parseInt(env("SPD_DUELSEAT", "0"));
		log("starting as " + (host ? "host" : "joiner " + seat));

		Thread t = new Thread("Duel test driver") {
			@Override
			public void run() {
				try {
					Thread.sleep(host ? 1000 : 3000 + seat * 1500L);
				} catch (InterruptedException ignored) {}

				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						HeroClass[] classes = {HeroClass.WARRIOR, HeroClass.DUELIST, HeroClass.ROGUE, HeroClass.HUNTRESS};
						if (host) {
							DuelGame.prepareEndgameHero(HeroClass.WARRIOR, HeroSubClass.BERSERKER,
									HeroClass.WARRIOR.armorAbilities()[0], new DuelLoadout());
						} else {
							DuelGame.prepareTestHero(classes[seat % classes.length], new DuelLoadout());
						}
						DuelGame.LobbyListener l = new DuelGame.LobbyListener() {
							public void onLobbyChanged() { log("lobby now has " + DuelGame.players().size() + " players"); }
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
						Thread.sleep(300);
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
					if (idle > 30000) {
						log("STALL: nothing happened for 30s (current player " + DuelGame.currentPlayer()
								+ ", my turn " + DuelGame.myTurn() + ")");
						dumpThreads();
						exit(2);
					}
					if (System.currentTimeMillis() - start > 300000) {
						log("TIMEOUT after 5 minutes");
						exit(4);
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	/** Render thread, a few times a second. */
	private static void step() {
		//host: when everyone's in, set the teams and start
		if ("host".equals(mode()) && DuelGame.inLobby() && !configured) {
			int want = Integer.parseInt(env("SPD_DUELPLAYERS", "2"));
			if (DuelGame.players().size() >= want) {
				configured = true;
				if ("2v1".equals(env("SPD_DUELMODE", "ffa"))) {
					DuelGame.setMode(DuelGame.MODE_TEAMS);
					for (DuelGame.Player p : DuelGame.players()) {
						if (p.id != 0 && p.team == 0) DuelGame.toggleTeam(p.id);
					}
				} else {
					DuelGame.setMode(DuelGame.MODE_FFA);
				}
				log("starting match: " + DuelGame.startProblem());
				DuelGame.startMatch();
			}
			return;
		}

		if (DuelGame.isOver()) {
			logFinalState();
			log("PASS");
			exit(0);
			return;
		}
		if (!(Game.scene() instanceof GameScene) || !DuelGame.active()) return;
		Hero h = Dungeon.hero;
		if (h == null || !h.isAlive() || !h.ready || !DuelGame.myTurn()) return;

		//nearest enemy figure
		DuelOpponent target = null;
		int best = Integer.MAX_VALUE;
		for (DuelGame.Player p : DuelGame.players()) {
			if (p.puppet == null || !p.alive || !DuelGame.isEnemy(p)) continue;
			int d = Dungeon.level.distance(h.pos, p.puppet.pos);
			if (d < best) { best = d; target = p.puppet; }
		}
		if (target == null) return;

		//stress: knockback and local status effects
		if (!pushed && actions >= 3 && "host".equals(mode()) && Dungeon.level.adjacent(h.pos, target.pos)) {
			pushed = true;
			int away = target.pos + (target.pos - h.pos);
			int to = DuelController.EventPlayer.freeCellNear(away, target);
			if (to >= 0 && to != target.pos) {
				log("STRESS: knocking " + target.name() + " back from " + target.pos + " to " + to);
				DuelController.EventPlayer.slide(target, to);
			}
		}
		if (actions == 4 && "1".equals(env("SPD_DUELSEAT", ""))) {
			log("STRESS: sleep/slow/chill/paralysis on " + target.name());
			Buff.affect(target, MagicalSleep.class);
			Buff.prolong(target, Slow.class, 10f);
			Buff.prolong(target, Chill.class, 10f);
			Buff.prolong(target, Paralysis.class, 5f);
		}

		int cell;
		if (Dungeon.level.adjacent(h.pos, target.pos)) {
			cell = target.pos;
		} else {
			cell = -1;
			int bd = Integer.MAX_VALUE;
			for (int n : PathFinder.NEIGHBOURS8) {
				int c = h.pos + n;
				if (c < 0 || c >= Dungeon.level.length() || !Dungeon.level.passable[c] || Actor.findChar(c) != null) continue;
				int d = Dungeon.level.distance(c, target.pos);
				if (d < bd) { bd = d; cell = c; }
			}
			if (cell == -1) cell = h.pos;
		}

		actions++;
		progress("my action " + actions + ": " + (cell == target.pos ? "attack " + target.name() : "move")
				+ " | me HP " + h.HP + "/" + h.HT + " at " + h.pos + " | target HP " + target.HP + " at " + target.pos);
		if (h.handle(cell)) h.next();

		if (actions >= MAX_ACTIONS) {
			log("RESULT reached " + MAX_ACTIONS + " actions without a winner");
			logFinalState();
			log("PASS");
			exit(0);
		}
	}

	private static void logFinalState() {
		Hero h = Dungeon.hero;
		StringBuilder sb = new StringBuilder("FINAL me(" + DuelGame.myId() + ") ");
		sb.append(h == null ? "?" : ("pos " + h.pos + " hp " + h.HP + (h.isAlive() ? "" : " DEAD")));
		for (DuelGame.Player p : DuelGame.players()) {
			if (p.puppet == null) continue;
			sb.append(" | p").append(p.id).append(" pos ").append(p.puppet.pos).append(p.alive ? "" : " OUT");
		}
		log(sb.toString());
	}

	private static void dumpThreads() {
		for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
			StringBuilder sb = new StringBuilder("THREAD " + e.getKey().getName() + " (" + e.getKey().getState() + ")\n");
			for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append("\n");
			System.out.println(sb);
		}
		System.out.flush();
	}

	private static boolean exiting = false;

	private static void exit(final int code) {
		if (exiting) return;
		exiting = true;
		new Thread() {
			@Override
			public void run() {
				//linger so the last messages reach everyone before this copy disconnects
				try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
				System.exit(code);
			}
		}.start();
	}
}
