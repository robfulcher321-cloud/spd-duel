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

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.watabou.noosa.Image;

/**
 * A look-alike of another player's buff, shown on their figure when the real buff's code
 * only works on your own hero (e.g. the warrior's seal shield). It shows the same icon,
 * text and shield amount, and does nothing else.
 */
public class DuelBuffView extends ShieldBuff {

	{
		detachesAtZero = false;
		announced = false;
	}

	int icon = 0;
	float fade = 0;
	String iconText = "";
	String title = "";
	String text = "";
	Class<?> of;

	@Override
	public boolean act() {
		spend(TICK);
		return true;
	}

	@Override
	public int icon() {
		return icon;
	}

	@Override
	public void tintIcon(Image icon) {
		//keep the icon's own colours
	}

	@Override
	public float iconFadePercent() {
		return fade;
	}

	@Override
	public String iconTextDisplay() {
		return iconText == null ? "" : iconText;
	}

	@Override
	public String name() {
		return title == null || title.isEmpty() ? "Effect" : title;
	}

	@Override
	public String desc() {
		return text == null ? "" : text;
	}
}
