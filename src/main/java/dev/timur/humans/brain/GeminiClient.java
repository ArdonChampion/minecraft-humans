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
import java.util.concurrent.CompletableFuture;

/** Minimal REST client for Gemini Decisions (choose/score) and generateContent (speech). */
public final class GeminiClient {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    private final HumansPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private String key;

    public GeminiClient(HumansPlugin plugin) {
        this.plugin = plugin;
        reloadKey();
    }

    public void reloadKey() {
        String path = plugin.getConfig().getString("gemini.api_key_file", "");
        try {
            key = Files.readString(Path.of(path)).trim();
            plugin.getLogger().info("Gemini key loaded (" + key.length() + " chars).");
        } catch (Exception e) {
            key = null;
            plugin.getLogger().warning("No Gemini key at " + path + " - humans will use the offline brain only.");
        }
    }

    public boolean ready() { return key != null && !key.isEmpty(); }

    public record DecisionResult(JsonObject answers, int tokens) {}

    /** POST /v1beta/decisions. input = shared evidence text, questions = map of question objects. */
    public CompletableFuture<DecisionResult> decide(String input, JsonObject questions) {
        JsonObject body = new JsonObject();
        body.addProperty("model", plugin.getConfig().getString("gemini.decision_model", "gemini-decision-1"));
        body.addProperty("input", input);
        body.add("questions", questions);
        return post("decisions", body).thenApply(j -> {
            JsonObject answers = j.has("answers") ? j.getAsJsonObject("answers") : new JsonObject();
            int tok = j.has("usage") && j.getAsJsonObject("usage").has("total_tokens") ? j.getAsJsonObject("usage").get("total_tokens").getAsInt() : input.length() / 4;
            return new DecisionResult(answers, tok);
        });
    }

    /** One short spoken line from the chat model. */
    public CompletableFuture<String> say(String system, String user) {
        JsonObject body = new JsonObject();
        JsonObject sys = new JsonObject(); JsonArray sp = new JsonArray(); JsonObject st = new JsonObject();
        st.addProperty("text", system); sp.add(st); sys.add("parts", sp);
        body.add("systemInstruction", sys);
        JsonArray contents = new JsonArray(); JsonObject c = new JsonObject(); c.addProperty("role", "user");
        JsonArray parts = new JsonArray(); JsonObject pt = new JsonObject(); pt.addProperty("text", user); parts.add(pt); c.add("parts", parts);
        contents.add(c); body.add("contents", contents);
        JsonObject gen = new JsonObject();
        gen.addProperty("maxOutputTokens", 90);
        gen.addProperty("temperature", 1.0);
        JsonObject think = new JsonObject(); think.addProperty("thinkingLevel", "minimal"); gen.add("thinkingConfig", think);
        body.add("generationConfig", gen);
        String model = plugin.getConfig().getString("gemini.chat_model", "gemini-3.5-flash-lite");
        return post("models/" + model + ":generateContent", body).thenApply(j -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (var part : j.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts")) {
                    JsonObject po = part.getAsJsonObject();
                    if (po.has("text") && !(po.has("thought") && po.get("thought").getAsBoolean())) sb.append(po.get("text").getAsString());
                }
                return clean(sb.toString());
            } catch (Exception e) { return null; }
        });
    }

    private static String clean(String s) {
        s = s.replaceAll("\\*[^*]{0,40}\\*", "").replace("\n", " ").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) s = s.substring(1, s.length() - 1);
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body) {
        if (!ready()) return CompletableFuture.failedFuture(new IllegalStateException("no API key"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) {
                String b = r.body();
                throw new RuntimeException("HTTP " + r.statusCode() + ": " + (b.length() > 300 ? b.substring(0, 300) : b));
            }
            return JsonParser.parseString(r.body()).getAsJsonObject();
        });
    }
}
