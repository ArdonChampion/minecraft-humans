package dev.timur.humans.plan;

import com.google.gson.*;
import dev.timur.humans.*;
import dev.timur.humans.brain.OptionGenerator;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Turns a goal + the current situation into a short list of skill calls, using the text AI. */
public final class Planner {
    private Planner() {}

    public record Result(String thought, String say, String title, List<Goal.Step> steps, boolean done) {}

    static final String SKILLS = """
        chop_trees {logs:int} - find and chop trees until you collected that many more logs (also picks them up)
        mine {block:"stone|dirt|sand|gravel|clay|coal_ore|iron_ore|copper_ore|gold_ore|redstone_ore|lapis_ore|diamond_ore", count:int} - mine visible blocks of that type nearby (iron needs stone pickaxe, diamond/gold/redstone need iron pickaxe)
        strip_mine {y:int, length:int} - staircase down to height y, then a long tunnel, mining every ore it uncovers. Iron is common around y=16..48, diamonds around y=-58..-50. Takes a few minutes; bring food and torches.
        explore {for:"trees|stone|animals|water|a flat spot|anything"} - walk further away to find something
        go_to {x:int,y:int,z:int} | go_to {player:"NAME"} | go_to {human:"NAME"} | go_to {place:"home|village|bed"}
        follow {player:"NAME", seconds:int}
        go_outside {} - leave a cave / get from underground to the surface
        collect_items {} - pick up dropped items nearby
        craft {item:"RECIPE", count:int} - planks, sticks and a crafting table are made automatically when needed
        smelt {} - furnace: cook raw meat/potatoes, smelt raw iron/gold, sand->glass, cobblestone->stone (needs a furnace item + fuel)
        eat {} | sleep {} | place_bed {} (puts a bed you carry in your house)
        build {structure:"house", width:5-11, depth:5-11, height:3-5, roof:"flat|peaked", material:"planks|cobblestone|logs|any", at:"here|home|village|X,Z"}
        build {structure:"farm", width:3-9, depth:3-9, at:...} - tilled farmland with seeds (needs a hoe and seeds/carrots/potatoes; odd sizes get a water hole if you carry a water bucket)
        build {structure:"pen", width:4-12, depth:4-12, at:...} - fence animal pen with a gate (needs fences + 1 fence gate)
        build {structure:"wall"|"path", length:int, direction:"north|south|east|west", height:1-4, material:"...", at:...}
        build {structure:"custom", name:"what it is", layers:[["row1","row2"],["row1","row2"]], legend:{"#":"oak_planks","C":"cobblestone","G":"glass","T":"torch","D":"door"}, at:...} - your own design: layers go bottom->top, rows go north->south, characters west->east, space or '.' = leave empty. Max 300 blocks. You must carry the materials.
        set_village {} - claim the area where you stand as your (and your friends') village; you can build and dig there freely
        farm {} - harvest ripe crops nearby and replant them
        gather_plants {} - break grass and flowers nearby (grass sometimes drops wheat seeds; flowers make nice gifts)
        breed_animals {animal:"cow|sheep|pig|chicken"} - feed two animals their food (cows/sheep: wheat, pigs: carrots/potatoes, chickens: seeds)
        hunt {animal:"cow|pig|chicken|sheep|rabbit", count:int}
        fight {target:"nearest_monster|zombie|skeleton|spider|creeper"} - never players
        give {to:"player:NAME" or "human:NAME", item:"material_name", count:int}
        look_in_chest {} - open the nearest chest and see what's inside
        take_from_chest {item:"material_name", count:int} - ONLY when the owner allowed it
        put_in_chest {item:"material_name", count:int}
        ask {player:"NAME", text:"question"} - ask a player (their reply shows up in your recent events next round)
        say {text:"..."}
        stand_at {x:int,y:int,z:int, face:"PLAYER_NAME", seconds:int} - go to an exact spot and stay there (e.g. lining up)
        break_block {x:int,y:int,z:int, x2:int,y2:int,z2:int} - walk over and break one block (or a box of up to 32, x2/y2/z2 optional) like a player: you must be able to see it (break what's in front first), any block works but without the right tool it's ~3x slower and stone-type blocks drop nothing without a pickaxe. Storage/beds can't be broken.
        wait {seconds:int}
        dance {} | pet_animal {}
        have_baby {with:"HUMAN_NAME"} - start a family with another adult human; you both need to be fed and have 6+ food items between you
        """;

    static String recipes() {
        StringBuilder s = new StringBuilder();
        for (Items.Recipe r : Items.RECIPES) s.append(r.id()).append(", ");
        return s.substring(0, s.length() - 2);
    }

