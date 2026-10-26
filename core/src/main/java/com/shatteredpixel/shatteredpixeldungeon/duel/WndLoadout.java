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

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Cudgel;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gloves;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Rapier;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/** Lets the player choose their test fighter's gear before the duel. */
public class WndLoadout extends Window {

	public interface Done {
		void onReady(DuelLoadout loadout);
	}

	private static final int WIDTH_P = 134;
	private static final int WIDTH_L = 164;
	private static final int ROW = 18;

	private final HeroClass cls;
	private final boolean endgame;
	private final Done done;
	private final DuelLoadout lo;

	private final ArrayList<Component> parts = new ArrayList<>();
	private Component content;

	public WndLoadout(HeroClass cls, boolean endgame, Done done) {
		super();
		this.cls = cls;
		this.endgame = endgame;
		this.done = done;
		this.lo = DuelLoadout.last;
		rebuild();
	}

	private static int w() {
		return PixelScene.landscape() ? WIDTH_L : WIDTH_P;
	}

	private Class<?> classWeapon() {
		switch (cls) {
			case MAGE:     return MagesStaff.class;
			case ROGUE:    return Dagger.class;
			case HUNTRESS: return Gloves.class;
			case DUELIST:  return Rapier.class;
			case CLERIC:   return Cudgel.class;
			case WARRIOR: default: return WornShortsword.class;
		}
	}

	private Class<?> classArtifact() {
		if (cls == HeroClass.ROGUE) return CloakOfShadows.class;
		if (cls == HeroClass.CLERIC) return HolyTome.class;
		return null;
	}

	// ------------------------------------------------------------------ option kinds

	private enum Kind { ITEM, ENCHANT, GLYPH }

	private static String label(Kind k, Class<?> c) {
		switch (k) {
			case ENCHANT: return DuelLoadout.enchantName(c);
			case GLYPH:   return DuelLoadout.glyphName(c);
			default:      return DuelLoadout.itemName(c);
		}
	}

	private static Image icon(Kind k, Class<?> c) {
		if (c == null) return null;
		switch (k) {
			case ENCHANT: return DuelIcons.enchant(c);
			case GLYPH:   return DuelIcons.glyph(c);
			default:      return DuelIcons.item(c);
		}
	}

	private static Image overlay(Kind k, Class<?> c) {
		return (k == Kind.ITEM && c != null) ? DuelIcons.overlay(c) : null;
	}

	private static void info(Kind k, Class<?> c) {
		if (c == null) return;
		switch (k) {
			case ENCHANT: DuelInfo.enchant(c); break;
			case GLYPH:   DuelInfo.glyph(c); break;
			default:      DuelInfo.item(c); break;
		}
	}

	/** Adds the "i" button at the right end of a row, if there is something to explain. */
	private static void addInfo(Component parent, float y, int w, final Kind k, final Class<?> c) {
		if (c == null) return;
		IconButton b = DuelInfo.button(new Runnable() {
			public void run() { info(k, c); }
		});
		b.setRect(w - ROW, y, ROW, ROW);
		parent.add(b);
	}

	private static Image defaultImage(Kind k, String defaultLabel, Class<?> defaultIcon) {
		if (defaultIcon != null) return icon(k, defaultIcon);
		if ("Random".equals(defaultLabel)) return DuelIcons.random();
		return null;
	}

	// ------------------------------------------------------------------ layout

	private void rebuild() {
		for (Component c : parts) {
			remove(c);
			c.destroy();
		}
		parts.clear();

		int w = w();
		float y = 1;

		RenderedTextBlock title = PixelScene.renderTextBlock("Choose your gear", 9);
		title.hardlight(TITLE_COLOR);
		title.maxWidth(w);
		title.setPos(0, y);
		add(title);
		parts.add(title);
		y = title.bottom() + 2;

		RenderedTextBlock info = PixelScene.renderTextBlock(6);
		info.text(endgame
				? "Weapon +10, ring +3, wands +3, artifact maxed."
				: "Weapon +3, ring +1, wands +2, artifact +3.", w);
		info.setPos(0, y);
		add(info);
		parts.add(info);
		final float top = info.bottom() + 3;

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		parts.add(pane);
		content = pane.content();
		y = 0;

		y = slot(y, "Weapon", Kind.ITEM, lo.weapon, "Class weapon", classWeapon(), DuelLoadout.weapons(),
				new Picked() { public void on(Class<?> c) { lo.weapon = c; } });
		y = slot(y, "Enchant", Kind.ENCHANT, lo.enchant, "None", null, DuelLoadout.enchants(),
				new Picked() { public void on(Class<?> c) { lo.enchant = c; } });
		y = slot(y, "Glyph", Kind.GLYPH, lo.glyph, "None", null, DuelLoadout.glyphs(),
				new Picked() { public void on(Class<?> c) { lo.glyph = c; } });
		y = slot(y, "Ring", Kind.ITEM, lo.ring, "None", null, DuelLoadout.of(Generator.Category.RING),
				new Picked() { public void on(Class<?> c) { lo.ring = c; } });
		y = slot(y, "Wand 1", Kind.ITEM, lo.wand1, "None", null, DuelLoadout.of(Generator.Category.WAND),
				new Picked() { public void on(Class<?> c) { lo.wand1 = c; } });
		y = slot(y, "Wand 2", Kind.ITEM, lo.wand2, "None", null, DuelLoadout.of(Generator.Category.WAND),
				new Picked() { public void on(Class<?> c) { lo.wand2 = c; } });

		Class<?> classArt = classArtifact();
		Class<?> artChosen = lo.keepClassArtifact ? null : lo.artifact;
		String artDefault = classArt != null ? "Class artifact" : "None";
		y = slot(y, "Artifact", Kind.ITEM, artChosen, artDefault, classArt, DuelLoadout.of(Generator.Category.ARTIFACT),
				new Picked() {
					public void on(Class<?> c) {
						lo.keepClassArtifact = c == null;
						lo.artifact = c;
					}
				});

		for (int i = 0; i < 3; i++) {
			final int idx = i;
			y = slot(y, "Item " + (i + 1), Kind.ITEM, lo.consumables[i], "Random", null, DuelLoadout.consumablePool(),
					new Picked() { public void on(Class<?> c) { lo.consumables[idx] = c; } });
		}

		content.setRect(0, 0, w, y);
		int maxH = (int) (PixelScene.uiCamera.height * 0.8f);
		final float paneH = Math.min(y, maxH - top - 22);
		y = top + paneH;

		RedButton ready = new RedButton("Ready to fight") {
			@Override
			protected void onClick() {
				hide();
				done.onReady(lo);
			}
		};
		ready.textColor(TITLE_COLOR);
		ready.setRect(0, y + 3, w, 18);
		add(ready);
		parts.add(ready);
		y = ready.bottom();

		resize(w, (int) y);
		//the scroll area works out its screen position from the window's camera, which only
		//settles on resize, so place it after resizing
		pane.setRect(0, top, w, paneH);
	}

