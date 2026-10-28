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
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MailArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.PlateArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.SparseArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything about a match that isn't networking or drawing.
 *
 * How a match stays in sync: each phone is in charge of its own hero. Turns rotate through
 * the players (host first). On my turn my hero acts for one tick of game time, while my
 * phone records what happened as a list of events (each step, swing, hit and push). The
 * events go to every other phone, which plays them back on my figure. Damage I deal is
 * applied to the real hero it hit on that player's phone; pushes move the real hero too.
 * A player whose hero dies tells everyone; the last team standing wins.
 */
public class DuelGame {

	//bump this whenever the message format changes, so mismatched builds refuse to play
	public static final int PROTOCOL = 3;
	public static final int MAX_PLAYERS = 4;

	public static final int MODE_FFA = 0;
	public static final int MODE_TEAMS = 1;

	// ------------------------------------------------------------------ players

	public static class Player {
		public int id;
		public String name;          //their hero, e.g. "Berserker (lvl 30)"
		public String pname = "";    //the player's own name
		public boolean picking;      //choosing a new hero in the lobby
		public boolean mystery;      //random fighter: shown as "?" until the match starts
		public volatile boolean away; //switched to another app

		/** What the lobby shows for their hero. */
		public String heroLabel() {
			return mystery ? "Mystery fighter (lvl " + lvl + ")" : name;
		}
		public HeroClass cls = HeroClass.WARRIOR;
		public int tier;
		public int lvl;
		public int team;
		public Bundle hero;          //only kept by the host until the match starts
		public volatile boolean alive = true;
		public DuelOpponent puppet;  //this player's figure on my screen (null for me)
		public Hero ghost;           //their hero, for their defence stats

		Bundle toBundle(boolean withHero) {
			Bundle b = new Bundle();
			b.put("id", id);
			b.put("name", name);
			b.put("cls", cls);
			b.put("tier", tier);
			b.put("lvl", lvl);
			b.put("team", team);
			b.put("pname", pname);
			b.put("picking", picking);
			b.put("mystery", mystery);
			if (withHero && hero != null) b.put("hero", hero);
			return b;
		}

		static Player fromBundle(Bundle b) {
			Player p = new Player();
			p.id = b.getInt("id");
			p.name = b.getString("name");
			p.cls = b.getEnum("cls", HeroClass.class);
			p.tier = b.getInt("tier");
			p.lvl = b.getInt("lvl");
			p.team = b.getInt("team");
			p.pname = b.getString("pname");
			p.picking = b.getBoolean("picking");
			p.mystery = b.getBoolean("mystery");
			if (b.contains("hero")) p.hero = b.getBundle("hero");
			return p;
		}

		/** "Rob" or, if they never set a name, their hero. */
		public String who() {
			return pname == null || pname.isEmpty() ? name : pname;
		}
	}

	private static final ArrayList<Player> players = new ArrayList<>();
	private static volatile int myId = 0;
	private static volatile int mode = MODE_FFA;

	private static volatile boolean active = false;   //a match is on screen
	private static volatile boolean inLobby = false;  //hosting/joining, not fighting yet
	private static volatile boolean over = false;
	private static boolean host;

	private static Bundle myHello;
	private static DuelController controller;
	private static final ConcurrentLinkedQueue<Bundle> incoming = new ConcurrentLinkedQueue<>();

	public static boolean active() { return active; }
	public static boolean inLobby() { return inLobby; }
	public static boolean isHost() { return host; }
	public static boolean isOver() { return over; }
	public static int myId() { return myId; }
	public static int mode() { return mode; }

	public static synchronized ArrayList<Player> players() {
		return new ArrayList<>(players);
	}

	public static synchronized Player player(int id) {
		for (Player p : players) if (p.id == id) return p;
		return null;
	}

	public static Player me() {
		return player(myId);
	}

	// ------------------------------------------------------------------ staying alive in the background

	/**
	 * Android: keeps the app running (with a "match in progress" notification) while a lobby or
	 * match is on, so switching to another app doesn't get the game closed by the system.
	 */
	public interface KeepAlive {
		void set(boolean on);
	}

	public static volatile KeepAlive keepAlive;

	private static void keepAlive(boolean on) {
		DuelCrash.sessionOpen(on);
		KeepAlive k = keepAlive;
		if (k == null) return;
		try {
			k.set(on);
		} catch (Throwable t) {
			DuelTestDriver.log("keep-alive failed: " + t);
		}
	}

	private static volatile boolean pausedNow = false;

	/** The app went to the background (or came back). The others are told, so they know why it's quiet. */
	public static void appPaused(boolean paused) {
		if (pausedNow == paused) return;
		pausedNow = paused;
		if (!(inLobby || (active && !over))) return;
		Bundle b = new Bundle();
		b.put("type", "away");
		b.put("who", myId);
		b.put("on", paused);
		if (host) DuelNet.broadcast(b);
		else DuelNet.send(DuelNet.HOST_PEER, b);
	}

