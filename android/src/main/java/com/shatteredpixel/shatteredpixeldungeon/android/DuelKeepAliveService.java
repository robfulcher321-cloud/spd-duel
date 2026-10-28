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

package com.shatteredpixel.shatteredpixeldungeon.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Runs while a duel lobby or match is open. A foreground service (with its "match in
 * progress" notification) tells Android the app is in use even when you switch to another
 * app, so the system doesn't close it and its network connection to the other phones
 * stays up. A Wi-Fi lock keeps the phone's Wi-Fi from dozing off meanwhile.
 */
public class DuelKeepAliveService extends Service {

	private static final String CHANNEL = "spd_duel_match";
	private static final int NOTIFICATION_ID = 5290;

	private WifiManager.WifiLock wifiLock;

	public static void set(Context context, boolean on) {
		Intent i = new Intent(context, DuelKeepAliveService.class);
		try {
			if (on) {
				if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
				else context.startService(i);
			} else {
				context.stopService(i);
			}
		} catch (Exception e) {
			//not allowed right now (e.g. the app is already in the background): carry on without it
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26 && nm != null) {
			NotificationChannel ch = new NotificationChannel(CHANNEL, "Duel matches", NotificationManager.IMPORTANCE_LOW);
			ch.setDescription("Shown while a duel lobby or match is open, so it keeps running if you switch apps.");
			nm.createNotificationChannel(ch);
		}

		Intent open = new Intent(this, AndroidLauncher.class);
		open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		PendingIntent pi = PendingIntent.getActivity(this, 0, open,
				Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

		Notification.Builder b = Build.VERSION.SDK_INT >= 26
				? new Notification.Builder(this, CHANNEL)
				: new Notification.Builder(this);
		b.setContentTitle("SPD Duel")
				.setContentText("Match in progress. Tap to go back to it.")
				.setSmallIcon(R.mipmap.ic_launcher)
				.setOngoing(true)
				.setContentIntent(pi);
		Notification n = b.build();

		try {
			if (Build.VERSION.SDK_INT >= 29) {
				startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
			} else {
				startForeground(NOTIFICATION_ID, n);
			}
		} catch (Exception e) {
			stopSelf();
			return START_NOT_STICKY;
		}

		if (wifiLock == null) {
			try {
				WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
				if (wm != null) {
					wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spd-duel-match");
					wifiLock.setReferenceCounted(false);
					wifiLock.acquire();
				}
			} catch (Exception ignored) {}
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		if (wifiLock != null && wifiLock.isHeld()) {
			try { wifiLock.release(); } catch (Exception ignored) {}
		}
		wifiLock = null;
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
