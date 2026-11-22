package dev.timur.humans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;

public final class HumansCommand implements TabExecutor {
    private final HumansPlugin plugin;
    public HumansCommand(HumansPlugin plugin) { this.plugin = plugin; }

    private static void msg(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.YELLOW)); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        HumanManager m = plugin.manager();
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                int n = 1; String name = null;
                if (a.length > 1) { try { n = Math.max(1, Math.min(10, Integer.parseInt(a[1]))); } catch (NumberFormatException e) { name = a[1]; } }
                for (int i = 0; i < n; i++) {
                    String nm = name != null ? name : m.freshName();
                    var loc = p.getLocation().add(plugin.manager().rnd().nextGaussian() * (n > 1 ? 2 : 0), 0, plugin.manager().rnd().nextGaussian() * (n > 1 ? 2 : 0));
                    Human h = m.spawn(loc, nm);
                    msg(s, "Spawned " + h.name + " (" + h.personality.describe() + ")");
                }
            }
            case "spawnat" -> { // console-friendly: /humans spawnat <x> <z> [count]
                if (a.length < 3) { msg(s, "/humans spawnat <x> <z> [count]"); return true; }
                var w = org.bukkit.Bukkit.getWorlds().getFirst();
                int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]);
                int n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                for (int i = 0; i < n; i++) {
                    var top = w.getHighestBlockAt(x + i * 2, z).getLocation().add(0.5, 1, 0.5);
                    var land = dev.timur.humans.tasks.Act.nearestLand(top, 32);
                    if (land != null) top = land;
                    Human h = m.spawn(top, m.freshName());
                    msg(s, "Spawned " + h.name + " at " + top.getBlockX() + "," + top.getBlockY() + "," + top.getBlockZ() + " (" + h.personality.describe() + ")");
                }
            }
            case "awake" -> {
                boolean v = !plugin.getConfig().getBoolean("brain.always_awake", false);
                plugin.getConfig().set("brain.always_awake", v); msg(s, "always_awake = " + v);
            }
            case "list" -> {
                if (m.all().isEmpty()) msg(s, "No humans yet. /humans spawn");
                for (Human h : m.all())
                    msg(s, h.name + (h.dead ? " [dead]" : h.valid() ? " @ " + h.loc().getBlockX() + "," + h.loc().getBlockY() + "," + h.loc().getBlockZ() + " - " + (h.task == null ? "idle" : h.task.label()) : " [unloaded]"));
            }
            case "info" -> {
                Human h = find(s, a); if (h == null) return true;
                msg(s, h.name + ": " + h.personality.describe());
                msg(s, "Mood " + h.mood.id() + ", hunger " + Math.round(h.hunger) + ", house " + h.hutBuilt + ", last: " + h.lastOutcome);
                msg(s, "Inventory: " + h.inv.describe());
                msg(s, "Memories: " + String.join(" | ", h.memories));
            }
            case "remove" -> {
                Human h = find(s, a); if (h == null) return true;
                m.remove(h); msg(s, "Removed " + h.name + ".");
            }
            case "home" -> {
                if (!(s instanceof Player p)) return true;
                Human h = find(s, a); if (h == null) return true;
                h.home = p.getLocation(); h.hutCenter = null; m.save(h); msg(s, h.name + "'s home is now here.");
            }
            case "tp" -> {
                if (!(s instanceof Player p)) return true;
                Human h = find(s, a); if (h == null || !h.valid()) return true;
                p.teleport(h.loc());
            }
            case "give" -> { // /humans give <name> <material> [n]   (testing)
                Human h = a.length > 2 ? m.byName(a[1]) : null;
                Material mat = a.length > 2 ? Material.matchMaterial(a[2]) : null;
                if (h == null || mat == null) { msg(s, "/humans give <name> <material> [amount]"); return true; }
                h.inv.add(mat, a.length > 3 ? Integer.parseInt(a[3]) : 1); msg(s, "Gave " + h.name + " " + mat);
            }
            case "think" -> {
                Human h = find(s, a); if (h == null) return true;
                m.interrupt(h, "felt like reconsidering"); msg(s, h.name + " will rethink.");
            }
            case "text" -> msg(s, plugin.text().status());
            case "budget" -> {
                for (String line : plugin.budget().summary().split("\n")) msg(s, line);
                msg(s, "Decisions so far: " + plugin.brain().aiDecisions + " AI, " + plugin.brain().fallbackDecisions + " offline");
            }
            case "probe" -> { // /humans probe <name> : blocks around a human (debugging)
                Human h = a.length > 1 ? m.byName(a[1]) : null;
                if (h == null || !h.valid()) { msg(s, "No such (loaded) human."); return true; }
                var l = h.loc();
                msg(s, h.name + " @" + String.format("%.2f %.2f %.2f", l.getX(), l.getY(), l.getZ()) + " task=" + (h.task == null ? "-" : h.task.label()) + " trapped=" + h.trapped + " energy=" + Math.round(h.energy) + " project=" + h.project);
                for (int dy = 2; dy >= -1; dy--) {
                    StringBuilder sb = new StringBuilder("y" + (l.getBlockY() + dy) + ": ");
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            Material t = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz).getType();
                            sb.append(dx == 0 && dz == 0 ? "[" : "").append(t.isAir() ? "." : t == Material.WATER ? "~" : t.name().substring(0, Math.min(4, t.name().length())).toLowerCase(Locale.ROOT)).append(dx == 0 && dz == 0 ? "]" : "").append(' ');
                        }
                        sb.append("| ");
                    }
                    msg(s, sb.toString());
                }
            }
            case "protect" -> { // /humans protect <radius> [name] - a players' area humans must leave alone
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                int r = a.length > 1 ? Math.max(4, Math.min(200, Integer.parseInt(a[1]))) : 30;
                plugin.zones().add(p.getLocation(), r, a.length > 2 ? a[2] : p.getName() + "'s base");
                msg(s, "Protected a " + r + "-block radius around you. Humans won't break, build or pick up items here.");
            }
            case "unprotect" -> {
                if (!(s instanceof Player p)) { msg(s, "Players only."); return true; }
                msg(s, "Removed " + plugin.zones().removeAt(p.getLocation()) + " protected area(s) covering this spot.");
            }
            case "zones" -> {
                if (plugin.zones().manual().isEmpty()) msg(s, "No manual zones. (Automatic: " + plugin.getConfig().getInt("zones.auto_radius", 9) + " blocks around players' chests/beds/furnaces/signs.)");
                for (var z : plugin.zones().manual()) msg(s, z.name() + ": " + z.world() + " " + z.x() + "," + z.z() + " r=" + z.r());
            }
            case "goal" -> { // /humans goal <name> <text...> - give a human a goal (testing / admin)
                if (a.length < 3) { msg(s, "/humans goal <name> <what to do>"); return true; }
                Human h = m.byName(a[1]);
                if (h == null || !h.valid()) { msg(s, "No such (loaded) human."); return true; }
                String text = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                dev.timur.humans.plan.Goal g = new dev.timur.humans.plan.Goal("Admin says: \"" + text + "\"", s.getName(), s instanceof Player p ? p.getUniqueId() : null, "", m.now());
                h.goal = g;
                m.startTask(h, new dev.timur.humans.brain.Option("do_request", "goal", () -> new dev.timur.humans.plan.PlanTask(g), 1));
                h.commitUntil = m.now() + 20L * 60 * 15; h.commitWho = s.getName();
                msg(s, h.name + " is now working on: " + text);
            }
            case "cancel" -> {
                Human h = a.length > 1 ? m.byName(a[1]) : null;
                if (h == null) { msg(s, "/humans cancel <name>"); return true; }
                h.goal = null; h.commitUntil = 0; if (h.task != null && h.valid()) { h.task.stop(h); h.task = null; } h.needsThink = true;
                msg(s, h.name + " dropped their goal.");
            }
            case "plan" -> {
                Human h = a.length > 1 ? m.byName(a[1]) : null;
                if (h == null) { msg(s, "/humans plan <name>"); return true; }
                if (h.goal == null) { msg(s, h.name + " has no goal right now."); return true; }
                msg(s, h.name + " goal: " + h.goal.title + " (round " + h.goal.rounds + ", step " + h.goal.stepIdx + "/" + h.goal.steps.size() + ")");
                if (!h.goal.lastThought.isEmpty()) msg(s, "Thinking: " + h.goal.lastThought);
                for (int i = 0; i < h.goal.steps.size(); i++) msg(s, (i == h.goal.stepIdx ? "> " : "  ") + h.goal.steps.get(i).describe());
                for (String l : h.goal.log.subList(Math.max(0, h.goal.log.size() - 6), h.goal.log.size())) msg(s, "  done: " + l);
            }
            case "debug" -> { plugin.toggleDebug(); msg(s, "Debug logging " + (plugin.debug() ? "on" : "off")); }
            case "pack" -> { if (s instanceof Player p) plugin.pack().send(p); }
            case "reloadkey" -> { plugin.gemini().reloadKey(); msg(s, "Key " + (plugin.gemini().ready() ? "loaded" : "missing")); }
            default -> {
                msg(s, "/humans spawn [name|count]  - spawn humans where you stand (their home)");
                msg(s, "/humans list | info <name> | remove <name> | home <name> | tp <name>");
                msg(s, "/humans think <name> | give <name> <item> [n] | budget | debug | pack | reloadkey");
                msg(s, "Talk to humans in chat (say their name), right-click for info, sneak+right-click to gift.");
            }
        }
        return true;
    }

    private Human find(CommandSender s, String[] a) {
        if (a.length < 2) {
            if (s instanceof Player p) {
                Human best = null; double bd = 1e9;
                for (Human h : plugin.manager().all()) if (h.valid() && h.loc().getWorld() == p.getWorld() && h.loc().distance(p.getLocation()) < bd) { bd = h.loc().distance(p.getLocation()); best = h; }
                if (best != null) return best;
            }
            msg(s, "Which human? Give a name."); return null;
        }
        Human h = plugin.manager().byName(a[1]);
        if (h == null) msg(s, "No human called " + a[1]);
        return h;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(List.of("spawn", "list", "info", "remove", "home", "tp", "give", "think", "budget", "debug", "pack", "reloadkey"), a[0]);
        if (a.length == 2 && !a[0].equalsIgnoreCase("spawn")) {
            List<String> names = new ArrayList<>(); for (Human h : plugin.manager().all()) names.add(h.name);
            return filter(names, a[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> in, String p) {
        List<String> out = new ArrayList<>(); for (String x : in) if (x.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))) out.add(x); return out;
    }
}
