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
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/** Lets the player choose their test fighter's gear before the duel. */
public class WndLoadout extends Window {

	public interface Done {
		void onReady(DuelLoadout loadout);
	}

	private static final int WIDTH_P = 130;
	private static final int WIDTH_L = 160;
	private static final int ROW = 16;

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

	private int w() {
		return PixelScene.landscape() ? WIDTH_L : WIDTH_P;
	}

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
		float top = info.bottom() + 3;

		ScrollPane pane = new ScrollPane(new Component());
		add(pane);
		parts.add(pane);
		content = pane.content();
		y = 0;

		y = row(y, "Weapon: " + (lo.weapon == null ? "class weapon" : DuelLoadout.itemName(lo.weapon)), new Runnable() {
			public void run() {
				pick("Weapon", "Class weapon", DuelLoadout.weapons(), Kind.ITEM, new Picked() {
					public void on(Class<?> c) { lo.weapon = c; }
				});
			}
		});
		y = row(y, "Enchant: " + (lo.enchant == null ? "none" : DuelLoadout.enchantName(lo.enchant)), new Runnable() {
			public void run() {
				pick("Weapon enchantment", "None", DuelLoadout.enchants(), Kind.ENCHANT, new Picked() {
					public void on(Class<?> c) { lo.enchant = c; }
				});
			}
		});
		y = row(y, "Glyph: " + (lo.glyph == null ? "none" : DuelLoadout.glyphName(lo.glyph)), new Runnable() {
			public void run() {
				pick("Armour glyph", "None", DuelLoadout.glyphs(), Kind.GLYPH, new Picked() {
					public void on(Class<?> c) { lo.glyph = c; }
				});
			}
		});
		y = row(y, "Ring: " + (lo.ring == null ? "none" : DuelLoadout.itemName(lo.ring)), new Runnable() {
			public void run() {
				pick("Ring", "None", DuelLoadout.of(Generator.Category.RING), Kind.ITEM, new Picked() {
					public void on(Class<?> c) { lo.ring = c; }
				});
			}
		});
		y = row(y, "Wand 1: " + (lo.wand1 == null ? "none" : DuelLoadout.itemName(lo.wand1)), new Runnable() {
			public void run() {
				pick("Wand 1", "None", DuelLoadout.of(Generator.Category.WAND), Kind.ITEM, new Picked() {
					public void on(Class<?> c) { lo.wand1 = c; }
				});
			}
		});
		y = row(y, "Wand 2: " + (lo.wand2 == null ? "none" : DuelLoadout.itemName(lo.wand2)), new Runnable() {
			public void run() {
				pick("Wand 2", "None", DuelLoadout.of(Generator.Category.WAND), Kind.ITEM, new Picked() {
					public void on(Class<?> c) { lo.wand2 = c; }
				});
			}
		});

		String art = lo.keepClassArtifact
				? (cls == HeroClass.ROGUE || cls == HeroClass.CLERIC ? "class artifact" : "none")
				: (lo.artifact == null ? "none" : DuelLoadout.itemName(lo.artifact));
		y = row(y, "Artifact: " + art, new Runnable() {
			public void run() {
				pick("Artifact", "Class default", DuelLoadout.of(Generator.Category.ARTIFACT), Kind.ITEM, new Picked() {
					public void on(Class<?> c) {
						lo.keepClassArtifact = c == null;
						lo.artifact = c;
					}
				});
			}
		});

		for (int i = 0; i < 3; i++) {
			final int slot = i;
			Class<?> c = lo.consumables[i];
			y = row(y, "Item " + (i + 1) + ": " + (c == null ? "random" : DuelLoadout.itemName(c)), new Runnable() {
				public void run() {
					pick("Item " + (slot + 1), "Random", DuelLoadout.consumablePool(), Kind.ITEM, new Picked() {
						public void on(Class<?> c) { lo.consumables[slot] = c; }
					});
				}
			});
		}

		content.setRect(0, 0, w, y);
		int maxH = (int) (PixelScene.uiCamera.height * 0.8f);
		float paneH = Math.min(y, maxH - top - 22);
		pane.setRect(0, top, w, paneH);
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
		//settles on resize; place it again now so it lines up on the first showing
		pane.setRect(0, top, w, paneH);
	}

	private float row(float y, String label, final Runnable action) {
		RedButton b = new RedButton(label, 6) {
			@Override
			protected void onClick() {
				action.run();
			}
		};
		b.setRect(0, y, w(), ROW);
		content.add(b);
		return y + ROW + 1;
	}

	// ------------------------------------------------------------------ picker

	private enum Kind { ITEM, ENCHANT, GLYPH }

	private interface Picked {
		void on(Class<?> c);
	}

	private void pick(String title, String noneLabel, ArrayList<Class<?>> options, Kind kind, final Picked picked) {
		String[] labels = new String[options.size()];
		for (int i = 0; i < options.size(); i++) {
			Class<?> c = options.get(i);
			switch (kind) {
				case ENCHANT: labels[i] = DuelLoadout.enchantName(c); break;
				case GLYPH:   labels[i] = DuelLoadout.glyphName(c); break;
				default:      labels[i] = DuelLoadout.itemName(c); break;
			}
		}
		final ArrayList<Class<?>> opts = options;
		Game.scene().addToFront(new WndPickList(title, noneLabel, labels) {
			@Override
			protected void onPick(int index) {
				picked.on(index < 0 ? null : opts.get(index));
				rebuild();
			}
		});
	}

	/** A scrolling list of choices; index -1 is the "none/default" entry at the top. */
	public static abstract class WndPickList extends Window {

		public WndPickList(String title, String noneLabel, String[] labels) {
			super();
			int w = PixelScene.landscape() ? WIDTH_L : WIDTH_P;

			RenderedTextBlock t = PixelScene.renderTextBlock(title, 9);
			t.hardlight(TITLE_COLOR);
			t.maxWidth(w);
			t.setPos(0, 1);
			add(t);

			ScrollPane pane = new ScrollPane(new Component());
			add(pane);
			Component content = pane.content();

			float y = 0;
			for (int i = -1; i < labels.length; i++) {
				final int index = i;
				RedButton b = new RedButton(i < 0 ? noneLabel : labels[i], 6) {
					@Override
					protected void onClick() {
						WndPickList.this.hide();
						onPick(index);
					}
				};
				b.setRect(0, y, w, ROW);
				content.add(b);
				y += ROW + 1;
			}
			content.setRect(0, 0, w, y);

			float top = t.bottom() + 3;
			int maxH = (int) (PixelScene.uiCamera.height * 0.7f);
			int paneH = (int) Math.min(y, maxH - top);
			resize(w, (int) (top + paneH));
			pane.setRect(0, top, w, paneH);
		}

		protected abstract void onPick(int index);
	}
}
