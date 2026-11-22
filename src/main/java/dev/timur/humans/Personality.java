package dev.timur.humans;

import java.util.*;

/** Stable personality: drives the fallback brain and is described to the AI. */
public record Personality(double bravery, double friendliness, double diligence, double generosity, double chattiness,
                          String quirk, String speech) {

    private static final String[] QUIRKS = {
        "loves collecting shiny things", "is terrified of creepers", "hums while working", "dreams of building a castle",
        "holds grudges for a long time", "always wants to share food", "is very competitive", "is a bit lazy but funny",
        "is suspicious of strangers at first", "thinks they are the best miner alive", "talks to animals", "is a hopeless romantic about sunsets"
    };
    private static final String[] SPEECH = {
        "short and blunt", "cheerful and goofy", "dramatic and theatrical", "calm and wise", "sarcastic but kind",
        "nervous, stutters sometimes", "friendly and chatty, uses slang", "grumpy old-timer"
    };

    public static Personality random(Random r) {
        return new Personality(r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(), r.nextDouble(),
            QUIRKS[r.nextInt(QUIRKS.length)], SPEECH[r.nextInt(SPEECH.length)]);
    }

    private static String level(double v, String low, String mid, String high) { return v < 0.33 ? low : v < 0.66 ? mid : high; }

    public String describe() {
        return String.join(", ",
            level(bravery, "cowardly", "cautious", "brave"),
            level(friendliness, "grumpy", "polite", "very friendly"),
            level(diligence, "lazy", "steady worker", "hard-working"),
            level(generosity, "greedy", "fair", "generous"),
            level(chattiness, "quiet", "talks sometimes", "chatty"))
            + "; " + quirk + "; speaks " + speech;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bravery", bravery); m.put("friendliness", friendliness); m.put("diligence", diligence);
        m.put("generosity", generosity); m.put("chattiness", chattiness); m.put("quirk", quirk); m.put("speech", speech);
        return m;
    }

    public static Personality deserialize(Map<String, Object> m, Random r) {
        if (m == null) return random(r);
        return new Personality(d(m, "bravery"), d(m, "friendliness"), d(m, "diligence"), d(m, "generosity"), d(m, "chattiness"),
            String.valueOf(m.getOrDefault("quirk", QUIRKS[0])), String.valueOf(m.getOrDefault("speech", SPEECH[0])));
    }

    private static double d(Map<String, Object> m, String k) { return m.get(k) instanceof Number n ? n.doubleValue() : 0.5; }
}
