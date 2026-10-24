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

import com.watabou.utils.Bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plain TCP connection between two phones on the same network, plus UDP broadcast
 * so the joining phone can find the host without typing an IP.
 *
 * Every message is a Bundle, sent as a 4-byte length followed by the (gzipped) bundle.
 * All callbacks fire on network threads; the caller must hop to the render thread.
 */
public class DuelNet {

	public static final int TCP_PORT = 52900;
	public static final int UDP_PORT = 52901;
	private static final String BEACON_TAG = "SPDDUEL1";
	private static final int MAX_MESSAGE = 4 * 1024 * 1024;

	public interface Listener {
		void onConnected();
		void onMessage(Bundle msg);
		void onDisconnected(String reason);
	}

	public static class FoundHost {
		public final String ip;
		public final String label;
		public long lastSeen;

		FoundHost(String ip, String label) {
			this.ip = ip;
			this.label = label;
		}
	}

	private static Listener listener;

	private static ServerSocket serverSocket;
	private static Socket socket;
	private static DataOutputStream out;
	private static volatile boolean running = false;

	private static DatagramSocket beaconSocket;
	private static DatagramSocket discoverySocket;
	private static final LinkedHashMap<String, FoundHost> found = new LinkedHashMap<>();

	public static boolean isConnected() {
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	// ---------------------------------------------------------------- hosting

	public static void host(final String beaconLabel, Listener l) {
		close();
		listener = l;
		running = true;

		Thread t = new Thread("Duel host") {
			@Override
			public void run() {
				try {
					serverSocket = new ServerSocket();
					serverSocket.setReuseAddress(true);
					serverSocket.bind(new InetSocketAddress(TCP_PORT));
					startBeacon(beaconLabel);
					Socket s = serverSocket.accept();
					stopBeacon();
					try { serverSocket.close(); } catch (IOException ignored) {}
					serverSocket = null;
					attach(s);
				} catch (IOException e) {
					if (running) fail("Couldn't host: " + e.getMessage());
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private static void startBeacon(final String label) {
		Thread t = new Thread("Duel beacon") {
			@Override
			public void run() {
				try {
					beaconSocket = new DatagramSocket();
					beaconSocket.setBroadcast(true);
					byte[] data = (BEACON_TAG + "|" + label).getBytes(StandardCharsets.UTF_8);
					while (running && beaconSocket != null && !isConnected()) {
						for (InetAddress target : broadcastTargets()) {
							try {
								beaconSocket.send(new DatagramPacket(data, data.length, target, UDP_PORT));
							} catch (IOException ignored) {
								//some interfaces refuse broadcast, keep trying the others
							}
						}
						Thread.sleep(1000);
					}
				} catch (Exception ignored) {
					//discovery is optional, joining by IP still works
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private static void stopBeacon() {
		if (beaconSocket != null) {
			beaconSocket.close();
			beaconSocket = null;
		}
	}

	private static ArrayList<InetAddress> broadcastTargets() {
		ArrayList<InetAddress> result = new ArrayList<>();
		try {
			for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback()) continue;
				for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
					if (ia.getBroadcast() != null) result.add(ia.getBroadcast());
				}
			}
		} catch (Exception ignored) {}
		try {
			result.add(InetAddress.getByName("255.255.255.255"));
		} catch (Exception ignored) {}
		return result;
	}

	// ---------------------------------------------------------------- joining

	public static void join(final String ip, Listener l) {
		close();
		listener = l;
		running = true;

		Thread t = new Thread("Duel join") {
			@Override
			public void run() {
				try {
					Socket s = new Socket();
					s.connect(new InetSocketAddress(ip.trim(), TCP_PORT), 8000);
					attach(s);
				} catch (IOException e) {
					fail("Couldn't connect to " + ip + ": " + e.getMessage());
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	public static void startDiscovery() {
		stopDiscovery();
		synchronized (found) {
			found.clear();
		}
		Thread t = new Thread("Duel discovery") {
			@Override
			public void run() {
				try {
					DatagramSocket ds = new DatagramSocket((java.net.SocketAddress) null);
					ds.setReuseAddress(true);
					ds.setBroadcast(true);
					ds.bind(new InetSocketAddress(UDP_PORT));
					ds.setSoTimeout(1000);
					discoverySocket = ds;
					byte[] buf = new byte[512];
					while (discoverySocket == ds) {
						DatagramPacket p = new DatagramPacket(buf, buf.length);
						try {
							ds.receive(p);
						} catch (SocketTimeoutException e) {
							continue;
						}
						String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
						if (!s.startsWith(BEACON_TAG + "|")) continue;
						String ip = p.getAddress().getHostAddress();
						String label = s.substring(BEACON_TAG.length() + 1);
						synchronized (found) {
							FoundHost h = found.get(ip);
							if (h == null || !h.label.equals(label)) {
								h = new FoundHost(ip, label);
								found.put(ip, h);
							}
							h.lastSeen = System.currentTimeMillis();
						}
					}
				} catch (Exception ignored) {
					//socket closed or broadcast not allowed; manual IP entry still works
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	public static ArrayList<FoundHost> foundHosts() {
		ArrayList<FoundHost> result = new ArrayList<>();
		long now = System.currentTimeMillis();
		synchronized (found) {
			for (FoundHost h : found.values()) {
				if (now - h.lastSeen < 5000) result.add(h);
			}
		}
		return result;
	}

	public static void stopDiscovery() {
		DatagramSocket ds = discoverySocket;
		discoverySocket = null;
		if (ds != null) ds.close();
	}

	// ---------------------------------------------------------------- connection

	private static void attach(Socket s) throws IOException {
		s.setTcpNoDelay(true);
		socket = s;
		out = new DataOutputStream(s.getOutputStream());
		startWriter(out);
		final DataInputStream in = new DataInputStream(s.getInputStream());

		Thread reader = new Thread("Duel reader") {
			@Override
			public void run() {
				try {
					while (running) {
						int len = in.readInt();
						if (len <= 0 || len > MAX_MESSAGE) throw new IOException("bad message size");
						byte[] data = new byte[len];
						in.readFully(data);
						Bundle b = Bundle.read(new ByteArrayInputStream(data));
						Listener l = listener;
						if (l != null) l.onMessage(b);
					}
				} catch (IOException e) {
					if (running) fail("Connection lost");
				}
			}
		};
		reader.setDaemon(true);
		reader.start();

		Listener l = listener;
		if (l != null) l.onConnected();
	}

	//messages are written by one thread, in order. Never touch the network on the
	//render/actor threads (Android forbids network access on the main thread).
	private static final LinkedBlockingQueue<Bundle> outbox = new LinkedBlockingQueue<>();

	private static void startWriter(final DataOutputStream o) {
		outbox.clear();
		Thread writer = new Thread("Duel writer") {
			@Override
			public void run() {
				try {
					while (running && out == o) {
						Bundle msg = outbox.poll(500, TimeUnit.MILLISECONDS);
						if (msg == null) continue;
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						Bundle.write(msg, bytes);
						byte[] data = bytes.toByteArray();
						o.writeInt(data.length);
						o.write(data);
						o.flush();
					}
				} catch (IOException e) {
					if (running) fail("Connection lost");
				} catch (InterruptedException ignored) {
				}
			}
		};
		writer.setDaemon(true);
		writer.start();
	}

	public static void send(Bundle msg) {
		if (out == null) return;
		outbox.offer(msg);
	}

	/** Sends a final message and waits briefly so it is out before the socket closes. */
	public static void sendAndClose(Bundle msg) {
		send(msg);
		long until = System.currentTimeMillis() + 1000;
		while (!outbox.isEmpty() && System.currentTimeMillis() < until) {
			try { Thread.sleep(20); } catch (InterruptedException e) { break; }
		}
		try { Thread.sleep(100); } catch (InterruptedException ignored) {}
		close();
	}

	private static void fail(String reason) {
		Listener l = listener;
		close();
		if (l != null) l.onDisconnected(reason);
	}

	public static void close() {
		running = false;
		listener = null;
		stopBeacon();
		stopDiscovery();
		if (serverSocket != null) {
			try { serverSocket.close(); } catch (IOException ignored) {}
			serverSocket = null;
		}
		if (socket != null) {
			try { socket.close(); } catch (IOException ignored) {}
			socket = null;
		}
		out = null;
	}

	// ---------------------------------------------------------------- helpers

	/** This phone's address on the local network, for the joining player to type in. */
	public static String localIp() {
		String fallback = null;
		try {
			Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()) {
				NetworkInterface ni = nis.nextElement();
				if (!ni.isUp() || ni.isLoopback()) continue;
				Enumeration<InetAddress> addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress a = addrs.nextElement();
					if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
					String name = ni.getName().toLowerCase();
					if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))) {
						return a.getHostAddress();
					}
					if (fallback == null || a.isSiteLocalAddress()) fallback = a.getHostAddress();
				}
			}
		} catch (Exception ignored) {}
		return fallback == null ? "unknown" : fallback;
	}
}
