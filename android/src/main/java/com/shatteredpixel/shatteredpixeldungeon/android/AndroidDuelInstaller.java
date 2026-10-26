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

package com.shatteredpixel.shatteredpixeldungeon.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.shatteredpixel.shatteredpixeldungeon.duel.DuelUpdate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the new APK inside the game and hands it to Android's own installer, which
 * shows its usual "Update this app?" prompt on top of the game. No browser involved.
 */
public class AndroidDuelInstaller implements DuelUpdate.Installer {

	private static final String ACTION = "com.robfulcher.spdduel.INSTALL_STATUS";

	private final Activity activity;
	private DuelUpdate.Progress progress;
	private boolean receiverRegistered = false;

	public AndroidDuelInstaller(Activity activity) {
		this.activity = activity;
	}

	@Override
	public boolean needsPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return !activity.getPackageManager().canRequestPackageInstalls();
		}
		return false;
	}

	@Override
	public void openPermissionSettings() {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
						Uri.parse("package:" + activity.getPackageName()));
				activity.startActivity(i);
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public void downloadAndInstall(final String url, final DuelUpdate.Progress p) {
		this.progress = p;
		Thread t = new Thread("Duel update download") {
			@Override
			public void run() {
				PackageInstaller.Session session = null;
				HttpURLConnection c = null;
				try {
					//GitHub's download link redirects to its file server; follow it by hand to be safe
					String target = url;
					for (int hops = 0; hops < 5; hops++) {
						c = (HttpURLConnection) new URL(target).openConnection();
						c.setInstanceFollowRedirects(false);
						c.setConnectTimeout(15000);
						c.setReadTimeout(30000);
						c.setRequestProperty("User-Agent", "SPD-Duel");
						int code = c.getResponseCode();
						if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
							target = c.getHeaderField("Location");
							c.disconnect();
							continue;
						}
						if (code != 200) throw new Exception("download failed (HTTP " + code + ")");
						break;
					}
					long total = c.getContentLength();

					PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
					PackageInstaller.SessionParams params =
							new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
					params.setAppPackageName(activity.getPackageName());
					if (total > 0) params.setSize(total);
					int id = pi.createSession(params);
					session = pi.openSession(id);

					InputStream in = c.getInputStream();
					OutputStream out = session.openWrite("SPD-Duel.apk", 0, total > 0 ? total : -1);
					byte[] buf = new byte[64 * 1024];
					long done = 0;
					int n;
					long lastReport = 0;
					while ((n = in.read(buf)) > 0) {
						out.write(buf, 0, n);
						done += n;
						if (total > 0 && done - lastReport > total / 50) {
							lastReport = done;
							progress.progress(done / (float) total);
						}
					}
					session.fsync(out);
					out.close();
					in.close();
					progress.progress(1f);

					registerReceiver();
					Intent status = new Intent(ACTION).setPackage(activity.getPackageName());
					int flags = PendingIntent.FLAG_UPDATE_CURRENT;
					if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
					PendingIntent pending = PendingIntent.getBroadcast(activity, id, status, flags);
					progress.installing();
					session.commit(pending.getIntentSender());
					session.close();
					session = null;
				} catch (Exception e) {
					if (session != null) {
						try { session.abandon(); } catch (Exception ignored) {}
					}
					progress.failed(e.getMessage() == null ? e.toString() : e.getMessage());
				} finally {
					if (c != null) c.disconnect();
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private void registerReceiver() {
		if (receiverRegistered) return;
		receiverRegistered = true;
		BroadcastReceiver r = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
				if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
					//Android wants the player to confirm: show its install prompt
					Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
					if (confirm != null) {
						confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						try {
							activity.startActivity(confirm);
						} catch (Exception e) {
							if (progress != null) progress.failed("couldn't open the installer");
						}
					}
				} else if (status != PackageInstaller.STATUS_SUCCESS) {
					String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
					if (progress != null) progress.failed(status == PackageInstaller.STATUS_FAILURE_ABORTED
							? "update cancelled" : (msg != null ? msg : "install failed"));
				}
				//on success Android restarts into the new version by itself
			}
		};
		IntentFilter f = new IntentFilter(ACTION);
		if (Build.VERSION.SDK_INT >= 33) {
			activity.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
		} else {
			activity.registerReceiver(r, f);
		}
	}
}
