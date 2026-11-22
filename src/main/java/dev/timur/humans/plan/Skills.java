package dev.timur.humans.plan;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.timur.humans.*;
import dev.timur.humans.brain.Lines;
import dev.timur.humans.brain.OptionGenerator;
import dev.timur.humans.tasks.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;

import java.util.*;

/** Turns one planner step into a Task (or does it instantly), with clear error messages the planner can learn from. */
public final class Skills {
    private Skills() {}

    /** task != null: run it. Otherwise done = instant success text, or error. */
    public record Start(Task task, String done, String error) {
        static Start task(Task t) { return new Start(t, null, null); }
        static Start ok(String s) { return new Start(null, s, null); }
        static Start err(String s) { return new Start(null, null, s); }
    }

    static String s(JsonObject a, String k, String def) {
        JsonElement e = a.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString().trim() : def;
    }

    static int i(JsonObject a, String k, int def) {
        try { JsonElement e = a.get(k); return e != null && e.isJsonPrimitive() ? (int) Math.round(e.getAsDouble()) : def; } catch (Exception ex) { return def; }
    }

    static Material mat(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.toLowerCase(Locale.ROOT).trim().replace(' ', '_').replace("minecraft:", "");
        Material m = Material.matchMaterial(n);
        if (m == null && n.endsWith("s")) m = Material.matchMaterial(n.substring(0, n.length() - 1));
        return m;
    }

