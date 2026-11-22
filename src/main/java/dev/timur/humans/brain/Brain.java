package dev.timur.humans.brain;

import com.google.gson.JsonObject;
import dev.timur.humans.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Item;

import java.util.*;
import java.util.logging.Level;

/**
 * Batches every human that needs a decision into ONE Gemini Decision call:
 * shared evidence = world + each human's situation; per human: action choice, mood, should-talk.
 * Falls back to a hand-written utility brain when the API is unavailable or out of budget.
 */
public final class Brain {
    private final HumansPlugin plugin;
    private boolean inFlight;
    private long lastCallMs, lastBudgetWarn, lastErrorLog;
    private int consecutiveErrors;
    public long aiDecisions, fallbackDecisions;

    public Brain(HumansPlugin plugin) { this.plugin = plugin; }

    public void pump() {
        if (inFlight) return;
        HumanManager m = plugin.manager();
        List<Human> want = new ArrayList<>();
        for (Human h : m.all()) if (h.needsThink && !h.thinking && h.valid() && m.now() >= h.thinkNotBefore && m.isAwake(h)) want.add(h);
        for (Iterator<Human> it = want.iterator(); it.hasNext(); ) {
            Human h = it.next();
            if (h.offlineNext) { it.remove(); h.offlineNext = false; decideOffline(h, m); }
        }
        if (want.isEmpty()) return;
        // humans nobody can see use the free offline brain; Gemini is spent where players are
        if (!plugin.getConfig().getBoolean("brain.always_awake", false)) {
            double r = plugin.getConfig().getDouble("brain.ai_radius", 48);
            for (Iterator<Human> it = want.iterator(); it.hasNext(); ) {
                Human h = it.next();
                if (m.nearestPlayer(h, r) == null) { it.remove(); h.urgent = false; decideOffline(h, m); }
            }
            if (want.isEmpty()) return;
        }
        long nowMs = System.currentTimeMillis();
        double minGap = plugin.getConfig().getDouble("budget.min_seconds_between_calls", 2.0) * 1000;
        if (consecutiveErrors > 0) minGap *= Math.min(30, 1 << Math.min(consecutiveErrors, 5));
        boolean aiOk = plugin.gemini().ready();
        boolean urgent = want.stream().anyMatch(h -> h.urgent);
        if (aiOk && nowMs - lastCallMs < (urgent && consecutiveErrors == 0 ? 400 : minGap)) {
            // humans with nothing to do shouldn't stand around too long: fall back if they've waited > 6s
            for (Human h : want) if (h.task == null && m.now() - h.lastThinkTick > 120) decideOffline(h, m);
            return;
        }
        want.sort(Comparator.comparingLong(h -> h.lastThinkTick));
        int max = plugin.getConfig().getInt("brain.max_humans_per_call", 20);
        if (want.size() > max) want = new ArrayList<>(want.subList(0, max));

        Map<Human, List<Option>> options = new LinkedHashMap<>();
        for (Human h : want) options.put(h, OptionGenerator.generate(h, m));

        if (!aiOk) { options.forEach((h, o) -> applyChoice(h, o, null, m)); return; }

        StringBuilder input = new StringBuilder(header(want.getFirst().loc().getWorld()));
        JsonObject questions = new JsonObject();
        Map<Human, String> qids = new HashMap<>();
        int i = 0;
        for (var e : options.entrySet()) {
            Human h = e.getKey();
            String q = "h" + (++i);
            qids.put(h, q);
            input.append(context(h, q, m));
            questions.add(q + "_act", choice(
                "You are the mind of " + h.name + " (section " + q + "). Choose what " + h.name + " does next. Live like a real, believable person in this world, with "
                    + h.name + "'s personality. Notice your situation first: if trapped, on fire or in danger, deal with that. Otherwise mix things up like a real person: "
                    + "some work (gathering, crafting, your project), some fun (animals, flowers, exploring, watching the view, chatting with other humans) and some rest when tired. "
                    + "Avoid doing the same kind of thing again and again. Keep promises to players, help friends, be playful and entertaining when players are around. "
                    + "Don't repeat an action that just failed.",
                e.getValue()));
            JsonObject moods = new JsonObject();
            moods.addProperty("neutral", "calm, neutral, focused");
            moods.addProperty("happy", "happy, pleased, excited, grateful");
            moods.addProperty("sad", "sad, disappointed, lonely, hurt feelings");
            moods.addProperty("angry", "angry, annoyed, offended");
            moods.addProperty("scared", "scared, nervous, in danger");
            moods.addProperty("surprised", "surprised, amazed, confused");
            questions.add(q + "_mood", choiceRaw("Which facial expression best shows how " + h.name + " (section " + q + ") feels right now?", moods));
            if (m.nearestPlayer(h, 16) != null && System.currentTimeMillis() - h.lastTalkMs > plugin.getConfig().getInt("brain.talk_cooldown_seconds", 40) * 1000L) {
                JsonObject talk = new JsonObject();
                talk.addProperty("type", "noul");
                talk.addProperty("instructions", "Would " + h.name + " (section " + q + ") naturally say something out loud to the players nearby right now, given their personality and what just happened?");
                questions.add(q + "_talk", talk);
            }
            h.thinking = true;
            h.lastThinkTick = m.now();
        }
        int est = input.length() / 4 + questions.toString().length() / 4;
        if (!plugin.budget().canDecide(est)) {
            if (nowMs - lastBudgetWarn > 60_000) { plugin.getLogger().warning("Decision budget reached - using offline brain for now. " + plugin.budget().summary().replace('\n', ' ')); lastBudgetWarn = nowMs; }
            options.forEach((h, o) -> { h.thinking = false; applyChoice(h, o, null, m); });
            return;
        }
        inFlight = true;
        lastCallMs = nowMs;
        final String inputStr = input.toString();
        plugin.gemini().decide(inputStr, questions).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            inFlight = false;
            HumanLog.log(err != null ? "ai_error" : "ai_call", null, "api", "decision", "humans", options.size(), "ms", System.currentTimeMillis() - nowMs,
                "tokens", err == null ? res.tokens() : null, "error", err == null ? null : String.valueOf(err.getMessage()));
            if (err != null) {
                consecutiveErrors++;
                if (System.currentTimeMillis() - lastErrorLog > 30_000) { plugin.getLogger().log(Level.WARNING, "Gemini Decision call failed: " + err.getMessage()); lastErrorLog = System.currentTimeMillis(); }
            } else {
                consecutiveErrors = 0;
                plugin.budget().recordDecision(res.tokens());
                if (plugin.debug()) plugin.getLogger().info("[debug] decision for " + options.size() + " humans, " + res.tokens() + " tokens");
            }
            for (var e : options.entrySet()) {
                Human h = e.getKey();
                h.thinking = false;
                if (!h.valid()) continue;
                applyChoice(h, e.getValue(), err == null ? res.answers() : null, m, qids.get(h));
            }
        }));
    }

    private void applyChoice(Human h, List<Option> opts, JsonObject answers, HumanManager m) { applyChoice(h, opts, answers, m, null); }

    private void applyChoice(Human h, List<Option> opts, JsonObject answers, HumanManager m, String q) {
        Option chosen = null;
        boolean talk = false;
        if (answers != null && q != null) {
            JsonObject act = obj(answers, q + "_act");
            if (act != null && act.has("choice")) {
                String id = act.get("choice").getAsString();
                for (Option o : opts) if (o.id().equals(id)) { chosen = o; break; }
            }
            JsonObject mood = obj(answers, q + "_mood");
            if (mood != null && mood.has("choice") && conf(mood) > 0.4)
                h.setMood(Expression.parse(mood.get("choice").getAsString(), Expression.NEUTRAL), plugin, m.now());
            JsonObject t = obj(answers, q + "_talk");
            if (t != null && t.has("noul") && t.get("noul").getAsDouble() > 0.6) talk = true;
            if (plugin.debug() && act != null) plugin.getLogger().info("[debug] " + h.name + " -> " + act.get("choice") + " (conf " + String.format("%.2f", conf(act)) + ")");
        }
        boolean fromAi = chosen != null;
        if (chosen == null) { chosen = offlinePick(h, opts, m); fallbackDecisions++; } else aiDecisions++;
        logDecision(h, opts, chosen, fromAi ? "ai" : answers == null ? "offline" : "ai-invalid-choice", answers, q);
        m.startTask(h, chosen);
        if (chosen.say() != null && chosen.say().startsWith("!")) {
            if (m.nearestPlayer(h, 24) != null) plugin.chat().broadcast(h, chosen.say().substring(1));
        } else if (chosen.say() != null && m.rnd().nextDouble() < 0.7) {
            plugin.chat().speakUp(h, chosen.say());
        } else if (talk || chosen.talk() && answers == null && m.rnd().nextDouble() < h.personality.chattiness() * 0.5)
            plugin.chat().speakUp(h, "You just decided to: " + chosen.text() + ".");
    }

    private void decideOffline(Human h, HumanManager m) {
        h.lastThinkTick = m.now();
        List<Option> opts = OptionGenerator.generate(h, m);
        fallbackDecisions++;
        Option o = offlinePick(h, opts, m);
        logDecision(h, opts, o, "offline", null, null);
        m.startTask(h, o);
    }

    /** Log what the human could do, what it picked and why (for debugging weird behaviour). */
    static void logDecision(Human h, List<Option> opts, Option chosen, String src, JsonObject answers, String q) {
        if (!HumanLog.on() || chosen == null) return;
        JsonObject ev = HumanLog.ev("decide", h);
        ev.addProperty("src", src);
        ev.addProperty("chose", chosen.id());
        ev.addProperty("text", chosen.text());
        if (answers != null && q != null) {
            JsonObject act = obj(answers, q + "_act"); if (act != null) ev.addProperty("conf", conf(act));
            JsonObject md = obj(answers, q + "_mood"); if (md != null && md.has("choice")) ev.addProperty("mood", md.get("choice").getAsString());
            JsonObject t = obj(answers, q + "_talk"); if (t != null && t.has("noul")) ev.addProperty("talk", t.get("noul").getAsDouble());
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int i = 0; i < Math.min(12, opts.size()); i++) arr.add(opts.get(i).id() + " " + String.format("%.2f", opts.get(i).score()));
        ev.add("options", arr);
        ev.addProperty("n_options", opts.size());
        ev.addProperty("hunger", Math.round(h.hunger)); ev.addProperty("energy", Math.round(h.energy));
        if (h.valid()) ev.addProperty("hp", Math.round(h.entity.getHealth()));
        ev.addProperty("last", h.lastOutcome);
        if (h.goal != null) ev.addProperty("goal", h.goal.title);
        HumanLog.log(ev);
    }

    /** Utility-based fallback: highest score + a bit of randomness, avoiding what just failed. */
    static Option offlinePick(Human h, List<Option> opts, HumanManager m) {
        Option best = null; double bs = -1e9;
        for (Option o : opts) {
            double s = o.score() + m.rnd().nextGaussian() * 0.08;
            if (h.lastOutcome.startsWith("FAILED") && h.lastOutcome.contains(o.text().toLowerCase(Locale.ROOT).split(" ")[0])) s -= 0.3;
            if (s > bs) { bs = s; best = o; }
        }
        return best;
    }

    // ---------------------------------------------------------------- prompt building

    private static String header(World w) {
        long t = w.getTime();
        int hour = (int) ((t / 1000 + 6) % 24), min = (int) ((t % 1000) * 60 / 1000);
        String part = t < 1000 ? "early morning" : t < 6000 ? "morning" : t < 11000 ? "afternoon" : t < 12600 ? "dusk (night is coming)" : t < 23000 ? "NIGHT (monsters spawn)" : "dawn";
        return "MINECRAFT WORLD STATE. Day " + (w.getFullTime() / 24000 + 1) + ", " + String.format("%02d:%02d", hour, min) + " (" + part + "). Weather: "
            + (w.isThundering() ? "thunderstorm" : w.hasStorm() ? "rain" : "clear") + ".\n"
            + "Each section below describes one human: an AI-controlled person who lives in this world and behaves like a real survival player.\n\n";
    }

    static String context(Human h, String q, HumanManager m) {
        StringBuilder s = new StringBuilder();
        s.append("=== ").append(q).append(": ").append(h.name).append(" ===\n");
        s.append("Personality: ").append(h.personality.describe()).append("\n");
        s.append(String.format("Health %.0f/20, hunger %.0f/20 (20 = full).", h.entity.getHealth(), h.hunger));
        s.append(String.format(" Energy %.0f/20%s.", h.energy, h.energy < 6 ? " (TIRED)" : ""));
        s.append(" Best tools: pickaxe=").append(Items.pickaxeTier(h.inv).name().toLowerCase()).append(", weapon=").append(Items.nice(Items.weaponItem(h.inv))).append(".\n");
        s.append("Inventory: ").append(h.inv.describe()).append(".\n");
        if (h.home != null && h.home.getWorld() == h.loc().getWorld())
            s.append("Home: ").append(Math.round(h.home.distance(h.loc()))).append(" blocks away, ").append(h.hutBuilt ? "has a house there." : "no house built yet.").append("\n");
        if (h.goal != null && !h.goal.done) s.append("Current goal: ").append(h.goal.title).append(h.goal.from != null ? " (asked by " + h.goal.from + ")" : "").append(".\n");
        if (h.underground) s.append("! ").append(h.name).append(" is underground in a cave.\n");
        if (h.isChild()) s.append(h.name).append(" is a CHILD (parents: ").append(String.join(", ", h.parents)).append(") - plays, doesn't work.\n");
        if (h.project != null) s.append("Personal project: ").append(OptionGenerator.projectDesc(h.project)).append(".\n");
        if (h.trapped) s.append("!! ").append(h.name).append(" is TRAPPED ").append(h.trappedOpenSky ? "at the bottom of a hole" : "in an enclosed space").append(" and cannot walk away - must dig/climb out.\n");
        if (h.fireAtHome) s.append("!! ").append(h.name).append("'s HOUSE IS ON FIRE.\n");
        else if (h.fireNear > 0) s.append("! Fire is burning nearby (").append(h.fireNear).append(" flames).\n");
        if (h.commitUntil > m.now() && h.commitWho != null) s.append("Promised ").append(h.commitWho).append(" to keep ").append(h.task == null ? "doing what they asked" : h.task.label()).append(".\n");
        if (!h.recentActs.isEmpty()) s.append("Recent activities (newest first): ").append(String.join(", ", h.recentActs)).append(".\n");
        s.append("Currently: ").append(h.task == null ? "nothing" : h.task.label() + " (for " + (m.now() - h.taskStartTick) / 20 + "s)").append(". Last result: ").append(h.lastOutcome).append(".\n");
        // surroundings
        List<String> near = new ArrayList<>();
        for (Entity e : h.entity.getNearbyEntities(20, 8, 20)) {
            int d = (int) Math.round(e.getLocation().distance(h.loc()));
            if (e instanceof Player p) near.add("player " + p.getName() + " " + d + "m (" + h.name + " " + Human.trustWord(h.trustOf(p.getUniqueId())) + " them" + (p.getHealth() < 8 ? ", they're hurt" : "") + ")");
            else if (m.byEntity(e) != null) near.add("human " + m.byEntity(e).name + " " + d + "m");
            else if (e instanceof Monster) near.add(HumanManager.nameOf(e) + " " + d + "m");
            else if (e instanceof Animals) near.add(HumanManager.nameOf(e) + " " + d + "m");
            if (near.size() >= 10) break;
        }
        if (!near.isEmpty()) s.append("Nearby: ").append(String.join(", ", near)).append(".\n");
        if (h.pendingRequest != null && h.pendingRequester != null) {
            Player p = Bukkit.getPlayer(h.pendingRequester);
            if (p != null) s.append("PLAYER REQUEST: ").append(p.getName()).append(" asked ").append(h.name).append(" to ").append(h.pendingRequest.replace("request_", "").replace('_', ' ')).append(".\n");
        }
        if (!h.recent.isEmpty()) s.append("Recent events (newest first): ").append(String.join(" | ", h.recent)).append(".\n");
        if (!h.memories.isEmpty()) s.append("Memories: ").append(String.join(" | ", h.memories.stream().limit(8).toList())).append(".\n");
        s.append("\n");
        return s.toString();
    }

    private static JsonObject choice(String instructions, List<Option> opts) {
        JsonObject crit = new JsonObject();
        for (Option o : opts) if (!crit.has(o.id())) crit.addProperty(o.id(), o.text());
        if (crit.size() < 2) crit.addProperty("rest_extra", "Take a short break and look around");
        return choiceRaw(instructions, crit);
    }

    private static JsonObject choiceRaw(String instructions, JsonObject criteria) {
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions", instructions);
        q.add("criteria", criteria);
        return q;
    }

    private static JsonObject obj(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null; }
    private static double conf(JsonObject o) { return o.has("confidence") ? o.get("confidence").getAsDouble() : 0; }
}