    static String system(Human h) {
        return "You are the planning mind of " + h.name + ", an AI-driven human living in a Minecraft 26.3 survival world on a small server with friends. "
            + "You think like a smart, resourceful and fun survival player. Personality: " + h.personality.describe() + ".\n"
            + "Each round you get the situation and a GOAL. Reply with ONLY one JSON object, no markdown:\n"
            + "{\"thought\":\"1-2 sentences of reasoning\",\"say\":\"optional short line " + h.name + " says out loud, or empty\",\"goal_title\":\"2-6 word name for the goal\","
            + "\"steps\":[{\"do\":\"skill_name\", ...args}],\"done\":false}\n"
            + "Rules:\n- Give 1-6 steps for the NEXT part of the work. You will be called again with the results, so adapt as you go.\n"
            + "- When the goal is achieved (or truly impossible) reply with \"done\":true and no steps, and say something about it.\n"
            + "- Only use the skills below with sensible arguments. Coordinates are block coordinates.\n"
            + "- Get what you're missing first (no pickaxe -> chop_trees, craft wooden_pickaxe; need iron -> stone pickaxe first, then mine/strip_mine iron_ore and smelt).\n"
            + "- Respect players: never break or build inside players' bases (protected areas), never take from chests without permission, never attack players.\n"
            + "- If a step failed, read why and try a different approach instead of repeating the same thing.\n"
            + "- Keep the players' requests' spirit: be helpful and entertaining. You can only do what the skills allow; if asked for something impossible, do the closest fun thing and say so.\n"
            + "SKILLS:\n" + SKILLS + "Craftable recipes: " + recipes() + ".\n";
    }

