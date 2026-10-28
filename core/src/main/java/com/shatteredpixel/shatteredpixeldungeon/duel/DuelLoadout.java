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
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

/**
 * The gear a player picked for their test fighter. A null choice means "keep the class
 * default" (or "none" where there is no default); a null consumable means "random".
 * Upgrade levels are fixed by the fighter's stage so the two players stay on the same budget.
 */
public class DuelLoadout {

	public Class<?> weapon;         //null = class starting weapon
	public Class<?> enchant;        //null = none
	public Class<?> glyph;          //null = none
	public Class<?> ring;           //null = none
	public Class<?> wand1, wand2;   //null = none
	public boolean keepClassArtifact = true;
	public Class<?> artifact;       //used when keepClassArtifact is false; null = none
	public final Class<?>[] consumables = new Class<?>[3]; //null = random
	public int extraConsumables = 0;  //more random ones on top (the mystery fighter gets 2)

	/** Everything random, for the mystery fighter: gear suited to the stage and 5 random items. */
	public static DuelLoadout randomFor(boolean endgame) {
		DuelLoadout l = new DuelLoadout();
		ArrayList<Class<?>> wep = new ArrayList<>();
		Generator.Category[] tiers = endgame
				? new Generator.Category[]{Generator.Category.WEP_T3, Generator.Category.WEP_T4, Generator.Category.WEP_T5}
				: new Generator.Category[]{Generator.Category.WEP_T2, Generator.Category.WEP_T3};
		for (Generator.Category c : tiers) for (Class<?> cl : c.classes) wep.add(cl);
		l.weapon = Random.element(wep);
		l.enchant = Random.element(enchants());
		l.glyph = Random.element(glyphs());
		l.ring = Random.element(of(Generator.Category.RING));
		ArrayList<Class<?>> wands = of(Generator.Category.WAND);
		l.wand1 = Random.element(wands);
		wands.remove(l.wand1);
		l.wand2 = Random.element(wands);
		if (Random.Int(2) == 0) {
			l.keepClassArtifact = false;
			l.artifact = Random.element(of(Generator.Category.ARTIFACT));
		}
		l.extraConsumables = 2;
		return l;
	}

	/** Remembered while the app is open, so a rematch starts from the same picks. */
	public static DuelLoadout last = new DuelLoadout();

	// ------------------------------------------------------------------ choices

	public static ArrayList<Class<?>> weapons() {
		ArrayList<Class<?>> list = new ArrayList<>();
		for (Generator.Category c : new Generator.Category[]{
				Generator.Category.WEP_T1, Generator.Category.WEP_T2, Generator.Category.WEP_T3,
				Generator.Category.WEP_T4, Generator.Category.WEP_T5}) {
			for (Class<?> cl : c.classes) list.add(cl);
		}
		return list;
	}

	public static ArrayList<Class<?>> enchants() {
		ArrayList<Class<?>> list = new ArrayList<>();
		for (Class<?> cl : Weapon.Enchantment.common) list.add(cl);
		for (Class<?> cl : Weapon.Enchantment.uncommon) list.add(cl);
		for (Class<?> cl : Weapon.Enchantment.rare) list.add(cl);
		return list;
	}

	public static ArrayList<Class<?>> glyphs() {
		ArrayList<Class<?>> list = new ArrayList<>();
		for (Class<?> cl : Armor.Glyph.common) list.add(cl);
		for (Class<?> cl : Armor.Glyph.uncommon) list.add(cl);
		for (Class<?> cl : Armor.Glyph.rare) list.add(cl);
		return list;
	}

	public static ArrayList<Class<?>> of(Generator.Category c) {
		ArrayList<Class<?>> list = new ArrayList<>();
		for (Class<?> cl : c.classes) list.add(cl);
		return list;
	}

	public static ArrayList<Class<?>> consumablePool() {
		ArrayList<Class<?>> list = new ArrayList<>();
		list.addAll(of(Generator.Category.POTION));
		list.addAll(of(Generator.Category.SCROLL));
		list.addAll(of(Generator.Category.SEED));
		list.addAll(of(Generator.Category.STONE));
		return list;
	}

	// ------------------------------------------------------------------ names for the menus

	public static String itemName(Class<?> cl) {
		try {
			return Messages.capitalize(Messages.get(cl, "name"));
		} catch (Exception e) {
			return cl.getSimpleName();
		}
	}

