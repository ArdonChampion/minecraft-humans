package dev.timur.humans;

import java.util.Locale;

/** Face expressions. Each one is a separate skin texture in the resource pack. */
public enum Expression {
    NEUTRAL, HAPPY, SAD, ANGRY, SCARED, SURPRISED, HURT, BLINK, SLEEPING;

    /** Moods the decision model may pick as a human's resting face. */
    public static final Expression[] MOODS = {NEUTRAL, HAPPY, SAD, ANGRY, SCARED, SURPRISED};

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Expression parse(String s, Expression def) {
        if (s == null) return def;
        try { return valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return def; }
    }
}
