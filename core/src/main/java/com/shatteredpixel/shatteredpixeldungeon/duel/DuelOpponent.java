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
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

/**
 * Another player's hero as it appears on this phone. It's a puppet: it never decides
 * anything. It moves and swings only when its owner's turn is played back, and anything
 * my hero does to it (hits, misses, damage, pushes) is recorded and sent to its owner.
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

	private DuelGame.Player owner;
	private boolean ally = false;

	public void setup(DuelGame.Player owner, int spawn, boolean ally) {
		this.owner = owner;
		this.ally = ally;
		this.pos = spawn;
		if (owner.ghost != null) {
			owner.ghost.pos = spawn;
			HT = Math.max(1, owner.ghost.HT);
		}
		HP = HT;
		alignment = ally ? Alignment.ALLY : Alignment.ENEMY;
		enemySeen = true;
	}

	public int ownerId() {
		return owner == null ? -1 : owner.id;
	}

	@Override
	public CharSprite sprite() {
		return new DuelOpponentSprite(owner == null ? com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.WARRIOR : owner.cls,
				owner == null ? 0 : owner.tier);
	}

	@Override
	public String name() {
		if (owner == null) return "Opponent";
		return owner.name + (ally ? " (ally)" : "");
	}

	@Override
	public String description() {
		return ally
				? "Your teammate. Their phone controls this hero."
				: "Another player's hero. Their phone controls it and rolls its attacks.";
	}

	// ------------------------------------------------------------- defence, from their real stats

	@Override
	public int defenseSkill(Char enemy) {
		if (owner != null && owner.ghost != null) {
			try {
				return owner.ghost.defenseSkill(enemy);
			} catch (Exception ignored) {}
		}
		return 5;
	}

	@Override
	public int drRoll() {
		if (owner != null && owner.ghost != null) {
			try {
				return owner.ghost.drRoll();
			} catch (Exception ignored) {}
		}
		return 0;
	}

	@Override
	public String defenseVerb() {
		//only called when an attack misses this figure
		if (Dungeon.hero != null) DuelGame.recordSwing(ownerId(), false);
		if (owner != null && owner.ghost != null) {
			try {
				return owner.ghost.defenseVerb();
			} catch (Exception ignored) {}
		}
		return "dodged";
	}

	@Override
	public int defenseProc(Char enemy, int damage) {
		if (enemy == Dungeon.hero) DuelGame.recordSwing(ownerId(), true);
		return super.defenseProc(enemy, damage);
	}

	@Override
	public boolean surprisedBy(Char enemy, boolean attacking) {
		//no free sneak attacks just for the fight starting; invisibility still counts
		return enemy == Dungeon.hero && enemy.invisible > 0 && (!attacking || enemy.canSurpriseAttack());
	}

	@Override
	public void damage(int dmg, Object src) {
		//fire, gas, bleeding etc. hurt the real hero on its own phone, from the copied arena and buffs
		boolean lingering = src instanceof Blob || src instanceof Buff;
		if (!lingering && DuelGame.active() && !DuelGame.isOver() && owner != null && owner.alive) {
			DuelGame.recordDamage(ownerId(), dmg);
		}
		super.damage(dmg, src);
	}

	// ------------------------------------------------------------- buffs

	/** True while copying buffs from another phone, so they aren't sent back out. */
	boolean applyingSnapshot = false;

	@Override
	public synchronized boolean add(Buff buff) {
		if (!super.add(buff)) return false;
		//buffs on a figure are for show: they never tick here (the real one ticks on its owner's phone)
		Actor.remove(buff);
		if (!applyingSnapshot) DuelGame.buffInflicted(ownerId(), buff);
		return true;
	}

	@Override
	public void die(Object cause) {
		//only the owner's phone decides when their hero is dead; keep the figure standing until it says so
		HP = Math.max(HP, 1);
	}

	@Override
	public boolean interact(Char c) {
		//teammates don't swap places (that would only happen on one phone)
		return true;
	}

	// ------------------------------------------------------------- driven by playback

	public void placeAt(int cell) {
		pos = cell;
		if (owner != null && owner.ghost != null) owner.ghost.pos = cell;
		Dungeon.level.occupyCell(this);
	}

	/** End of the owner's turn: their phone's word on where they are and how healthy. */
	public void syncFrom(int newPos, int hp, int ht) {
		HT = Math.max(1, ht);
		HP = Math.max(1, Math.min(HT, hp));
		if (owner != null && owner.ghost != null) {
			owner.ghost.HT = HT;
			owner.ghost.HP = HP;
		}
		if (newPos != pos && newPos >= 0 && newPos < Dungeon.level.length()) {
			int to = DuelController.EventPlayer.freeCellNear(newPos, this);
			if (to >= 0) {
				placeAt(to);
				if (sprite != null) sprite.place(to);
			}
		}
	}

	/** Someone else hit this player; the real number lands on their phone. */
	public void showHit(int amount) {
		HP = Math.max(1, HP - amount);
		if (sprite != null && sprite.visible) {
			sprite.showStatus(CharSprite.NEGATIVE, Integer.toString(amount));
			sprite.flash();
		}
	}

	public void knockOut() {
		if (sprite != null) sprite.die();
		destroy();
	}

	@Override
	public void destroy() {
		super.destroy();
	}

	// ------------------------------------------------------------- never acts on its own

	@Override
	protected boolean act() {
		//status effects from my phone (sleep, charm, corruption...) can't reach the real hero,
		//so they mustn't change how this figure looks or behaves either
		state = HUNTING;
		alignment = ally ? Alignment.ALLY : Alignment.ENEMY;
		paralysed = 0;
		if (fieldOfView == null || fieldOfView.length != Dungeon.level.length()) {
			fieldOfView = new boolean[Dungeon.level.length()];
		}
		Dungeon.level.updateFieldOfView(this, fieldOfView);
		internalTime = true;
		timeToNow();
		super.spendConstant(TICK);
		internalTime = false;
		return true;
	}

	private boolean internalTime = false;

	@Override
	protected void spend(float time) {
		//ignore: slow, chill, haste etc. on this figure only exist on one phone
	}

	@Override
	protected void spendConstant(float time) {
		if (internalTime) super.spendConstant(time);
	}

	@Override
	protected void postpone(float time) {
		//ignore
	}

	@Override
	public void onAttackComplete() {
		//swings are only animations here; damage comes from the owner's turn events
	}
}
