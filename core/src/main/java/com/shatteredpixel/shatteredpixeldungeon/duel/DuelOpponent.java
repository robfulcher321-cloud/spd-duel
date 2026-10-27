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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

/**
 * The other player's hero as it appears on this phone. It never decides anything itself:
 * on its turn it reports what my hero did, then waits for the other phone's report and
 * replays it.
 */
public class DuelOpponent extends Mob {

	{
		spriteClass = DuelOpponentSprite.class;

		HP = HT = 20;
		defenseSkill = 0;
		EXP = 0;
		maxLvl = -1;

		state = HUNTING;
		alignment = Alignment.ENEMY;
	}

	private Hero ghost;
	private volatile boolean waiting = false;
	private boolean announced = false;
	private boolean reportedSinceLastTurn;

	private int pendingDamage = 0;
	private boolean pendingHit = false;

	public void setup(Hero ghost, int spawn, boolean movesFirst) {
		this.ghost = ghost;
		this.pos = spawn;
		ghost.pos = spawn;
		HT = Math.max(1, ghost.HT);
		HP = HT;
		enemySeen = true;

		if (movesFirst) {
			//on the joining phone the opponent (the host) acts before my hero each round,
			//and there's nothing of mine to report before their first move
			actPriority = HERO_PRIO + 1;
			reportedSinceLastTurn = true;
		} else {
			reportedSinceLastTurn = false;
		}
	}

	public boolean isWaiting() {
		return waiting;
	}

	@Override
	public String name() {
		return DuelGame.opponentName();
	}

	@Override
	public String description() {
		return "Another player's hero. Every swing they take is rolled on their phone.";
	}

	// ------------------------------------------------------------- defence, from their real stats

	@Override
	public int defenseSkill(Char enemy) {
		if (ghost != null) {
			try {
				return ghost.defenseSkill(enemy);
			} catch (Exception ignored) {}
		}
		return 5;
	}

	@Override
	public int drRoll() {
		if (ghost != null) {
			try {
				return ghost.drRoll();
			} catch (Exception ignored) {}
		}
		return 0;
	}

	@Override
	public String defenseVerb() {
		//only called when an attack misses this figure
		if (Dungeon.hero != null) DuelGame.recordAttack(false);
		if (ghost != null) {
			try {
				return ghost.defenseVerb();
			} catch (Exception ignored) {}
		}
		return "dodged";
	}

	@Override
	public int defenseProc(Char enemy, int damage) {
		if (enemy == Dungeon.hero) DuelGame.recordAttack(true);
		return super.defenseProc(enemy, damage);
	}

	@Override
	public boolean surprisedBy(Char enemy, boolean attacking) {
		//no free sneak attacks just for opening the fight; invisibility still counts
		return enemy == Dungeon.hero && enemy.invisible > 0 && (!attacking || enemy.canSurpriseAttack());
	}

	@Override
	public void damage(int dmg, Object src) {
		if (DuelGame.active() && !DuelGame.isOver()) {
			DuelGame.recordDamageDealt(dmg);
		}
		super.damage(dmg, src);
	}

	@Override
	public void die(Object cause) {
		//only the other phone decides when its hero is dead; keep the figure standing until it says so
		HP = Math.max(HP, 1);
	}

	// ------------------------------------------------------------- turns

	@Override
	protected boolean act() {
		try {
			return doAct();
		} catch (Throwable t) {
			//never let a duel bug freeze the game: report it and carry on
			com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.reportException(t);
			DuelTestDriver.log("ERROR in opponent act: " + t);
			GLog.n("Duel error: " + t.getClass().getSimpleName() + " " + t.getMessage());
			waiting = false;
			spend(TICK);
			return true;
		}
	}

	private boolean doAct() {
		DuelTestDriver.log("opponent act at time " + Actor.now() + " (reported=" + reportedSinceLastTurn + ", waiting=" + waiting + ")");
		if (fieldOfView == null || fieldOfView.length != Dungeon.level.length()) {
			fieldOfView = new boolean[Dungeon.level.length()];
		}
		Dungeon.level.updateFieldOfView(this, fieldOfView);

		if (!announced) {
			announced = true;
			GLog.h("Duel against " + name() + "! " + (DuelGame.isHost() ? "You moved first." : "They move first."));
		}

		if (!DuelGame.active() || DuelGame.isOver()) {
			waiting = false;
			spend(TICK);
			return true;
		}

		if (!reportedSinceLastTurn) {
			DuelGame.sendMyTurn();
			reportedSinceLastTurn = true;
		}

		Bundle turn = DuelGame.pollTurn();
		if (turn == null) {
			if (!waiting) {
				waiting = true;
				GLog.i("Waiting for " + name() + "...");
			}
			//stay the current actor until the message arrives; DuelGame calls next() then
			return false;
		}

		waiting = false;
		reportedSinceLastTurn = false;
		DuelTestDriver.progress("replaying their turn " + turn.getInt("seq"));
		return replay(turn);
	}

	private boolean replay(Bundle turn) {
		HT = Math.max(1, turn.getInt("ht"));
		HP = Math.max(1, Math.min(HT, turn.getInt("hp")));
		if (ghost != null) {
			ghost.HT = HT;
			ghost.HP = HP;
		}

		moveTo(turn.getInt("pos"));

		int dmg = turn.getInt("dmg");
		boolean swung = turn.getBoolean("attacked");
		boolean hit = turn.getBoolean("hit");

		Hero me = Dungeon.hero;
		if (swung && me != null && sprite != null && sprite.visible) {
			pendingDamage = dmg;
			pendingHit = hit;
			sprite.attack(me.pos);
			return false; //onAttackComplete finishes the turn
		}

		applyIncoming(dmg, swung, hit);
		spend(TICK);
		return true;
	}

	private void moveTo(int newPos) {
		if (newPos == pos || newPos < 0 || newPos >= Dungeon.level.length()) return;

		Char occupant = Actor.findChar(newPos);
		if (occupant != null && occupant != this) {
			//my hero is standing there (e.g. something on my phone knocked us around); stand next to it
			int best = -1;
			for (int n : PathFinder.NEIGHBOURS8) {
				int c = newPos + n;
				if (c >= 0 && c < Dungeon.level.length() && Dungeon.level.passable[c] && Actor.findChar(c) == null) {
					best = c;
					break;
				}
			}
			if (best == -1) return;
			newPos = best;
		}

		int old = pos;
		pos = newPos;
		if (ghost != null) ghost.pos = newPos;
		if (sprite != null) {
			if (Dungeon.level.adjacent(old, newPos)) {
				sprite.move(old, newPos);
			} else {
				sprite.place(newPos);
			}
		}
		Dungeon.level.occupyCell(this);
	}

	@Override
	public void onAttackComplete() {
		applyIncoming(pendingDamage, true, pendingHit);
		pendingDamage = 0;
		pendingHit = false;
		spend(TICK);
		next();
	}

	private void applyIncoming(int dmg, boolean swung, boolean hit) {
		Hero me = Dungeon.hero;
		if (me == null || !me.isAlive()) return;

		if (swung && !hit && dmg <= 0) {
			if (me.sprite != null) me.sprite.showStatus(CharSprite.NEUTRAL, me.defenseVerb());
			Sample.INSTANCE.play(Assets.Sounds.MISS);
			return;
		}

		if (dmg > 0) {
			if (swung) Sample.INSTANCE.play(Assets.Sounds.HIT);
			me.damage(dmg, this);
		}
	}
}
