package dev.timur.humans.brain;

import dev.timur.humans.HumansPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;

/** Tracks free-tier usage so we never hit Google's limits (RPD resets at midnight Pacific). */
public final class Budget {
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private final HumansPlugin plugin;
    private final File file;
    private LocalDate day;
    private int decisionsToday, chatToday;
    private long decisionTokensToday;
    private final Deque<long[]> decisionWindow = new ArrayDeque<>(); // {timeMs, tokens}
    private final Deque<Long> chatWindow = new ArrayDeque<>();

    public Budget(HumansPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "budget.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        day = LocalDate.parse(y.getString("day", LocalDate.now(PACIFIC).toString()));
        decisionsToday = y.getInt("decisions", 0);
        chatToday = y.getInt("chat", 0);
        decisionTokensToday = y.getLong("decision_tokens", 0);
        rollDay();
    }

    private void rollDay() {
        LocalDate now = LocalDate.now(PACIFIC);
        if (!now.equals(day)) { day = now; decisionsToday = 0; chatToday = 0; decisionTokensToday = 0; }
    }

    private void prune(long now) {
        while (!decisionWindow.isEmpty() && now - decisionWindow.peekFirst()[0] > 60_000) decisionWindow.pollFirst();
        while (!chatWindow.isEmpty() && now - chatWindow.peekFirst() > 60_000) chatWindow.pollFirst();
    }

    public synchronized boolean canDecide(int estTokens) {
        rollDay();
        long now = System.currentTimeMillis();
        prune(now);
        var c = plugin.getConfig();
        if (decisionsToday >= c.getInt("budget.decisions_per_day", 9000)) return false;
        if (decisionWindow.size() >= c.getInt("budget.decisions_per_minute", 120)) return false;
        long tok = 0; for (long[] e : decisionWindow) tok += e[1];
        return tok + estTokens <= c.getInt("budget.decision_tokens_per_minute", 40000);
    }

    public synchronized void recordDecision(int tokens) {
        rollDay();
        decisionsToday++;
        decisionTokensToday += tokens;
        decisionWindow.addLast(new long[]{System.currentTimeMillis(), tokens});
    }

    public synchronized boolean canChat() {
        rollDay();
        long now = System.currentTimeMillis();
        prune(now);
        var c = plugin.getConfig();
        return chatToday < c.getInt("budget.chat_per_day", 400) && chatWindow.size() < c.getInt("budget.chat_per_minute", 8);
    }

    public synchronized void recordChat() { rollDay(); chatToday++; chatWindow.addLast(System.currentTimeMillis()); }

    public synchronized String summary() {
        rollDay();
        prune(System.currentTimeMillis());
        var c = plugin.getConfig();
        long tok = 0; for (long[] e : decisionWindow) tok += e[1];
        return "Decisions today: " + decisionsToday + "/" + c.getInt("budget.decisions_per_day", 9000)
            + " (" + decisionTokensToday + " tokens), last minute: " + decisionWindow.size() + " calls / " + tok + " tokens"
            + "\nChat lines today: " + chatToday + "/" + c.getInt("budget.chat_per_day", 400)
            + "\nResets at midnight Pacific (" + day + ")";
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("day", day.toString()); y.set("decisions", decisionsToday); y.set("chat", chatToday); y.set("decision_tokens", decisionTokensToday);
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("Could not save budget: " + e.getMessage()); }
    }
}
