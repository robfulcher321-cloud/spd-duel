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

import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

/** End of a match (or being knocked out of one). Saved runs are never affected. */
public class WndDuelResult extends WndOptions {

	private final boolean canWatch;

	public WndDuelResult(String title, String text, boolean canWatch) {
		super(title, text + "\n\nYour saved runs are untouched.",
				canWatch ? new String[]{"Keep watching", "Leave match"} : new String[]{"Back to title"});
		this.canWatch = canWatch;
	}

	@Override
	protected void onSelect(int index) {
		if (canWatch && index == 0) return; //just close the window and spectate
		DuelGame.returnToTitle();
	}

	@Override
	public void onBackPressed() {
		hide();
		if (!canWatch) DuelGame.returnToTitle();
	}
}
