package dev.timur.humans;

import dev.timur.humans.brain.Brain;
import dev.timur.humans.brain.Budget;
import dev.timur.humans.brain.ChatBrain;
import dev.timur.humans.brain.GeminiClient;
import dev.timur.humans.brain.TextClient;
import dev.timur.humans.skin.PackServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class HumansPlugin extends JavaPlugin {
    private static HumansPlugin instance;
    private HumanManager manager;
    private GeminiClient gemini;
    private TextClient text;
    private Zones zones;
    private Knowledge knowledge;
    private AdminAccess admins;
    private HumanLog humanLog;
    private Budget budget;
    private Brain brain;
    private ChatBrain chat;
    private PackServer pack;
    private boolean debug;

    public static HumansPlugin get() { return instance; }
    public HumanManager manager() { return manager; }
    public GeminiClient gemini() { return gemini; }
    public TextClient text() { return text; }
    public Zones zones() { return zones; }
    public Knowledge knowledge() { return knowledge; }
    public AdminAccess admins() { return admins; }
    public Budget budget() { return budget; }
    public Brain brain() { return brain; }
    public ChatBrain chat() { return chat; }
    public PackServer pack() { return pack; }
    public boolean debug() { return debug; }
    public void toggleDebug() { debug = !debug; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        System.setProperty("java.awt.headless", "true");
        humanLog = new HumanLog(this);
        HumanLog.log("server_start", null, "version", getPluginMeta().getVersion());
        budget = new Budget(this);
        gemini = new GeminiClient(this);
        text = new TextClient(this);
        zones = new Zones(this);
        knowledge = new Knowledge(this);
        pack = new PackServer(this);
        pack.buildAndStart();
        manager = new HumanManager(this);
        brain = new Brain(this);
        chat = new ChatBrain(this);
        var pm = getServer().getPluginManager();
        pm.registerEvents(new HumanListener(this), this);
        pm.registerEvents(chat, this);
        pm.registerEvents(pack, this);
        admins = new AdminAccess(this);
        pm.registerEvents(admins, this);
        admins.applyOnline();
        var cmd = getCommand("humans");
        HumansCommand hc = new HumansCommand(this);
        cmd.setExecutor(hc); cmd.setTabCompleter(hc);
        manager.loadAll();
        Bukkit.getScheduler().runTaskTimer(this, manager::tick, 1, 1);
        Bukkit.getScheduler().runTaskTimer(this, brain::pump, 40, 5);
        Bukkit.getScheduler().runTaskTimer(this, () -> { manager.saveAll(); budget.save(); zones.save(); knowledge.save(); }, 20 * 120, 20 * 120);
        getLogger().info("Humans enabled. AI: " + (gemini.ready() ? "Gemini" : "offline"));
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            for (Human h : manager.all()) if (h.task != null && h.valid()) { try { h.task.stop(h); } catch (Exception ignored) {} }
            manager.saveAll();
        }
        if (budget != null) budget.save();
        if (zones != null) zones.save();
        if (knowledge != null) knowledge.save();
        if (pack != null) pack.stop();
        HumanLog.log("server_stop", null);
        if (humanLog != null) humanLog.close();
    }
}