	/** One loadout line, e.g. [sword picture] "Weapon: Greatsword". */
	private float slot(float y, final String name, final Kind kind, Class<?> current,
	                   final String defaultLabel, final Class<?> defaultIcon,
	                   final ArrayList<Class<?>> options, final Picked picked) {
		Image img;
		Image ovl;
		String value;
		if (current != null) {
			img = icon(kind, current);
			ovl = overlay(kind, current);
			value = label(kind, current);
		} else {
			img = defaultImage(kind, defaultLabel, defaultIcon);
			ovl = defaultIcon != null ? overlay(kind, defaultIcon) : null;
			value = defaultLabel;
		}

		DuelIcons.Row b = new DuelIcons.Row(name + ": " + value, img, ovl) {
			@Override
			protected void onClick() {
				pick(name, kind, defaultLabel, defaultIcon, options, picked);
			}
		};
		Class<?> explain = current != null ? current : defaultIcon;
		b.setRect(0, y, explain != null ? w() - ROW : w(), ROW);
		content.add(b);
		addInfo(content, y, w(), kind, explain);
		return y + ROW + 1;
	}

	// ------------------------------------------------------------------ picker

	private interface Picked {
		void on(Class<?> c);
	}

	private void pick(String title, final Kind kind, final String defaultLabel, final Class<?> defaultIcon,
	                  final ArrayList<Class<?>> options, final Picked picked) {
		Game.scene().addToFront(new WndPickList(title, defaultLabel, defaultIcon, kind, options) {
			@Override
			protected void onPick(int index) {
				picked.on(index < 0 ? null : options.get(index));
				rebuild();
			}
		});
	}

	/** A scrolling list of choices, each with its picture; index -1 is the default entry at the top. */
	private static abstract class WndPickList extends Window {

		WndPickList(String title, String defaultLabel, Class<?> defaultIcon, Kind kind, ArrayList<Class<?>> options) {
			super();
			int w = w();

			RenderedTextBlock t = PixelScene.renderTextBlock(title, 9);
			t.hardlight(TITLE_COLOR);
			t.maxWidth(w);
			t.setPos(0, 1);
			add(t);

			ScrollPane pane = new ScrollPane(new Component());
			add(pane);
			Component content = pane.content();

			float y = 0;
			for (int i = -1; i < options.size(); i++) {
				final int index = i;
				Class<?> c = i < 0 ? null : options.get(i);
				DuelIcons.Row b = new DuelIcons.Row(
						c == null ? defaultLabel : label(kind, c),
						c == null ? defaultImage(kind, defaultLabel, defaultIcon) : icon(kind, c),
						c == null ? (defaultIcon != null ? overlay(kind, defaultIcon) : null) : overlay(kind, c)) {
					@Override
					protected void onClick() {
						WndPickList.this.hide();
						onPick(index);
					}
				};
				Class<?> explain = c != null ? c : defaultIcon;
				b.setRect(0, y, explain != null ? w - ROW : w, ROW);
				content.add(b);
				addInfo(content, y, w, kind, explain);
				y += ROW + 1;
			}
			content.setRect(0, 0, w, y);

			float top = t.bottom() + 3;
			int maxH = (int) (PixelScene.uiCamera.height * 0.75f);
			int paneH = (int) Math.min(y, maxH - top);
			resize(w, (int) (top + paneH));
			pane.setRect(0, top, w, paneH);
		}

		protected abstract void onPick(int index);
	}
}
