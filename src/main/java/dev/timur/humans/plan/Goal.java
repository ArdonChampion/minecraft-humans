package dev.timur.humans.plan;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A multi-step goal a human works on until it's done (asked by a player, or chosen for themselves). */
public final class Goal {
    public record Step(String skill, JsonObject args) {
        public String describe() {
            StringBuilder s = new StringBuilder(skill);
            for (var e : args.entrySet()) if (!e.getKey().equals("do")) s.append(' ').append(e.getKey()).append('=').append(e.getValue().toString().replace("\"", ""));
            return s.length() > 160 ? s.substring(0, 157) + "..." : s.toString();
        }
    }

    public String text;            // what to achieve
    public String title;           // short name (from the planner)
    public final String from;      // player name, or null when self-chosen
    public final UUID fromId;
    public final String group;     // other humans asked together (for teamwork), may be empty
    public final long created;
    public final List<String> log = new ArrayList<>();   // results of earlier steps
    public List<Step> steps = new ArrayList<>();
    public int stepIdx, rounds;
    public boolean done, plannerSaysDone;
    public String lastThought = "";

    public Goal(String text, String from, UUID fromId, String group, long created) {
        this.text = text; this.title = text.length() > 40 ? text.substring(0, 37) + "..." : text;
        this.from = from; this.fromId = fromId; this.group = group == null ? "" : group; this.created = created;
    }

    public void note(String s) { log.add(s); while (log.size() > 14) log.removeFirst(); }
}
