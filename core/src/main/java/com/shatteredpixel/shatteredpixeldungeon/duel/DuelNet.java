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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP connections between phones on the same network (or a VPN like ZeroTier), plus UDP
 * broadcast so joining phones can find a host without typing an IP.
 *
 * The host keeps one connection per joined phone (peer ids 1, 2, 3...) and relays between
 * them; a joining phone has a single connection to the host (peer id 0).
 *
 * Every message is a Bundle, sent as a 4-byte length followed by the (gzipped) bundle.
 * All callbacks fire on network threads; the caller must hop to the render thread.
 */
public class DuelNet {

	public static final int TCP_PORT = 52900;
	public static final int UDP_PORT = 52901;
	//the host answers "anyone hosting?" questions sent straight to it on this port
	public static final int PROBE_PORT = 52902;
	private static final String BEACON_TAG = "SPDDUEL2";
	private static final int MAX_MESSAGE = 4 * 1024 * 1024;

	public static final int HOST_PEER = 0;

	public interface Listener {
		void onConnected(int peer);
		void onMessage(int peer, Bundle msg);
		void onDisconnected(int peer, String reason);
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

	/** One open connection to another phone. */
	private static class Conn {
		final int peer;
		final Socket socket;
		final DataOutputStream out;
		final LinkedBlockingQueue<Bundle> outbox = new LinkedBlockingQueue<>();
		volatile boolean open = true;

		Conn(int peer, Socket socket) throws IOException {
			this.peer = peer;
			this.socket = socket;
			socket.setTcpNoDelay(true);
			this.out = new DataOutputStream(socket.getOutputStream());
		}
	}

	private static volatile Listener listener;
	private static volatile boolean running = false;
	private static volatile boolean accepting = false;

	private static ServerSocket serverSocket;
	private static final ConcurrentHashMap<Integer, Conn> conns = new ConcurrentHashMap<>();
	private static int nextPeer = 1;

	private static DatagramSocket beaconSocket;
	private static volatile String beaconLabel = "";
	private static DatagramSocket discoverySocket;
	private static DatagramSocket probeSocket;
	private static DatagramSocket answerSocket;

	/**
	 * Android: phones on Wi-Fi throw away broadcast packets to save battery unless the app
	 * asks to keep them. The Android launcher fills this in.
	 */
	public interface WifiHook {
		void listenForBroadcasts(boolean on);
	}
	public static volatile WifiHook wifi;

	/** Tests only: don't listen for broadcasts, so the direct "anyone hosting?" search is what's tested. */
	static volatile boolean testNoBroadcast = false;
	private static final LinkedHashMap<String, FoundHost> found = new LinkedHashMap<>();

	public static int connectionCount() {
		return conns.size();
	}

	// ---------------------------------------------------------------- hosting