    static String snapshot(Human h, HumanManager m, Goal g) {
        Location l = h.loc(); World w = l.getWorld();
        StringBuilder s = new StringBuilder();
        long t = w.getTime();
        s.append("TIME: day ").append(w.getFullTime() / 24000 + 1).append(t < 12600 || t > 23400 ? ", daytime" : ", NIGHT").append(w.hasStorm() ? ", raining" : "").append(". You see perfectly in the dark (night vision), so darkness never blocks you.\n");
        s.append("YOU: ").append(h.name).append(h.isChild() ? " (a child)" : "").append(" at x=").append(l.getBlockX()).append(" y=").append(l.getBlockY()).append(" z=").append(l.getBlockZ())
            .append(h.underground ? " (UNDERGROUND, no sky)" : "").append(h.trapped ? " (TRAPPED)" : "")
            .append(String.format(". Health %.0f/20, hunger %.0f/20, energy %.0f/20.%n", h.entity.getHealth(), h.hunger, h.energy));
        s.append("INVENTORY: ").append(h.inv.describe()).append(".\n");
        s.append("PLACES: home ").append(xyz(h.home)).append(h.hutBuilt ? " (has a house)" : " (no house)")
            .append(", village ").append(h.village == null ? "none" : xyz(h.village)).append(", bed ").append(h.bed == null ? "none" : xyz(h.bed)).append(".\n");
        if (h.project != null) s.append("PERSONAL PROJECT: ").append(OptionGenerator.projectDesc(h.project)).append(".\n");
        List<String> people = new ArrayList<>(), mobs = new ArrayList<>();
        Map<String, Integer> animals = new TreeMap<>();
        for (Entity e : h.entity.getNearbyEntities(40, 16, 40)) {
            Location el = e.getLocation(); int d = (int) el.distance(l);
            if (e instanceof Player p) people.add("player " + p.getName() + " at " + xyz(el) + " (" + d + "m, you " + Human.trustWord(h.trustOf(p.getUniqueId())) + " them)");
            else if (m.byEntity(e) != null) { Human o = m.byEntity(e); people.add("human " + o.name + (o.isChild() ? " (child)" : "") + " at " + xyz(el) + " (" + d + "m, doing: " + (o.task == null ? "nothing" : o.task.label()) + ")"); }
            else if (e instanceof Monster && d < 24) mobs.add(e.getType().name().toLowerCase(Locale.ROOT) + " " + d + "m");
            else if (e instanceof Animals) animals.merge(e.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        s.append("PEOPLE NEARBY: ").append(people.isEmpty() ? "none" : String.join("; ", people)).append(".\n");
        if (!mobs.isEmpty()) s.append("MONSTERS: ").append(String.join(", ", mobs)).append(".\n");
        if (!animals.isEmpty()) s.append("ANIMALS NEARBY: ").append(animals).append(".\n");
        OptionGenerator.Scan sc = OptionGenerator.scan(h, m);
        List<String> res = new ArrayList<>();
        if (sc.tree != null) res.add("tree at " + xyz(sc.tree.getLocation()));
        if (sc.stone != null) res.add("exposed stone at " + xyz(sc.stone.getLocation()));
        sc.ores.forEach((k, b) -> res.add(k + " at " + xyz(b.getLocation())));
        if (!sc.crops.isEmpty()) res.add(sc.crops.size() + " ripe crops");
        if (!sc.flowers.isEmpty()) res.add("flowers/grass");
        s.append("RESOURCES SEEN: ").append(res.isEmpty() ? "nothing special" : String.join(", ", res)).append(".\n");
        Block chest = nearestChest(h);
        if (chest != null) s.append("NEAREST CHEST: at ").append(xyz(chest.getLocation())).append(h.chestPermission.isEmpty() ? " (no permission to take anything)" : " (permission from: " + names(h) + ")").append(".\n");
        s.append("PLAYERS' BASES (protected, don't break/build there): ").append(HumansPlugin.get().zones().describeNear(l, h)).append(".\n");
        if (!h.memories.isEmpty()) s.append("MEMORIES: ").append(String.join(" | ", h.memories.stream().limit(8).toList())).append(".\n");
        if (!h.recent.isEmpty()) s.append("RECENT EVENTS (newest first): ").append(String.join(" | ", h.recent)).append(".\n");
        s.append("\nGOAL: ").append(g.text);
        if (g.from != null) s.append(" (asked by player ").append(g.from).append(")");
        if (!g.group.isEmpty()) s.append(" [you were asked together with: ").append(g.group).append(" - coordinate, split the work, don't all do the same thing]");
        s.append("\n");
        if (!g.log.isEmpty()) s.append("PROGRESS SO FAR (oldest first):\n- ").append(String.join("\n- ", g.log)).append("\n");
        s.append("Round ").append(g.rounds + 1).append(". What next?");
        return s.toString();
    }

    private static String names(Human h) {
        List<String> n = new ArrayList<>();
        for (UUID id : h.chestPermission.keySet()) n.add(h.knownNames.getOrDefault(id, "a player"));
        return String.join(", ", n);
    }

    static Block nearestChest(Human h) {
        Location l = h.loc(); Block best = null; double bd = 20 * 20;
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            if (!l.getWorld().isChunkLoaded(x, z)) continue;
            for (var st : l.getWorld().getChunkAt(x, z).getTileEntities())
                if ((st instanceof org.bukkit.block.Chest || st instanceof org.bukkit.block.Barrel) && st.getLocation().distanceSquared(l) < bd) { bd = st.getLocation().distanceSquared(l); best = st.getBlock(); }
        }
        return best;
    }

    static String xyz(Location l) { return l == null ? "none" : l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }

    public static CompletableFuture<Result> plan(Human h, HumanManager m, Goal g) {
        String sys = system(h), user = snapshot(h, m, g);
        var cfg = HumansPlugin.get().getConfig();
        return HumansPlugin.get().text().complete(sys, user, cfg.getInt("text.plan_max_tokens", 4000), cfg.getInt("text.plan_timeout_seconds", 70), 1500)
            .thenApply(Planner::parse);
    }

    static Result parse(String raw) {
        String s = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new RuntimeException("planner gave no JSON: " + (s.length() > 120 ? s.substring(0, 120) : s));
        JsonObject j;
        try {
            com.google.gson.stream.JsonReader r = new com.google.gson.stream.JsonReader(new java.io.StringReader(s.substring(a, b + 1)));
            r.setLenient(true);
            j = JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) { throw new RuntimeException("planner JSON unreadable: " + e.getMessage()); }
        List<Goal.Step> steps = new ArrayList<>();
        if (j.has("steps") && j.get("steps").isJsonArray())
            for (JsonElement e : j.getAsJsonArray("steps")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String skill = str(o, "do"); if (skill.isEmpty()) skill = str(o, "skill");
                if (o.has("args") && o.get("args").isJsonObject()) for (var kv : o.getAsJsonObject("args").entrySet()) o.add(kv.getKey(), kv.getValue());
                if (!skill.isEmpty()) steps.add(new Goal.Step(skill.toLowerCase(Locale.ROOT).trim(), o));
                if (steps.size() >= 8) break;
            }
        boolean done = j.has("done") && j.get("done").isJsonPrimitive() && j.get("done").getAsBoolean();
        return new Result(str(j, "thought"), str(j, "say"), str(j, "goal_title"), steps, done);
    }

    static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }
}
