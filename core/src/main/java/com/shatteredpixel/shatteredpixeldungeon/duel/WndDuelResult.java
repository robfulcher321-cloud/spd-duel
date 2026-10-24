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

import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

public class WndDuelResult extends WndOptions {

	public WndDuelResult(boolean won, boolean forfeit) {
		super(won ? "Victory!" : "Defeat",
				won
					? (forfeit
						? DuelGame.opponentName() + " left the duel. You win by forfeit."
						: "You defeated " + DuelGame.opponentName() + "!")
					: DuelGame.opponentName() + " defeated you. Your saved run is untouched.",
				"Back to title");
	}

	@Override
	protected void onSelect(int index) {
		DuelGame.returnToTitle();
	}

	@Override
	public void onBackPressed() {
		hide();
		DuelGame.returnToTitle();
	}
}
