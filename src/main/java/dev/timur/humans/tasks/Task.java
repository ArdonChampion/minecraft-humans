package dev.timur.humans.tasks;

import dev.timur.humans.Human;
import dev.timur.humans.HumanManager;

/** Something a human does over many ticks (chop a tree, fight, build...). Pure Java — no AI calls. */
public abstract class Task {
    public enum Status { RUNNING, SUCCESS, FAIL }

    protected String outcome = "";
    protected int age;

    /** Short present-tense label, shown under the name and told to the AI, e.g. "chopping an oak tree". */
    public abstract String label();

    protected abstract Status run(Human h, HumanManager m);

    public final Status tick(Human h, HumanManager m) {
        age++;
        if (age > maxTicks()) { outcome = label() + " took too long, gave up"; return Status.FAIL; }
        return run(h, m);
    }

    public void stop(Human h) { h.nav.stop(); }

    public String outcome() { return outcome.isEmpty() ? label() : outcome; }

    public int maxTicks() { return 20 * 120; }

    public int age() { return age; }

    /** Physical work (tires the human out and earns a breather afterwards). */
    public boolean isWork() { return false; }

    /** Extra state for debug logs. */
    public String debugInfo() { return ""; }
}
