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

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/**
 * The pre-match lobby. Everyone sees who has joined and with which hero; the host chooses
 * Free-for-all or Teams, moves players between teams, and starts the match.
 */
public class WndLobby extends Window implements DuelGame.LobbyListener {

	private static final int WIDTH_P = 146;
	private static final int WIDTH_L = 190;
	private static final int ROW = 20;

	private static final int TEAM_A = 0xFF8844;
	private static final int TEAM_B = 0x44AAFF;

	private final boolean host;
	private String status;
	private final ArrayList<Component> parts = new ArrayList<>();

	public WndLobby(boolean host, String status) {
		super();
		this.host = host;
		this.status = status;
		rebuild();
	}

	private static int w() {
		return PixelScene.landscape() ? WIDTH_L : WIDTH_P;
	}

	private RenderedTextBlock text(String s, int size, float y, int color) {
		RenderedTextBlock t = PixelScene.renderTextBlock(size);
		t.text(s, w());
		if (color != -1) t.hardlight(color);
		t.setPos(0, y);
		add(t);
		parts.add(t);
		return t;
	}

	private void rebuild() {
		for (Component c : parts) {
			remove(c);
			c.destroy();
		}
		parts.clear();

		int w = w();
		float y = 1;

		y = text(host ? "Your match lobby" : "Match lobby", 9, y, TITLE_COLOR).bottom() + 3;

		ArrayList<DuelGame.Player> players = DuelGame.players();
		boolean teams = DuelGame.mode() == DuelGame.MODE_TEAMS;

		if (host) {
			StringBuilder ips = new StringBuilder();
			for (String ip : DuelNet.allIps()) {
				if (ips.length() > 0) ips.append(", ");
				ips.append(ip);
			}
			y = text("Others join from Duel > Join a match. If your game doesn't appear for them, " +
					"they can type one of your IPs: " + ips + "\n(On ZeroTier, use the ZeroTier one.)", 6, y, -1).bottom() + 4;
		} else if (status != null && players.isEmpty()) {
			y = text(status, 6, y, -1).bottom() + 4;
		}

		if (!players.isEmpty()) {
			//---- match type
			y = text("Match type", 7, y, TITLE_COLOR).bottom() + 2;
			float half = (w - 2) / 2f;
			RedButton ffa = new RedButton("Free-for-all", 7) {
				@Override
				protected void onClick() {
					if (host) DuelGame.setMode(DuelGame.MODE_FFA);
				}
			};
			RedButton tm = new RedButton("Teams", 7) {
				@Override
				protected void onClick() {
					if (host) DuelGame.setMode(DuelGame.MODE_TEAMS);
				}
			};
			ffa.textColor(teams ? 0x888888 : TITLE_COLOR);
			tm.textColor(teams ? TITLE_COLOR : 0x888888);
			ffa.enable(host || !teams);
			tm.enable(host || teams);
			ffa.setRect(0, y, half, 18);
			tm.setRect(half + 2, y, half, 18);
			add(ffa); parts.add(ffa);
			add(tm); parts.add(tm);
			y += 20;

			String modeDesc = teams
					? (host ? "Tap a player to move them between Team A and Team B. 1v1, 2v1 and 2v2 all work." : "The host sets the teams.")
					: "Everyone for themselves. Last hero standing wins.";
			y = text(modeDesc, 6, y, -1).bottom() + 4;

			//---- players
			y = text("Players (" + players.size() + "/" + DuelGame.MAX_PLAYERS + ")", 7, y, TITLE_COLOR).bottom() + 2;
			for (final DuelGame.Player p : players) {
				String label = p.who();
				if (p.id == 0) label += "  - host";
				if (p.id == DuelGame.myId()) label += "  (you)";
				if (teams) label = (p.team == 0 ? "[A] " : "[B] ") + label;
				label += "\n" + (p.picking ? "choosing a hero..." : p.name);

				DuelIcons.Row row = new DuelIcons.Row(label, DuelIcons.hero(p.cls, p.tier), null) {
					@Override
					protected void onClick() {
						if (host && DuelGame.mode() == DuelGame.MODE_TEAMS) DuelGame.toggleTeam(p.id);
					}
				};
				if (teams) row.textColor(p.team == 0 ? TEAM_A : TEAM_B);
				if (p.picking) row.textColor(0xAAAAAA);
				row.setRect(0, y, w, ROW + 4);
				add(row);
				parts.add(row);
				y += ROW + 5;
			}
			y += 1;

			//---- my hero and gear, changeable any time before the match starts
			RedButton change = new RedButton("Change hero / loadout", 7) {
				@Override
				protected void onClick() {
					WndDuel.changeHero();
				}
			};
			change.icon(Icons.get(Icons.BACKPACK_LRG));
			change.setRect(0, y, w, 18);
			add(change);
			parts.add(change);
			y = change.bottom() + 4;
		}

		//---- start / status
		if (host) {
			String problem = DuelGame.startProblem();
			if (!problem.isEmpty()) {
				y = text(problem, 6, y, 0xAAAAAA).bottom() + 3;
			}
			RedButton start = new RedButton("Start match") {
				@Override
				protected void onClick() {
					DuelGame.startMatch();
				}
			};
			start.textColor(TITLE_COLOR);
			start.icon(Icons.get(Icons.CHALLENGE_COLOR));
			start.enable(problem.isEmpty());
			start.setRect(0, y, w, 20);
			add(start);
			parts.add(start);
			y = start.bottom() + 2;
		} else if (!players.isEmpty()) {
			y = text("Waiting for the host to start the match...", 6, y, 0xAAAAAA).bottom() + 4;
		}

		RedButton leave = new RedButton("Leave") {
			@Override
			protected void onClick() {
				onBackPressed();
			}
		};
		leave.setRect(0, y, w, 18);
		add(leave);
		parts.add(leave);
		y = leave.bottom();

		resize(w, (int) y);
	}

	@Override
	public void onBackPressed() {
		DuelGame.cancelLobby();
		super.onBackPressed();
	}

	@Override
	public void update() {
		super.update();
		//I was choosing a hero but closed the pickers without finishing: I'm not choosing any more
		if (DuelGame.amPicking() && !WndDuel.pickerOpen()) {
			DuelGame.setPicking(false);
		}
	}

	// ------------------------------------------------------------- lobby events (render thread)

	@Override
	public void onLobbyChanged() {
		if (parent != null) rebuild();
	}

	@Override
	public void onStatus(String text) {
		status = text;
		if (parent != null) rebuild();
	}

	@Override
	public void onFailed(String reason) {
		if (parent != null) hide();
		Game.scene().addToFront(new WndOptions("Lobby closed", reason, "OK"));
	}
}
