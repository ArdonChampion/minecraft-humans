package dev.timur.humans.plan;

import dev.timur.humans.*;
import dev.timur.humans.tasks.Act;
import dev.timur.humans.tasks.Task;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Works on a Goal: asks the planner for a few steps, runs them one by one as tasks,
 * reports results back and re-plans until the planner says the goal is done.
 */
public final class PlanTask extends Task {
    private final Goal g;
    private Task cur;
    private boolean waiting;
    private int waitTicks;
    private long nextPlanTick;

    public PlanTask(Goal g) { this.g = g; }

    public Goal goal() { return g; }

    public String label() {
        if (waiting) return "thinking how to: " + g.title;
        return cur != null ? cur.label() : "working on: " + g.title;
    }

    public int maxTicks() { return 20 * 60 * 25; }

    @Override public boolean isWork() { return cur != null && cur.isWork(); }

    @Override public String debugInfo() { return "goal=" + g.title + " round=" + g.rounds + " step=" + g.stepIdx + "/" + g.steps.size() + (cur != null ? " cur=" + cur.label() : ""); }

    protected Status run(Human h, HumanManager m) {
        if (g.done) { outcome = "finished: " + g.title; HumanLog.log("goal_end", h, "goal", g.title, "result", "done", "rounds", g.rounds, "from", g.from); doneGoal(h, g.title + (g.from != null ? " (for " + g.from + ")" : "")); return Status.SUCCESS; }
        if (waiting) {
            if (++waitTicks > 20 * 90) { waiting = false; g.note("(planning took too long)"); if (++g.rounds > 12) return giveUp(h); }
            if (age % 20 == 0) { Player p = m.nearestPlayer(h, 8); if (p != null) Act.lookAt(h, p.getEyeLocation()); }
            return Status.RUNNING;
        }
        if (cur != null) {
            Status s = cur.tick(h, m);
            if (s == Status.RUNNING) return s;
            cur.stop(h);
            Goal.Step st = g.steps.get(g.stepIdx);
            g.note((s == Status.SUCCESS ? "OK " : "FAILED ") + st.describe() + " -> " + cur.outcome());
            HumanLog.log("plan_step", h, "goal", g.title, "round", g.rounds, "step", st.describe(), "status", s.name(), "outcome", cur.outcome(), "secs", cur.age() / 20);
            cur = null;
            g.stepIdx++;
            if (s == Status.FAIL) { g.stepIdx = g.steps.size(); nextPlanTick = m.now() + 60; g.quick = false; }
            return Status.RUNNING;
        }
        if (g.stepIdx < g.steps.size()) {
            Goal.Step st = g.steps.get(g.stepIdx);
            Skills.Start r;
            try { r = Skills.start(h, m, g, st); } catch (Exception e) { r = new Skills.Start(null, null, "error: " + e.getMessage()); }
            if (r.task() != null) { cur = r.task(); return Status.RUNNING; }
            if (r.error() != null) { HumanLog.log("plan_step", h, "goal", g.title, "round", g.rounds, "step", st.describe(), "status", "REJECTED", "outcome", r.error());
                g.note("FAILED " + st.describe() + " -> " + r.error()); g.stepIdx = g.steps.size(); nextPlanTick = m.now() + 20; g.quick = false; return Status.RUNNING; }
            g.note("OK " + st.describe() + (r.done() == null ? "" : " -> " + r.done()));
            g.stepIdx++;
            return Status.RUNNING;
        }
        if (g.plannerSaysDone || g.quick) { g.done = true; return Status.RUNNING; }
        if (m.now() < nextPlanTick) return Status.RUNNING; // don't re-think every second after quick failures
        requestPlan(h, m);
        return Status.RUNNING;
    }

    private Status giveUp(Human h) {
        HumanLog.log("goal_end", h, "goal", g.title, "result", "gave_up", "rounds", g.rounds, "log", String.join(" || ", g.log));
        g.done = true;
        doneGoal(h, g.title + " (gave up)");
        outcome = "gave up on: " + g.title + " (too many attempts)";
        h.remember("I gave up on \"" + g.title + "\"");
        return Status.FAIL;
    }

    private static void doneGoal(Human h, String what) {
        h.goalsDone.remove(what);
        h.goalsDone.addFirst(what);
        while (h.goalsDone.size() > 12) h.goalsDone.removeLast();
    }

    private void requestPlan(Human h, HumanManager m) {
        if (g.rounds >= HumansPlugin.get().getConfig().getInt("planner.max_rounds", 20)) { giveUp(h); return; }
        if (!HumansPlugin.get().text().ready()) { g.done = true; outcome = "can't plan without the text AI"; return; }
        waiting = true; waitTicks = 0;
        HumansPlugin plugin = HumansPlugin.get();
        long t0 = System.currentTimeMillis();
        Planner.plan(h, m, g).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (HumanLog.on()) {
                com.google.gson.JsonObject ev = HumanLog.ev("plan", h);
                HumanLog.put(ev, "goal_text", g.text); HumanLog.put(ev, "from", g.from); HumanLog.put(ev, "round", g.rounds + 1); HumanLog.put(ev, "ms", System.currentTimeMillis() - t0);
                if (err != null || res == null) HumanLog.put(ev, "error", err == null ? "no answer" : String.valueOf(err.getMessage()));
                else {
                    HumanLog.put(ev, "title", res.title()); HumanLog.put(ev, "thought", res.thought()); HumanLog.put(ev, "say", res.say()); HumanLog.put(ev, "done", res.done());
                    com.google.gson.JsonArray st = new com.google.gson.JsonArray(); for (Goal.Step x : res.steps()) st.add(x.describe()); ev.add("steps", st);
                }
                HumanLog.put(ev, "history", String.join(" || ", g.log));
                HumanLog.log(ev);
            }
            waiting = false;
            g.rounds++;
            if (err != null || res == null) {
                g.note("(couldn't think of a plan: " + (err == null ? "no answer" : err.getMessage()) + ")");
                if (plugin.debug()) plugin.getLogger().info("[debug] " + h.name + " plan failed: " + (err == null ? "null" : err.getMessage()));
                return;
            }
            if (!res.title().isBlank()) g.title = res.title().length() > 48 ? res.title().substring(0, 45) + "..." : res.title();
            g.lastThought = res.thought();
            g.steps = res.steps(); g.stepIdx = 0;
            g.plannerSaysDone = res.done() || res.steps().isEmpty();
            if (plugin.debug()) {
                StringBuilder sb = new StringBuilder();
                for (Goal.Step s : res.steps()) sb.append(" | ").append(s.describe());
                plugin.getLogger().info("[debug] " + h.name + " plan r" + g.rounds + " (" + g.title + "): " + res.thought() + sb + (res.done() ? " | DONE" : ""));
            }
            if (!res.say().isBlank() && h.valid() && m.nearestPlayer(h, 32) != null) plugin.chat().saySpontaneous(h, res.say().length() > 160 ? res.say().substring(0, 157) + "..." : res.say());
        }));
    }

    @Override public void stop(Human h) {
        super.stop(h);
        if (cur != null) {
            cur.stop(h);
            // the interrupted step will be retried when the goal is resumed
            cur = null;
        }
        waiting = false;
    }
}
