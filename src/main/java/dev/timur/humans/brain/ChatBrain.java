package dev.timur.humans.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.timur.humans.*;
import dev.timur.humans.tasks.Life;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;

/**
 * Players talk to humans in normal chat. Every human within earshot hears it; the addressed one (by name, or the one you were
 * just talking to) answers with ONE AI call that sees its whole situation, the conversation and what players point at,
 * and returns both what to say and what to do (planner skills / a goal). Messages are queued, never dropped.
 * Fallback when DeepSeek is down: the older Gemini-Decision intent pipeline with ready-made lines.
 */
public final class ChatBrain implements Listener {
    private final HumansPlugin plugin;
    private static final String[] INTENTS = {
        "smalltalk", "chatting, making a comment or telling them something",
        "question", "asking them a question (about themselves, the world, their opinion...)",
        "greeting", "saying hello",
        "goodbye", "saying goodbye",
        "compliment", "being nice, thanking, praising or complimenting",
        "insult", "insulting, mocking, threatening or being rude",
        "apology", "apologising",
        "yes", "answering yes / allowing / agreeing to something the human asked",
        "no", "answering no / refusing something the human asked",
        "warning", "warning them about danger (fire, their house burning, a monster, lava...)",
        "request_follow", "asking them to follow or come along",
        "request_come", "asking them to come here / come over",
        "request_stop", "asking them to stop, wait or stay",
        "request_go_home", "telling them to go home",
        "request_give", "asking them to give, share or trade an item",
        "request_fight", "asking them to fight or kill a monster",
        "request_wood", "asking them to get wood or chop trees",
        "request_mine", "asking them to mine stone or ores",
        "request_build", "asking them to build their house",
        "request_escape", "telling them to get out of a hole / climb out / escape / dig out",
        "request_extinguish", "asking them to put out a fire",
        "request_dance", "asking them to dance, jump around or do a trick",
        "request_sleep", "telling them to sleep, nap or go to bed",
        "request_eat", "telling them to eat something",
        "request_feed_animals", "asking them to feed or breed animals",
        "request_farm", "asking them to harvest crops / farm",
        "request_explore", "asking them to go exploring",
        "request_go_outside", "telling them to get out of a cave / go outside / come up",
        "request_place_bed", "telling them to place or use a bed",
        "request_baby", "asking them to have a baby / start a family / make a child",
        "request_goal", "asking them to do a bigger or multi-step job: get specific resources (iron, diamonds, food...), build something (village, house, farm, pen, statue...), organise, line up, prepare for night, set up a base, etc.",
        "request_other", "asking them to do some other specific thing not listed here"
    };
    private static final String[] REPLIES = {
        "talk", "needs a real answer in words: a question, an opinion, a story, a joke, or anything that deserves more than a couple of words",
        "agree", "a quick 'ok' / 'sure'",
        "thanks", "a quick thank-you",
        "greet", "just say hi back",
        "bye", "just say bye",
        "laugh", "just laugh it off",
        "annoyed", "a short annoyed comeback",
        "shrug", "a short unsure 'dunno' / 'maybe'",
        "nothing", "no reply needed"
    };