    static Player player(String name) {
        if (name == null) return null;
        name = name.replace("player:", "").trim();
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) for (Player q : Bukkit.getOnlinePlayers()) if (q.getName().equalsIgnoreCase(name)) return q;
        return p;
    }

    public static Start start(Human h, HumanManager m, Goal g, Goal.Step step) {
        JsonObject a = step.args();
        Location l = h.loc();
        switch (step.skill()) {
            case "chop_trees", "chop", "get_wood" -> { return Start.task(new Work.ChopCount(i(a, "logs", i(a, "count", 8)))); }
            case "mine" -> {
                String what = s(a, "block", s(a, "item", "stone"));
                if (what.contains("log") || what.contains("wood")) return Start.task(new Work.ChopCount(i(a, "count", 8)));
                return Start.task(new Work.MineCount(what, i(a, "count", 8)));
            }
            case "strip_mine" -> {
                int y = i(a, "y", 16);
                if (y >= l.getBlockY() - 2) y = Math.max(l.getWorld().getMinHeight() + 6, l.getBlockY() - 12);
                return Start.task(new Work.StripMine(Math.max(l.getWorld().getMinHeight() + 6, y), i(a, "length", 30)));
            }
            case "explore" -> { return Start.task(new Tasks.Explore(s(a, "for", "something interesting"))); }
            case "go_to", "goto", "walk_to" -> {
                if (a.has("player")) {
                    Player p = player(s(a, "player", ""));
                    if (p == null || p.getWorld() != l.getWorld()) return Start.err("player " + s(a, "player", "?") + " is not online nearby");
                    return Start.task(new Tasks.Greet(p, false, "walking to " + p.getName()));
                }
                if (a.has("human")) {
                    Human o = m.byName(s(a, "human", ""));
                    if (o == null || !o.valid()) return Start.err("no human called " + s(a, "human", "?") + " nearby");
                    return Start.task(new Tasks.Goto(o.loc(), 2.5, false, "walking to " + o.name));
                }
                if (a.has("place")) {
                    String p = s(a, "place", "home");
                    Location to = p.equals("village") ? h.village : p.equals("bed") ? h.bed : h.home;
                    if (to == null) return Start.err("you don't have a " + p + " yet");
                    return Start.task(new Tasks.Goto(to, 2, false, "going to my " + p));
                }
                if (a.has("x") && a.has("z")) {
                    int x = i(a, "x", l.getBlockX()), z = i(a, "z", l.getBlockZ());
                    int y = a.has("y") ? i(a, "y", l.getBlockY()) : l.getWorld().getHighestBlockYAt(x, z) + 1;
                    Location to = new Location(l.getWorld(), x + 0.5, y, z + 0.5);
                    if (to.distance(l) > 70) return Start.err("that spot is too far (" + (int) to.distance(l) + " blocks); go in smaller hops of under 60 blocks");
                    return Start.task(new Tasks.Goto(to, 1.5, false, "walking to " + x + "," + z));
                }
                return Start.err("go_to needs player, human, place or x/z");
            }
            case "follow" -> {
                Player p = player(s(a, "player", ""));
                if (p == null) return Start.err("player " + s(a, "player", "?") + " is not online");
                return Start.task(new Tasks.Follow(p, Math.max(10, Math.min(i(a, "seconds", 60), 300)) * 20));
            }
            case "go_outside" -> { return Start.task(new Work.GoOutside()); }
            case "collect_items", "collect" -> { return Start.task(new Tasks.CollectItems()); }
            case "craft" -> {
                String id = s(a, "item", "").toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
                Items.Recipe r = Items.byId(id);
                if (r == null) for (Items.Recipe x : Items.RECIPES) if (x.result().name().equalsIgnoreCase(id) || x.id().equals(id + "s") || (x.id() + "s").equals(id)) r = x;
                if (r == null) return Start.err("unknown recipe '" + id + "'. Known: " + Planner.recipes());
                int want = Math.max(1, Math.min(i(a, "count", 1), 64));
                int times = (int) Math.ceil(want / (double) r.amount());
                Inventory sim = h.inv.copy(); List<Items.Recipe> all = new ArrayList<>();
                for (int k = 0; k < times; k++) {
                    List<Items.Recipe> st = Items.plan(sim, r);
                    if (st == null) {
                        if (k == 0) return Start.err("can't craft " + r.nice() + ": missing materials (needs " + needs(r) + "; you have " + h.inv.describe() + ")");
                        break;
                    }
                    for (Items.Recipe x : st) Items.consume(sim, x);
                    all.addAll(st);
                }
                return Start.task(new Tasks.Craft(all, (times * r.amount()) + " " + Items.nice(r.result())));
            }
            case "smelt", "cook" -> {
                if (!h.inv.has(Material.FURNACE, 1)) return Start.err("needs a furnace item (craft furnace from 8 cobblestone)");
                return Start.task(new Tasks.Smelt());
            }
            case "eat" -> { return Items.bestFood(h.inv).isPresent() ? Start.task(new Tasks.Eat()) : Start.err("no food in inventory"); }
            case "sleep" -> {
                long t = l.getWorld().getTime();
                return Start.task(t > 12600 && t < 23400 ? new Tasks.Sleep() : new Tasks.Sleep(20 * 20));
            }
            case "place_bed" -> { return Start.task(new Work.PlaceBed()); }
            case "build" -> { return build(h, a); }
            case "set_village" -> {
                if (HumansPlugin.get().zones().isProtected(l, null)) return Start.err("this spot is a player's base; pick a free area at least 10 blocks away");
                h.village = l.clone();
                if (!h.hutBuilt) h.home = l.clone();
                for (String n : g.group.split(",\\s*")) { Human o = m.byName(n); if (o != null && o.village == null) { o.village = l.clone(); o.event(h.name + " picked our village spot at " + Planner.xyz(l)); } }
                h.remember("our village is at x=" + l.getBlockX() + " z=" + l.getBlockZ());
                return Start.ok("this is now my village (x=" + l.getBlockX() + " z=" + l.getBlockZ() + ")");
            }
            case "gather_plants", "gather_seeds", "pick_flowers" -> {
                OptionGenerator.Scan sc = OptionGenerator.scan(h, m);
                if (sc.flowers.isEmpty()) return Start.err("no grass or flowers nearby - explore a grassy area");
                return Start.task(new Life.PickPlants(new ArrayList<>(sc.flowers)));
            }
            case "farm", "harvest" -> {
                OptionGenerator.Scan sc = OptionGenerator.scan(h, m);
                if (sc.crops.isEmpty()) return Start.err("no ripe crops nearby (outside players' bases)");
                return Start.task(new Life.FarmCrops(new ArrayList<>(sc.crops)));
            }
            case "breed_animals", "breed" -> {
                EntityType ty = entity(s(a, "animal", "cow"));
                if (ty == null) return Start.err("unknown animal");
                return Start.task(new Life.FeedAnimals(ty));
            }
            case "hunt" -> {
                EntityType ty = entity(s(a, "animal", "cow"));
                if (ty == null) return Start.err("unknown animal");
                return Start.task(new Work.HuntCount(ty, i(a, "count", 2)));
            }
            case "fight", "attack" -> {
                String tg = s(a, "target", "nearest_monster").toLowerCase(Locale.ROOT);
                if (player(tg) != null) return Start.err("you never attack players");
                Monster best = null; double bd = 1e9;
                for (Entity e : h.entity.getNearbyEntities(24, 10, 24))
                    if (e instanceof Monster mo && !mo.isDead() && (tg.contains("nearest") || tg.contains("monster") || mo.getType().name().toLowerCase(Locale.ROOT).contains(tg)) && e.getLocation().distanceSquared(l) < bd) { bd = e.getLocation().distanceSquared(l); best = mo; }
                if (best == null) return Start.err("no such monster nearby");
                return Start.task(new Tasks.Attack(best, false));
            }
            case "give" -> {
                String to = s(a, "to", s(a, "player", ""));
                Material mt = mat(s(a, "item", ""));
                if (mt == null) return Start.err("unknown item '" + s(a, "item", "") + "'");
                int n = Math.max(1, i(a, "count", 1));
                if (h.inv.count(mt) <= 0) return Start.err("you have no " + Items.nice(mt));
                if (to.startsWith("human:") || m.byName(to) != null) {
                    Human o = m.byName(to.replace("human:", ""));
                    if (o == null || !o.valid()) return Start.err("no human " + to);
                    return Start.task(new Work.GiveHuman(o, mt, n));
                }
                Player p = player(to);
                if (p == null) return Start.err("player " + to + " is not online");
                return Start.task(new Tasks.Give(p, mt, n));
            }
            case "look_in_chest", "search_chests", "inspect_chest" -> {
                Block c = Planner.nearestChest(h);
                if (c == null) return Start.err("no chest within 20 blocks");
                return Start.task(new Life.InspectChest(c));
            }
            case "take_from_chest" -> {
                Block c = Planner.nearestChest(h);
                if (c == null) return Start.err("no chest within 20 blocks");
                boolean allowed = h.chestPermission.values().stream().anyMatch(t -> m.now() - t < 20 * 60 * 30)
                    || g.from != null && g.text.toLowerCase(Locale.ROOT).matches(".*\\b(take|grab|get|use)\\b.*\\bchests?\\b.*");
                if (!allowed) return Start.err("no permission to take from chests - ask the owner first");
                Material mt = mat(s(a, "item", ""));
                if (mt == null) return Start.err("unknown item");
                return Start.task(new Life.TakeFromChest(c, mt, g.from == null ? "a player" : g.from));
            }
            case "put_in_chest" -> {
                Block c = Planner.nearestChest(h);
                Material mt = mat(s(a, "item", ""));
                if (c == null) return Start.err("no chest within 20 blocks");
                if (mt == null || h.inv.count(mt) == 0) return Start.err("you have no " + s(a, "item", "?"));
                return Start.task(new PutInChest(c, mt, Math.max(1, i(a, "count", h.inv.count(mt)))));
            }
            case "ask" -> {
                Player p = player(s(a, "player", ""));
                String text = s(a, "text", "");
                if (text.isEmpty()) return Start.err("ask needs text");
                HumansPlugin.get().chat().broadcast(h, (p != null ? p.getName() + ", " : "") + text);
                h.event("I asked " + (p != null ? p.getName() : "everyone") + ": " + text);
                return Start.task(new Tasks.Idle(60, "waiting for an answer"));
            }
            case "say", "chat" -> {
                String text = s(a, "text", "");
                if (!text.isEmpty()) { HumansPlugin.get().chat().broadcast(h, text.length() > 160 ? text.substring(0, 157) + "..." : text); h.event(h.name + " said: \"" + text + "\""); }
                return Start.ok("said it");
            }
            case "stand_at" -> {
                int x = i(a, "x", l.getBlockX()), z = i(a, "z", l.getBlockZ());
                int y = a.has("y") ? i(a, "y", l.getBlockY()) : l.getWorld().getHighestBlockYAt(x, z) + 1;
                Location to = new Location(l.getWorld(), x + 0.5, y, z + 0.5);
                if (to.distance(l) > 60) return Start.err("that spot is too far");
                return Start.task(new Work.StandAt(to, player(s(a, "face", "")), i(a, "seconds", 20)));
            }
            case "wait", "rest" -> { return Start.task(new Tasks.Idle(Math.max(1, Math.min(i(a, "seconds", 5), 60)) * 20, "waiting")); }
            case "dance" -> { return Start.task(new Life.Dance()); }
            case "pet_animal", "pet" -> {
                for (Entity e : h.entity.getNearbyEntities(16, 6, 16)) if (e instanceof Animals an) return Start.task(new Life.PetAnimal(an));
                return Start.err("no animals nearby");
            }
            case "have_baby" -> {
                Human o = m.byName(s(a, "with", ""));
                String why = Family.cantHaveBaby(h, o, m);
                if (why != null) return Start.err(why);
                return Start.task(new Family.HaveBaby(o));
            }
            default -> { return Start.err("unknown skill '" + step.skill() + "'"); }
        }
    }

    static String needs(Items.Recipe r) {
        List<String> n = new ArrayList<>();
        r.inputs().forEach((k, v) -> n.add(v + " " + (k instanceof Material mm ? Items.nice(mm) : k.toString().toLowerCase(Locale.ROOT).replaceAll(".*:", ""))));
        return String.join(" + ", n) + (r.needsTable() ? " at a crafting table" : "");
    }

    static EntityType entity(String n) {
        try { return EntityType.valueOf(n.toUpperCase(Locale.ROOT).replaceAll("S$", "").trim()); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static Start build(Human h, JsonObject a) {
        String st = s(a, "structure", "house").toLowerCase(Locale.ROOT);
        String at = s(a, "at", "here");
        if (h.village != null && at.equals("here") && h.village.distance(h.loc()) > 30) at = "village";
        Build.Blueprint bp;
        switch (st) {
            case "house", "hut", "home" -> bp = Build.house(i(a, "width", 7), i(a, "depth", 7), i(a, "height", 4), s(a, "roof", "peaked"), s(a, "material", "any"));
            case "farm" -> bp = Build.farm(i(a, "width", 5), i(a, "depth", 5));
            case "pen", "fence", "animal_pen" -> bp = Build.pen(i(a, "width", 6), i(a, "depth", 6));
            case "wall", "path" -> bp = Build.line(st, i(a, "length", 8), s(a, "direction", "east"), i(a, "height", 2), s(a, "material", "any"));
            case "custom" -> {
                List<List<String>> layers = new ArrayList<>();
                if (a.has("layers") && a.get("layers").isJsonArray())
                    for (JsonElement layer : a.getAsJsonArray("layers")) {
                        List<String> rows = new ArrayList<>();
                        if (layer.isJsonArray()) for (JsonElement r : layer.getAsJsonArray()) rows.add(r.getAsString());
                        else if (layer.isJsonPrimitive()) rows.addAll(Arrays.asList(layer.getAsString().split("\\|")));
                        layers.add(rows);
                    }
                Map<Character, String> legend = new HashMap<>();
                if (a.has("legend") && a.get("legend").isJsonObject())
                    for (var e : a.getAsJsonObject("legend").entrySet()) if (!e.getKey().isEmpty()) legend.put(e.getKey().charAt(0), e.getValue().getAsString().toLowerCase(Locale.ROOT).replace("minecraft:", ""));
                if (layers.isEmpty() || legend.isEmpty()) return Start.err("custom build needs layers and legend");
                bp = Build.custom(s(a, "name", "build"), layers, legend);
                if (bp.pieces().isEmpty()) return Start.err("custom build has no blocks (check legend characters)");
            }
            default -> { return Start.err("unknown structure '" + st + "' (house, farm, pen, wall, path, custom)"); }
        }
        return Start.task(new Build.BuildTask(bp, at));
    }

    /** Put items into a chest (gifts, storage). */
    static final class PutInChest extends Life.InspectChest {
        private final Material mat; private final int n;
        PutInChest(Block chest, Material mat, int n) { super(chest); this.mat = mat; this.n = n; }
        @Override public String label() { return "putting " + Items.nice(mat) + " in a chest"; }
        @Override protected Status finishLook(Human h, HumanManager m) {
            int k = Math.min(n, h.inv.count(mat));
            if (chest.getState() instanceof org.bukkit.block.Container c && k > 0) {
                var left = c.getInventory().addItem(new org.bukkit.inventory.ItemStack(mat, k));
                int notStored = left.values().stream().mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum();
                h.inv.remove(mat, k - notStored);
                close();
                outcome = "put " + (k - notStored) + " " + Items.nice(mat) + " in the chest" + (notStored > 0 ? " (chest full)" : "");
                return Status.SUCCESS;
            }
            close();
            outcome = "couldn't put anything in the chest";
            return Status.FAIL;
        }
    }
}
