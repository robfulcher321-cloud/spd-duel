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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Slow;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLiquidFlame;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFrost;
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
 * tile, and joiner 1 throws a fire potion, zaps a frost wand and slows/paralyses its target,
 * to exercise pushes, effects and the shared arena. Every turn is logged; a stall prints what every thread is doing.
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
	private static boolean threw = false;
	private static boolean lobbyRestarted = false;
	private static boolean matchRestarted = false;
	private static boolean lobbyChatted = false;
	private static boolean matchChatted = false;
	private static boolean slotsChecked = false;
	private static boolean shot = false;

	/** Takes a screenshot a few frames after being added (so everything has drawn). */
	static class Shot extends com.watabou.noosa.Gizmo {
		private final String name;
		private int frames = 0;
		Shot(String name) { this.name = name; }
		@Override
		public void draw() {
			if (++frames == 4) {
				DuelSelfTest.writeScreenshot(name);
				log("screenshot " + name);
				killAndErase();
			}
		}
	}
	private static int changeStep = 0;
	private static int settle = 0;
	private static boolean imaged = false;
	private static boolean leapt = false;
	private static DuelGame.LobbyListener pendingLobby = null;
	private static long searchStart = 0;
	private static boolean zapped = false;
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
						DuelGame.setPlayerName(host ? "HostBot" : "Bot" + seat);
						if (host) {
							DuelGame.prepareEndgameHero(HeroClass.WARRIOR, HeroSubClass.BERSERKER,
									HeroClass.WARRIOR.armorAbilities()[0], new DuelLoadout());
						} else {
							DuelGame.prepareTestHero(classes[seat % classes.length], new DuelLoadout());
						}
						DuelGame.LobbyListener l = new DuelGame.LobbyListener() {
							public void onLobbyChanged() {
								StringBuilder sb = new StringBuilder();
								for (DuelGame.Player p : DuelGame.players()) {
									sb.append(" | ").append(p.who()).append(": ").append(p.picking ? "choosing a hero" : p.name);
								}
								log("lobby now has " + DuelGame.players().size() + " players" + sb);
							}
							public void onStatus(String text) { log("lobby: " + text); }
							public void onFailed(String reason) { log("lobby FAILED: " + reason); exit(3); }
						};
						if (host) {
							DuelGame.hostLobby(l);
						} else if (env("SPD_DUELDISCOVER", "").isEmpty()) {
							DuelGame.joinLobby("127.0.0.1", l);
						} else {
							//find the host the way a player would: the Join screen's search
							if ("probe".equals(env("SPD_DUELDISCOVER", ""))) DuelNet.testNoBroadcast = true;
							DuelNet.startDiscovery();
							pendingLobby = l;
							log("searching for a hosted match...");
						}
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
								+ ", my turn " + DuelGame.myTurn() + ") " + DuelGame.debugState());
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
		if (exiting) return;
		//joiner searching for the host
		if (pendingLobby != null) {
			if (searchStart == 0) searchStart = System.currentTimeMillis();
			java.util.ArrayList<DuelNet.FoundHost> hosts = DuelNet.foundHosts();
			if (!hosts.isEmpty()) {
				DuelNet.FoundHost h = hosts.get(0);
				progress("found hosted match '" + h.label + "' at " + h.ip + " after "
						+ (System.currentTimeMillis() - searchStart) + "ms");
				DuelNet.stopDiscovery();
				DuelGame.LobbyListener l = pendingLobby;
				pendingLobby = null;
				DuelGame.joinLobby(h.ip, l);
			} else if (System.currentTimeMillis() - searchStart > 20000) {
				log("SEARCH FAILED: no hosted match found in 20s");
				exit(5);
			} else {
				lastProgress = System.currentTimeMillis();
			}
			return;
		}

		//joiner 1: change hero in the lobby, like a player tapping "Change hero / loadout"
		//joiner 2: pick a random endgame mystery fighter
		String seatNo = env("SPD_DUELSEAT", "");
		if (("1".equals(seatNo) || "2".equals(seatNo)) && DuelGame.inLobby() && DuelGame.me() != null && changeStep < 3) {
			changeStep++;
			if (changeStep == 1) {
				DuelGame.setPicking(true);
				log("LOBBY: opening the hero picker");
			} else if (changeStep == 3) {
				if ("1".equals(seatNo)) {
					DuelGame.prepareTestHero(HeroClass.HUNTRESS, new DuelLoadout());
				} else {
					DuelGame.prepareMysteryHero(true);
				}
				DuelGame.heroChanged();
				DuelGame.setPicking(false);
				log("LOBBY: changed hero to " + Dungeon.hero.heroClass + " " + Dungeon.hero.subClass
						+ (DuelGame.me() != null ? "" : "") + " with " + Dungeon.hero.belongings.backpack.items.size() + " backpack items");
			}
			return;
		}

		//host: when everyone's in (and nobody is still choosing), set the teams and start
		if ("host".equals(mode()) && DuelGame.inLobby() && !configured) {
			int want = Integer.parseInt(env("SPD_DUELPLAYERS", "2"));
			if (DuelGame.players().size() >= want && settle++ >= 10) {
				if (!DuelGame.startProblem().isEmpty()) {
					log("not starting yet: " + DuelGame.startProblem());
					return;
				}
				if (!lobbyRestarted) {
					//like Android rebuilding the app while the host sits in the lobby
					lobbyRestarted = true;
					log("STRESS: app rebuilt while hosting the lobby");
					com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.switchNoFade(
							com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class);
					return;
				}
				if (!lobbyChatted) {
					lobbyChatted = true;
					DuelChat.send("hello from the host");
					return;
				}
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

		//my quickslots must only hold my own items
		if (!slotsChecked) {
			slotsChecked = true;
			int mine = 0;
			for (int i = 0; i < com.shatteredpixel.shatteredpixeldungeon.QuickSlot.SIZE; i++) {
				com.shatteredpixel.shatteredpixeldungeon.items.Item it = Dungeon.quickslot.getItem(i);
				if (it == null) continue;
				if (h.belongings.contains(it)) mine++;
				else log("QUICKSLOT LEAK: slot " + i + " holds " + it.getClass().getSimpleName() + " that isn't mine");
			}
			log("quickslots checked: " + mine + " of my items");
		}

		//joiner 1: like Android rebuilding the app mid-match; it should land back in the match
		if ("1".equals(env("SPD_DUELSEAT", "")) && actions == 6 && !matchRestarted && !env("SPD_REBUILDTEST", "").isEmpty()) {
			matchRestarted = true;
			progress("STRESS: app rebuilt mid-match");
			com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.switchNoFade(
					com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class);
			return;
		}

		//chat mid-match (joiner 2), and a look at the screen (host)
		if ("2".equals(env("SPD_DUELSEAT", "")) && actions == 2 && !matchChatted) {
			matchChatted = true;
			DuelChat.send("gg");
		}
		if ("host".equals(mode()) && actions == 5 && !shot) {
			shot = true;
			Game.scene().add(new Shot("match_" + env("SPD_DUELMODE", "ffa") + "_host"));
		}

		//an enemy player's creature right next to me gets hit first
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : Dungeon.level.mobs.toArray(new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob[0])) {
			if (m.duelOwner >= 0 && m.alignment == com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY
					&& m.invisible == 0 && Dungeon.level.adjacent(h.pos, m.pos)) {
				actions++;
				progress("my action " + actions + ": attack copy of " + m.getClass().getSimpleName() + " from player " + m.duelOwner);
				if (h.handle(m.pos)) h.next();
				return;
			}
		}

		//stress (joiner 2): mirror images, creatures that must show and be hittable on every phone
		if ("2".equals(env("SPD_DUELSEAT", "")) && actions == 1 && !imaged) {
			imaged = true;
			int n = com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMirrorImage.spawnImages(h, 2);
			progress("STRESS: summoned " + n + " mirror images");
		}

		//stress (host): the warrior's armour ability (heroic leap) next to its target
		if ("host".equals(mode()) && actions == 6 && !leapt) {
			leapt = true;
			com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor ar = h.belongings.armor();
			if (ar instanceof com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor && h.armorAbility != null) {
				int land = -1;
				for (int n : PathFinder.NEIGHBOURS8) {
					int c = target.pos + n;
					if (c >= 0 && c < Dungeon.level.length() && Dungeon.level.passable[c] && Actor.findChar(c) == null
							&& c != h.pos) { land = c; break; }
				}
				if (land >= 0) {
					actions++;
					progress("STRESS: " + h.armorAbility.name() + " to " + land + " next to " + target.name());
					ar.execute(h, ar.defaultAction());
					GameScene.handleCell(land);
					return;
				}
			}
		}

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
		//stress (joiner 1): a fire potion, a frost bolt, then slow + paralysis, all of which must
		//really happen to the victim's hero and show on everyone's screen
		if ("1".equals(env("SPD_DUELSEAT", ""))) {
			if (actions == 2 && !threw) {
				threw = true;
				actions++;
				PotionOfLiquidFlame p = new PotionOfLiquidFlame();
				p.identify();
				if (p.collect()) {
					progress("STRESS: throwing liquid flame at " + target.name() + " at " + target.pos);
					p.cast(h, target.pos);
					return;
				}
			}
			if (actions == 5 && !zapped) {
				zapped = true;
				actions++;
				WandOfFrost w = new WandOfFrost();
				w.identify();
				if (w.collect()) {
					progress("STRESS: zapping frost at " + target.name() + " at " + target.pos);
					w.execute(h, Wand.AC_ZAP);
					GameScene.handleCell(target.pos);
					return;
				}
			}
			if (actions == 8) {
				log("STRESS: slow + paralysis on " + target.name());
				Buff.prolong(target, Slow.class, 3f);
				Buff.prolong(target, Paralysis.class, 2f);
			}
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

		//rooted and nothing in reach: wait a turn, like a player would
		if (cell != target.pos && h.rooted) {
			actions++;
			progress("my action " + actions + ": rooted, waiting");
			h.rest(false);
			return;
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
