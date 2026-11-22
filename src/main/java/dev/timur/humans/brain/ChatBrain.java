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
 * Players talk to humans in normal chat.
 * Gemini Decision understands the message (intent / trust / mood / what kind of reply).
 * Simple replies ("ok", "thanks", "hi") are ready-made lines; only real conversation goes to Flash-Lite.
 * Requests are carried out directly - humans are cooperative unless they really dislike you.
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

    private void handle(Player p, String text) {
        if (!p.isOnline()) return;
        HumanManager m = plugin.manager();
        String lower = text.toLowerCase(Locale.ROOT);
        List<Human> targets = new ArrayList<>();
        boolean everyone = lower.matches(".*\\b(everyone|everybody|guys|all of you|y'all)\\b.*");
        for (Human h : m.all()) {
            if (!h.valid() || h.loc().getWorld() != p.getWorld()) continue;
            double d = h.loc().distance(p.getLocation());
            if (d < 64 && lower.matches(".*\\b" + java.util.regex.Pattern.quote(h.name.toLowerCase(Locale.ROOT)) + "\\b.*")) targets.add(h);
            else if (everyone && d < 24) targets.add(h);
        }
        boolean named = !targets.isEmpty();
        if (!named) {
            Human best = null; double bd = 12;
            for (Human h : m.all()) {
                if (!h.valid() || h.loc().getWorld() != p.getWorld()) continue;
                double d = h.loc().distance(p.getLocation());
                if (d < bd) { bd = d; best = h; }
            }
            if (best != null) targets.add(best);
        }
        for (Human h : targets.subList(0, Math.min(everyone ? 6 : 2, targets.size()))) respond(h, p, text, named);
    }

    private static JsonObject choiceQ(String instructions, String[] pairs) {
        JsonObject q = new JsonObject(); q.addProperty("type", "choice"); q.addProperty("instructions", instructions);
        JsonObject c = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) c.addProperty(pairs[i], pairs[i + 1]);
        q.add("criteria", c);
        return q;
    }

    private void respond(Human h, Player p, String text, boolean named) {
        if (h.chatInFlight || h.sleeping && !named) return;
        HumanManager m = plugin.manager();
        h.chatInFlight = true;
        h.event(p.getName() + " said: \"" + text + "\"");
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
            if (!named && a.has("addressed") && a.getAsJsonObject("addressed").get("noul").getAsDouble() < 0.45) return;
            String it = a.has("intent") ? a.getAsJsonObject("intent").get("choice").getAsString() : "smalltalk";
            String kind = a.has("reply") ? a.getAsJsonObject("reply").get("choice").getAsString() : "talk";
            double idx = a.has("trust") ? a.getAsJsonObject("trust").get("score").getAsDouble() : 2;
            h.addTrust(p, (idx - 2) * 0.35);
            Expression face = a.has("mood") ? Expression.parse(a.getAsJsonObject("mood").get("choice").getAsString(), Expression.NEUTRAL) : Expression.NEUTRAL;
            if (face != Expression.NEUTRAL) h.express(face, 80, plugin, m.now());
            if (it.equals("insult")) h.remember(p.getName() + " insulted me: \"" + trim(text, 60) + "\"");
            if (it.equals("compliment") || it.equals("apology")) h.remember(p.getName() + " was nice to me (" + it + ")");
            if (plugin.debug()) plugin.getLogger().info("[debug] chat " + p.getName() + "->" + h.name + ": intent=" + it + " reply=" + kind + " trust=" + String.format("%.2f", idx) + " face=" + face);

            if (it.startsWith("request_") && !it.equals("request_other")) { handleRequest(h, p, it); return; }
            if ((it.equals("yes") || it.equals("no")) && h.ask != null && h.ask.player().equals(p.getUniqueId()) && m.now() - h.ask.tick() < 20 * 120) { handleAnswer(h, p, it.equals("yes")); return; }
            if (it.equals("warning")) { handleWarning(h, p, text); return; }

            String extra = it.equals("request_other") ? " (They are asking you to do something. You are a simple Minecraft villager-like person: say whether you would and joke about it, but you can't actually do unusual things.)" : "";
            if (kind.equals("talk") || it.equals("question") || !extra.isEmpty()) talk(h, p, text, extra);
            else replyCanned(h, p, kind);
        }));
    }

    // ---------------------------------------------------------------- requests

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
        int delay = 6 + m.rnd().nextInt(14);
        if (r.option() == null) {
            String why = r.cant() != null ? r.cant() : Lines.pick(Lines.CANT, h, p.getName(), null);
            later(delay, () -> say(h, why));
            return;
        }
        boolean yes = m.rnd().nextDouble() < acceptChance(h, p, intent);
        later(delay, () -> {
            if (!h.valid()) return;
            if (yes) {
                say(h, Lines.pick(Lines.AGREE, h, p.getName(), null));
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
        plugin.gemini().say(persona(h, p), p.getName() + " says to you: " + text + extra).whenComplete((line, e2) -> Bukkit.getScheduler().runTask(plugin, () -> {
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
        if (h.chatInFlight || !h.valid()) return;
        Player near = plugin.manager().nearestPlayer(h, 16);
        if (near == null) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - h.lastTalkMs < 8000) return;
        h.lastTalkMs = nowMs;
        if (!plugin.gemini().ready() || !plugin.budget().canChat()) { broadcast(h, Canned.line(h, plugin.manager().rnd())); return; }
        plugin.budget().recordChat();
        h.chatInFlight = true;
        List<String> names = new ArrayList<>();
        for (Player p : h.entity.getWorld().getPlayers()) if (p.getLocation().distance(h.loc()) < 16) names.add(p.getName());
        plugin.gemini().say(persona(h, near), "Say ONE short natural line out loud to the people nearby (" + String.join(", ", names) + "). " + situation)
            .whenComplete((line, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                h.chatInFlight = false;
                if (line != null && !line.isBlank() && h.valid()) { broadcast(h, line); h.event(h.name + " said: \"" + trim(line, 80) + "\""); }
            }));
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
            + (h.project != null ? "Your current project: " + OptionGenerator.projectDesc(h.project) + ". " : "")
            + "You carry: " + h.inv.describe() + ". " + (h.hutBuilt ? "You have built your own house. " : "You don't have a house yet. ")
            + (h.recent.isEmpty() ? "" : "Recent events: " + String.join(" | ", h.recent) + ". ")
            + (h.memories.isEmpty() ? "" : "Memories: " + String.join(" | ", h.memories.stream().limit(8).toList()) + ". ")
            + "Reply with ONE short spoken line (max 25 words) in the same language the player used. Be in character, natural, funny when it fits. "
            + "No asterisks, no emojis, no quotation marks, no narration.";
    }

    public void broadcast(Human h, String line) {
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
