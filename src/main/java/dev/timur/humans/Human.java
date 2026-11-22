package dev.timur.humans;

import dev.timur.humans.skin.Look;
import dev.timur.humans.tasks.Task;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.*;

/** One AI human: persistent state + a handle to its Mannequin body. */
public final class Human {
    public final UUID id;
    public String name;
    public Look look;
    public Personality personality;
    public final Inventory inv = new Inventory();
    public Location home;
    public boolean hutBuilt;
    public Location hutCenter;
    public double hunger = 20;
    public final Map<UUID, Double> trust = new HashMap<>();   // 0 (hate) .. 4 (best friend), default 2
    public final Map<UUID, String> knownNames = new HashMap<>();
    public final Deque<String> recent = new ArrayDeque<>();    // short-term events
    public final Deque<String> memories = new ArrayDeque<>();  // long-term notes

    // runtime
    public Mannequin entity;
    public final Navigator nav = new Navigator(this);
    public Task task;
    public String lastOutcome = "just woke up";
    public boolean needsThink = true;
    public boolean thinking;
    public long lastThinkTick;
    public long taskStartTick;
    public long lastTalkMs, lastSpontaneousMs;
    public boolean chatInFlight;
    public String pendingRequest;          // what a player asked for
    public UUID pendingRequester;
    public UUID lastAttacker;
    public long lastAttackedTick;
    public UUID helpTarget;                // a player under attack nearby
    public long helpTick;
    public boolean dead;
    public long respawnAtMs;
    public Double exploreYaw;
    public boolean offlineNext;
    public long noSiteUntil;
    public String lastOptionId = "";
    public final Map<String, Long> failedOptions = new HashMap<>(); // option id -> tick it failed
    public int quickFails;
    public int swimFails;
    public final Deque<Long> navFails = new ArrayDeque<>();
    public final Set<UUID> badItems = new HashSet<>();            // next decision without AI (after an instant failure)
    private final Map<Long, Long> badBlocks = new HashMap<>(); // unreachable targets -> expiry tick

