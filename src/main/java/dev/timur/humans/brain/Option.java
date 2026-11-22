package dev.timur.humans.brain;

import dev.timur.humans.tasks.Task;

import java.util.function.Supplier;

/**
 * One concrete thing a human could do right now. The AI picks between options;
 * `score` is what the offline fallback brain uses instead.
 * `talk` = choosing this should make the human say something.
 * `say` = what to say when chosen: "!text" = a ready line (no AI), otherwise a situation for Flash-Lite. May be null.
 */
public record Option(String id, String text, Supplier<Task> make, double score, boolean talk, String say) {
    public Option(String id, String text, Supplier<Task> make, double score) { this(id, text, make, score, false, null); }
    public Option(String id, String text, Supplier<Task> make, double score, boolean talk) { this(id, text, make, score, talk, null); }

    public Option withScore(double s, String note) { return new Option(id, note == null ? text : text + note, make, s, talk, say); }

    /** Coarse kind of action, used to keep humans from doing the same thing over and over. */
    public String family() { int i = id.indexOf('_'); return i < 0 ? id : id.substring(0, i); }
}