	/** Starts listening for players. Keeps accepting until {@link #stopAccepting()}. */
	public static void host(String label, Listener l) {
		close();
		listener = l;
		running = true;
		accepting = true;
		nextPeer = 1;
		beaconLabel = label;

		Thread t = new Thread("Duel host") {
			@Override
			public void run() {
				try {
					serverSocket = new ServerSocket();
					serverSocket.setReuseAddress(true);
					serverSocket.bind(new InetSocketAddress(TCP_PORT));
					startBeacon();
					startAnswering();
					while (running && accepting) {
						Socket s = serverSocket.accept();
						if (!accepting) {
							try { s.close(); } catch (IOException ignored) {}
							break;
						}
						attach(nextPeer++, s);
					}
				} catch (IOException e) {
					if (running && accepting) {
						Listener li = listener;
						if (li != null) li.onDisconnected(HOST_PEER, "Couldn't host: " + e.getMessage());
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	public static void setBeaconLabel(String label) {
		beaconLabel = label;
	}

	/** The match is starting: no more players can join. */
	public static void stopAccepting() {
		accepting = false;
		stopBeacon();
		if (serverSocket != null) {
			try { serverSocket.close(); } catch (IOException ignored) {}
			serverSocket = null;
		}
	}

	private static void startBeacon() {
		Thread t = new Thread("Duel beacon") {
			@Override
			public void run() {
				try {
					beaconSocket = new DatagramSocket();
					beaconSocket.setBroadcast(true);
					while (running && accepting && beaconSocket != null) {
						byte[] data = (BEACON_TAG + "|" + beaconLabel).getBytes(StandardCharsets.UTF_8);
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
		DatagramSocket b = beaconSocket;
		beaconSocket = null;
		if (b != null) b.close();
		DatagramSocket a = answerSocket;
		answerSocket = null;
		if (a != null) a.close();
	}

	/** Host: answer phones that ask every address on their network "anyone hosting?". */
	private static void startAnswering() {
		Thread t = new Thread("Duel probe answers") {
			@Override
			public void run() {
				try {
					DatagramSocket rs = new DatagramSocket((java.net.SocketAddress) null);
					rs.setReuseAddress(true);
					rs.bind(new InetSocketAddress(PROBE_PORT));
					answerSocket = rs;
					byte[] buf = new byte[64];
					while (running && accepting && answerSocket == rs) {
						DatagramPacket p = new DatagramPacket(buf, buf.length);
						rs.receive(p);
						String q = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
						if (!q.equals(BEACON_TAG + "?")) continue;
						byte[] data = (BEACON_TAG + "|" + beaconLabel).getBytes(StandardCharsets.UTF_8);
						try {
							rs.send(new DatagramPacket(data, data.length, p.getSocketAddress()));
						} catch (IOException ignored) {}
					}
				} catch (Exception ignored) {
					//socket closed when the match starts; broadcast and typed IPs still work
				}
			}
		};
		t.setDaemon(true);
		t.start();
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
					rememberHost(ip.trim());
					attach(HOST_PEER, s);
				} catch (IOException e) {
					Listener li = listener;
					close();
					if (li != null) li.onDisconnected(HOST_PEER, "Couldn't connect to " + ip + ": " + e.getMessage());
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	private static void noteHost(DatagramPacket p) {
		String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
		if (!s.startsWith(BEACON_TAG + "|")) return;
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

	private static final String RECENT_KEY = "duel_recent_hosts";

	/** Remember a host that worked, so it's asked directly next time (handy on ZeroTier). */
	public static void rememberHost(String ip) {
		try {
			ArrayList<String> list = recentHosts();
			list.remove(ip);
			list.add(0, ip);
			while (list.size() > 6) list.remove(list.size() - 1);
			StringBuilder sb = new StringBuilder();
			for (String s : list) sb.append(sb.length() > 0 ? "," : "").append(s);
			com.watabou.utils.GameSettings.put(RECENT_KEY, sb.toString());
		} catch (Exception ignored) {}
	}

	static ArrayList<String> recentHosts() {
		ArrayList<String> list = new ArrayList<>();
		try {
			String v = com.watabou.utils.GameSettings.getString(RECENT_KEY, "", 200);
			for (String s : v.split(",")) if (!s.trim().isEmpty()) list.add(s.trim());
		} catch (Exception ignored) {}
		return list;
	}

	/**
	 * Every address worth asking: each address on each network this phone is on (Wi-Fi,
	 * hotspot, ZeroTier...), up to about a thousand per network, plus recent hosts and the
	 * broadcast addresses. Big networks are narrowed to the 256 addresses around this phone.
	 */
	static ArrayList<InetAddress> probeTargets() {
		java.util.LinkedHashSet<InetAddress> result = new java.util.LinkedHashSet<>();
		for (String ip : recentHosts()) {
			try { result.add(InetAddress.getByName(ip)); } catch (Exception ignored) {}
		}
		try {
			for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback()) continue;
				for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
					if (!(ia.getAddress() instanceof Inet4Address)) continue;
					byte[] b = ia.getAddress().getAddress();
					int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
					int prefix = ia.getNetworkPrefixLength();
					if (prefix < 22 || prefix > 30) prefix = 24;
					int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
					int net = ip & mask;
					int size = 1 << (32 - prefix);
					for (int i = 1; i < size - 1; i++) {
						int a = net + i;
						result.add(InetAddress.getByAddress(new byte[]{
								(byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a}));
					}
					if (ia.getBroadcast() != null) result.add(ia.getBroadcast());
				}
			}
		} catch (Exception ignored) {}
		try {
			result.add(InetAddress.getByName("255.255.255.255"));
		} catch (Exception ignored) {}
		return new ArrayList<>(result);
	}

	/** Joiner: ask every address on my networks "anyone hosting?" every couple of seconds. */
	private static void startProbing() {
		Thread t = new Thread("Duel probe") {
			@Override
			public void run() {
				try {
					DatagramSocket ps = new DatagramSocket();
					ps.setBroadcast(true);
					ps.setSoTimeout(200);
					probeSocket = ps;
					byte[] q = (BEACON_TAG + "?").getBytes(StandardCharsets.UTF_8);
					byte[] buf = new byte[512];
					while (probeSocket == ps) {
						for (InetAddress a : probeTargets()) {
							if (probeSocket != ps) return;
							try {
								ps.send(new DatagramPacket(q, q.length, a, PROBE_PORT));
							} catch (IOException ignored) {
								//unreachable address or interface: carry on
							}
						}
						long until = System.currentTimeMillis() + 2000;
						while (probeSocket == ps && System.currentTimeMillis() < until) {
							DatagramPacket p = new DatagramPacket(buf, buf.length);
							try {
								ps.receive(p);
								noteHost(p);
							} catch (SocketTimeoutException ignored) {}
						}
					}
				} catch (Exception ignored) {
					//socket closed
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
		WifiHook w = wifi;
		if (w != null) {
			try { w.listenForBroadcasts(true); } catch (Exception ignored) {}
		}
		startProbing();
		if (testNoBroadcast) return;
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
						noteHost(p);
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
		DatagramSocket ps = probeSocket;
		probeSocket = null;
		if (ps != null) ps.close();
		WifiHook w = wifi;
		if (w != null) {
			try { w.listenForBroadcasts(false); } catch (Exception ignored) {}
		}
	}

	// ---------------------------------------------------------------- connections

	private static void attach(final int peer, Socket s) throws IOException {
		final Conn c = new Conn(peer, s);
		conns.put(peer, c);
		final DataInputStream in = new DataInputStream(s.getInputStream());

		Thread writer = new Thread("Duel writer " + peer) {
			@Override
			public void run() {
				try {
					while (running && c.open) {
						Bundle msg = c.outbox.poll(500, TimeUnit.MILLISECONDS);
						if (msg == null) continue;
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						Bundle.write(msg, bytes);
						byte[] data = bytes.toByteArray();
						c.out.writeInt(data.length);
						c.out.write(data);
						c.out.flush();
					}
				} catch (IOException e) {
					lost(c, "Connection lost");
				} catch (InterruptedException ignored) {
				}
			}
		};
		writer.setDaemon(true);
		writer.start();

		Thread reader = new Thread("Duel reader " + peer) {
			@Override
			public void run() {
				try {
					while (running && c.open) {
						int len = in.readInt();
						if (len <= 0 || len > MAX_MESSAGE) throw new IOException("bad message size");
						byte[] data = new byte[len];
						in.readFully(data);
						Bundle b = Bundle.read(new ByteArrayInputStream(data));
						Listener l = listener;
						if (l != null) l.onMessage(peer, b);
					}
				} catch (IOException e) {
					lost(c, "Connection lost");
				}
			}
		};
		reader.setDaemon(true);
		reader.start();

		Listener l = listener;
		if (l != null) l.onConnected(peer);
	}

	private static void lost(Conn c, String reason) {
		if (!c.open) return;
		c.open = false;
		conns.remove(c.peer);
		try { c.socket.close(); } catch (IOException ignored) {}
		Listener l = listener;
		if (running && l != null) l.onDisconnected(c.peer, reason);
	}

	public static void send(int peer, Bundle msg) {
		Conn c = conns.get(peer);
		if (c != null && c.open) c.outbox.offer(msg);
	}

	/** To every connected phone (on a joining phone that's just the host). */
	public static void broadcast(Bundle msg) {
		for (Conn c : conns.values()) {
			if (c.open) c.outbox.offer(msg);
		}
	}

	/** Host relaying: to everyone except the phone the message came from. */
	public static void broadcastExcept(int peer, Bundle msg) {
		for (Conn c : conns.values()) {
			if (c.open && c.peer != peer) c.outbox.offer(msg);
		}
	}

	/** Drops one phone (e.g. the lobby is full). */
	public static void kick(int peer) {
		Conn c = conns.get(peer);
		if (c == null) return;
		c.open = false;
		conns.remove(peer);
		try { c.socket.close(); } catch (IOException ignored) {}
	}

	/** Sends a final message to everyone and waits briefly so it is out before closing. */
	public static void broadcastAndClose(Bundle msg) {
		broadcast(msg);
		long until = System.currentTimeMillis() + 1000;
		boolean pending = true;
		while (pending && System.currentTimeMillis() < until) {
			pending = false;
			for (Conn c : conns.values()) if (!c.outbox.isEmpty()) pending = true;
			try { Thread.sleep(20); } catch (InterruptedException e) { break; }
		}
		try { Thread.sleep(100); } catch (InterruptedException ignored) {}
		close();
	}

	public static void close() {
		running = false;
		accepting = false;
		listener = null;
		stopBeacon();
		stopDiscovery();
		if (serverSocket != null) {
			try { serverSocket.close(); } catch (IOException ignored) {}
			serverSocket = null;
		}
		for (Conn c : conns.values()) {
			c.open = false;
			try { c.socket.close(); } catch (IOException ignored) {}
		}
		conns.clear();
	}

	// ---------------------------------------------------------------- helpers

	/** This phone's address on the local network, for joining players to type in. */
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

	/** All IPv4 addresses (Wi-Fi, ZeroTier, Tailscale...) so the host can read out the right one. */
	public static ArrayList<String> allIps() {
		ArrayList<String> result = new ArrayList<>();
		try {
			Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
			while (nis.hasMoreElements()) {
				NetworkInterface ni = nis.nextElement();
				if (!ni.isUp() || ni.isLoopback()) continue;
				Enumeration<InetAddress> addrs = ni.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress a = addrs.nextElement();
					if (a instanceof Inet4Address && !a.isLoopbackAddress()) result.add(a.getHostAddress());
				}
			}
		} catch (Exception ignored) {}
		return result;
	}
}