    public static long blockKey(org.bukkit.block.Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF); }
    public void markBad(org.bukkit.block.Block b, long now) { badBlocks.put(blockKey(b), now + 20 * 60 * 5); if (badBlocks.size() > 500) badBlocks.entrySet().removeIf(e -> e.getValue() < now); }
    public boolean isBad(org.bukkit.block.Block b, long now) { Long t = badBlocks.get(blockKey(b)); return t != null && t > now; }
    public boolean urgent;                 // jump the decision queue

    // stamina, goals, promises
    public double energy = 20;             // 0 exhausted .. 20 fresh
    public String project;                 // personal project id (see OptionGenerator.PROJECTS)
    public long projectSince;
    public long commitUntil;               // doing something a player asked: don't get distracted until this tick
    public String commitWho;
    public long thinkNotBefore;            // short breather between tasks
    public final Deque<String> recentActs = new ArrayDeque<>();
    public final Map<UUID, Long> greeted = new HashMap<>();
    public final Map<UUID, Long> visited = new HashMap<>();
    public final Map<Long, Long> seenChests = new HashMap<>();
    public long lastAskFoodTick = -1_000_000;

    // awareness
    public boolean trapped; public boolean trappedOpenSky; public long trappedSince;
    public int fireNear; public boolean fireAtHome;
    public UUID alertFrom; public long alertTick;

    // bigger plans, places, family
    public dev.timur.humans.plan.Goal goal;     // multi-step goal the planner works on (from a player or self-chosen)
    public long lastSelfPlanTick = -1_000_000;
    public Location village;                    // their own settlement area (may build/dig here)
    public Location bed;                        // foot block of their placed bed
    public double growth = 1.0;                 // 1 = adult; children grow from 0.5
    public final List<String> parents = new ArrayList<>();
    public long lastBabyTick = -1_000_000;
    public boolean underground;
    public final Map<UUID, Long> chestPermission = new HashMap<>(); // player -> tick they allowed taking from their chests

    public boolean isChild() { return growth < 1.0; }

    /** A question the human asked a player ("can I take bread from this chest?"). */
    public record Ask(org.bukkit.block.Block chest, org.bukkit.Material mat, UUID player, long tick) {}
    public Ask ask;

    // face
    public Expression mood = Expression.NEUTRAL;   // resting face
    Expression transientExpr;
    long transientUntil;
    Expression shown;
    long blinkUntil, nextBlink;
    public boolean sleeping;

    // name-tag line under the name
    String status = "";
    String bubble;
    long bubbleUntil;

    public Human(UUID id, String name, Look look, Personality p) {
        this.id = id; this.name = name; this.look = look; this.personality = p;
    }

    public boolean valid() { return entity != null && entity.isValid() && !dead; }
    public Location loc() { return entity.getLocation(); }

    // ---------------------------------------------------------------- trust & memory

    public double trustOf(UUID player) { return trust.getOrDefault(player, 2.0); }
    public void addTrust(Player p, double delta) {
        knownNames.put(p.getUniqueId(), p.getName());
        trust.put(p.getUniqueId(), Math.max(0, Math.min(4, trustOf(p.getUniqueId()) + delta)));
    }
    public static String trustWord(double t) {
        return t < 0.6 ? "hates" : t < 1.4 ? "distrusts" : t < 2.4 ? "neutral towards" : t < 3.3 ? "likes" : "best friends with";
    }

    // ---------------------------------------------------------------- hearing / conversation

    /** Something said within earshot (by a player or a human, including this one). */
    public record Heard(long tick, String who, String text) {}
    /** A chat message waiting for this human's mind to answer. */
    public record Msg(UUID player, String name, String text, boolean named, long tick) {}
    public final Deque<Heard> heard = new ArrayDeque<>();
    public final List<Msg> inbox = new ArrayList<>();
    public boolean mindBusy, mindScheduled;

    public void hear(String who, String text, long now) {
        heard.addLast(new Heard(now, who, text.length() > 200 ? text.substring(0, 197) + "..." : text));
        while (heard.size() > 18) heard.removeFirst();
    }

    /** The conversation around this human, oldest first, with how long ago each line was said. */
    public String conversation(long now, int max, long maxAgeTicks) {
        List<String> out = new ArrayList<>();
        for (Heard x : heard) {
            if (now - x.tick() > maxAgeTicks) continue;
            long s = (now - x.tick()) / 20;
            out.add("[" + (s < 60 ? s + "s" : s / 60 + "m") + " ago] " + (x.who().equals(name) ? name + " (you)" : x.who()) + ": " + x.text());
        }
        if (out.size() > max) out = out.subList(out.size() - max, out.size());
        return String.join("\n", out);
    }

    public void event(String s) {
        recent.addFirst(s);
        while (recent.size() > 8) recent.removeLast();
    }

    public void remember(String s) {
        memories.addFirst(s);
        while (memories.size() > 15) memories.removeLast();
    }

    // ---------------------------------------------------------------- face

    /** Show an expression for a while (plays its sound once). */
    public void express(Expression e, int ticks, HumansPlugin plugin, long now) {
        if (!valid()) return;
        boolean changed = transientExpr != e || now >= transientUntil;
        transientExpr = e;
        transientUntil = now + ticks;
        if (changed) plugin.manager().playExpressionSound(this, e);
        updateFace(plugin, now);
    }

    public void setMood(Expression e, HumansPlugin plugin, long now) {
        if (e == mood) return;
        mood = e;
        if (e != Expression.NEUTRAL) plugin.manager().playExpressionSound(this, e);
        updateFace(plugin, now);
    }

    void updateFace(HumansPlugin plugin, long now) {
        if (!valid()) return;
        Expression want;
        if (sleeping) want = Expression.SLEEPING;
        else if (transientExpr != null && now < transientUntil) want = transientExpr;
        else if (now < blinkUntil) want = Expression.BLINK;
        else want = mood;
        if (want != shown) {
            entity.setProfile(plugin.manager().profile(look, want));
            shown = want;
        }
    }

    void tickFace(HumansPlugin plugin, long now, Random rnd) {
        if (now >= nextBlink) {
            blinkUntil = now + 3;
            nextBlink = now + 50 + rnd.nextInt(90);
        }
        updateFace(plugin, now);
    }

    // ---------------------------------------------------------------- name tag

    public void setStatus(String s) { status = s; }

    public void say(String text, long now) {
        bubble = text;
        bubbleUntil = now + Math.max(60, Math.min(200, text.length() * 3L));
    }

    void tickTag(long now) {
        if (!valid()) return;
        Component line;
        if (bubble != null && now < bubbleUntil) {
            String t = bubble.length() > 60 ? bubble.substring(0, 57) + "..." : bubble;
            line = Component.text("\"" + t + "\"", NamedTextColor.WHITE);
        } else {
            line = Component.text(status, NamedTextColor.GRAY);
        }
        if (!line.equals(entity.getDescription())) entity.setDescription(line);
    }
}
