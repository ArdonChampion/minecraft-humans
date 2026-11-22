package dev.timur.humans;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPOutputStream;

/**
 * Always-on event log for debugging the AI: one JSON object per line in plugins/Humans/logs/humans-YYYY-MM-DD.jsonl.
 * Written on a background thread; old days are gzipped and deleted after `logs.keep_days`.
 * Every line has: t (UTC time), type, and usually h (human name).
 */
public final class HumanLog {
    private static HumanLog instance;
    private final HumansPlugin plugin;
    private final File dir;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(20_000);
    private final Thread writer;
    private final Gson gson = new Gson();
    private volatile boolean running = true;

    public HumanLog(HumansPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "logs");
        dir.mkdirs();
        instance = this;
        writer = new Thread(this::loop, "Humans-log");
        writer.setDaemon(true);
        writer.start();
        housekeeping();
    }

    public static boolean on() { return instance != null && instance.plugin.getConfig().getBoolean("logs.enabled", true); }

    /** Start an event. Fill it with .add/.prop and call log(). */
    public static JsonObject ev(String type, Human h) {
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("type", type);
        if (h != null) {
            o.addProperty("h", h.name);
            if (h.valid()) {
                Location l = h.loc();
                JsonArray p = new JsonArray(); p.add(l.getBlockX()); p.add(l.getBlockY()); p.add(l.getBlockZ());
                o.add("pos", p);
            }
        }
        return o;
    }

    public static void log(JsonObject o) {
        if (!on()) return;
        if (!instance.queue.offer(instance.gson.toJson(o))) { /* queue full: drop rather than stall the server */ }
    }

    /** Quick one-liner events. kv = key, value, key, value... */
    public static void log(String type, Human h, Object... kv) {
        if (!on()) return;
        JsonObject o = ev(type, h);
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, String.valueOf(kv[i]), kv[i + 1]);
        log(o);
    }

    public static void put(JsonObject o, String k, Object v) {
        if (v == null) return;
        if (v instanceof Number n) o.addProperty(k, n);
        else if (v instanceof Boolean b) o.addProperty(k, b);
        else if (v instanceof com.google.gson.JsonElement e) o.add(k, e);
        else { String s = String.valueOf(v); o.addProperty(k, s.length() > 4000 ? s.substring(0, 4000) + "..." : s); }
    }

    public static void error(String where, Human h, Throwable t) {
        if (!on()) return;
        JsonObject o = ev("error", h);
        o.addProperty("where", where);
        o.addProperty("error", String.valueOf(t));
        StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
        String st = sw.toString();
        o.addProperty("stack", st.length() > 3000 ? st.substring(0, 3000) : st);
        log(o);
    }

    private void loop() {
        LocalDate day = null; Writer w = null;
        while (running || !queue.isEmpty()) {
            try {
                String line = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (line == null) { if (w != null) w.flush(); continue; }
                LocalDate now = LocalDate.now(ZoneOffset.UTC);
                if (!now.equals(day)) {
                    if (w != null) w.close();
                    day = now;
                    w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "humans-" + day + ".jsonl"), true), StandardCharsets.UTF_8));
                    housekeeping();
                }
                w.write(line); w.write('\n');
                if (queue.isEmpty()) w.flush();
            } catch (InterruptedException e) { break; }
            catch (Exception e) { plugin.getLogger().warning("Humans log write failed: " + e.getMessage()); }
        }
        try { if (w != null) w.close(); } catch (IOException ignored) {}
    }

    /** gzip finished days, delete very old ones. */
    private void housekeeping() {
        int keep = plugin.getConfig().getInt("logs.keep_days", 14);
        String today = "humans-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl";
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                String n = f.getName();
                if (n.endsWith(".jsonl") && !n.equals(today)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = new GZIPOutputStream(new FileOutputStream(new File(dir, n + ".gz")))) { in.transferTo(out); }
                    f.delete();
                } else if (n.endsWith(".jsonl.gz") && f.lastModified() < System.currentTimeMillis() - keep * 86_400_000L) f.delete();
            } catch (Exception ignored) {}
        }
    }

    public void close() {
        running = false;
        try { writer.join(3000); } catch (InterruptedException ignored) {}
    }
}