	public static String enchantName(Class<?> cl) {
		try {
			Weapon.Enchantment e = (Weapon.Enchantment) Reflection.newInstance(cl);
			return Messages.capitalize(e.name("weapon").trim());
		} catch (Exception e) {
			return cl.getSimpleName();
		}
	}

	public static String glyphName(Class<?> cl) {
		try {
			Armor.Glyph g = (Armor.Glyph) Reflection.newInstance(cl);
			return Messages.capitalize(g.name("armour").trim());
		} catch (Exception e) {
			return cl.getSimpleName();
		}
	}

	// ------------------------------------------------------------------ applying

	public void apply(Hero h, boolean endgame) {
		int wepLvl  = endgame ? 10 : 3;
		int ringLvl = endgame ? 3 : 1;
		int wandLvl = endgame ? 3 : 2;
		int artLvl  = endgame ? 10 : 3;

		//weapon (the class weapon was already upgraded by the fighter builder)
		if (weapon != null) {
			try {
				KindOfWeapon w = (KindOfWeapon) Reflection.newInstance(weapon);
				w.upgrade(wepLvl);
				w.identify();
				if (h.belongings.weapon != null) {
					Dungeon.quickslot.clearItem(h.belongings.weapon);
				}
				h.belongings.weapon = w;
				w.activate(h);
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
			}
		}
		if (enchant != null && h.belongings.weapon instanceof Weapon) {
			((Weapon) h.belongings.weapon).enchant((Weapon.Enchantment) Reflection.newInstance(enchant));
		}

		//armour glyph
		if (glyph != null && h.belongings.armor != null) {
			h.belongings.armor.inscribe((Armor.Glyph) Reflection.newInstance(glyph));
		}

		//ring
		if (ring != null) {
			try {
				Ring r = (Ring) Reflection.newInstance(ring);
				r.upgrade(ringLvl);
				r.identify();
				h.belongings.ring = r;
				r.activate(h);
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
			}
		}

		//wands go in the backpack and onto the quickslots
		for (Class<?> wc : new Class<?>[]{wand1, wand2}) {
			if (wc == null) continue;
			try {
				Wand wand = (Wand) Reflection.newInstance(wc);
				wand.upgrade(wandLvl);
				wand.identify();
				wand.curCharges = wand.maxCharges;
				wand.collect();
				addToQuickslot(wand);
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
			}
		}

		//artifact
		if (!keepClassArtifact) {
			Artifact old = h.belongings.artifact instanceof Artifact ? (Artifact) h.belongings.artifact : null;
			if (old != null) {
				Dungeon.quickslot.clearItem(old);
				//remove the old artifact's passive effect (its buffs are inner classes of it)
				for (Buff b : h.buffs()) {
					if (b.getClass().getEnclosingClass() == old.getClass()) b.detach();
				}
				h.belongings.artifact = null;
			}
			if (artifact != null) {
				try {
					Artifact a = (Artifact) Reflection.newInstance(artifact);
					try {
						a.transferUpgrade(artLvl);
					} catch (Exception e) {
						//some artifacts can't level up outside a dungeon; they just stay at +0
					}
					a.identify();
					h.belongings.artifact = a;
					a.activate(h);
					addToQuickslot(a);
				} catch (Exception e) {
					ShatteredPixelDungeon.reportException(e);
				}
			}
		}

		//three consumables (plus any extra random ones)
		ArrayList<Class<?>> pool = consumablePool();
		ArrayList<Class<?>> picks = new ArrayList<>();
		for (Class<?> c : consumables) picks.add(c);
		for (int i = 0; i < extraConsumables; i++) picks.add(null);
		for (Class<?> c : picks) {
			Class<?> pick = c != null ? c : Random.element(pool);
			try {
				Item it = (Item) Reflection.newInstance(pick);
				it.identify();
				it.collect();
			} catch (Exception e) {
				ShatteredPixelDungeon.reportException(e);
			}
		}
	}

	private static void addToQuickslot(Item item) {
		if (Dungeon.quickslot.contains(item)) return;
		for (int i = 0; i < com.shatteredpixel.shatteredpixeldungeon.QuickSlot.SIZE; i++) {
			if (Dungeon.quickslot.getItem(i) == null) {
				Dungeon.quickslot.setSlot(i, item);
				return;
			}
		}
	}
}