	private static void awayChanged(Bundle msg) {
		final Player p = player(msg.getInt("who"));
		if (p == null) return;
		p.away = msg.getBoolean("on");
		DuelTestDriver.log("player " + p.id + (p.away ? " switched away" : " is back"));
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				if (active && !over) {
					if (p.away) GLog.w("%s", p.who() + " switched to another app. The match waits for them.");
					else GLog.i("%s", p.who() + " is back.");
				}
			}
		});
		lobbyChanged();
	}

	// ------------------------------------------------------------------ my name

	private static final String NAME_KEY = "duel_name";

	public static String playerName() {
		String n = "";
		try {
			n = com.watabou.utils.GameSettings.getString(NAME_KEY, "", 16);
		} catch (Exception ignored) {}
		if (n == null || n.trim().isEmpty()) {
			n = "Player " + (100 + com.watabou.utils.Random.Int(900));
			setPlayerName(n);
		}
		return n;
	}

	public static void setPlayerName(String n) {
		if (n == null) return;
		n = n.replace("|", "").replace("\n", " ").trim();
		if (n.length() > 16) n = n.substring(0, 16);
		if (n.isEmpty()) return;
		try {
			com.watabou.utils.GameSettings.put(NAME_KEY, n);
		} catch (Exception ignored) {}
		if (myHello != null) myHello.put("pname", n);
	}

	// ------------------------------------------------------------------ building my fighter

	/** How my current hero was made, so each new lobby gets a fresh copy (full potions etc). */
	public interface HeroRecipe {
		void build() throws Exception;
	}

	private static HeroRecipe recipe;

	/**
	 * Mystery fighter: random class (and subclass + armour ability at endgame), random gear and
	 * five random items. Nobody, me included, sees what it is until the match starts. A new
	 * lobby rolls a new one.
	 */
	public static void prepareMysteryHero(final boolean endgame) {
		HeroClass cls = com.watabou.utils.Random.element(HeroClass.values());
		DuelLoadout loadout = DuelLoadout.randomFor(endgame);
		if (endgame) {
			prepareEndgameHero(cls, com.watabou.utils.Random.element(cls.subClasses()),
					com.watabou.utils.Random.element(cls.armorAbilities()), loadout);
		} else {
			prepareTestHero(cls, loadout);
		}
		recipe = new HeroRecipe() {
			public void build() { prepareMysteryHero(endgame); }
		};
		myHello.put("mystery", true);
	}

	/** Before hosting/joining: build my hero again from my last choice (or a default fighter). */
	public static void rebuildMyHero() {
		try {
			if (recipe != null) {
				recipe.build();
				return;
			}
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
		}
		prepareTestHero(HeroClass.WARRIOR, new DuelLoadout());
	}

	/**
	 * Loads a saved run's hero into memory to fight with. Only reads the save file;
	 * nothing about the match is ever written back to it.
	 */
	public static void prepareHero(final int slot) throws Exception {
		recipe = new HeroRecipe() {
			public void build() throws Exception { prepareHero(slot); }
		};
		Dungeon.loadGame(slot, true);
		Hero h = Dungeon.hero;
		if (h == null) throw new Exception("That save has no hero");

		h.HP = h.HT; //everyone starts the fight at full health
		buildHello(h, Messages.capitalize(h.heroClass.title()) + " (lvl " + h.lvl + ")");
	}

	public static final int TEST_LEVEL = 12;
	public static final int ENDGAME_LEVEL = 30;

	/** A fresh hero of the class with its normal starting kit, made without touching any save. */
	private static Hero freshHero(HeroClass cls) {
		GamesInProgress.selectedClass = cls;
		Dungeon.seed = DungeonSeed.randomSeed();
		Dungeon.customSeedText = "";
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.init();
		return Dungeon.hero;
	}

	/** Spends every available talent point, filling talents in the game's listed order. */
	private static void fillTalents(Hero h) {
		for (int tier = 1; tier <= Math.min(h.talents.size(), Talent.MAX_TALENT_TIERS); tier++) {
			int points = h.talentPointsAvailable(tier);
			for (Talent t : new ArrayList<>(h.talents.get(tier - 1).keySet())) {
				while (points > 0 && h.pointsInTalent(t) < t.maxPoints()) {
					h.duelGiveTalentPoint(t);
					points--;
				}
			}
		}
	}

	private static void giveHealing(int amount) {
		PotionOfHealing healing = new PotionOfHealing();
		healing.quantity(amount).identify();
		healing.collect();
	}

	/**
	 * Mid-game test fighter: level 12, tier 1 and 2 talents filled in, class weapon +3,
	 * mail armour +2, 13 strength and two healing potions. Every class gets the same budget.
	 */
	public static void prepareTestHero(final HeroClass cls, final DuelLoadout loadout) {
		recipe = new HeroRecipe() {
			public void build() { prepareTestHero(cls, loadout); }
		};
		Hero h = freshHero(cls);
		h.duelSetLevel(TEST_LEVEL);
		h.STR = 13; //base 10 plus the ~3 strength potions found by this point
		fillTalents(h);

		if (h.belongings.weapon != null) {
			h.belongings.weapon.upgrade(3);
		}

		MailArmor armor = new MailArmor();
		armor.upgrade(2);
		armor.identify();
		if (cls == HeroClass.WARRIOR) {
			armor.affixSeal(new BrokenSeal());
		}
		h.belongings.armor = armor;

		giveHealing(2);
		if (loadout != null) loadout.apply(h, false);

		buildHello(h, "Test " + Messages.capitalize(cls.title()) + " (lvl " + h.lvl + ")");
	}

	/**
	 * Endgame test fighter: level 30 with the chosen subclass and armour ability, every
	 * talent point spent, class weapon +10, plate-based class armour +10 (ability fully
	 * charged), 18 strength, a maxed class artifact and three healing potions.
	 */
	public static void prepareEndgameHero(final HeroClass cls, final HeroSubClass sub, final ArmorAbility ability, final DuelLoadout loadout) {
		recipe = new HeroRecipe() {
			public void build() { prepareEndgameHero(cls, sub, ability, loadout); }
		};
		Hero h = freshHero(cls);
		h.duelSetLevel(ENDGAME_LEVEL);
		h.STR = 18; //base 10 plus all 8 strength potions in a run

		h.subClass = sub;
		Talent.initSubclassTalents(h);
		h.armorAbility = ability;
		Talent.initArmorTalents(h);
		fillTalents(h);

		if (h.belongings.weapon != null) {
			h.belongings.weapon.upgrade(10);
		}

		PlateArmor plate = new PlateArmor();
		plate.upgrade(10);
		plate.identify();
		if (cls == HeroClass.WARRIOR) {
			plate.affixSeal(new BrokenSeal());
		}
		ClassArmor armor = ClassArmor.upgrade(h, plate);
		armor.identify();
		armor.charge = 100;
		h.belongings.armor = armor;
		armor.activate(h);

		if (h.belongings.artifact instanceof CloakOfShadows) {
			CloakOfShadows cloak = (CloakOfShadows) h.belongings.artifact;
			cloak.transferUpgrade(10);
			cloak.directCharge(10);
		} else if (h.belongings.artifact instanceof HolyTome) {
			HolyTome tome = (HolyTome) h.belongings.artifact;
			tome.transferUpgrade(10);
			tome.directCharge(10);
		}

		giveHealing(3);
		if (loadout != null) loadout.apply(h, true);

		buildHello(h, Messages.capitalize(sub.title()) + " (lvl " + h.lvl + ")");
	}

	static Bundle helloForTest() {
		return myHello;
	}

	private static void buildHello(Hero h, String name) {
		myHello = new Bundle();
		myHello.put("type", "hello");
		myHello.put("protocol", PROTOCOL);
		myHello.put("hero", h);
		myHello.put("cls", h.heroClass);
		myHello.put("tier", h.tier());
		myHello.put("lvl", h.lvl);
		myHello.put("name", name);
		myHello.put("pname", playerName());
	}

	public static String myLabel() {
		Hero h = Dungeon.hero;
		if (h == null) return "Hero";
		return Messages.capitalize(h.heroClass.title()) + " lvl " + h.lvl;
	}

	// ------------------------------------------------------------------ lobby

	public interface LobbyListener {
		/** The player list or match settings changed. */
		void onLobbyChanged();
		void onStatus(String text);
		void onFailed(String reason);
	}

	private static volatile LobbyListener lobby;

	public static void setLobbyListener(LobbyListener l) {
		lobby = l;
	}

	private static void resetState() {
		synchronized (DuelGame.class) {
			players.clear();
		}
		incoming.clear();
		active = false;
		over = false;
		controller = null;
		myId = 0;
		mode = MODE_FFA;
		current = 0;
		myTurn = false;
		events.clear();
		DuelChat.reset();
		synchronized (inflicted) {
			inflicted.clear();
		}
	}

	public static void hostLobby(LobbyListener l) {
		resetState();
		amPicking = false;
		keepAlive(true);
		host = true;
		inLobby = true;
		lobby = l;

		Player me = playerFromHello(0, myHello);
		me.team = 0;
		synchronized (DuelGame.class) {
			players.add(me);
		}
		DuelNet.host(beaconText(), netListener);
		lobbyChanged();
	}

	public static void joinLobby(String ip, LobbyListener l) {
		resetState();
		amPicking = false;
		keepAlive(true);
		host = false;
		inLobby = true;
		lobby = l;
		DuelNet.join(ip, netListener);
	}

	public static void cancelLobby() {
		amPicking = false;
		keepAlive(false);
		inLobby = false;
		lobby = null;
		DuelNet.close();
	}

	/** What the Join screen shows about my lobby: name|class|armour tier|hero|players|max|mode. */
	private static String beaconText() {
		Player me = me();
		String hero = me != null ? me.heroLabel() : myLabel();
		String cls = me != null && me.mystery ? "MYSTERY" : me != null && me.cls != null ? me.cls.name() : "WARRIOR";
		int tier = me != null ? me.tier : 0;
		return playerName() + "|" + cls + "|" + tier + "|" + hero.replace("|", "") + "|"
				+ players().size() + "|" + MAX_PLAYERS + "|" + (mode == MODE_TEAMS ? "Teams" : "Free-for-all");
	}

	private static Player playerFromHello(int id, Bundle hello) {
		Player p = new Player();
		p.id = id;
		p.name = hello.getString("name");
		p.cls = hello.getEnum("cls", HeroClass.class);
		p.tier = hello.getInt("tier");
		p.lvl = hello.getInt("lvl");
		p.hero = hello.getBundle("hero");
		p.pname = hello.getString("pname");
		p.mystery = hello.getBoolean("mystery");
		return p;
	}

	// ---------------- host-only lobby controls

	public static void setMode(int m) {
		if (!host || !inLobby) return;
		mode = m;
		broadcastLobby();
	}

	/** Teams mode: swap a player between team A and team B. */
	public static void toggleTeam(int id) {
		if (!host || !inLobby) return;
		Player p = player(id);
		if (p == null) return;
		p.team = p.team == 0 ? 1 : 0;
		broadcastLobby();
	}

	/** I picked a new hero/loadout in the lobby: everyone sees it. */
	public static void heroChanged() {
		if (!inLobby || myHello == null) return;
		if (host) {
			Player me = me();
			if (me != null) {
				Player fresh = playerFromHello(me.id, myHello);
				me.name = fresh.name;
				me.cls = fresh.cls;
				me.tier = fresh.tier;
				me.lvl = fresh.lvl;
				me.hero = fresh.hero;
				me.pname = fresh.pname;
				me.mystery = fresh.mystery;
				me.picking = false;
			}
			broadcastLobby();
		} else {
			Bundle b = new Bundle();
			b.put("type", "hero");
			b.put("hero", myHello.getBundle("hero"));
			b.put("cls", myHello.getEnum("cls", HeroClass.class));
			b.put("tier", myHello.getInt("tier"));
			b.put("lvl", myHello.getInt("lvl"));
			b.put("name", myHello.getString("name"));
			b.put("pname", myHello.getString("pname"));
			b.put("mystery", myHello.getBoolean("mystery"));
			DuelNet.send(DuelNet.HOST_PEER, b);
		}
		DuelTestDriver.log("I changed my hero to " + myHello.getString("name"));
	}

	private static volatile boolean amPicking = false;

	public static boolean amPicking() {
		return amPicking;
	}

	/** I opened (or closed) the hero picker in the lobby; the others see "choosing a hero". */
	public static void setPicking(boolean on) {
		amPicking = on;
		if (!inLobby) return;
		if (host) {
			Player me = me();
			if (me != null) me.picking = on;
			broadcastLobby();
		} else {
			Bundle b = new Bundle();
			b.put("type", "picking");
			b.put("on", on);
			DuelNet.send(DuelNet.HOST_PEER, b);
		}
	}

	/** Empty string if the host can start; otherwise why not. */
	public static String startProblem() {
		ArrayList<Player> ps = players();
		if (ps.size() < 2) return "Waiting for at least one more player.";
		for (Player p : ps) {
			if (p.picking) return p.who() + " is choosing a hero.";
		}
		if (mode == MODE_TEAMS) {
			boolean a = false, b = false;
			for (Player p : ps) {
				if (p.team == 0) a = true; else b = true;
			}
			if (!a || !b) return "Both teams need at least one player.";
		}
		return "";
	}

	public static void startMatch() {
		if (!host || !inLobby || !startProblem().isEmpty()) return;
		DuelNet.stopAccepting();

		Bundle start = new Bundle();
		start.put("type", "start");
		start.put("mode", mode);
		ArrayList<Bundle> list = new ArrayList<>();
		for (Player p : players()) list.add(p.toBundle(true));
		start.put("players", list.toArray(new Bundle[0]));
		DuelNet.broadcast(start);
		beginMatch(start);
	}

	private static void broadcastLobby() {
		ArrayList<Player> ps = players();
		ArrayList<Bundle> list = new ArrayList<>();
		for (Player p : ps) list.add(p.toBundle(false));
		for (Player p : ps) {
			if (p.id == myId) continue;
			Bundle b = new Bundle();
			b.put("type", "lobby");
			b.put("mode", mode);
			b.put("you", p.id);
			b.put("players", list.toArray(new Bundle[0]));
			DuelNet.send(p.id, b);
		}
		DuelNet.setBeaconLabel(beaconText());
		lobbyChanged();
	}

	private static void lobbyChanged() {
		final LobbyListener l = lobby;
		if (l == null) return;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				l.onLobbyChanged();
			}
		});
	}

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
		amPicking = false;
		keepAlive(false);
		final LobbyListener l = lobby;
		inLobby = false;
		lobby = null;
		DuelNet.close();
		if (l == null) return;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				l.onFailed(reason);
			}
		});
	}

	// ------------------------------------------------------------------ network messages

	private static final DuelNet.Listener netListener = new DuelNet.Listener() {
		@Override
		public void onConnected(int peer) {
			if (!host) {
				status("Connected! Joining the lobby...");
				DuelNet.send(DuelNet.HOST_PEER, myHello);
			}
		}

		@Override
		public void onMessage(int peer, Bundle msg) {
			try {
				handle(peer, msg);
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
				DuelTestDriver.log("ERROR handling message: " + e);
			}
		}

		@Override
		public void onDisconnected(int peer, String reason) {
			if (host) {
				if (peer == DuelNet.HOST_PEER) {
					//couldn't open the lobby at all
					lobbyFail(reason);
				} else if (inLobby) {
					synchronized (DuelGame.class) {
						players.remove(player(peer));
					}
					broadcastLobby();
				} else if (active && !over) {
					Bundle left = new Bundle();
					left.put("type", "left");
					left.put("who", peer);
					DuelNet.broadcast(left);
					incoming.add(left);
				}
			} else {
				if (inLobby) {
					lobbyFail(reason.startsWith("Couldn't") ? reason : "The host closed the lobby.");
				} else if (active && !over) {
					Bundle end = new Bundle();
					end.put("type", "end");
					end.put("why", "Lost connection to the host.");
					incoming.add(end);
				}
			}
		}
	};

	private static void handle(int peer, Bundle msg) {
		String type = msg.getString("type");

		if ("chat".equals(type)) {
			if (host) DuelNet.broadcastExcept(peer, msg);
			DuelChat.received(msg);
			return;
		}
		if ("away".equals(type)) {
			if (host) DuelNet.broadcastExcept(peer, msg);
			awayChanged(msg);
			return;
		}

		if ("hello".equals(type) && host) {
			if (msg.getInt("protocol") != PROTOCOL) {
				reject(peer, "Your SPD Duel is a different version from the host's. Restart the app and take the update.");
				return;
			}
			if (!inLobby || players().size() >= MAX_PLAYERS) {
				reject(peer, inLobby ? "That lobby is full." : "That match has already started.");
				return;
			}
			Player p = playerFromHello(peer, msg);
			//new players go to whichever team is smaller
			int a = 0, b = 0;
			for (Player o : players()) { if (o.team == 0) a++; else b++; }
			p.team = a <= b ? 0 : 1;
			synchronized (DuelGame.class) {
				players.add(p);
			}
			DuelTestDriver.log("lobby: player " + peer + " joined (" + p.name + ")");
			broadcastLobby();

		} else if ("hero".equals(type) && host && inLobby) {
			Player p = player(peer);
			if (p != null) {
				p.hero = msg.getBundle("hero");
				p.cls = msg.getEnum("cls", HeroClass.class);
				p.tier = msg.getInt("tier");
				p.lvl = msg.getInt("lvl");
				p.name = msg.getString("name");
				p.pname = msg.getString("pname");
				p.mystery = msg.getBoolean("mystery");
				p.picking = false;
				DuelTestDriver.log("lobby: player " + peer + " changed hero to " + p.name);
				broadcastLobby();
			}

		} else if ("picking".equals(type) && host && inLobby) {
			Player p = player(peer);
			if (p != null) {
				p.picking = msg.getBoolean("on");
				broadcastLobby();
			}

		} else if ("lobby".equals(type) && !host) {
			mode = msg.getInt("mode");
			myId = msg.getInt("you");
			synchronized (DuelGame.class) {
				players.clear();
				for (Bundle b : msg.getBundleArray("players")) players.add(Player.fromBundle(b));
			}
			lobbyChanged();

		} else if ("reject".equals(type)) {
			lobbyFail(msg.getString("why"));

		} else if ("start".equals(type) && !host) {
			final Bundle start = msg;
			Game.runOnRenderThread(new Callback() {
				@Override
				public void call() {
					beginMatch(start);
				}
			});

		} else if ("turn".equals(type) || "dead".equals(type) || "left".equals(type)) {
			if (host) DuelNet.broadcastExcept(peer, msg); //relay to everyone else
			DuelTestDriver.progress("RECEIVED " + type + " from " + (msg.contains("from") ? msg.getInt("from") : msg.getInt("who"))
					+ (msg.contains("seq") ? " seq " + msg.getInt("seq") : ""));
			incoming.add(msg);

		} else if ("end".equals(type) && !host) {
			incoming.add(msg);
		}
	}

	private static void reject(int peer, String why) {
		Bundle b = new Bundle();
		b.put("type", "reject");
		b.put("why", why);
		DuelNet.send(peer, b);
		final int p = peer;
		new Thread() {
			@Override
			public void run() {
				try { Thread.sleep(500); } catch (InterruptedException ignored) {}
				DuelNet.kick(p);
			}
		}.start();
	}

	// ------------------------------------------------------------------ starting the match

	static final ArrayList<String> reveals = new ArrayList<>();

	private static void beginMatch(Bundle start) {
		if (!inLobby) return;

		mode = start.getInt("mode");
		ArrayList<Player> roster = new ArrayList<>();
		for (Bundle b : start.getBundleArray("players")) roster.add(Player.fromBundle(b));

		for (Player p : roster) {
			if (p.id == myId) continue;
			p.ghost = heroFrom(p.hero);
		}
		synchronized (DuelGame.class) {
			players.clear();
			players.addAll(roster);
		}

		inLobby = false;
		lobby = null;
		active = true;
		over = false;
		sentSeq = 0;
		events.clear();
		incoming.clear();
		DuelTestDriver.progress("match starting: " + roster.size() + " players, mode " + (mode == MODE_TEAMS ? "teams" : "ffa") + ", I am " + myId);

		Mob.clearHeldAllies();
		DuelController.resetPlayback();
		DuelMirror.reset();
		DuelFx.install();
		GameLog.wipe();
		//mystery fighters are revealed once the arena is on screen
		synchronized (reveals) {
			reveals.clear();
			for (Player p : roster) {
				if (!p.mystery) continue;
				reveals.add(p.id == myId ? "Your mystery fighter is: " + p.name
						: p.who() + "'s mystery fighter is: " + p.name);
			}
		}
		Dungeon.droppedItems = new SparseArray<>();
		InterlevelScene.mode = InterlevelScene.Mode.NONE;

		DuelArenaLevel arena = new DuelArenaLevel();
		arena.create();

		for (Player p : roster) {
			if (p.id == myId) continue;
			DuelOpponent puppet = new DuelOpponent();
			boolean ally = !isEnemy(p);
			puppet.setup(p, DuelArenaLevel.spawn(indexOf(roster, p)), ally);
			p.puppet = puppet;
			arena.mobs.add(puppet);
		}

		Hero me = Dungeon.hero;
		me.HP = me.HT;
		me.curAction = me.lastAction = null;

		Dungeon.switchLevel(arena, DuelArenaLevel.spawn(indexOf(roster, me())));
		//saved runs store leftover times; line my hero up with the start of the match
		Actor.delayChar(me, -me.cooldown());

		controller = new DuelController();
		Actor.add(controller);

		current = roster.get(0).id;
		if (current == myId) startMyTurn(); else myTurn = false;

		Game.switchScene(GameScene.class);
	}

	/**
	 * True while unpacking another player's hero, buffs or creatures. Their buffs' action
	 * buttons (berserk, freerun, combo...) belong to them, not to my screen.
	 */
	static volatile boolean decodingForeign = false;

	public static boolean foreignAction(Object action) {
		if (decodingForeign) return true;
		if (!(action instanceof com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff)) return false;
		if (!active && !inLobby) return false;
		com.shatteredpixel.shatteredpixeldungeon.actors.Char t = ((com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff) action).target;
		return t != null && t != Dungeon.hero;
	}

	/** Turns a shared hero back into a Hero object (used only for its defence stats). */
	private static Hero heroFrom(Bundle heroBundle) {
		if (heroBundle == null) return null;
		//unpacking their hero puts their items in "their" quickslots, which are really mine: keep mine
		com.shatteredpixel.shatteredpixeldungeon.items.Item[] myQuickslots =
				new com.shatteredpixel.shatteredpixeldungeon.items.Item[com.shatteredpixel.shatteredpixeldungeon.QuickSlot.SIZE];
		for (int i = 0; i < myQuickslots.length; i++) myQuickslots[i] = Dungeon.quickslot.getItem(i);
		decodingForeign = true;
		try {
			Bundle wrapper = new Bundle();
			wrapper.put("h", heroBundle);
			return (Hero) wrapper.get("h");
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
			return null;
		} finally {
			decodingForeign = false;
			for (int i = 0; i < myQuickslots.length; i++) {
				if (myQuickslots[i] == null) Dungeon.quickslot.clearSlot(i);
				else Dungeon.quickslot.setSlot(i, myQuickslots[i]);
			}
		}
	}

	private static int indexOf(ArrayList<Player> roster, Player p) {
		for (int i = 0; i < roster.size(); i++) if (roster.get(i).id == p.id) return i;
		return 0;
	}

	/** Which side a player is on: their team in Teams mode, just themselves in Free-for-all. */
	static int sideOf(Player p) {
		return mode == MODE_TEAMS ? p.team : 100 + p.id;
	}

	static boolean isEnemy(Player p) {
		if (p == null || p.id == myId) return false;
		return sideOf(p) != sideOf(me());
	}

	public static String describe(Player p) {
		return p.who() + (p.pname != null && !p.pname.isEmpty() ? " (" + p.name + ")" : "") + (p.id == 0 ? " - host" : "");
	}

	// ------------------------------------------------------------------ turns

	private static volatile int current = 0;
	private static volatile boolean myTurn = false;
	private static int actionsThisTurn = 0;
	private static float turnStart = 0;
	private static int sentSeq = 0;

	//only the player holding the turn can act. A turn is one tick of game time, but never
	//more than MAX_ACTIONS actions (time-stopping effects would otherwise give endless moves)
	private static final int MAX_ACTIONS = 3;

	public static int currentPlayer() { return current; }
	public static boolean myTurn() { return myTurn; }
	public static int actionsThisTurn() { return actionsThisTurn; }

	/**
	 * Handing the turn over is one step: the screen saying "your turn now" and the game loop
	 * deciding "not my turn, wait" must never interleave, or each can assume the other moves first.
	 */
	static final Object TURN_LOCK = new Object();

	/** Test runs: everything about whose turn it is, for when a match gets stuck. */
	static String debugState() {
		DuelController c = controller;
		Hero h = Dungeon.hero;
		return "myTurn=" + myTurn + " current=" + current + " actions=" + actionsThisTurn
				+ " turnStart=" + turnStart + " now=" + Actor.now()
				+ " controller[" + (c == null ? "none" : c.debugState()) + "]"
				+ " hero[ready=" + (h != null && h.ready) + " cooldown=" + (h != null ? h.cooldown() : -1)
				+ " paralysed=" + (h != null ? h.paralysed : -1) + "]"
				+ " processing=" + Actor.processing() + " thread=" + Thread.currentThread().getName();
	}

	static void startMyTurn() {
		DuelTestDriver.log("TURN start: " + debugState());
		actionsThisTurn = 0;
		turnStart = Actor.now();
		beginRecording();
		myTurn = true;
	}

	/** Called by the hero whenever it spends time: that's one action of my turn. */
	public static void heroSpentTime() {
		if (active && myTurn) {
			actionsThisTurn++;
			samplePositions();
		}
	}

	/** Start of every hero turn. If the hero may not act, the controller is brought forward instead. */
	public static boolean heroMayAct() {
		if (!active || over || controller == null) return true;
		if (myTurn) {
			if (actionsThisTurn == 0) return true;
			if (actionsThisTurn < MAX_ACTIONS && Actor.now() < turnStart + 1f - 0.001f) return true;
		}
		controller.bringForward();
		return false;
	}

	/** The next living player after the given one, in seat order. */
	static int nextAlive(int after) {
		ArrayList<Player> ps = players();
		int start = 0;
		for (int i = 0; i < ps.size(); i++) if (ps.get(i).id == after) start = i;
		for (int k = 1; k <= ps.size(); k++) {
			Player p = ps.get((start + k) % ps.size());
			if (p.alive) return p.id;
		}
		return after;
	}

	/** My turn is over: send what happened and pass the turn on. Actor thread. */
	static void finishMyTurn() {
		samplePositions();
		Hero me = Dungeon.hero;
		Bundle b = new Bundle();
		b.put("type", "turn");
		b.put("from", myId);
		b.put("seq", ++sentSeq);
		b.put("pos", me.pos);
		b.put("hp", Math.max(me.HP, 0));
		b.put("ht", me.HT);
		ArrayList<Bundle> evs;
		synchronized (events) {
			evs = new ArrayList<>(events);
			events.clear();
		}
		b.put("events", evs.toArray(new Bundle[0]));
		//how my hero and the arena look now: buffs and shields, fire, gas, plants, items, terrain
		try {
			b.put("buffs", DuelSync.myBuffs(me));
		} catch (Exception e) {
			DuelTestDriver.log("couldn't pack my buffs: " + e);
		}
		try {
			b.put("world", DuelSync.world());
		} catch (Exception e) {
			DuelTestDriver.log("couldn't pack the arena: " + e);
		}
		DuelTestDriver.progress("SENT turn " + sentSeq + ": pos " + me.pos + " hp " + me.HP + " events " + describeEvents(evs));
		DuelNet.broadcast(b);

		myTurn = false;
		current = nextAlive(myId);
		DuelTestDriver.log("TURN finish: " + debugState());
	}

	// ------------------------------------------------------------------ recording my turn

	private static final ArrayList<Bundle> events = new ArrayList<>();
	private static int lastMyPos = -1;
	private static final SparseArray<Integer> lastPuppetPos = new SparseArray<>();

	private static void beginRecording() {
		//events are not cleared here: damage my burning/poison did between my turns is still owed
		lastMyPos = Dungeon.hero != null ? Dungeon.hero.pos : -1;
		lastPuppetPos.clear();
		for (Player p : players()) {
			if (p.puppet != null) lastPuppetPos.put(p.id, p.puppet.pos);
		}
		DuelMirror.beginRecording();
		lastBuffSig = Dungeon.hero != null ? buffSignature(Dungeon.hero) : "";
		synchronized (events) {
			clock = 0;
			lastStamp = System.nanoTime();
		}
	}

	//when each event happened, so other phones play the turn back at the same pace
	//(gaps longer than a moment, like me deciding what to do, are cut short)
	private static float clock = 0;
	private static long lastStamp = 0;
	private static final float MAX_GAP = 0.6f;

	private static void addEvent(Bundle e) {
		synchronized (events) {
			long now = System.nanoTime();
			float gap = (now - lastStamp) / 1e9f;
			lastStamp = now;
			clock += Math.max(0, Math.min(gap, MAX_GAP));
			e.put("at", clock);
			events.add(e);
		}
	}

	/** Effects, my creatures' doings: only while it's my turn. */
	static boolean recordingFx() {
		return active && myTurn && !over;
	}

	static void addFxEvent(Bundle e) {
		if (!recordingFx()) return;
		addEvent(e);
	}

	private static String lastBuffSig = "";

	private static String buffSignature(Hero h) {
		StringBuilder sb = new StringBuilder();
		for (com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff b : h.buffs()) {
			if (DuelSync.shareable(b)) sb.append(b.getClass().getName()).append(';');
		}
		return sb.toString();
	}

	/** My hero's buffs changed mid-turn (a shield went up, I turned invisible...): show it now. */
	private static void sampleMyBuffs() {
		Hero me = Dungeon.hero;
		if (me == null) return;
		String sig = buffSignature(me);
		if (sig.equals(lastBuffSig)) return;
		lastBuffSig = sig;
		try {
			Bundle e = new Bundle();
			e.put("e", "self");
			e.put("b", DuelSync.myBuffs(me));
			addEvent(e);
		} catch (Exception ex) {
			DuelTestDriver.log("couldn't share my buffs: " + ex);
		}
	}

	/**
	 * Notices other figures being pushed by what I did, then my hero moving. Pushes are
	 * recorded first: they come from my attacks, which happen before my next step.
	 */
	static void samplePositions() {
		for (Player p : players()) {
			if (p.puppet == null || !p.alive) continue;
			Integer last = lastPuppetPos.get(p.id);
			if (last != null && last != p.puppet.pos) {
				Bundle e = new Bundle();
				e.put("e", "push");
				e.put("t", p.id);
				e.put("p", p.puppet.pos);
				addEvent(e);
			}
			lastPuppetPos.put(p.id, p.puppet.pos);
		}
		flushInflicted();
		if (recordingFx()) {
			sampleMyBuffs();
			DuelMirror.sampleMine();
		}
		Hero me = Dungeon.hero;
		if (me != null && me.pos != lastMyPos) {
			Bundle e = new Bundle();
			e.put("e", "move");
			e.put("p", me.pos);
			addEvent(e);
			lastMyPos = me.pos;
		}
	}

	/** My hero swung or shot at someone's figure. */
	static void recordSwing(int target, boolean hit) {
		if (!active || !myTurn) return;
		samplePositions(); //any steps first, so playback walks before swinging
		Bundle e = new Bundle();
		e.put("e", "swing");
		e.put("t", target);
		e.put("h", hit);
		addEvent(e);
	}

	/** Someone's figure took damage from my hero's doing (weapons, wands, burning...). */
	static void recordDamage(int target, int amount) {
		if (!active || amount <= 0) return;
		Bundle e = new Bundle();
		e.put("e", "dmg");
		e.put("t", target);
		e.put("a", amount);
		addEvent(e);
	}

	/** My hero threw something (a potion, a dart, an arrow...). */
	public static void recordThrow(Class<?> itemClass, int cell) {
		recordShot("throw", itemClass, cell);
	}

	/** My hero zapped a wand. */
	public static void recordZap(Class<?> wandClass, int cell) {
		recordShot("zap", wandClass, cell);
	}

	private static void recordShot(String kind, Class<?> cls, int cell) {
		if (!active || !myTurn || over) return;
		samplePositions();
		Bundle e = new Bundle();
		e.put("e", kind);
		e.put("c", cls.getName());
		e.put("p", cell);
		addEvent(e);
	}

	private static final ArrayList<Object[]> inflicted = new ArrayList<>();

	/** Something I did put a buff/debuff on another player's hero or creature. Sent once its duration is set. */
	static void buffInflicted(com.shatteredpixel.shatteredpixeldungeon.actors.Char target, com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff b) {
		if (!active || !myTurn || over || target == null) return;
		synchronized (inflicted) {
			inflicted.add(new Object[]{target, b});
		}
	}

	private static void flushInflicted() {
		ArrayList<Object[]> list;
		synchronized (inflicted) {
			if (inflicted.isEmpty()) return;
			list = new ArrayList<>(inflicted);
			inflicted.clear();
		}
		for (Object[] o : list) {
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff b = (com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff) o[1];
			if (b.target == null) continue; //already gone again
			if (!DuelSync.worthSending(b)) continue;
			try {
				Bundle e = new Bundle();
				e.put("e", "buff");
				if (!DuelFx.putChar(e, (com.shatteredpixel.shatteredpixeldungeon.actors.Char) o[0], false)) continue;
				e.put("b", DuelSync.packBuff(b));
				e.put("n", b.getClass().getSimpleName());
				addEvent(e);
			} catch (Exception ex) {
				DuelTestDriver.log("couldn't send " + b.getClass().getSimpleName() + ": " + ex);
			}
		}
	}

	static String describeEvents(Collection<Bundle> evs) {
		StringBuilder sb = new StringBuilder("[");
		java.util.TreeMap<String, Integer> fx = new java.util.TreeMap<>();
		for (Bundle e : evs) {
			if ("fx".equals(e.getString("e"))) {
				String k = e.getString("k");
				fx.put(k, fx.containsKey(k) ? fx.get(k) + 1 : 1);
				continue;
			}
			if (sb.length() > 1) sb.append(", ");
			sb.append(e.getString("e"));
			if (e.contains("co")) sb.append(" ").append(e.getInt("co")).append(e.contains("ci") ? "/" + e.getInt("ci") : "");
			if (e.contains("t")) sb.append(" ").append(e.getInt("t"));
			if (e.contains("p")) sb.append("@").append(e.getInt("p"));
			if (e.contains("a")) sb.append(" ").append(e.getInt("a"));
			if (e.contains("n")) sb.append(" ").append(e.getString("n"));
			if (e.contains("c")) {
				String c = e.getString("c");
				sb.append(" ").append(c.substring(c.lastIndexOf('.') + 1));
			}
		}
		if (!fx.isEmpty()) sb.append(sb.length() > 1 ? ", " : "").append("fx").append(fx);
		return sb.append("]").toString();
	}

	// ------------------------------------------------------------------ the match director (render thread)

	static Bundle pollIncoming() {
		return incoming.poll();
	}

	/** A player is out (dead or gone). Render thread. */
	static void playerOut(int id, boolean left) {
		Player p = player(id);
		if (p == null || !p.alive) return;
		p.alive = false;
		if (p.puppet != null) {
			p.puppet.knockOut();
		}
		if (id != myId) {
			GLog.w(describe(p) + (left ? " left the match." : " was knocked out!"));
		}
		if (current == id) {
			current = nextAlive(id);
		}
		checkOver();
	}

	/** After someone's turn has been played back on my screen. Render thread. */
	static void turnPlayedBack(int from) {
		if (over) return;
		if (current == from) current = nextAlive(from);
		DuelTestDriver.log("TURN played back from " + from + ": " + debugState());
	}

	/** Is it my turn now? Called by the director when it has nothing else to do. */
	static boolean shouldStartMyTurn() {
		Player me = me();
		return active && !over && !myTurn && current == myId && me != null && me.alive;
	}

	static DuelController controller() {
		return controller;
	}

	private static void checkOver() {
		if (over) return;
		ArrayList<Integer> teams = new ArrayList<>();
		for (Player p : players()) {
			if (p.alive && !teams.contains(sideOf(p))) teams.add(sideOf(p));
		}
		if (teams.size() > 1) return;

		over = true;
		DuelCrash.sessionOpen(false);
		Player me = me();
		boolean won = me != null && !teams.isEmpty() && sideOf(me) == teams.get(0);
		StringBuilder winners = new StringBuilder();
		for (Player p : players()) {
			if (!teams.isEmpty() && sideOf(p) == teams.get(0)) {
				if (winners.length() > 0) winners.append(" and ");
				winners.append(p.id == myId ? "you" : describe(p));
			}
		}
		DuelTestDriver.progress("MATCH OVER: " + (won ? "won" : "lost") + " (winners: " + winners + ")");
		GameScene.show(new WndDuelResult(won ? "Victory!" : "Defeat",
				won ? (mode == MODE_TEAMS ? "Your team wins!" : "You are the last one standing!")
					: "The winner: " + winners + ".", false));
	}

	/** The host ended the match or the connection dropped. Render thread. */
	static void matchEnded(String why) {
		if (over) return;
		over = true;
		DuelCrash.sessionOpen(false);
		DuelTestDriver.log("MATCH ENDED: " + why);
		GameScene.show(new WndDuelResult("Match ended", why, false));
	}

	/** Called from Hero.die while a match is active, instead of the normal game-over. */
	public static void onLocalDeath() {
		Player me = me();
		if (me == null || !me.alive) return;
		Bundle b = new Bundle();
		b.put("type", "dead");
		b.put("who", myId);
		DuelNet.broadcast(b);
		DuelTestDriver.progress("I was knocked out");
		myTurn = false;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				playerOut(myId, false);
				if (!over) {
					GameScene.show(new WndDuelResult("Knocked out",
							"You're out of this match. Keep watching to see who wins, or leave.", true));
				}
			}
		});
	}

	// ------------------------------------------------------------------ leaving

	/**
	 * Leaves the match (back to the title screen, or the app is leaving the arena some other
	 * way). If the match wasn't finished, the others are told this player left.
	 */
	public static void leave() {
		final boolean wasPlaying = active && !over;
		final boolean wasHost = host;
		final int id = myId;
		active = false;
		inLobby = false;
		over = true;
		controller = null;
		lobby = null;
		incoming.clear();
		keepAlive(false);
		DuelController.resetPlayback();
		DuelFx.uninstall();
		DuelMirror.reset();

		Thread t = new Thread("Duel leave") {
			@Override
			public void run() {
				if (wasPlaying) {
					Bundle b = new Bundle();
					if (wasHost) {
						b.put("type", "end");
						b.put("why", "The host left, so the match has ended.");
					} else {
						b.put("type", "left");
						b.put("who", id);
					}
					DuelNet.broadcastAndClose(b);
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
