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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import com.watabou.utils.Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Automated check run on GitHub's build machine (desktop build, virtual screen), only when
 * the SPD_SELFTEST environment variable is set. Opens every duel menu and info window,
 * builds every kind of test fighter, logs failures with their stack traces, and saves
 * screenshots. Never runs on phones.
 */
public class DuelSelfTest extends Gizmo {

	public static boolean enabled() {
		try {
			return System.getenv("SPD_SELFTEST") != null;
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean started = false;

	public static void startIfEnabled(Scene scene) {
		if (!enabled() || started) return;
		started = true;
		DuelSelfTest t = new DuelSelfTest();
		t.buildSteps();
		scene.add(t);
		log("starting, " + t.steps.size() + " steps");
		startWatchdog();
	}

	/** If frames stop being drawn, print what every thread is doing so the hang can be found. */
	private static void startWatchdog() {
		Thread w = new Thread("selftest watchdog") {
			@Override
			public void run() {
				long last = -1;
				int stalls = 0;
				while (true) {
					try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
					long frame = Gdx.graphics.getFrameId();
					log("watchdog: frame " + frame);
					if (frame == last) {
						stalls++;
						log("watchdog: NO NEW FRAMES for 15s, thread dump follows");
						for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
							StringBuilder sb = new StringBuilder("THREAD " + e.getKey().getName() + " (" + e.getKey().getState() + ")\n");
							for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append("\n");
							System.out.println(sb);
						}
						System.out.flush();
						if (stalls >= 2) {
							log("FAIL (hung)");
							System.exit(2);
						}
					}
					last = frame;
				}
			}
		};
		w.setDaemon(true);
		w.start();
	}

	private static class Step {
		final String name;
		final Runnable action;
		final boolean screenshot;
		Step(String name, boolean screenshot, Runnable action) {
			this.name = name;
			this.screenshot = screenshot;
			this.action = action;
		}
	}

	private final ArrayList<Step> steps = new ArrayList<>();
	private int index = -1;
	private int frames = 0;
	private int failures = 0;
	private String pendingShot = null;

	private static final int FRAMES_PER_STEP = 10;

	private static void log(String s) {
		System.out.println("SELFTEST " + s);
		System.out.flush();
	}

	private void add(String name, boolean shot, Runnable r) {
		steps.add(new Step(name, shot, r));
	}

	private void buildSteps() {
		add("duel menu", true, new Runnable() { public void run() { Game.scene().addToFront(new WndDuel()); } });

		for (final HeroClass cls : HeroClass.values()) {
			add("class info " + cls, cls == HeroClass.MAGE, new Runnable() { public void run() { DuelInfo.heroClass(cls); } });
			for (final HeroSubClass sub : cls.subClasses()) {
				add("subclass info " + sub, false, new Runnable() { public void run() { DuelInfo.subclass(cls, sub); } });
			}
			for (final ArmorAbility ab : cls.armorAbilities()) {
				add("ability info " + cls + " " + ab.getClass().getSimpleName(), true,
						new Runnable() { public void run() { DuelInfo.ability(cls, ab); } });
			}
		}

		add("class menu (endgame)", true, new Runnable() { public void run() { WndDuel.pickTestClass(false, true); } });
		add("subclass menu", true, new Runnable() { public void run() { WndDuel.pickSubclass(false, HeroClass.MAGE); } });
		add("ability menu", true, new Runnable() { public void run() { WndDuel.pickAbility(false, HeroClass.MAGE, HeroSubClass.BATTLEMAGE); } });
		add("level menu", true, new Runnable() { public void run() { WndDuel.pickTestFighter(false); } });

		for (final HeroClass cls : HeroClass.values()) {
			add("loadout " + cls, cls == HeroClass.ROGUE, new Runnable() {
				public void run() {
					Game.scene().addToFront(new WndLoadout(cls, true, new WndLoadout.Done() {
						public void onReady(DuelLoadout l) {}
					}));
				}
			});
		}

		//every item / enchant / glyph info window
		ArrayList<Class<?>> items = new ArrayList<>();
		items.addAll(DuelLoadout.weapons());
		items.addAll(DuelLoadout.of(Generator.Category.RING));
		items.addAll(DuelLoadout.of(Generator.Category.WAND));
		items.addAll(DuelLoadout.of(Generator.Category.ARTIFACT));
		items.addAll(DuelLoadout.consumablePool());
		for (final Class<?> c : items) {
			add("item info " + c.getSimpleName(), false, new Runnable() { public void run() { DuelInfo.item(c); } });
		}
		for (final Class<?> c : DuelLoadout.enchants()) {
			add("enchant info " + c.getSimpleName(), false, new Runnable() { public void run() { DuelInfo.enchant(c); } });
		}
		for (final Class<?> c : DuelLoadout.glyphs()) {
			add("glyph info " + c.getSimpleName(), false, new Runnable() { public void run() { DuelInfo.glyph(c); } });
		}

		//build every fighter type, with a full loadout, and check the hero survives the trip to the other phone
		for (final HeroClass cls : HeroClass.values()) {
			add("build mid-game " + cls, false, new Runnable() {
				public void run() {
					DuelGame.prepareTestHero(cls, fullLoadout(0));
					roundTrip();
				}
			});
			int n = 0;
			for (final HeroSubClass sub : cls.subClasses()) {
				for (final ArmorAbility ab : cls.armorAbilities()) {
					final int seed = n++;
					add("build endgame " + sub + " " + ab.getClass().getSimpleName(), false, new Runnable() {
						public void run() {
							DuelGame.prepareEndgameHero(cls, sub, ab, fullLoadout(seed));
							roundTrip();
						}
					});
				}
			}
		}

		add("update check", false, new Runnable() {
			public void run() {
				log("update check: this build " + DuelUpdate.myBuild() + ", latest on GitHub " + DuelUpdate.fetchLatestForTest());
			}
		});
	}

	/** A loadout that exercises a different weapon/ring/wand/artifact each time. */
	private static DuelLoadout fullLoadout(int seed) {
		DuelLoadout l = new DuelLoadout();
		ArrayList<Class<?>> w = DuelLoadout.weapons();
		ArrayList<Class<?>> en = DuelLoadout.enchants();
		ArrayList<Class<?>> gl = DuelLoadout.glyphs();
		ArrayList<Class<?>> ri = DuelLoadout.of(Generator.Category.RING);
		ArrayList<Class<?>> wa = DuelLoadout.of(Generator.Category.WAND);
		ArrayList<Class<?>> ar = DuelLoadout.of(Generator.Category.ARTIFACT);
		ArrayList<Class<?>> co = DuelLoadout.consumablePool();
		l.weapon = w.get(seed % w.size());
		l.enchant = en.get(seed % en.size());
		l.glyph = gl.get(seed % gl.size());
		l.ring = ri.get(seed % ri.size());
		l.wand1 = wa.get(seed % wa.size());
		l.wand2 = wa.get((seed + 5) % wa.size());
		l.keepClassArtifact = false;
		l.artifact = ar.get(seed % ar.size());
		l.consumables[0] = co.get(seed % co.size());
		l.consumables[1] = null;
		l.consumables[2] = co.get((seed * 7 + 3) % co.size());
		return l;
	}

	/** Serialises the hello message and reads the hero back, as the other phone would. */
	private static void roundTrip() {
		Bundle hello = DuelGame.helloForTest();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Bundle.write(hello, out);
		try {
			Bundle back = Bundle.read(new ByteArrayInputStream(out.toByteArray()));
			if (back.get("hero") == null) throw new RuntimeException("hero didn't survive serialisation");
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	// ------------------------------------------------------------------ running

	@Override
	public void update() {
		super.update();
		frames++;
		if (index >= 0 && frames < FRAMES_PER_STEP) return;
		frames = 0;

		index++;
		if (index >= steps.size()) {
			log("finished: " + steps.size() + " steps, " + failures + " failures");
			log(failures == 0 ? "PASS" : "FAIL");
			Gdx.app.exit();
			return;
		}

		closeWindows();
		Step s = steps.get(index);
		log("step " + (index + 1) + "/" + steps.size() + ": " + s.name);
		try {
			s.action.run();
		} catch (Throwable t) {
			failures++;
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			log("ERROR in '" + s.name + "':\n" + sw);
		}
		if (s.screenshot) {
			pendingShot = s.name.replaceAll("[^A-Za-z0-9]+", "_");
		}
		//stay on top so the screenshot is taken after everything else has drawn
		if (parent != null) parent.bringToFront(this);
	}

	@Override
	public void draw() {
		super.draw();
		if (pendingShot != null && frames >= FRAMES_PER_STEP - 2) {
			try {
				int w = Gdx.graphics.getBackBufferWidth();
				int h = Gdx.graphics.getBackBufferHeight();
				byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
				for (int i = 3; i < px.length; i += 4) px[i] = (byte) 255;
				Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
				BufferUtils.copy(px, 0, pm.getPixels(), px.length);
				PixmapIO.writePNG(Gdx.files.local("selftest/" + pendingShot + ".png"), pm);
				pm.dispose();
				log("screenshot " + pendingShot);
			} catch (Throwable t) {
				log("screenshot failed: " + t);
			}
			pendingShot = null;
		}
	}

	private static void closeWindows() {
		try {
			Field f = Group.class.getDeclaredField("members");
			f.setAccessible(true);
			@SuppressWarnings("unchecked")
			ArrayList<Gizmo> members = new ArrayList<>((ArrayList<Gizmo>) f.get(Game.scene()));
			for (Gizmo g : members) {
				if (g instanceof Window) ((Window) g).hide();
			}
		} catch (Exception e) {
			log("couldn't close windows: " + e);
		}
	}
}
