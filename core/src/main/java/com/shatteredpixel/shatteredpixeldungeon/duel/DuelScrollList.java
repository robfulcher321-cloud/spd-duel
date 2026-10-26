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
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;

import java.util.ArrayList;

/**
 * A scrolling list that's easy to use with a thumb:
 *  - taps only count if your finger didn't move (dragging never selects anything)
 *  - a wide scroll bar on the right that you can grab and drag, or tap to jump
 */
public class DuelScrollList extends Component {

	public static final int BAR = 9;

	private ScrollPane pane;
	private Component content;
	private float contentHeight = 0;

	private ColorBlock track;
	private ColorBlock thumb;
	private ColorBlock grip1, grip2, grip3;
	private PointerArea barArea;
	private boolean dragging = false;
	private float grabOffset = 0;

	private final ArrayList<RectF> areas = new ArrayList<>();
	private final ArrayList<Runnable> actions = new ArrayList<>();

	public DuelScrollList() {
		super();

		pane = new ScrollPane(new Component()) {
			@Override
			public void onClick(float x, float y) {
				//only called by the pane when the touch didn't turn into a drag
				for (int i = areas.size() - 1; i >= 0; i--) {
					if (areas.get(i).inside(x, y)) {
						Sample.INSTANCE.play(Assets.Sounds.CLICK);
						actions.get(i).run();
						return;
					}
				}
			}
		};
		add(pane);
		content = pane.content();

		track = new ColorBlock(1, 1, 0xFF2A2A26);
		add(track);
		thumb = new ColorBlock(1, 1, 0xFFB8B09A);
		add(thumb);
		grip1 = new ColorBlock(1, 1, 0xFF6E6858);
		grip2 = new ColorBlock(1, 1, 0xFF6E6858);
		grip3 = new ColorBlock(1, 1, 0xFF6E6858);
		add(grip1);
		add(grip2);
		add(grip3);

		barArea = new PointerArea(0, 0, 0, 0) {
			@Override
			protected void onPointerDown(PointerEvent event) {
				PointF p = camera().screenToCamera((int) event.current.x, (int) event.current.y);
				if (p.y >= thumb.y && p.y <= thumb.y + thumb.height()) {
					grabOffset = p.y - thumb.y;          //grabbed the handle: keep it under the finger
				} else {
					grabOffset = thumb.height() / 2f;    //tapped the track: jump there
				}
				dragging = true;
				dragTo(p.y);
			}

			@Override
			protected void onDrag(PointerEvent event) {
				if (!dragging) return;
				PointF p = camera().screenToCamera((int) event.current.x, (int) event.current.y);
				dragTo(p.y);
			}

			@Override
			protected void onPointerUp(PointerEvent event) {
				dragging = false;
			}
		};
		add(barArea);
	}

	public Component content() {
		return content;
	}

	/** Width available for rows (the scroll bar takes the right edge). */
	public float innerWidth() {
		return width - BAR - 1;
	}

	/** Registers a tappable area in content coordinates. Later areas win where they overlap. */
	public void onTap(float x, float y, float w, float h, Runnable action) {
		areas.add(new RectF(x, y, x + w, y + h));
		actions.add(action);
	}

	public void setContentHeight(float h) {
		contentHeight = h;
	}

	public float scrollY() {
		return content.camera == null ? 0 : content.camera.scroll.y;
	}

	public void scrollToY(float y) {
		pane.scrollTo(0, y);
	}

	private float maxScroll() {
		return Math.max(0, contentHeight - height);
	}

	private void dragTo(float fingerY) {
		float room = height - thumb.height();
		if (room <= 0) return;
		float frac = (fingerY - grabOffset - y) / room;
		frac = Math.max(0, Math.min(1, frac));
		pane.scrollTo(0, frac * maxScroll());
	}

	@Override
	protected void layout() {
		content.setRect(0, 0, innerWidth(), contentHeight);
		pane.setRect(x, y, innerWidth(), height);

		float barX = x + width - BAR;
		track.x = barX;
		track.y = y;
		track.size(BAR, height);

		barArea.x = barX - 3; //a little extra width to make it easy to hit
		barArea.y = y;
		barArea.width = BAR + 3;
		barArea.height = height;

		updateThumb();
	}

	private void updateThumb() {
		boolean scrollable = contentHeight > height + 0.5f;
		track.visible = thumb.visible = grip1.visible = grip2.visible = grip3.visible = scrollable;
		barArea.active = scrollable;
		if (!scrollable) return;

		float th = Math.max(18, height * height / contentHeight);
		float room = height - th;
		float frac = maxScroll() > 0 ? scrollY() / maxScroll() : 0;
		thumb.x = x + width - BAR + 1;
		thumb.y = y + room * frac;
		thumb.size(BAR - 2, th);

		//three little ridges so it reads as a handle
		float gx = thumb.x + 2;
		float gy = thumb.y + th / 2f - 4;
		grip1.x = grip2.x = grip3.x = gx;
		grip1.y = gy; grip2.y = gy + 3; grip3.y = gy + 6;
		grip1.size(BAR - 6, 1); grip2.size(BAR - 6, 1); grip3.size(BAR - 6, 1);
		PixelScene.align(thumb);
	}

	@Override
	public synchronized void update() {
		super.update();
		updateThumb();
	}
}
