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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * If the game crashes, what went wrong is written to a file. Next time the app opens, the
 * title screen shows it with a button to copy it, so it can be pasted to whoever's fixing it.
 */
public class DuelCrash {

	private static final String FILE = "duel_crash.txt";
	private static boolean installed = false;

	public static void install() {
		if (installed) return;
		installed = true;
		final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				record(t, e);
				if (previous != null) previous.uncaughtException(t, e);
			}
		});
	}

	static void record(Thread t, Throwable e) {
		try {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			pw.println("SPD Duel " + Game.version + " crashed on thread " + t.getName());
			pw.println("Duel state: active=" + DuelGame.active() + " lobby=" + DuelGame.inLobby()
					+ " host=" + DuelGame.isHost() + " myTurn=" + DuelGame.myTurn());
			e.printStackTrace(pw);
			pw.flush();
			Gdx.files.local(FILE).writeString(sw.toString(), false);
		} catch (Throwable ignored) {
			//nothing more we can do
		}
	}

	// ------------------------------------------------------------------ lobby/match closed by the system

	private static final String DIAG = "spd_duel_diag";

	/** A lobby or match opened (or closed normally). If the app dies in between, we'll know next launch. */
	static void sessionOpen(boolean open) {
		try {
			com.badlogic.gdx.Preferences p = Gdx.app.getPreferences(DIAG);
			if (open) {
				p.putLong("session", System.currentTimeMillis());
				p.putString("keepalive", "not started");
			} else {
				p.remove("session");
			}
			p.flush();
		} catch (Throwable ignored) {}
	}

	/** Android: the keep-alive service reports how starting it went. */
	public static void keepAliveStatus(String status) {
		try {
			com.badlogic.gdx.Preferences p = Gdx.app.getPreferences(DIAG);
			p.putString("keepalive", status);
			p.flush();
		} catch (Throwable ignored) {}
	}

	/** Title screen, fresh app start: if a lobby/match was open when the app went away, say so. */
	private static boolean showClosedSession() {
		try {
			if (DuelGame.inLobby() || DuelGame.active()) return false; //it survived: nothing to report
			com.badlogic.gdx.Preferences p = Gdx.app.getPreferences(DIAG);
			if (!p.contains("session")) return false;
			long when = p.getLong("session");
			final String keep = p.getString("keepalive", "unknown");
			p.remove("session");
			p.flush();
			final String text = "SPD Duel " + Game.version + ": the lobby/match opened "
					+ ((System.currentTimeMillis() - when) / 60000) + " min ago was closed because the app "
					+ "was shut down while in the background. Keep-alive service: " + keep + ".";
			Game.scene().addToFront(new WndOptions("Your last match was closed",
					"Android shut the game down while it was in the background, so the lobby or match ended.\n\n"
							+ "Keep-alive service: " + keep + "\n\n"
							+ "If this keeps happening, try Settings > Apps > SPD Duel > Battery > Unrestricted. "
							+ "Tap Copy and paste it to me too.",
					"Copy", "Close") {
				@Override
				protected void onSelect(int index) {
					if (index == 0) {
						try {
							Gdx.app.getClipboard().setContents(text);
						} catch (Throwable ignored) {}
					}
				}
			});
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** Title screen: show the last crash, if there was one. */
	public static void showIfAny() {
		try {
			FileHandle f = Gdx.files.local(FILE);
			if (!f.exists()) {
				showClosedSession();
				return;
			}
			try {
				Gdx.app.getPreferences(DIAG).remove("session");
				Gdx.app.getPreferences(DIAG).flush();
			} catch (Throwable ignored) {}
			final String text = f.readString();
			f.delete();
			String shown = text.length() > 900 ? text.substring(0, 900) + "\n..." : text;
			Game.scene().addToFront(new WndOptions("The game crashed last time",
					"Sorry about that. Tap Copy and paste it to me so I can fix it:\n\n" + shown,
					"Copy", "Close") {
				@Override
				protected void onSelect(int index) {
					if (index == 0) {
						try {
							Gdx.app.getClipboard().setContents(text);
						} catch (Throwable ignored) {}
					}
				}
			});
		} catch (Throwable ignored) {}
	}
}
