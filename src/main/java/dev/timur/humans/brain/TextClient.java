package dev.timur.humans.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.timur.humans.HumansPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Text generation (speech + planning) via an OpenAI-compatible endpoint (DeepSeek through 100milionseek by default).
 * Falls back to Gemini Flash-Lite when the endpoint is down, slow or out of quota.
 */
public final class TextClient {
    private final HumansPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Semaphore slots = new Semaphore(4); // the service allows 5 parallel requests per key
    private final Deque<Long> window = new ArrayDeque<>();
    private String key;
    private volatile long downUntilMs;
    public volatile int calls, failures, fallbacks;

    public TextClient(HumansPlugin plugin) { this.plugin = plugin; reloadKey(); }

    public void reloadKey() {
        String path = plugin.getConfig().getString("text.api_key_file", "");
        try {
            key = Files.readString(Path.of(path)).trim();
            plugin.getLogger().info("Text AI key loaded (" + plugin.getConfig().getString("text.model") + ").");
        } catch (Exception e) {
            key = null;
            plugin.getLogger().warning("No text AI key at " + path + " - using Gemini Flash-Lite for speech/plans.");
        }
    }

    public boolean ready() { return key != null && !key.isEmpty() || plugin.gemini().ready(); }

    private synchronized boolean allowed() {
        long now = System.currentTimeMillis();
        while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
        if (window.size() >= plugin.getConfig().getInt("text.per_minute", 30)) return false;
        window.addLast(now);
        return true;
    }

    /** One short spoken line. */
    public CompletableFuture<String> say(String system, String user) {
        return complete(system, user, plugin.getConfig().getInt("text.speech_max_tokens", 900), 25, 90).thenApply(TextClient::clean);
    }

    /**
     * Free-form completion. maxTokens includes the model's reasoning. fallbackTokens = max output for the Gemini fallback.
     */
    public CompletableFuture<String> complete(String system, String user, int maxTokens, int timeoutSec, int fallbackTokens) {
        boolean primary = key != null && !key.isEmpty() && System.currentTimeMillis() > downUntilMs && allowed();
        if (!primary) return fallback(system, user, fallbackTokens);
        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.getConfig().getString("text.model", "deepseek-v4.1-flash-free"));
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.9);
        JsonArray msgs = new JsonArray();
        JsonObject s = new JsonObject(); s.addProperty("role", "system"); s.addProperty("content", system); msgs.add(s);
        JsonObject u = new JsonObject(); u.addProperty("role", "user"); u.addProperty("content", user); msgs.add(u);
        body.add("messages", msgs);
        HttpRequest req = HttpRequest.newBuilder(URI.create(plugin.getConfig().getString("text.base_url", "https://100milionseek.top/v1") + "/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        CompletableFuture<String> out = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            boolean got = false;
            try {
                got = slots.tryAcquire(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                if (!got) throw new RuntimeException("all text slots busy");
                calls++;
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    if (r.statusCode() == 429 || r.statusCode() >= 500) downUntilMs = System.currentTimeMillis() + (r.statusCode() == 429 && r.body().contains("daily_quota") ? 3_600_000 : 30_000);
                    throw new RuntimeException("HTTP " + r.statusCode() + ": " + r.body().substring(0, Math.min(200, r.body().length())));
                }
                JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
                JsonObject msg = j.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                String content = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
                if (content.isBlank()) throw new RuntimeException("empty reply (reasoning used up max_tokens?)");
                out.complete(content);
            } catch (Exception e) {
                failures++;
                if (e instanceof java.net.http.HttpTimeoutException) downUntilMs = System.currentTimeMillis() + 20_000;
                if (plugin.debug()) plugin.getLogger().info("[debug] text AI failed (" + e.getMessage() + "), falling back to Gemini");
                fallback(system, user, fallbackTokens).whenComplete((v, e2) -> { if (e2 != null) out.completeExceptionally(e2); else out.complete(v); });
            } finally { if (got) slots.release(); }
        });
        return out;
    }

    private CompletableFuture<String> fallback(String system, String user, int maxTokens) {
        if (!plugin.gemini().ready()) return CompletableFuture.failedFuture(new IllegalStateException("no text AI available"));
        fallbacks++;
        return plugin.gemini().generate(system, user, maxTokens);
    }

    public String status() {
        return "Text AI: " + plugin.getConfig().getString("text.model") + " calls=" + calls + " failures=" + failures + " gemini-fallbacks=" + fallbacks
            + (System.currentTimeMillis() < downUntilMs ? " (paused " + (downUntilMs - System.currentTimeMillis()) / 1000 + "s after errors)" : "");
    }

    static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("(?s)<think>.*?</think>", "").replaceAll("\\*[^*]{0,40}\\*", "").replace("\n", " ").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
