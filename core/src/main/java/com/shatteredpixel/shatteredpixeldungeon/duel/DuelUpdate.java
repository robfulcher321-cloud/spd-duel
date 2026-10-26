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

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub for a newer SPD Duel build when the title screen opens, and offers to
 * download it. Android then installs it over this one; heroes and runs are kept.
 */
public class DuelUpdate {

	private static final String REPO = "robfulcher321-cloud/spd-duel";
	private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
	public static final String APK_URL = "https://github.com/" + REPO + "/releases/latest/download/SPD-Duel.apk";

	private static final int BASE_CODE = 2000; //matches build.gradle

	private static boolean checked = false;

	public static int myBuild() {
		return Math.max(0, Game.versionCode - BASE_CODE);
	}

	/** Called by the title screen. Only checks once per app launch, quietly, off the main thread. */
	public static void checkOnce() {
		if (checked || DuelSelfTest.enabled()) return;
		checked = true;

		Thread t = new Thread("Duel update check") {
			@Override
			public void run() {
				final int latest = fetchLatestBuild();
				if (latest > myBuild()) {
					Game.runOnRenderThread(new Callback() {
						@Override
						public void call() {
							offer(latest);
						}
					});
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	static int fetchLatestForTest() {
		return fetchLatestBuild();
	}

	private static int fetchLatestBuild() {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(API).openConnection();
			c.setConnectTimeout(6000);
			c.setReadTimeout(6000);
			c.setRequestProperty("Accept", "application/vnd.github+json");
			c.setRequestProperty("User-Agent", "SPD-Duel");
			if (c.getResponseCode() != 200) return -1;

			StringBuilder sb = new StringBuilder();
			BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null) sb.append(line);
			r.close();

			//release titles look like "SPD Duel build 42"
			Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"SPD Duel build (\\d+)\"").matcher(sb);
			return m.find() ? Integer.parseInt(m.group(1)) : -1;
		} catch (Exception e) {
			return -1; //no internet etc. - just don't offer an update
		} finally {
			if (c != null) c.disconnect();
		}
	}

	// ------------------------------------------------------------------ installing inside the game

	/** Provided by the Android launcher; null on platforms that can't install (falls back to the browser). */
	public interface Installer {
		boolean needsPermission();
		void openPermissionSettings();
		void downloadAndInstall(String url, Progress p);
	}

	public interface Progress {
		void progress(float fraction);
		void installing();
		void failed(String reason);
	}

	public static Installer installer;

	private static void offer(int latest) {
		if (!(Game.scene() instanceof TitleScene)) return;
		Game.scene().addToFront(new WndOptions(Icons.get(Icons.CHANGES), "Update available",
				"SPD Duel build " + latest + " is out (you have build " + myBuild() + ").\n\n" +
				"It downloads and installs from inside the game and keeps your heroes. " +
				"Both duel players need the same build.",
				"Update now", "Later") {
			@Override
			protected void onSelect(int index) {
				if (index == 0) startUpdate();
			}
		});
	}

	public static void startUpdate() {
		if (installer == null) {
			ShatteredPixelDungeon.platform.openURI(APK_URL);
			return;
		}
		if (installer.needsPermission()) {
			Game.scene().addToFront(new WndOptions(Icons.get(Icons.WARNING), "One-time permission",
					"Android needs you to allow SPD Duel to install updates.\n\n" +
					"Tap Open settings, switch on \"Allow from this source\", then come back and tap Update now.",
					"Open settings", "Update now", "Cancel") {
				@Override
				protected void onSelect(int index) {
					if (index == 0) {
						installer.openPermissionSettings();
						//show this again when they come back, so "Update now" is one tap away
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								startUpdate();
							}
						});
					} else if (index == 1) {
						if (installer.needsPermission()) {
							startUpdate(); //still not allowed; explain again
						} else {
							download();
						}
					}
				}
			});
			return;
		}
		download();
	}

	private static void download() {
		final WndUpdating wnd = new WndUpdating();
		Game.scene().addToFront(wnd);
		installer.downloadAndInstall(APK_URL, new Progress() {
			@Override
			public void progress(final float f) {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						wnd.setText("Downloading... " + Math.round(f * 100) + "%");
					}
				});
			}

			@Override
			public void installing() {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						wnd.setText("Downloaded. Tap Update (or Install) on Android's prompt.\n\n" +
								"The game will close while it installs - just open it again afterwards.");
					}
				});
			}

			@Override
			public void failed(final String reason) {
				Game.runOnRenderThread(new Callback() {
					@Override
					public void call() {
						wnd.hide();
						Game.scene().addToFront(new WndOptions(Icons.get(Icons.WARNING), "Update didn't finish",
								reason + "\n\nYou can try again, or download it in your browser instead.",
								"Try again", "Use browser", "Close") {
							@Override
							protected void onSelect(int index) {
								if (index == 0) startUpdate();
								else if (index == 1) ShatteredPixelDungeon.platform.openURI(APK_URL);
							}
						});
					}
				});
			}
		});
	}

	/** Small status window shown while the update downloads. */
	private static class WndUpdating extends com.shatteredpixel.shatteredpixeldungeon.ui.Window {

		private final com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock text;

		WndUpdating() {
			super();
			int w = com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.landscape() ? 144 : 120;
			com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock title =
					com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.renderTextBlock("Updating SPD Duel", 9);
			title.hardlight(TITLE_COLOR);
			title.setPos(0, 2);
			add(title);
			text = com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.renderTextBlock(6);
			add(text);
			setText("Starting download...");
		}

		void setText(String s) {
			int w = com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.landscape() ? 144 : 120;
			text.text(s, w);
			text.setPos(0, 16);
			resize(w, (int) text.bottom() + 2);
		}

		@Override
		public void onBackPressed() {
			//keep the window while downloading; Android's prompt takes over afterwards
		}
	}
}
