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
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndHeroInfo;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoSubclass;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.utils.Reflection;

/**
 * The little "i" buttons in the duel menus, and the explanation windows they open.
 * Uses the game's own info screens wherever one exists.
 */
public class DuelInfo {

	public static IconButton button(final Runnable onClick) {
		return new IconButton(Icons.get(Icons.INFO)) {
			@Override
			protected void onClick() {
				onClick.run();
			}
		};
	}

	private static void show(Window w) {
		Game.scene().addToFront(w);
	}

	/**
	 * The game's talent icons (e.g. Heroic Energy) look up "the current hero's class", which
	 * doesn't exist on the title screen. Point them at the class being viewed while the window is built.
	 */
	private interface Build { Window make(); }

	private static void showForClass(HeroClass cls, Build b) {
		//these menus are only reachable from the title screen, before a fighter is built, so
		//no hero is in play; leave the class set so tabs opened later in the window also work
		Dungeon.hero = null;
		GamesInProgress.selectedClass = cls;
		show(b.make());
	}

	public static void heroClass(final HeroClass cls) {
		showForClass(cls, new Build() { public Window make() { return new WndHeroInfo(cls); } });
	}

	public static void subclass(final HeroClass cls, final HeroSubClass sub) {
		showForClass(cls, new Build() { public Window make() { return new WndInfoSubclass(cls, sub); } });
	}

	public static void ability(final HeroClass cls, final ArmorAbility ability) {
		showForClass(cls, new Build() { public Window make() { return new WndInfoArmorAbility(cls, ability); } });
	}

	public static void message(Image icon, String title, String text) {
		show(new WndTitledMessage(icon, title, text));
	}

	/** The game's full item description; falls back to a plain description if the item can't describe itself here. */
	public static void item(Class<?> cl) {
		Item it;
		try {
			it = (Item) Reflection.newInstance(cl);
		} catch (Exception e) {
			return;
		}
		try {
			try {
				it.identify();
			} catch (Exception ignored) {
				//some item types can't be identified outside a run; the plain description still works
			}
			show(new WndInfoItem(it));
		} catch (Exception e) {
			String desc;
			try {
				desc = it.desc();
			} catch (Exception e2) {
				desc = "";
			}
			message(DuelIcons.item(cl), DuelLoadout.itemName(cl), desc);
		}
	}

	public static void enchant(Class<?> cl) {
		try {
			Weapon.Enchantment e = (Weapon.Enchantment) Reflection.newInstance(cl);
			message(DuelIcons.enchant(cl), DuelLoadout.enchantName(cl), e.desc());
		} catch (Exception ignored) {
		}
	}

	public static void glyph(Class<?> cl) {
		try {
			Armor.Glyph g = (Armor.Glyph) Reflection.newInstance(cl);
			message(DuelIcons.glyph(cl), DuelLoadout.glyphName(cl), g.desc());
		} catch (Exception ignored) {
		}
	}

	public static String capital(String s) {
		return Messages.capitalize(s);
	}
}
