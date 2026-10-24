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

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;

/**
 * A fixed, symmetric arena. It has no randomness and no exits, so both phones build
 * exactly the same map and nobody can walk off into their saved run.
 */
public class DuelArenaLevel extends Level {

	//interior is 13x11, surrounded by a wall
	public static final int W = 15;
	public static final int H = 13;

	//    x: 0123456789ABCDE
	private static final String[] LAYOUT = {
			"###############",
			"#.............#",
			"#.............#",
			"#...#.....#...#",
			"#.............#",
			"#......~......#",
			"#..H..~#~..J..#",
			"#......~......#",
			"#.............#",
			"#...#.....#...#",
			"#.............#",
			"#.............#",
			"###############",
	};

	{
		color1 = 0x534f3e;
		color2 = 0xb9d661;
		viewDistance = 12;
	}

	public static int hostSpawn() {
		return find('H');
	}

	public static int joinerSpawn() {
		return find('J');
	}

	private static int find(char c) {
		for (int y = 0; y < H; y++) {
			int x = LAYOUT[y].indexOf(c);
			if (x >= 0) return y * W + x;
		}
		return W + 1;
	}

	@Override
	public String tilesTex() {
		return Assets.Environment.TILES_PRISON;
	}

	@Override
	public String waterTex() {
		return Assets.Environment.WATER_PRISON;
	}

	@Override
	protected boolean build() {
		setSize(W, H);
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				char c = LAYOUT[y].charAt(x);
				int t;
				switch (c) {
					case '#': t = Terrain.WALL; break;
					case '~': t = Terrain.WATER; break;
					default:  t = Terrain.EMPTY; break;
				}
				map[y * W + x] = t;
			}
		}
		return true;
	}

	@Override
	public int entrance() {
		return hostSpawn();
	}

	@Override
	public Mob createMob() {
		return null;
	}

	@Override
	protected void createMobs() {
	}

	@Override
	protected void createItems() {
	}

	@Override
	public Actor addRespawner() {
		return null;
	}

	@Override
	public int randomRespawnCell(Char ch) {
		return DuelGame.isHost() ? hostSpawn() : joinerSpawn();
	}
}
