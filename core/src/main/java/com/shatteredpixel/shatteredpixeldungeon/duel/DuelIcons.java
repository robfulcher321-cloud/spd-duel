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
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.watabou.noosa.Image;
import com.watabou.utils.Reflection;

/** Game artwork for the duel menus, plus a button that lines icons up in a column. */
public class DuelIcons {

	/** Item picture. Potions, scrolls and rings get their blank sprite; their type symbol is the overlay. */
	public static Image item(Class<?> cl) {
		try {
			Item it = (Item) Reflection.newInstance(cl);
			if (it instanceof Potion)  return new ItemSprite(ItemSpriteSheet.POTION_HOLDER);
			if (it instanceof Scroll)  return new ItemSprite(ItemSpriteSheet.SCROLL_HOLDER);
			if (it instanceof Ring)    return new ItemSprite(ItemSpriteSheet.RING_HOLDER);
			return new ItemSprite(it.image());
		} catch (Exception e) {
			return new ItemSprite(ItemSpriteSheet.SOMETHING);
		}
	}

	/** The small type symbol the game draws on identified potions/scrolls/rings, or null. */
	public static Image overlay(Class<?> cl) {
		try {
			Item it = (Item) Reflection.newInstance(cl);
			if (it.icon < 0) return null;
			Image img = new Image(Assets.Sprites.ITEM_ICONS);
			img.frame(ItemSpriteSheet.Icons.film.get(it.icon));
			return img;
		} catch (Exception e) {
			return null;
		}
	}

	public static Image enchant(Class<?> cl) {
		try {
			Weapon.Enchantment e = (Weapon.Enchantment) Reflection.newInstance(cl);
			return new ItemSprite(ItemSpriteSheet.SWORD, e.glowing());
		} catch (Exception e) {
			return new ItemSprite(ItemSpriteSheet.SWORD);
		}
	}

	public static Image glyph(Class<?> cl) {
		try {
			Armor.Glyph g = (Armor.Glyph) Reflection.newInstance(cl);
			return new ItemSprite(ItemSpriteSheet.ARMOR_MAIL, g.glowing());
		} catch (Exception e) {
			return new ItemSprite(ItemSpriteSheet.ARMOR_MAIL);
		}
	}

	public static Image hero(HeroClass cls, int armorTier) {
		return HeroSprite.avatar(cls, armorTier);
	}

	public static Image random() {
		return Icons.get(Icons.SHUFFLE);
	}

	/**
	 * A button with a fixed 16px icon column on the left, so text in a list lines up
	 * whether or not a row has a picture. An optional small overlay sits on the icon's corner.
	 */
	public static class Row extends RedButton {

		private static final int SLOT = 16;
		private Image overlay;

		public Row(String label, Image icon, Image overlay) {
			super(label, 6);
			multiline = true;
			if (icon != null) icon(icon);
			if (overlay != null) {
				this.overlay = overlay;
				add(overlay);
			}
		}

		private boolean passive = false;

		/** For rows inside a DuelScrollList: the list decides what was tapped, so the row ignores touches. */
		public Row passive() {
			passive = true;
			hotArea.active = false;
			return this;
		}

		/** Brief highlight when the list reports this row was tapped. */
		public void flash() {
			bg.brightness(1.2f);
			flashTime = 0.15f;
		}

		private float flashTime = 0;

		@Override
		public void update() {
			super.update();
			if (passive) hotArea.active = false;
			if (flashTime > 0 && (flashTime -= com.watabou.noosa.Game.elapsed) <= 0) {
				bg.resetColor();
			}
		}

		@Override
		protected void layout() {
			super.layout();

			float slotX = x + bg.marginLeft() + 1;

			if (icon != null) {
				//shrink anything bigger than the slot (nothing in the game is, but be safe)
				float s = Math.min(1f, Math.min(SLOT / Math.max(1f, icon.width), (height - 2) / Math.max(1f, icon.height)));
				icon.scale.set(s);
				icon.x = slotX + (SLOT - icon.width()) / 2f;
				icon.y = y + (height - icon.height()) / 2f;
				PixelScene.align(icon);
			}

			if (overlay != null) {
				overlay.x = slotX + SLOT - overlay.width();
				overlay.y = y + height - overlay.height() - 2;
				PixelScene.align(overlay);
			}

			if (text != null) {
				float textX = slotX + SLOT + 3;
				text.maxWidth((int) (x + width - textX - bg.marginRight()));
				text.setPos(textX, y + (height - text.height()) / 2f);
				PixelScene.align(text);
			}
		}
	}
}
