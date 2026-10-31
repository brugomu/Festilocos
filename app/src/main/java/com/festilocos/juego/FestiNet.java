package com.festilocos.juego;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Local Wi-Fi co-op bridge.
 * The host runs a small WebSocket server and announces it on the network (mDNS / NSD).
 * Guests find it with discoverStart() and connect straight from the game's JavaScript with a WebSocket.
 * Everything that happens here is reported to the page through window.__festiNet(type, a, b).
 */
public class FestiNet {
    private static final String TAG = "FESTI-NET";
    public static final int PORT = 47777;
    private static final String SERVICE_TYPE = "_festilocos._tcp.";

    private final Activity act;
    private WebView web;
    private Server server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener regListener;
    private NsdManager.DiscoveryListener discListener;
    private WifiManager.MulticastLock lock;
    private final LinkedList<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean resolving = false;
    private final Map<WebSocket, Integer> ids = Collections.synchronizedMap(new HashMap<>());
    private final Map<Integer, WebSocket> socks = Collections.synchronizedMap(new HashMap<>());
    private int nextId = 1;

    public FestiNet(Activity act, WebView web) { this.act = act; this.web = web; }
    public void setWebView(WebView w) { web = w; }

    private void emit(final String type, final String a, final String b) {
        act.runOnUiThread(() -> {
            if (web == null) return;
            String js = "window.__festiNet&&window.__festiNet(" + JSONObject.quote(type) + "," + JSONObject.quote(a == null ? "" : a) + "," + JSONObject.quote(b == null ? "" : b) + ")";
            web.evaluateJavascript(js, null);
        });
    }

    // ---------- host ----------
    private class Server extends WebSocketServer {
        Server() { super(new InetSocketAddress(PORT)); setReuseAddr(true); setConnectionLostTimeout(8); }
        @Override public void onOpen(WebSocket conn, ClientHandshake h) {
            int id; synchronized (FestiNet.this) { id = nextId++; }
            ids.put(conn, id); socks.put(id, conn);
            emit("join", String.valueOf(id), "");
        }
        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            Integer id = ids.remove(conn);
            if (id != null) { socks.remove(id); emit("leave", String.valueOf(id), ""); }
        }
        @Override public void onMessage(WebSocket conn, String msg) {
            Integer id = ids.get(conn);
            if (id != null) emit("msg", String.valueOf(id), msg);
        }
        @Override public void onError(WebSocket conn, Exception ex) { Log.e(TAG, "server error", ex); emit("error", String.valueOf(ex.getMessage()), ""); }
        @Override public void onStart() { emit("hosting", ipList(), String.valueOf(PORT)); }
    }

    @JavascriptInterface
    public String hostStart(String name) {
        try {
            hostStop();
            server = new Server();
            server.start();
            register(name == null || name.isEmpty() ? "Festilocos" : name);
            return ipList();
        } catch (Exception e) { Log.e(TAG, "hostStart", e); return ""; }
    }

    @JavascriptInterface
    public void hostStop() {
        try { if (regListener != null && nsd != null) nsd.unregisterService(regListener); } catch (Exception ignored) {}
        regListener = null;
        try { if (server != null) server.stop(500); } catch (Exception ignored) {}
        server = null; ids.clear(); socks.clear();
    }

    @JavascriptInterface
    public void send(int id, String msg) {
        WebSocket s = socks.get(id);
        try { if (s != null && s.isOpen()) s.send(msg); } catch (Exception e) { Log.w(TAG, "send", e); }
    }

    @JavascriptInterface
    public void broadcast(String msg) {
        List<WebSocket> all; synchronized (socks) { all = new ArrayList<>(socks.values()); }
        for (WebSocket s : all) { try { if (s.isOpen()) s.send(msg); } catch (Exception e) { Log.w(TAG, "broadcast", e); } }
    }

    @JavascriptInterface
    public void kick(int id) { WebSocket s = socks.get(id); if (s != null) s.close(); }

    private void register(String name) {
        nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        regListener = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "registered " + i.getServiceName()); }
            public void onRegistrationFailed(NsdServiceInfo i, int e) { Log.w(TAG, "register failed " + e); }
            public void onServiceUnregistered(NsdServiceInfo i) {}
            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener); } catch (Exception e) { Log.w(TAG, "register", e); }
    }

    // ---------- guest: finding games on the network ----------
    @JavascriptInterface
    public void discoverStart() {
        discoverStop();
        try {
            WifiManager wifi = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wifi.createMulticastLock("festilocos"); lock.setReferenceCounted(false); lock.acquire();
        } catch (Exception ignored) {}
        nsd = (NsdManager) act.getSystemService(Context.NSD_SERVICE);
        discListener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t, int e) { emit("error", "No se pudo buscar partidas (" + e + ")", ""); }
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onServiceFound(NsdServiceInfo i) { if (i.getServiceType().contains("festilocos")) queueResolve(i); }
            public void onServiceLost(NsdServiceInfo i) { emit("lost", i.getServiceName(), ""); }
        };
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener); } catch (Exception e) { emit("error", "No se pudo buscar partidas", ""); }
    }

    private synchronized void queueResolve(NsdServiceInfo i) { resolveQueue.add(i); if (!resolving) resolveNext(); }

    // older Android versions only allow one resolve at a time
    private synchronized void resolveNext() {
        NsdServiceInfo i = resolveQueue.poll();
        if (i == null) { resolving = false; return; }
        resolving = true;
        try {
            nsd.resolveService(i, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) { resolveNext(); }
                public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress h = s.getHost();
                    if (h != null) emit("found", s.getServiceName(), h.getHostAddress() + ":" + s.getPort());
                    resolveNext();
                }
            });
        } catch (Exception e) { resolving = false; }
    }

    @JavascriptInterface
    public void discoverStop() {
        try { if (discListener != null && nsd != null) nsd.stopServiceDiscovery(discListener); } catch (Exception ignored) {}
        discListener = null;
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        synchronized (this) { resolveQueue.clear(); resolving = false; }
    }

    @JavascriptInterface
    public String deviceName() { return Build.MODEL == null ? "Festilocos" : Build.MODEL; }

    @JavascriptInterface
    public String ip() { return ipList(); }

    // every IPv4 address of this phone (Wi-Fi, or the hotspot it is sharing)
    private static String ipList() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) { if (sb.length() > 0) sb.append(","); sb.append(a.getHostAddress()); }
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    public void shutdown() { discoverStop(); hostStop(); }
}
