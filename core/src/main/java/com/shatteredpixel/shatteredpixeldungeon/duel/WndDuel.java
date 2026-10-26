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

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.HeroIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.watabou.noosa.Image;
import com.watabou.noosa.Game;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/** Title-screen menus for setting up a duel. */
public class WndDuel extends WndOptions {

	private static final int WIDTH_P = 120;
	private static final int WIDTH_L = 144;

	public WndDuel() {
		super("Duel",
				"Fight another player's hero, phone to phone on the same Wi-Fi. " +
				"You fight with the hero from one of your saved runs; the run itself isn't changed.",
				"Host a duel", "Join a duel");
	}

	@Override
	protected void onSelect(int index) {
		pickHero(index == 0);
	}

	private static void show(Window w) {
		Game.scene().addToFront(w);
	}

	private static int width() {
		return PixelScene.landscape() ? WIDTH_L : WIDTH_P;
	}

	// ------------------------------------------------------------------ hero picker

	private static void pickHero(final boolean hosting) {
		final ArrayList<GamesInProgress.Info> runs = GamesInProgress.checkAll();

		//saved runs first, then the ready-made test fighters
		String[] labels = new String[runs.size() + 1];
		for (int i = 0; i < runs.size(); i++) {
			GamesInProgress.Info info = runs.get(i);
			String cls = info.subClass != null && info.subClass != HeroSubClass.NONE
					? info.subClass.title() : info.heroClass.title();
			labels[i] = Messages.capitalize(cls) + ", lvl " + info.level + " (depth " + info.depth + ")";
		}
		labels[runs.size()] = "Test fighter (pick any class)";

		show(new WndOptions("Choose your hero",
				runs.isEmpty()
					? "You have no saved runs yet, but you can fight with a ready-made test fighter."
					: "Fight with a hero from a saved run, or a ready-made test fighter.",
				labels) {
			@Override
			protected boolean hasIcon(int index) { return getIcon(index) != null; }

			@Override
			protected Image getIcon(int index) {
				if (index < runs.size()) {
					GamesInProgress.Info info = runs.get(index);
					return DuelIcons.hero(info.heroClass, info.armorTier);
				}
				return Icons.get(Icons.CHALLENGE_COLOR);
			}

			@Override
			protected void onSelect(int index) {
				if (index == runs.size()) {
					pickTestFighter(hosting);
					return;
				}
				int slot = runs.get(index).slot;
				try {
					DuelGame.prepareHero(slot);
				} catch (Exception e) {
					ShatteredPixelDungeon.reportException(e);
					show(new WndOptions("Couldn't load hero", "That save couldn't be read.", "OK"));
					return;
				}
				heroReady(hosting);
			}
		});
	}

	private static void pickTestFighter(final boolean hosting) {
		show(new WndOptions("Test fighter", "How far along should your fighter be?",
				"Mid-game (level " + DuelGame.TEST_LEVEL + ")",
				"Endgame (level " + DuelGame.ENDGAME_LEVEL + ")") {
			@Override
			protected boolean hasIcon(int index) { return getIcon(index) != null; }

			@Override
			protected Image getIcon(int index) {
				return new ItemSprite(index == 0 ? ItemSpriteSheet.ARMOR_MAIL : ItemSpriteSheet.ARMOR_WARRIOR);
			}

			@Override
			protected void onSelect(int index) {
				pickTestClass(hosting, index == 1);
			}
		});
	}

	private static void pickTestClass(final boolean hosting, final boolean endgame) {
		final HeroClass[] classes = HeroClass.values();
		String[] labels = new String[classes.length];
		for (int i = 0; i < classes.length; i++) {
			labels[i] = Messages.capitalize(classes[i].title());
		}
		String desc = endgame
				? "Level " + DuelGame.ENDGAME_LEVEL + ", every talent filled in, class weapon +10, class armour +10 " +
				  "with its ability fully charged, 18 strength, maxed class artifact, three healing potions. " +
				  "You'll pick a subclass and armour ability next."
				: "Level " + DuelGame.TEST_LEVEL + " with its class weapon +3, mail armour +2, 13 strength, " +
				  "two healing potions and its first talents filled in. No subclass yet.";
		show(new WndOptions("Choose a class", desc, labels) {
			@Override
			protected boolean hasIcon(int index) { return getIcon(index) != null; }

			@Override
			protected Image getIcon(int index) {
				return DuelIcons.hero(classes[index], endgame ? 6 : 3);
			}

			@Override
			protected void onSelect(int index) {
				if (endgame) {
					pickSubclass(hosting, classes[index]);
				} else {
					build(hosting, classes[index], null, null);
				}
			}
		});
	}