    public ChatBrain(HumansPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, text));
    }

    // ---------------------------------------------------------------- hearing + routing

    /** Which human each player is currently talking to (so "drop me the pickaxe" + "juno" reach the same human). */
    private final Map<UUID, UUID> partner = new HashMap<>();
    private final Map<UUID, Long> partnerTick = new HashMap<>();

    private void handle(Player p, String text) {
        if (!p.isOnline()) return;
        HumanManager m = plugin.manager();
        long now = m.now();
        String lower = text.toLowerCase(Locale.ROOT);
        // everyone within earshot hears it, addressed or not
        for (Human h : m.all())
            if (h.valid() && h.loc().getWorld() == p.getWorld() && h.loc().distance(p.getLocation()) < 32) h.hear(p.getName(), text, now);
        List<Human> targets = new ArrayList<>();
        boolean everyone = lower.matches(".*\\b(everyone|everybody|guys|all of you|y'all|you all)\\b.*");
        for (Human h : m.all()) {
            if (!h.valid() || h.loc().getWorld() != p.getWorld()) continue;
            double d = h.loc().distance(p.getLocation());
            if (d < 64 && lower.matches(".*\\b" + java.util.regex.Pattern.quote(h.name.toLowerCase(Locale.ROOT)) + "\\b.*")) targets.add(h);
            else if (everyone && d < 24) targets.add(h);
        }
        boolean named = !targets.isEmpty();
        if (!named) {
            // keep talking to the human you were just talking to; otherwise the nearest one
            Human pt = null;
            UUID pid = partner.get(p.getUniqueId());
            if (pid != null && now - partnerTick.getOrDefault(p.getUniqueId(), 0L) < 20 * 120) {
                for (Human h : m.all()) if (h.id.equals(pid) && h.valid() && h.loc().getWorld() == p.getWorld() && h.loc().distance(p.getLocation()) < 48) pt = h;
            }
            if (pt == null) {
                double bd = 16;
                for (Human h : m.all()) {
                    if (!h.valid() || h.loc().getWorld() != p.getWorld()) continue;
                    double d = h.loc().distance(p.getLocation());
                    if (d < bd) { bd = d; pt = h; }
                }
            }
            if (pt != null) targets.add(pt);
        }
        if (targets.size() == 1) { partner.put(p.getUniqueId(), targets.getFirst().id); partnerTick.put(p.getUniqueId(), now); }
        for (Human h : targets.subList(0, Math.min(6, targets.size()))) enqueue(h, p, text, named);
    }

    private void enqueue(Human h, Player p, String text, boolean named) {
        HumanManager m = plugin.manager();
        if (h.sleeping && !named) { HumanLog.log("chat_ignored", h, "player", p.getName(), "text", text, "why", "asleep"); return; }
        h.event(p.getName() + " said: \"" + trim(text, 120) + "\"");
        h.inbox.add(new Human.Msg(p.getUniqueId(), p.getName(), text, named, m.now()));
        while (h.inbox.size() > 6) h.inbox.removeFirst();
        HumanLog.log("chat_heard", h, "player", p.getName(), "text", text, "named", named, "queued", h.inbox.size(), "busy", h.mindBusy);
        scheduleMind(h, plugin.getConfig().getInt("chat.gather_ticks", 30));
    }

    /** Wait a moment so quick bursts ("man can you drop me the pickaxe" / "juno") are answered together. */
    private void scheduleMind(Human h, int delay) {
        if (h.mindBusy || h.mindScheduled) return;
        h.mindScheduled = true;
        later(delay, () -> { h.mindScheduled = false; think(h); });
    }

    // ---------------------------------------------------------------- the mind: one AI call that hears, sees, talks AND acts

    private void think(Human h) {
        if (h.inbox.isEmpty() || h.mindBusy) return;
        if (!h.valid()) { h.inbox.clear(); return; }
        List<Human.Msg> msgs = new ArrayList<>(h.inbox);
        h.inbox.clear();
        Human.Msg last = msgs.getLast();
        Player p = Bukkit.getPlayer(last.player());
        if (p == null || !p.isOnline()) return;
        boolean anyNamed = msgs.stream().anyMatch(Human.Msg::named);
        // DeepSeek when it is up, otherwise the SAME prompt on Gemini Flash-Lite (TextClient falls back) - one behaviour either way
        boolean useMind = "deepseek".equalsIgnoreCase(plugin.getConfig().getString("chat.engine", "deepseek")) && plugin.text().ready();
        if (!useMind) { legacyRespond(h, p, last.text(), anyNamed); return; }
        HumanManager m = plugin.manager();
        h.mindBusy = true;
        StringBuilder user = new StringBuilder(dev.timur.humans.plan.Planner.world(h, m));
        user.append("\nNEW MESSAGE").append(msgs.size() > 1 ? "S" : "").append(" FOR YOU TO ANSWER (use the conversation above for context):\n");
        for (Human.Msg x : msgs)
            user.append("- ").append(x.name()).append(x.named() ? " (said your name)" : " (didn't say your name - maybe talking to you, maybe to someone else)").append(": ").append(x.text()).append("\n");
        user.append("You ").append(Human.trustWord(h.trustOf(p.getUniqueId()))).append(" ").append(p.getName()).append(". Reply with the JSON only.");
        long t0 = System.currentTimeMillis();
        int maxTokens = plugin.getConfig().getInt("chat.mind_max_tokens", 3500), timeout = plugin.getConfig().getInt("chat.mind_timeout_seconds", 15);
        plugin.text().complete(mindSystem(h), user.toString(), maxTokens, timeout, plugin.getConfig().getInt("chat.mind_fallback_tokens", 1500)).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            h.mindBusy = false;
            long ms = System.currentTimeMillis() - t0;
            JsonObject j = null; String problem = null;
            if (err != null) problem = String.valueOf(err.getMessage());
            else try { j = Brain.parseJson(res); } catch (Exception ex) { problem = "unparseable: " + ex.getMessage(); }
            if (j == null) {
                HumanLog.log("ai_error", h, "api", "mind", "ms", ms, "error", problem, "reply", res);
                if (h.valid() && p.isOnline()) legacyRespond(h, p, last.text(), anyNamed);
            } else {
                try { applyMind(h, p, msgs, j, res, ms); }
                catch (Exception ex) { HumanLog.error("mind", h, ex); }
            }
            if (!h.inbox.isEmpty()) scheduleMind(h, 10);
        }));
    }

    private String mindSystem(Human h) {
        return "You are the mind of " + h.name + ", a person living in a Minecraft 26.3 survival world on a small server with friends. "
            + "You are NOT an AI assistant and never say you are. Personality: " + h.personality.describe() + ".\n"
            + "Players talk to you in chat. You get your full situation (STATE), what you can see, the conversation around you and the new message(s). "
            + "Decide what you SAY and what you DO in one go - what you say must match what you actually do (never say you'll do something without putting it in steps/goal, "
            + "never claim you did something you didn't).\n"
            + "How to think:\n"
            + "- Read the whole conversation. Follow-ups like 'not that one', 'like I said', 'do it', 'here', or just your name refer to earlier messages - answer the real request.\n"
            + "- 'this', 'that', 'here', 'there', 'it' usually mean what the player is looking at, holding or standing on, or what they dropped (see PLAYERS AROUND YOU / ITEMS).\n"
            + "- Be helpful and fun: players are your friends. Do what they ask unless you truly hate them, it would hurt someone, or it's impossible. Doing beats talking.\n"
            + "- Use exact items, coordinates, names and counts from STATE in your skill arguments. Give exactly what was asked for.\n"
            + "- Be honest: only mention items, places and progress that really exist in STATE. If you can't do something, say why in plain words and offer the closest thing you can do.\n"
            + "- Small jobs you can do right now -> 'steps' (1-5 skills). Jobs that need gathering, crafting chains or building over time -> 'goal' (describe what's wanted; a planner works it out). "
            + "A request to stop/wait/stay -> 'stop': true. Just chatting -> no steps, no goal (you keep doing what you were doing).\n"
            + "- If what you are CURRENTLY DOING already is what they want (they ask where you're going, repeat the order, or hurry you), set 'keep_current': true, "
            + "leave steps/goal empty and just answer - starting over would throw away your progress.\n"
            + "- If a message clearly is NOT meant for you (talking to another player or human), set 'ignore': true and leave everything else empty.\n"
            + "- Speak like a real player in chat: short (max 25 words), casual, in the SAME LANGUAGE the player wrote in, in character. No emojis, asterisks, quotes or narration.\n"
            + "Reply with ONLY this JSON object:\n"
            + "{\"ignore\":false,\"say\":\"what you say out loud (can be empty)\",\"mood\":\"neutral|happy|sad|angry|scared|surprised\",\"trust\":0,"
            + "\"remember\":\"a short fact worth remembering long-term, or empty\",\"stop\":false,\"keep_current\":false,\"title\":\"2-5 words naming what you'll do\","
            + "\"steps\":[{\"do\":\"skill_name\", ...args}],\"goal\":\"\"}\n"
            + "trust = how this makes you feel about the player, from -2 (much worse) to 2 (much better).\n"
            + "SKILLS (for steps):\n" + dev.timur.humans.plan.Planner.skillsText();
    }

    private void applyMind(Human h, Player p, List<Human.Msg> msgs, JsonObject j, String raw, long ms) {
        HumanManager m = plugin.manager();
        boolean anyNamed = msgs.stream().anyMatch(Human.Msg::named);
        boolean ignore = bool(j, "ignore") && !anyNamed;
        String say = TextClient.clean(str(j, "say")), goal = str(j, "goal").trim(), title = str(j, "title").trim(), mood = str(j, "mood"), rem = str(j, "remember").trim();
        boolean stop = bool(j, "stop"), keep = bool(j, "keep_current");
        List<dev.timur.humans.plan.Goal.Step> steps = new ArrayList<>();
        try { steps = dev.timur.humans.plan.Planner.parse(raw).steps(); } catch (Exception ignored) {}
        double trust = 0;
        try { if (j.has("trust") && j.get("trust").isJsonPrimitive()) trust = Math.max(-2, Math.min(2, j.get("trust").getAsDouble())); } catch (Exception ignored) {}
        if (HumanLog.on()) {
            JsonObject ev = HumanLog.ev("mind", h);
            HumanLog.put(ev, "player", p.getName());
            JsonArray in = new JsonArray(); for (Human.Msg x : msgs) in.add(x.name() + ": " + x.text()); ev.add("heard", in);
            HumanLog.put(ev, "ignore", ignore); HumanLog.put(ev, "say", say); HumanLog.put(ev, "mood", mood); HumanLog.put(ev, "trust", trust);
            HumanLog.put(ev, "stop", stop); HumanLog.put(ev, "keep_current", keep); HumanLog.put(ev, "goal", goal); HumanLog.put(ev, "title", title); HumanLog.put(ev, "remember", rem);
            JsonArray st = new JsonArray(); for (var s : steps) st.add(s.describe()); ev.add("steps", st);
            HumanLog.put(ev, "ms", ms);
            HumanLog.log(ev);
        }
        if (ignore) return;
        h.addTrust(p, trust * 0.2);
        Expression face = Expression.parse(mood, Expression.NEUTRAL);
        if (face != Expression.NEUTRAL) h.express(face, 80, plugin, m.now());
        if (!rem.isEmpty() && !rem.equalsIgnoreCase("none")) h.remember(trim(rem, 120));
        if (say != null && !say.isBlank()) say(h, say);
        String lastText = msgs.getLast().text();
        if (stop) {
            if (h.goal != null) h.event("stopped working on: " + h.goal.title);
            h.goal = null;
            m.startTask(h, new Option("do_request", "stop and wait, like " + p.getName() + " asked", () -> new dev.timur.humans.tasks.Tasks.Idle(20 * 20, "waiting"), 1));
            h.commitUntil = m.now() + 20 * 60; h.commitWho = p.getName();
        }
        // already doing exactly this? keep going instead of starting over
        if (!keep && !stop && h.goal != null && !h.goal.done && h.task instanceof dev.timur.humans.plan.PlanTask && goal.isEmpty() && !steps.isEmpty()) {
            List<String> rest = new ArrayList<>(), all = new ArrayList<>(), want = new ArrayList<>();
            for (int i = 0; i < h.goal.steps.size(); i++) { all.add(h.goal.steps.get(i).describe()); if (i >= h.goal.stepIdx - 1) rest.add(h.goal.steps.get(i).describe()); }
            for (var st : steps) want.add(st.describe());
            if (want.equals(all) || rest.containsAll(want)) keep = true;
        }
        if (keep && !stop) {
            HumanLog.log("mind_keep", h, "player", p.getName(), "task", h.task == null ? null : h.task.label());
            if (h.goal != null && h.goal.fromId != null && !h.goal.fromId.equals(p.getUniqueId())) h.goal.note(p.getName() + " also wants this");
            return;
        }
        if (!goal.isEmpty() || !steps.isEmpty()) {
            StringBuilder said = new StringBuilder();
            for (Human.Msg x : msgs) said.append(said.isEmpty() ? "" : " / ").append(x.text());
            dev.timur.humans.plan.Goal g = new dev.timur.humans.plan.Goal(p.getName() + " said: \"" + trim(said.toString(), 220) + "\"" + (goal.isEmpty() ? "" : " - what's wanted: " + goal),
                p.getName(), p.getUniqueId(), groupOf(h, p, lastText), m.now());
            if (!title.isEmpty()) g.title = trim(title, 48);
            if (!steps.isEmpty()) { g.steps = steps; g.quick = goal.isEmpty(); }
            HumanLog.log("goal_new", h, "from", p.getName(), "text", g.text, "group", g.group, "quick", g.quick);
            h.goal = g;
            m.startTask(h, new Option("do_request", "do what " + p.getName() + " asked: " + g.title, () -> new dev.timur.humans.plan.PlanTask(g), 1));
            h.commitUntil = m.now() + 20L * 60 * (g.quick ? 5 : 15);
            h.commitWho = p.getName();
            h.remember("agreed to " + p.getName() + "'s request: " + trim(lastText, 60));
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }
    private static boolean bool(JsonObject o, String k) { try { return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean(); } catch (Exception e) { return false; } }

    private static JsonObject choiceQ(String instructions, String[] pairs) {
        JsonObject q = new JsonObject(); q.addProperty("type", "choice"); q.addProperty("instructions", instructions);
        JsonObject c = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) c.addProperty(pairs[i], pairs[i + 1]);
        q.add("criteria", c);
        return q;
    }

    /** Fallback when DeepSeek is unavailable: Gemini Decision sorts the message into an intent. */
    private void legacyRespond(Human h, Player p, String text, boolean named) {
        if (h.chatInFlight) return;
        HumanManager m = plugin.manager();
        h.chatInFlight = true;
        if (!plugin.gemini().ready()) { offlineReply(h, p, text); h.chatInFlight = false; return; }

        // understand (one cheap Decision call)
        JsonObject qs = new JsonObject();
        qs.add("intent", choiceQ("What is " + p.getName() + " doing with this chat message towards " + h.name + "?", INTENTS));
        JsonObject trust = new JsonObject(); trust.addProperty("type", "score");
        trust.addProperty("instructions", "How should this message change how much " + h.name + " likes " + p.getName() + ", given " + h.name + "'s personality?");
        JsonArray levels = new JsonArray(); for (String l : new String[]{"much less", "a bit less", "no change", "a bit more", "much more"}) levels.add(l);
        trust.add("criteria", levels); qs.add("trust", trust);
        JsonObject mood = new JsonObject(); mood.addProperty("type", "choice");
        mood.addProperty("instructions", "Which facial expression would " + h.name + " make hearing this?");
        JsonObject mc = new JsonObject();
        for (Expression ex : Expression.MOODS) mc.addProperty(ex.id(), ex.id());
        mood.add("criteria", mc); qs.add("mood", mood);
        qs.add("reply", choiceQ("What kind of reply would " + h.name + " give out loud? Prefer a short reply unless the message really deserves words.", REPLIES));
        if (!named) {
            JsonObject addr = new JsonObject(); addr.addProperty("type", "noul");
            addr.addProperty("instructions", "Is " + p.getName() + " talking to " + h.name + " (a person standing near them) rather than to other players in chat?");
            qs.add("addressed", addr);
        }
        String evidence = Brain.context(h, "h1", m).replace("=== h1: ", "=== ") + "\n" + p.getName() + " says in chat: \"" + text + "\"";
        if (!plugin.budget().canDecide(evidence.length() / 4 + 450)) { offlineReply(h, p, text); h.chatInFlight = false; return; }

        plugin.gemini().decide(evidence, qs).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            h.chatInFlight = false;
            if (err != null || !h.valid()) { if (err != null && h.valid()) offlineReply(h, p, text); return; }
            plugin.budget().recordDecision(res.tokens());
            JsonObject a = res.answers();
            if (!named && a.has("addressed") && a.getAsJsonObject("addressed").get("noul").getAsDouble() < 0.45) {
                HumanLog.log("chat_ignored", h, "player", p.getName(), "text", text, "addressed", a.getAsJsonObject("addressed").get("noul").getAsDouble());
                return;
            }
            String it = a.has("intent") ? a.getAsJsonObject("intent").get("choice").getAsString() : "smalltalk";
            String kind = a.has("reply") ? a.getAsJsonObject("reply").get("choice").getAsString() : "talk";
            double idx = a.has("trust") ? a.getAsJsonObject("trust").get("score").getAsDouble() : 2;
            h.addTrust(p, (idx - 2) * 0.35);
            Expression face = a.has("mood") ? Expression.parse(a.getAsJsonObject("mood").get("choice").getAsString(), Expression.NEUTRAL) : Expression.NEUTRAL;
            if (face != Expression.NEUTRAL) h.express(face, 80, plugin, m.now());
            if (it.equals("insult")) h.remember(p.getName() + " insulted me: \"" + trim(text, 60) + "\"");
            if (it.equals("compliment") || it.equals("apology")) h.remember(p.getName() + " was nice to me (" + it + ")");
            HumanLog.log("chat_in", h, "player", p.getName(), "text", text, "named", named, "intent", it, "reply_kind", kind,
                "trust_score", idx, "trust_now", h.trustOf(p.getUniqueId()), "face", face.id());
            if (plugin.debug()) plugin.getLogger().info("[debug] chat " + p.getName() + "->" + h.name + ": intent=" + it + " reply=" + kind + " trust=" + String.format("%.2f", idx) + " face=" + face);

            if (it.equals("request_goal") || it.equals("request_other")) { handleGoal(h, p, text, groupOf(h, p, text)); return; }
            if (it.startsWith("request_")) { handleRequest(h, p, it); return; }
            if ((it.equals("yes") || it.equals("no")) && h.ask != null && h.ask.player().equals(p.getUniqueId()) && m.now() - h.ask.tick() < 20 * 120) { handleAnswer(h, p, it.equals("yes")); return; }
            if (it.equals("warning")) { handleWarning(h, p, text); return; }

            if (kind.equals("talk") || it.equals("question")) talk(h, p, text, "");
            else replyCanned(h, p, kind);
        }));
    }

    // ---------------------------------------------------------------- requests

    /** Other humans addressed by the same message (for teamwork). */
    private String groupOf(Human h, Player p, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean everyone = lower.matches(".*\\b(everyone|everybody|guys|all of you|y'all|you all)\\b.*");
        List<String> names = new ArrayList<>();
        for (Human o : plugin.manager().all()) {
            if (o == h || !o.valid() || o.loc().getWorld() != p.getWorld() || o.loc().distance(p.getLocation()) > 64) continue;
            if (everyone && o.loc().distance(p.getLocation()) < 24 || lower.matches(".*\\b" + java.util.regex.Pattern.quote(o.name.toLowerCase(Locale.ROOT)) + "\\b.*")) names.add(o.name);
        }
        return String.join(", ", names);
    }

    /** A bigger job: becomes a Goal the planner AI works through step by step. */
    public void handleGoal(Human h, Player p, String text, String group) {
        HumanManager m = plugin.manager();
        if (!plugin.text().ready()) { later(10, () -> say(h, Lines.pick(Lines.CANT, h, p.getName(), null))); return; }
        boolean yes = m.rnd().nextDouble() < acceptChance(h, p, "request_goal");
        later(6 + m.rnd().nextInt(14), () -> {
            if (!h.valid()) return;
            if (!yes) { say(h, Lines.pick(Lines.REFUSE, h, p.getName(), null)); h.remember("refused " + p.getName() + "'s request: " + trim(text, 60)); return; }
            say(h, Lines.pick(Lines.AGREE, h, p.getName(), null));
            dev.timur.humans.plan.Goal g = new dev.timur.humans.plan.Goal(p.getName() + " said: \"" + trim(text, 200) + "\"", p.getName(), p.getUniqueId(), group, m.now());
            HumanLog.log("goal_new", h, "from", p.getName(), "text", text, "group", group);
            h.goal = g;
            m.startTask(h, new Option("do_request", "work on " + p.getName() + "'s request", () -> new dev.timur.humans.plan.PlanTask(g), 1));
            h.commitUntil = m.now() + 20L * 60 * 15;
            h.commitWho = p.getName();
            h.remember("agreed to " + p.getName() + "'s request: " + trim(text, 60));
        });
    }

    /** Accept chance: humans are cooperative; only people they dislike get refused often. */
    private double acceptChance(Human h, Player p, String intent) {
        double tr = h.trustOf(p.getUniqueId());
        double c = tr >= 1.4 ? 0.95 : tr >= 0.6 ? 0.75 : 0.35;
        if (intent.equals("request_give")) c *= 0.75 + h.personality.generosity() * 0.25;
        if (intent.equals("request_escape") || intent.equals("request_extinguish")) c = 1; // obviously yes
        return c;
    }

    public void handleRequest(Human h, Player p, String intent) {
        HumanManager m = plugin.manager();
        OptionGenerator.Request r = OptionGenerator.request(h, m, p, intent);
        if (r.option() == null) HumanLog.log("request", h, "player", p.getName(), "intent", intent, "result", "cant", "reason", r.cant());
        int delay = 6 + m.rnd().nextInt(14);
        if (r.option() == null) {
            String why = r.cant() != null ? r.cant() : Lines.pick(Lines.CANT, h, p.getName(), null);
            later(delay, () -> say(h, why));
            return;
        }
        boolean yes = m.rnd().nextDouble() < acceptChance(h, p, intent);
        HumanLog.log("request", h, "player", p.getName(), "intent", intent, "result", yes ? "accepted" : "refused", "trust", h.trustOf(p.getUniqueId()));
        later(delay, () -> {
            if (!h.valid()) return;
            if (yes) {
                say(h, Lines.pick(Lines.AGREE, h, p.getName(), null));
                if (intent.equals("request_stop") || intent.equals("request_go_home")) { if (h.goal != null) h.event("stopped working on: " + h.goal.title); h.goal = null; }
                m.startTask(h, r.option());
                h.commitUntil = m.now() + 20 * 120;
                h.commitWho = p.getName();
                h.remember("agreed to " + p.getName() + "'s request (" + intent.replace("request_", "").replace('_', ' ') + ")");
            } else {
                say(h, Lines.pick(Lines.REFUSE, h, p.getName(), null));
                h.express(Expression.ANGRY, 30, plugin, m.now());
                h.remember("refused " + p.getName() + "'s request (" + intent.replace("request_", "").replace('_', ' ') + ")");
            }
        });
    }

    private void handleAnswer(Human h, Player p, boolean yes) {
        HumanManager m = plugin.manager();
        Human.Ask ask = h.ask;
        h.ask = null;
        later(6 + m.rnd().nextInt(10), () -> {
            if (!h.valid()) return;
            if (yes) {
                say(h, Lines.pick(Lines.ASK_OK, h, p.getName(), null));
                h.express(Expression.HAPPY, 40, plugin, m.now());
                h.chestPermission.put(p.getUniqueId(), m.now());
                m.startTask(h, new Option("do_request", "take from chest", () -> new Life.TakeFromChest(ask.chest(), ask.mat(), p.getName()), 1));
            } else {
                say(h, Lines.pick(Lines.ASK_DENIED, h, p.getName(), null));
                h.remember(p.getName() + " said I can't take things from their chest");
            }
        });
    }

    private void handleWarning(Human h, Player p, String text) {
        HumanManager m = plugin.manager();
        double tr = h.trustOf(p.getUniqueId());
        String l = text.toLowerCase(Locale.ROOT);
        boolean aboutHome = l.matches(".*\\b(house|home|hut|base)\\b.*");
        boolean seesFire = h.fireNear > 0 || !Awareness.fires(h.loc(), 12, 3, 6).isEmpty();
        boolean homeBurning = Awareness.homeOnFire(h);
        boolean homeClose = h.hutCenter != null && h.hutCenter.getWorld() == h.loc().getWorld() && h.hutCenter.distance(h.loc()) < 20;
        h.alertFrom = p.getUniqueId(); h.alertTick = m.now();
        later(5 + m.rnd().nextInt(8), () -> {
            if (!h.valid()) return;
            if (homeBurning && (homeClose || tr >= 1.4)) {
                h.fireAtHome = true;
                say(h, Lines.pick(Lines.WARN_BELIEVE, h, p.getName(), null));
                h.express(Expression.SCARED, 80, plugin, m.now());
                h.remember(p.getName() + " warned me my house was on fire - it was true!");
                h.addTrust(p, 0.3);
                m.interrupt(h, p.getName() + " warned me: MY HOUSE IS ON FIRE");
            } else if (seesFire) {
                say(h, Lines.pick(Lines.WARN_BELIEVE, h, p.getName(), null));
                m.interrupt(h, p.getName() + " warned me about the fire nearby");
            } else if (aboutHome && h.home != null && !homeClose && tr >= 1.2) {
                say(h, Lines.pick(Lines.WARN_CHECK, h, p.getName(), null));
                h.express(Expression.SURPRISED, 40, plugin, m.now());
                m.interrupt(h, p.getName() + " warned that something is wrong at my house: \"" + trim(text, 60) + "\"");
            } else if (tr < 1.2) {
                say(h, Lines.pick(Lines.WARN_DOUBT, h, p.getName(), null));
            } else {
                say(h, Lines.pick(Lines.WARN_NOTHING, h, p.getName(), null));
                m.interrupt(h, p.getName() + " warned me: \"" + trim(text, 60) + "\"");
            }
        });
    }

    // ---------------------------------------------------------------- replies

    private void replyCanned(Human h, Player p, String kind) {
        String[] pool = switch (kind) {
            case "agree" -> Lines.AGREE; case "thanks" -> Lines.THANKS; case "greet" -> Lines.GREET; case "bye" -> Lines.BYE;
            case "laugh" -> Lines.LAUGH; case "annoyed" -> Lines.ANNOYED; case "shrug" -> Lines.SHRUG;
            default -> null;
        };
        if (pool == null) return; // "nothing"
        later(8 + plugin.manager().rnd().nextInt(16), () -> say(h, Lines.pick(pool, h, p.getName(), null)));
    }

    private void talk(Human h, Player p, String text, String extra) {
        if (!plugin.budget().canChat()) { replyCanned(h, p, "shrug"); return; }
        plugin.budget().recordChat();
        h.chatInFlight = true;
        plugin.text().say(persona(h, p), p.getName() + " says to you: " + text + extra).whenComplete((line, e2) -> Bukkit.getScheduler().runTask(plugin, () -> {
            h.chatInFlight = false;
            if (!h.valid()) return;
            if (line == null || line.isBlank()) { replyCanned(h, p, "shrug"); return; }
            say(h, line);
        }));
    }

    private void say(Human h, String line) {
        if (!h.valid()) return;
        broadcast(h, line);
        h.event(h.name + " said: \"" + trim(line, 80) + "\"");
    }

    private void later(int ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); }

    /** Spontaneous line (the decision brain decided this human wants to talk). */
    public void speakUp(Human h, String situation) {
        if (h.chatInFlight || h.mindBusy || !h.valid()) return;
        Player near = plugin.manager().nearestPlayer(h, 16);
        if (near == null) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - h.lastTalkMs < 8000 || nowMs - h.lastSpontaneousMs < spontaneousCooldownMs()) return;
        h.lastTalkMs = nowMs;
        if (!plugin.text().ready() || !plugin.budget().canChat()) { broadcast(h, Canned.line(h, plugin.manager().rnd())); return; }
        plugin.budget().recordChat();
        h.chatInFlight = true;
        List<String> names = new ArrayList<>();
        for (Player p : h.entity.getWorld().getPlayers()) if (p.getLocation().distance(h.loc()) < 16) names.add(p.getName());
        plugin.text().say(persona(h, near), "Say ONE short natural line out loud to the people nearby (" + String.join(", ", names) + "). " + situation
            + " Don't repeat what you or others already said (see the conversation), and don't ask again a question nobody answered yet."
            + " If you have nothing new worth saying, reply with just: -")
            .whenComplete((line, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                h.chatInFlight = false;
                if (h.valid()) saySpontaneous(h, line);
            }));
    }

    private long spontaneousCooldownMs() { return plugin.getConfig().getInt("chat.spontaneous_cooldown_seconds", 45) * 1000L; }

    /**
     * Unprompted lines (decisions, planner thoughts): rate-limited per human and never a near-repeat of what this human
     * said in the last few minutes - so nobody nags or repeats the same question over and over.
     */
    public boolean saySpontaneous(Human h, String line) {
        if (line == null) return false;
        line = line.strip();
        if (line.isEmpty() || line.equals("-") || line.equals("...")) return false;
        long now = System.currentTimeMillis();
        if (now - h.lastSpontaneousMs < spontaneousCooldownMs()) { HumanLog.log("say_skipped", h, "line", line, "why", "cooldown"); return false; }
        if (repeats(h, line)) { HumanLog.log("say_skipped", h, "line", line, "why", "repeat"); return false; }
        h.lastSpontaneousMs = now;
        say(h, line);
        return true;
    }

    /** Is this nearly the same as something this human said in the last 4 minutes? */
    private boolean repeats(Human h, String line) {
        Set<String> a = words(line);
        if (a.size() < 2) return false;
        long now = plugin.manager().now();
        for (Human.Heard x : h.heard) {
            if (!x.who().equals(h.name) || now - x.tick() > 20 * 240) continue;
            Set<String> b = words(x.text());
            Set<String> inter = new HashSet<>(a); inter.retainAll(b);
            Set<String> uni = new HashSet<>(a); uni.addAll(b);
            if (!uni.isEmpty() && inter.size() / (double) uni.size() >= 0.45) return true;
        }
        return false;
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() > 2) out.add(w);
        return out;
    }

    /** Thank a player for a gift (ready-made line, no AI). */
    public void thank(Human h, Player p, String gift) {
        later(8 + plugin.manager().rnd().nextInt(12), () -> say(h, Lines.pick(Lines.THANKS, h, p.getName(), gift)));
    }

    String persona(Human h, Player p) {
        return "You are " + h.name + ", a person living inside a Minecraft survival world on a small server with friends. You are NOT an AI assistant and never say you are. "
            + "Personality: " + h.personality.describe() + ". "
            + "You feel " + h.mood.id() + " and you " + Human.trustWord(h.trustOf(p.getUniqueId())) + " " + p.getName() + ". "
            + "Right now you are " + (h.task == null ? "idle" : h.task.label()) + ". Health " + Math.round(h.entity.getHealth()) + "/20, hunger " + Math.round(h.hunger) + "/20, energy " + Math.round(h.energy) + "/20. "
            + (h.trapped ? "You are STUCK in a hole right now. " : "") + (h.fireAtHome ? "Your house is ON FIRE! " : h.fireNear > 0 ? "There's fire burning near you! " : "")
            + "You can see fine in the dark (like night vision), so night or caves don't blind you. "
            + (h.project != null ? "Your current project: " + OptionGenerator.projectDesc(h.project) + ". " : "")
            + "You carry: " + h.inv.describe() + ". " + (h.hutBuilt ? "You have built your own house. " : "You don't have a house yet. ")
            + (h.recent.isEmpty() ? "" : "Recent events: " + String.join(" | ", h.recent) + ". ")
            + (h.memories.isEmpty() ? "" : "Memories: " + String.join(" | ", h.memories.stream().limit(8).toList()) + ". ")
            + convoLine(h)
            + "Reply with ONE short spoken line (max 25 words) in the same language the player used. Be in character, natural, funny when it fits. "
            + "No asterisks, no emojis, no quotation marks, no narration.";
    }

    public void broadcast(Human h, String line) {
        HumanLog.log("say", h, "line", line, "task", h.task == null ? null : h.task.label());
        long nowT = plugin.manager().now();
        for (Human o : plugin.manager().all()) if (o.valid() && o.loc().getWorld() == h.loc().getWorld() && o.loc().distance(h.loc()) < 24) o.hear(h.name, line, nowT);
        Component msg = Component.text("<", NamedTextColor.GRAY).append(Component.text(h.name, NamedTextColor.GOLD)).append(Component.text("> ", NamedTextColor.GRAY)).append(Component.text(line, NamedTextColor.WHITE));
        for (Player p : h.entity.getWorld().getPlayers()) if (p.getLocation().distance(h.loc()) < 40) p.sendMessage(msg);
        h.say(line, plugin.manager().now());
        h.lastTalkMs = System.currentTimeMillis();
        h.entity.getWorld().playSound(h.loc(), "entity.wandering_trader.ambient", 0.5f, 1.0f + plugin.manager().rnd().nextFloat() * 0.3f);
        Player near = plugin.manager().nearestPlayer(h, 8);
        if (near != null && (h.task == null || h.task instanceof dev.timur.humans.tasks.Tasks.Idle)) dev.timur.humans.tasks.Act.lookAt(h, near.getEyeLocation());
    }

    /** No AI available: keyword matching. */
    private void offlineReply(Human h, Player p, String text) {
        String l = text.toLowerCase(Locale.ROOT);
        String[][] keys = {{"follow", "request_follow"}, {"come here", "request_come"}, {"come over", "request_come"}, {"stop", "request_stop"}, {"wait", "request_stop"},
            {"go home", "request_go_home"}, {"dance", "request_dance"}, {"get out", "request_escape"}, {"climb out", "request_escape"}, {"fire", "request_extinguish"},
            {"sleep", "request_sleep"}, {"eat", "request_eat"}, {"wood", "request_wood"}, {"mine", "request_mine"}};
        for (String[] k : keys) if (l.contains(k[0])) { handleRequest(h, p, k[1]); return; }
        if ((l.matches(".*\\b(yes|yeah|sure|ok|okay|yep)\\b.*") || l.matches(".*\\b(no|nope|nah)\\b.*")) && h.ask != null && h.ask.player().equals(p.getUniqueId())) {
            handleAnswer(h, p, !l.matches(".*\\b(no|nope|nah)\\b.*")); return;
        }
        if (l.matches(".*\\b(hi|hello|hey|yo|sup)\\b.*")) { replyCanned(h, p, h.trustOf(p.getUniqueId()) < 1.4 ? "annoyed" : "greet"); return; }
        if (l.matches(".*\\b(bye|cya|later)\\b.*")) { replyCanned(h, p, "bye"); return; }
        if (l.matches(".*\\b(thanks|thank you|ty)\\b.*")) { replyCanned(h, p, "agree"); return; }
        broadcast(h, Canned.line(h, plugin.manager().rnd()));
    }

    private String convoLine(Human h) {
        String c = h.conversation(plugin.manager().now(), 8, 20L * 60 * 4);
        return c.isEmpty() ? "" : "Conversation around you: " + c.replace("\n", " | ") + ". ";
    }

    private static String trim(String s, int n) { return s.length() > n ? s.substring(0, n - 3) + "..." : s; }

    /** Lines used when the AI budget is used up. */
    static final class Canned {
        static String line(Human h, Random r) {
            String[] happy = {"What a day!", "Life's good out here.", "Hehe, look at this!", "I love this place."};
            String[] sad = {"Not my best day...", "Sigh.", "I miss the old days."};
            String[] angry = {"Hmph.", "Don't push me.", "Leave me alone."};
            String[] scared = {"D-did you hear that?", "I don't like this...", "We should get inside!"};
            String[] neutral = {"Hmm.", "Busy busy.", "Need more wood...", "Nice weather.", "Hey there."};
            String[] pool = switch (h.mood) { case HAPPY -> happy; case SAD -> sad; case ANGRY -> angry; case SCARED -> scared; default -> neutral; };
            return pool[r.nextInt(pool.length)];
        }
    }
}
