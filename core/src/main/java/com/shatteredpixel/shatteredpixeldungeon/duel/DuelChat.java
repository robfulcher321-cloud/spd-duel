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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.util.ArrayList;

/**
 * Text chat for the lobby and the match. Messages go to the host, which passes them on to
 * everyone. In a match they also show in the game log and as a speech bubble over the speaker.
 */
public class DuelChat {

	public static class Line {
		public final int from;
		public final String name;
		public final String text;

		Line(int from, String name, String text) {
			this.from = from;
			this.name = name;
			this.text = text;
		}
	}

	private static final ArrayList<Line> lines = new ArrayList<>();
	private static final int KEEP = 40;
	public static final int MAX_LEN = 80;

	/** Messages that arrived since the chat was last opened. */
	static volatile int unread = 0;

	/** Something to redraw when a message arrives (the open chat window or the lobby). */
	interface Watcher {
		void chatChanged();
	}

	private static final ArrayList<Watcher> watchers = new ArrayList<>();

	static void watch(Watcher w) {
		synchronized (watchers) {
			if (!watchers.contains(w)) watchers.add(w);
		}
	}

	static void unwatch(Watcher w) {
		synchronized (watchers) {
			watchers.remove(w);
		}
	}

	public static ArrayList<Line> lines() {
		synchronized (lines) {
			return new ArrayList<>(lines);
		}
	}

	static void reset() {
		synchronized (lines) {
			lines.clear();
		}
		unread = 0;
	}

	private static String clean(String text) {
		if (text == null) return "";
		text = text.replace('\n', ' ').replace('\r', ' ').trim();
		if (text.length() > MAX_LEN) text = text.substring(0, MAX_LEN);
		return text;
	}

	/** I said something. */
	public static void send(String text) {
		text = clean(text);
		if (text.isEmpty()) return;
		Bundle b = new Bundle();
		b.put("type", "chat");
		b.put("from", DuelGame.myId());
		b.put("name", DuelGame.playerName());
		b.put("text", text);
		if (DuelGame.isHost()) {
			DuelNet.broadcast(b);
		} else {
			DuelNet.send(DuelNet.HOST_PEER, b);
		}
		DuelTestDriver.log("CHAT sent: " + text);
		add(DuelGame.myId(), DuelGame.playerName(), text, false);
	}

	/** Network thread: someone else said something. */
	static void received(Bundle msg) {
		final int from = msg.getInt("from");
		final String name = clean(msg.getString("name"));
		final String text = clean(msg.getString("text"));
		if (text.isEmpty()) return;
		DuelTestDriver.log("CHAT from " + name + ": " + text);
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				add(from, name, text, true);
			}
		});
	}

	/** Render thread. */
	private static void add(int from, String name, String text, boolean incoming) {
		synchronized (lines) {
			lines.add(new Line(from, name, text));
			while (lines.size() > KEEP) lines.remove(0);
		}
		if (incoming) {
			unread++;
			Sample.INSTANCE.play(Assets.Sounds.CHARGEUP, 0.4f, 1.6f);
		}
		if (DuelGame.active()) {
			GLog.i("%s", name + ": " + text);
			bubble(from, text);
		}
		ArrayList<Watcher> ws;
		synchronized (watchers) {
			ws = new ArrayList<>(watchers);
		}
		for (Watcher w : ws) {
			try { w.chatChanged(); } catch (Exception ignored) {}
		}
	}

	/** A short speech bubble over the speaker's hero. */
	private static void bubble(int from, String text) {
		Char c = DuelSync.charFor(from);
		if (c == null || c.sprite == null || !c.sprite.visible) return;
		String s = text.length() > 24 ? text.substring(0, 22) + ".." : text;
		c.sprite.showStatus(CharSprite.NEUTRAL, s);
	}

	// ------------------------------------------------------------------ the chat window

	static final String[] QUICK = {"gg", "nice!", "lol", "one sec", "rematch?", "good luck!"};

	public static class WndChat extends Window implements Watcher {

		private static final int WIDTH_P = 140;
		private static final int WIDTH_L = 200;
		private final ArrayList<Component> parts = new ArrayList<>();
		private boolean dirty = false;

		public WndChat() {
			super();
			unread = 0;
			watch(this);
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

			RenderedTextBlock title = PixelScene.renderTextBlock("Chat", 9);
			title.hardlight(TITLE_COLOR);
			title.setPos(0, y);
			add(title);
			parts.add(title);
			y = title.bottom() + 3;

			ArrayList<Line> all = lines();
			int start = Math.max(0, all.size() - 10);
			if (all.isEmpty()) {
				y = text("No messages yet.", 0x999999, y, w);
			}
			for (int i = start; i < all.size(); i++) {
				Line l = all.get(i);
				y = text(l.name + ": " + l.text, l.from == DuelGame.myId() ? 0xFFFF88 : 0xFFFFFF, y, w);
			}
			y += 4;

			//quick phrases, two per row
			float half = (w - 2) / 2f;
			for (int i = 0; i < QUICK.length; i++) {
				final String q = QUICK[i];
				RedButton b = new RedButton(q, 7) {
					@Override
					protected void onClick() {
						send(q);
					}
				};
				b.setRect(i % 2 == 0 ? 0 : half + 2, y, half, 16);
				add(b);
				parts.add(b);
				if (i % 2 == 1) y += 18;
			}
			if (QUICK.length % 2 == 1) y += 18;

			RedButton type = new RedButton("Type a message...") {
				@Override
				protected void onClick() {
					WndDuel.show(new WndTextInput("Say something", null, "", MAX_LEN, false, "Send", "Cancel") {
						@Override
						public void onSelect(boolean positive, String text) {
							if (positive) send(text);
						}
					});
				}
			};
			type.setRect(0, y, w, 18);
			add(type);
			parts.add(type);
			y = type.bottom() + 2;

			RedButton close = new RedButton("Close") {
				@Override
				protected void onClick() {
					hide();
				}
			};
			close.setRect(0, y, w, 16);
			add(close);
			parts.add(close);
			y = close.bottom();

			resize(w, (int) y);
		}

		private float text(String s, int color, float y, int w) {
			RenderedTextBlock t = PixelScene.renderTextBlock(6);
			t.text(s, w);
			t.hardlight(color);
			t.setPos(0, y);
			add(t);
			parts.add(t);
			return t.bottom() + 2;
		}

		@Override
		public void chatChanged() {
			unread = 0;
			//don't redraw under the typing box: new buttons would take its taps
			if (WndDuel.windowAbove(this)) dirty = true;
			else rebuild();
		}

		@Override
		public void update() {
			super.update();
			if (dirty && !WndDuel.windowAbove(this)) {
				dirty = false;
				rebuild();
			}
		}

		@Override
		public void hide() {
			unwatch(this);
			super.hide();
		}

		@Override
		public void destroy() {
			unwatch(this);
			super.destroy();
		}
	}

	// ------------------------------------------------------------------ button on the game screen

	public static class HudButton extends RedButton {

		private int shownUnread = -1;

		public HudButton() {
			super("Chat", 6);
		}

		@Override
		protected void onClick() {
			WndDuel.show(new WndChat());
		}

		@Override
		public void update() {
			super.update();
			if (shownUnread != unread) {
				shownUnread = unread;
				text(unread > 0 ? "Chat (" + unread + ")" : "Chat");
				textColor(unread > 0 ? 0xFFFF44 : 0xFFFFFF);
			}
		}
	}
}