	private static void pickSubclass(final boolean hosting, final HeroClass cls) {
		final HeroSubClass[] subs = cls.subClasses();
		String[] labels = new String[subs.length];
		for (int i = 0; i < subs.length; i++) {
			labels[i] = Messages.capitalize(subs[i].title());
		}
		show(new WndOptions("Choose a subclass", "Which " + cls.title() + " subclass?", labels) {
			@Override
			protected boolean hasIcon(int index) { return getIcon(index) != null; }

			@Override
			protected Image getIcon(int index) {
				return new HeroIcon(subs[index]);
			}

			@Override
			protected void onSelect(int index) {
				pickAbility(hosting, cls, subs[index]);
			}
		});
	}

	private static void pickAbility(final boolean hosting, final HeroClass cls, final HeroSubClass sub) {
		final ArmorAbility[] abilities = cls.armorAbilities();
		String[] labels = new String[abilities.length];
		for (int i = 0; i < abilities.length; i++) {
			labels[i] = Messages.capitalize(abilities[i].name());
		}
		show(new WndOptions("Choose an armour ability", "Used from your class armour.", labels) {
			@Override
			protected boolean hasIcon(int index) { return getIcon(index) != null; }

			@Override
			protected Image getIcon(int index) {
				return new HeroIcon(abilities[index]);
			}

			@Override
			protected void onSelect(int index) {
				build(hosting, cls, sub, abilities[index]);
			}
		});
	}

	private static void build(final boolean hosting, final HeroClass cls, final HeroSubClass sub, final ArmorAbility ability) {
		show(new WndLoadout(cls, sub != null, new WndLoadout.Done() {
			@Override
			public void onReady(DuelLoadout loadout) {
				buildWith(hosting, cls, sub, ability, loadout);
			}
		}));
	}

	private static void buildWith(boolean hosting, HeroClass cls, HeroSubClass sub, ArmorAbility ability, DuelLoadout loadout) {
		try {
			if (sub == null) {
				DuelGame.prepareTestHero(cls, loadout);
			} else {
				DuelGame.prepareEndgameHero(cls, sub, ability, loadout);
			}
		} catch (Exception e) {
			ShatteredPixelDungeon.reportException(e);
			show(new WndOptions("Couldn't make fighter", "Something went wrong building that hero.", "OK"));
			return;
		}
		heroReady(hosting);
	}

	private static void heroReady(boolean hosting) {
		if (hosting) {
			startHosting();
		} else {
			show(new WndJoin());
		}
	}

	// ------------------------------------------------------------------ hosting

	private static void startHosting() {
		final WndStatus status = new WndStatus("Hosting",
				"Waiting for an opponent...\n\n" +
				"On the other phone: Duel > Join a duel. If your game doesn't show up there, " +
				"enter this IP: " + DuelNet.localIp() + "\n\nBoth phones must be on the same Wi-Fi " +
				"(a phone hotspot works too).");
		show(status);
		DuelGame.hostLobby(status);
	}

	// ------------------------------------------------------------------ status window

	public static class WndStatus extends Window implements DuelGame.LobbyListener {

		private final RenderedTextBlock title;
		private final RenderedTextBlock text;
		private final RedButton cancel;

		public WndStatus(String titleText, String message) {
			super();

			title = PixelScene.renderTextBlock(titleText, 9);
			title.hardlight(TITLE_COLOR);
			add(title);

			text = PixelScene.renderTextBlock(6);
			add(text);

			cancel = new RedButton("Cancel") {
				@Override
				protected void onClick() {
					DuelGame.cancelLobby();
					WndStatus.this.hide();
				}
			};
			add(cancel);

			setMessage(message);
		}

		private void setMessage(String message) {
			int w = width();
			title.maxWidth(w);
			title.setPos(0, 2);
			text.text(message, w);
			text.setPos(0, title.bottom() + 4);
			cancel.setRect(0, text.bottom() + 6, w, 18);
			resize(w, (int) cancel.bottom());
		}

		@Override
		public void onBackPressed() {
			DuelGame.cancelLobby();
			super.onBackPressed();
		}

		@Override
		public void onStatus(String message) {
			if (parent != null) setMessage(message);
		}

		@Override
		public void onFailed(String reason) {
			if (parent != null) hide();
			show(new WndOptions("Duel cancelled", reason, "OK"));
		}
	}

	// ------------------------------------------------------------------ joining

	public static class WndJoin extends Window {

		private final ArrayList<Component> parts = new ArrayList<>();
		private String shownHosts = null;
		private float refresh = 0;

		public WndJoin() {
			super();
			DuelNet.startDiscovery();
			rebuild(new ArrayList<DuelNet.FoundHost>());
		}

		@Override
		public synchronized void update() {
			super.update();
			refresh -= Game.elapsed;
			if (refresh <= 0) {
				refresh = 0.5f;
				ArrayList<DuelNet.FoundHost> hosts = DuelNet.foundHosts();
				StringBuilder key = new StringBuilder();
				for (DuelNet.FoundHost h : hosts) key.append(h.ip).append(h.label).append(';');
				if (!key.toString().equals(shownHosts)) {
					rebuild(hosts);
				}
			}
		}

		private void rebuild(ArrayList<DuelNet.FoundHost> hosts) {
			StringBuilder key = new StringBuilder();
			for (DuelNet.FoundHost h : hosts) key.append(h.ip).append(h.label).append(';');
			shownHosts = key.toString();

			for (Component c : parts) {
				remove(c);
				c.destroy();
			}
			parts.clear();

			int w = width();
			float y = 2;

			RenderedTextBlock title = PixelScene.renderTextBlock("Join a duel", 9);
			title.hardlight(TITLE_COLOR);
			title.maxWidth(w);
			title.setPos(0, y);
			add(title);
			parts.add(title);
			y = title.bottom() + 4;

			RenderedTextBlock text = PixelScene.renderTextBlock(6);
			text.text(hosts.isEmpty()
					? "Looking for duels on this Wi-Fi...\n\nIf nothing appears after a few seconds, enter the IP shown on the host's phone."
					: "Tap a game to join it:", w);
			text.setPos(0, y);
			add(text);
			parts.add(text);
			y = text.bottom() + 6;

			for (final DuelNet.FoundHost h : hosts) {
				RedButton b = new RedButton(h.label + "  (" + h.ip + ")") {
					@Override
					protected void onClick() {
						connect(h.ip);
					}
				};
				b.multiline = true;
				b.setRect(0, y, w, 18);
				add(b);
				parts.add(b);
				y = b.bottom() + 2;
			}

			RedButton manual = new RedButton("Enter IP address") {
				@Override
				protected void onClick() {
					show(new WndTextInput("Host's IP address",
							"Type the IP shown on the host's screen, e.g. 192.168.1.20",
							"192.168.", 40, false, "Join", "Cancel") {
						@Override
						public void onSelect(boolean positive, String ip) {
							if (positive && ip != null && !ip.trim().isEmpty()) {
								connect(ip.trim());
							}
						}
					});
				}
			};
			manual.setRect(0, y + 4, w, 18);
			add(manual);
			parts.add(manual);
			y = manual.bottom() + 2;

			RedButton cancel = new RedButton("Cancel") {
				@Override
				protected void onClick() {
					onBackPressed();
				}
			};
			cancel.setRect(0, y, w, 18);
			add(cancel);
			parts.add(cancel);
			y = cancel.bottom();

			resize(w, (int) y);
		}

		private void connect(String ip) {
			DuelNet.stopDiscovery();
			hide();
			WndStatus status = new WndStatus("Joining", "Connecting to " + ip + "...");
			show(status);
			DuelGame.joinLobby(ip, status);
		}

		@Override
		public void onBackPressed() {
			DuelNet.stopDiscovery();
			super.onBackPressed();
		}
	}
}
