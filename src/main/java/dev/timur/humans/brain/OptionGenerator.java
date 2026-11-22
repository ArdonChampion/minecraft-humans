package dev.timur.humans.brain;

import dev.timur.humans.*;
import dev.timur.humans.tasks.Life;
import dev.timur.humans.tasks.Task;
import dev.timur.humans.tasks.Tasks;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.*;

import java.util.*;
import java.util.function.Supplier;

/** Looks at the world around a human and lists concrete, doable options. */
public final class OptionGenerator {
    private OptionGenerator() {}

    private static final Set<EntityType> FOOD_ANIMALS = EnumSet.of(EntityType.COW, EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP);

    /** What the scan found. */
    public static final class Scan {
        public Block tree; double treeD = 1e9;
        public final Map<String, Block> ores = new HashMap<>(); final Map<String, Double> oreD = new HashMap<>();
        public Block stone; double stoneD = 1e9;
        public final List<Block> flowers = new ArrayList<>();
        public final List<Block> crops = new ArrayList<>();
    }

    /** Personal projects give a human a longer-term direction (and variety). */
    public static final String[][] PROJECTS = {
        {"house", "build a proper house to live in"},
        {"animals", "take care of animals: pet, feed and breed them"},
        {"garden", "collect pretty flowers and give them to friends"},
        {"miner", "become a great miner: stone, coal and iron"},
        {"farmer", "help out on farms: harvest and replant crops, bake bread"},
        {"friend", "make friends with the players and the other humans"},
        {"explorer", "explore the land and enjoy the views"},
        {"chef", "cook good food and keep everyone fed"}
    };

    public static String projectDesc(String id) {
        for (String[] p : PROJECTS) if (p[0].equals(id)) return p[1];
        return id;
    }

    private static double projectBonus(Human h, String id) {
        if (h.project == null) return 0;
        return switch (h.project) {
            case "house" -> id.equals("build") || id.equals("chop") || id.equals("make_planks") || id.equals("craft_door") || id.equals("explore_site") ? 0.3 : 0;
            case "animals" -> id.startsWith("feed_") || id.equals("pet_animal") ? 0.4 : 0;
            case "garden" -> id.equals("pick_flowers") || id.startsWith("gift_") ? 0.4 : 0;
            case "miner" -> id.startsWith("mine_") || id.startsWith("craft_") && id.contains("pickaxe") || id.equals("smelt") || id.equals("explore_stone") ? 0.3 : 0;
            case "farmer" -> id.equals("farm") || id.startsWith("feed_") || id.equals("craft_bread") ? 0.4 : 0;
            case "friend" -> id.startsWith("greet_") || id.startsWith("gift_") || id.startsWith("visit_") || id.equals("dance") || id.startsWith("follow_") ? 0.3 : 0;
            case "explorer" -> id.startsWith("explore") || id.equals("wander") || id.equals("ponder") || id.equals("sunset") ? 0.3 : 0;
            case "chef" -> id.equals("smelt") || id.equals("hunt") || id.equals("craft_bread") || id.startsWith("gift_") ? 0.3 : 0;
            default -> 0;
        };
    }

    /** Actions that shouldn't be discouraged just because they repeat. */
    private static final Set<String> NO_VARIETY = Set.of("keep", "do", "eat", "sleep", "flee", "fight", "help", "escape", "extinguish", "collect", "check");

    public static List<Option> generate(Human h, HumanManager m) {
        List<Option> out = new ArrayList<>();
        Location loc = h.loc();
        World w = loc.getWorld();
        long t = w.getTime();
        boolean night = t > 12600 && t < 23400, dusk = t > 11000 && t <= 12600;
        Personality p = h.personality;
        double hp = h.entity.getHealth();

        // ---------- the current task (when re-evaluating)
        boolean committed = h.commitUntil > m.now();
        if (h.task != null) {
            Task cur = h.task;
            String what = cur instanceof dev.timur.humans.plan.PlanTask pt ? "working on your goal \"" + pt.goal().title + "\" (now: " + cur.label() + ")" : cur.label();
            out.add(committed
                ? new Option("keep", "Keep " + what + " - " + h.commitWho + " asked you to and you agreed", () -> cur, 1.4)
                : new Option("keep", "Keep " + what, () -> cur, 0.75 + p.diligence() * 0.2 + (cur instanceof dev.timur.humans.plan.PlanTask ? 0.35 : 0)));
        }

        // ---------- entities around
        List<Player> players = new ArrayList<>(); List<Monster> hostiles = new ArrayList<>(); List<Animals> animals = new ArrayList<>(), pets = new ArrayList<>();
        List<Human> others = new ArrayList<>();
        int items = 0;
        for (Entity e : h.entity.getNearbyEntities(22, 10, 22)) {
            if (e instanceof Player pl && pl.getGameMode() != GameMode.SPECTATOR) players.add(pl);
            else if (e instanceof Monster mo && !mo.isDead()) hostiles.add(mo);
            else if (m.byEntity(e) != null && m.byEntity(e) != h && m.byEntity(e).valid()) others.add(m.byEntity(e));
            else if (e instanceof Animals a && FOOD_ANIMALS.contains(a.getType()) && a.isAdult()) { if (!HumansPlugin.get().zones().isProtected(a.getLocation(), h)) animals.add(a); pets.add(a); }
            else if (e instanceof Animals a) pets.add(a);
            else if (e instanceof Item it && it.getLocation().distanceSquared(loc) < 100 && it.getPickupDelay() < 20 && !h.badItems.contains(it.getUniqueId()) && m.mayPickUp(h, it)) items++;
        }
        Comparator<Entity> byDist = Comparator.comparingDouble(e -> e.getLocation().distanceSquared(loc));
        players.sort(byDist); hostiles.sort(byDist); animals.sort(byDist); pets.sort(byDist);

        // ---------- awareness: trapped / fire
        if (h.trapped) {
            out.add(new Option("escape", h.trappedOpenSky ? "You are stuck in a HOLE: dig a staircase and climb out" : "You are walled in: dig your way out",
                Life.Escape::new, 1.6, true));
        }
        if (h.fireAtHome && h.hutCenter != null)
            out.add(new Option("extinguish", "YOUR HOUSE IS ON FIRE: run there and put the fire out", () -> new Life.Extinguish(h.hutCenter), 1.5, true));
        else if (h.fireNear > 0)
            out.add(new Option("extinguish", "Put out the fire burning nearby (" + h.fireNear + " flames) before it spreads", () -> new Life.Extinguish(h.loc()), 0.5 + p.bravery() * 0.5, true));
        if (h.alertFrom != null && m.now() - h.alertTick < 20 * 90 && h.home != null && h.home.getWorld() == w && h.home.distance(loc) > 8) {
            Player who = Bukkit.getPlayer(h.alertFrom);
            out.add(new Option("check_home", "Rush home to check, " + (who == null ? "someone" : who.getName()) + " warned that something is wrong there",
                () -> new Tasks.Goto(h.home, 3, true, "rushing home to check"), 0.4 + h.trustOf(h.alertFrom) / 4 * 0.8));
        }

        // ---------- danger
        Player attackerPlayer = null;
        if (h.lastAttacker != null && m.now() - h.lastAttackedTick < 20 * 30) {
            Entity a = Bukkit.getEntity(h.lastAttacker);
            if (a instanceof Player ap && ap.getWorld() == w && ap.getLocation().distance(loc) < 18) attackerPlayer = ap;
        }
        if (attackerPlayer != null) {
            final Player ap = attackerPlayer;
            double tr = h.trustOf(ap.getUniqueId());
            out.add(new Option("fight_back", "Fight back against " + ap.getName() + ", who just hit you (you " + Human.trustWord(tr) + " them)",
                () -> new Tasks.Attack(ap, false), p.bravery() * (2.5 - tr) / 2.5 + 0.1, true));
            out.add(new Option("flee_player", "Run away from " + ap.getName() + ", who just hit you", () -> new Tasks.Flee(ap), (1 - p.bravery()) * 0.8, true));
        }
        int listed = 0;
        for (Monster mo : hostiles) {
            double d = mo.getLocation().distance(loc);
            if (d > 16 || listed >= 2) break;
            listed++;
            boolean creeper = mo instanceof Creeper;
            String name = HumanManager.nameOf(mo);
            if (!creeper || p.bravery() > 0.7)
                out.add(new Option("fight_" + listed, "Fight the " + name + " " + Math.round(d) + " blocks away" + weaponNote(h),
                    () -> new Tasks.Attack(mo, false), 0.25 + p.bravery() * 0.5 - (hp < 8 ? 0.3 : 0) + (night ? 0 : 0.1)));
            if (d < 10 || creeper && d < 7)
                out.add(new Option("flee_" + listed, "Run away from the " + name + " (" + Math.round(d) + " blocks away)",
                    () -> new Tasks.Flee(mo), (1 - p.bravery()) * 0.7 + (creeper ? 0.45 : 0) + (hp < 8 ? 0.4 : 0), creeper));
        }
        if (h.helpTarget != null && m.now() - h.helpTick < 20 * 12) {
            Player victim = Bukkit.getPlayer(h.helpTarget);
            if (victim != null && victim.getWorld() == w && victim.getLocation().distance(loc) < 24) {
                Monster foe = null; double fd = 1e9;
                for (Entity e : victim.getNearbyEntities(8, 4, 8)) if (e instanceof Monster mo && e.getLocation().distanceSquared(victim.getLocation()) < fd) { foe = mo; fd = e.getLocation().distanceSquared(victim.getLocation()); }
                if (foe != null) {
                    final Monster f = foe; double tr = h.trustOf(victim.getUniqueId());
                    out.add(new Option("help", "Help " + victim.getName() + ", who is being attacked by a " + HumanManager.nameOf(f) + " (you " + Human.trustWord(tr) + " them)",
                        () -> new Tasks.Attack(f, false), 0.3 + tr / 4 * 0.5 + p.bravery() * 0.3, true));
                }
            }
        }

        // ---------- survival
        if (h.hunger < 17) Items.bestFood(h.inv).ifPresent(f ->
            out.add(new Option("eat", "Eat " + Items.nice(f) + " (hunger " + Math.round(h.hunger) + "/20)", Tasks.Eat::new, (20 - h.hunger) / 20 * 1.3)));
        int food = h.inv.countWhere(Items::isFood);
        if (!animals.isEmpty() && (food < 6 || h.hunger < 12)) {
            Animals a = animals.getFirst();
            out.add(new Option("hunt", "Hunt the " + HumanManager.nameOf(a) + " " + Math.round(a.getLocation().distance(loc)) + " blocks away for food (have " + food + " food)",
                () -> new Tasks.Attack(a, true), 0.35 + (20 - h.hunger) / 20 * 0.6));
        }
        if (night) out.add(new Option("sleep", "Go to bed, it is night" + (h.hutBuilt ? " (in your house)" : " (you have no house yet)"), Tasks.Sleep::new, hostiles.isEmpty() ? 0.95 : 0.4));
        if (h.home != null && h.home.getWorld() == w) {
            double hd = h.home.distance(loc);
            if (hd > 24) out.add(new Option("go_home", "Walk back home (" + Math.round(hd) + " blocks away)",
                () -> new Tasks.Goto(h.home, 2, false, "walking home"), 0.25 + (dusk || night ? 0.5 : 0)));
        }
        if (h.energy < 7)
            out.add(new Option("nap", "Take a nap, you're exhausted (energy " + Math.round(h.energy) + "/20)", () -> new Tasks.Sleep(20 * 25), 0.5 + (7 - h.energy) / 7 * 0.6));
        if (h.hunger < 10 && h.inv.countWhere(Items::isFood) == 0 && !players.isEmpty() && m.now() - h.lastAskFoodTick > 20 * 120) {
            Player pl = players.getFirst();
            out.add(new Option("ask_food", "Ask " + pl.getName() + " for some food (you're hungry and have none)",
                () -> { h.lastAskFoodTick = m.now(); return new Tasks.Greet(pl, false, "asking " + pl.getName() + " for food"); }, 0.55, false,
                "!" + dev.timur.humans.brain.Lines.pick(dev.timur.humans.brain.Lines.ASK_FOOD, h, pl.getName(), null)));
        }
        if (items > 0) out.add(new Option("collect", "Pick up the " + items + " items lying on the ground nearby", Tasks.CollectItems::new, 0.6));

        // ---------- gathering
        Scan s = scan(h, m);
        int wood = h.inv.countWhere(Tag.LOGS::isTagged) * 4 + h.inv.countWhere(Tag.PLANKS::isTagged);
        Items.Tier pick = Items.pickaxeTier(h.inv);
        if (s.tree != null) {
            Block tree = s.tree;
            out.add(new Option("chop", "Chop down the " + Items.nice(tree.getType()).replace(" log", "") + " tree " + Math.round(s.treeD) + " blocks away (have " + wood + " planks worth of wood)",
                () -> new Tasks.Harvest(treeLogs(tree), "chopping a tree", "logs"), wood < 12 ? 0.5 + p.diligence() * 0.2 : wood < 40 ? 0.3 : 0.1));
        }
        for (var e : s.ores.entrySet()) {
            String ore = e.getKey(); Block b = e.getValue();
            Items.Tier need = Items.requiredTier(b.getType());
            if (pick.ordinal() < need.ordinal()) continue;
            double value = switch (ore) { case "diamond ore" -> 0.85; case "iron ore" -> 0.6; case "gold ore" -> 0.45; case "coal ore" -> 0.5; default -> 0.25; };
            out.add(new Option("mine_" + ore.replace(' ', '_'), "Mine the " + ore + " " + Math.round(s.oreD.get(ore)) + " blocks away",
                () -> new Tasks.Harvest(vein(b, 8), "mining " + ore, ore.replace(" ore", "")), value));
        }
        if (s.stone != null && pick != Items.Tier.NONE && h.inv.count(Material.COBBLESTONE) < 40) {
            Block st = s.stone;
            out.add(new Option("mine_stone", "Mine some stone " + Math.round(s.stoneD) + " blocks away to get cobblestone (have " + h.inv.count(Material.COBBLESTONE) + ")",
                () -> new Tasks.Harvest(vein(st, 7), "mining stone", "cobblestone"), h.inv.count(Material.COBBLESTONE) < 12 ? 0.5 : 0.3));
        }

        if (s.tree == null && wood < 12)
            out.add(new Option("explore_trees", "Go exploring further away to find trees (there are none nearby, and you need wood)", () -> new Tasks.Explore("trees"), 0.5 + p.diligence() * 0.2));
        else if (s.stone == null && s.ores.isEmpty() && pick != Items.Tier.NONE && h.inv.count(Material.COBBLESTONE) < 12)
            out.add(new Option("explore_stone", "Go exploring to find exposed stone or ores (none nearby)", () -> new Tasks.Explore("stone"), 0.45));
        else out.add(new Option("explore", "Go exploring the surrounding area", () -> new Tasks.Explore("something interesting"), 0.15));

        // ---------- crafting
        for (Items.Recipe r : Items.RECIPES) {
            if (r.id().equals("planks") || r.id().equals("sticks")) continue; // made automatically as needed
            double score = craftValue(h, r);
            if (score <= 0) continue;
            List<Items.Recipe> steps = Items.plan(h.inv, r);
            if (steps == null) continue;
            String extra = steps.size() > 1 ? " (making the planks/sticks/crafting table it needs first)" : "";
            out.add(new Option("craft_" + r.id(), "Craft " + r.nice() + extra, () -> new Tasks.Craft(steps, r.nice()), score));
        }
        int logs = h.inv.countWhere(Tag.LOGS::isTagged);
        if (!h.hutBuilt && logs >= 2 && h.inv.countWhere(Items::isBuildingBlock) < Tasks.BuildHut.planSize()) {
            int n = Math.min(logs, 16);
            List<Items.Recipe> pl = Collections.nCopies(n, Items.byId("planks"));
            out.add(new Option("make_planks", "Turn " + n + " logs into " + n * 4 + " planks for building your house", () -> new Tasks.Craft(pl, n * 4 + " planks"), 0.15 + p.diligence() * 0.2));
        }
        boolean hasFuel = h.inv.has(Material.COAL, 1) || h.inv.countWhere(Tag.LOGS::isTagged) > 0 || h.inv.countWhere(Tag.PLANKS::isTagged) > 1;
        int smeltable = h.inv.countWhere(x -> Items.cooked(x) != null && x != Material.COBBLESTONE && x != Material.SAND && x != Material.RED_SAND);
        if (h.inv.has(Material.FURNACE, 1) && hasFuel && smeltable > 0)
            out.add(new Option("smelt", "Use your furnace to cook/smelt " + smeltable + " items (raw food, raw iron...)", Tasks.Smelt::new,
                0.45 + (h.inv.countWhere(Items::isRawMeat) > 0 && h.hunger < 12 ? 0.3 : 0)));

        // ---------- building
        if (!h.hutBuilt) {
            int blocks = h.inv.countWhere(Items::isBuildingBlock);
            if (blocks >= 10 && h.hutCenter == null && m.now() < h.noSiteUntil)
                out.add(new Option("explore_site", "Explore to find an open, flat spot to build your house (none near your home)", () -> new Tasks.Explore("a building spot") {
                    @Override public void stop(Human hh) { super.stop(hh); if (hh.valid() && Navigator.standable(hh.loc().getWorld(), hh.loc().getBlockX(), hh.loc().getBlockY(), hh.loc().getBlockZ())) { hh.home = hh.loc(); hh.noSiteUntil = 0; } }
                }, 0.4 + p.diligence() * 0.2));
            else if (blocks >= 10)
                out.add(new Option("build", "Work on building your own house (have " + blocks + " building blocks, a house needs about " + Tasks.BuildHut.planSize() + ")",
                    Tasks.BuildHut::new, 0.15 + p.diligence() * 0.2 + (blocks >= Tasks.BuildHut.planSize() ? 0.2 : 0)));
        }

        // ---------- social
        int pi = 0;
        for (Player pl : players) {
            if (pi >= 3) break;
            pi++;
            double tr = h.trustOf(pl.getUniqueId());
            double d = pl.getLocation().distance(loc);
            String rel = "you " + Human.trustWord(tr) + " " + pl.getName();
            Long lastGreet = h.greeted.get(pl.getUniqueId());
            if (tr >= 1 && (lastGreet == null || m.now() - lastGreet > 20 * 60 * 4))
                out.add(new Option("greet_" + pi, "Go over and say hi to " + pl.getName() + " (" + Math.round(d) + " blocks away, " + rel + ")",
                    () -> { h.greeted.put(pl.getUniqueId(), m.now()); return new Tasks.Greet(pl); }, 0.12 + p.friendliness() * 0.25 * tr / 2, false,
                    "!" + dev.timur.humans.brain.Lines.pick(dev.timur.humans.brain.Lines.GREET, h, pl.getName(), null)));
            if (tr >= 2.8) out.add(new Option("follow_" + pi, "Tag along with " + pl.getName() + " for a bit (" + rel + ")",
                () -> new Tasks.Follow(pl, 20 * 30), 0.05 + p.friendliness() * 0.15 * tr / 4));
            if (tr >= 2.8) {
                Material gift = giftFor(h);
                if (gift != null) {
                    int n = Math.max(1, Math.min(h.inv.count(gift) / 3, 8));
                    out.add(new Option("gift_" + pi, "Give " + pl.getName() + " " + n + " " + Items.nice(gift) + " as a gift (" + rel + ")",
                        () -> new Tasks.Give(pl, gift, n), 0.1 + p.generosity() * 0.3, true));
                }
            }
        }

        // ---------- everyday life
        boolean animalLover = p.quirk().contains("animals");
        if (!pets.isEmpty()) {
            Animals a = pets.getFirst();
            out.add(new Option("pet_animal", "Go pet the " + HumanManager.nameOf(a) + " " + Math.round(a.getLocation().distance(loc)) + " blocks away",
                () -> new Life.PetAnimal(a), 0.14 + (animalLover ? 0.3 : 0) + (h.mood == Expression.SAD ? 0.15 : 0)));
        }
        Set<EntityType> fedTypes = new HashSet<>();
        for (Animals a : pets) {
            if (!a.isAdult() || !a.canBreed() || a.isLoveMode() || !fedTypes.add(a.getType())) continue;
            Material bf = Life.breedFood(h, a);
            if (bf == null || h.inv.count(bf) < 2) continue;
            long mates = pets.stream().filter(x -> x.getType() == a.getType() && x.isAdult() && x.canBreed() && !x.isLoveMode()).count();
            if (mates < 2) continue;
            String kind = a.getType().name().toLowerCase(Locale.ROOT);
            out.add(new Option("feed_" + kind, "Feed " + Items.nice(bf) + " to the " + kind + "s so they have babies", () -> new Life.FeedAnimals(a.getType()), 0.3 + (animalLover ? 0.2 : 0)));
        }
        Block chest = nearestChest(h, m);
        if (chest != null)
            out.add(new Option("look_chest", "Have a look inside the " + Items.nice(chest.getType()) + " " + Math.round(chest.getLocation().distance(loc)) + " blocks away (just looking, you never take without asking)",
                () -> new Life.InspectChest(chest), 0.12 + (h.hunger < 12 && h.inv.countWhere(Items::isFood) == 0 ? 0.3 : 0)));
        if (!s.flowers.isEmpty())
            out.add(new Option("pick_flowers", "Pick some of the flowers and grass around you", () -> new Life.PickPlants(new ArrayList<>(s.flowers)),
                0.1 + (p.quirk().contains("romantic") ? 0.2 : 0) + (h.mood == Expression.HAPPY ? 0.08 : 0)));
        if (!s.crops.isEmpty())
            out.add(new Option("farm", "Harvest the " + s.crops.size() + " ripe crops on the farm nearby and replant them", () -> new Life.FarmCrops(new ArrayList<>(s.crops)), 0.25 + p.diligence() * 0.1));
        if (!players.isEmpty() && players.getFirst().getLocation().distance(loc) < 14 && (h.mood == Expression.HAPPY || p.chattiness() > 0.6))
            out.add(new Option("dance", "Do a silly little dance for " + players.getFirst().getName(), Life.Dance::new, 0.08 + (h.mood == Expression.HAPPY ? 0.12 : 0)));
        boolean sunsetTime = t > 11500 && t < 13000;
        out.add(sunsetTime
            ? new Option("sunset", "Sit back and watch the sunset", () -> new Life.Ponder("watching the sunset", 20 * 25, true), 0.35, false,
                "You are watching the sunset in your Minecraft world. Say something about it.")
            : new Option("ponder", "Sit back for a moment and think about life", () -> new Life.Ponder("thinking about life", 20 * 18, false), 0.08 + (1 - p.diligence()) * 0.12, false,
                "You are taking a moment to philosophize about life in this blocky world. Share ONE deep, weird or funny thought."));
        for (Human o : others) {
            Long last = h.visited.get(o.id);
            if (last != null && m.now() - last < 20 * 60 * 5) continue;
            out.add(new Option("visit_" + o.name.toLowerCase(Locale.ROOT), "Go have a little chat with " + o.name + " (another human like you)",
                () -> { h.visited.put(o.id, m.now()); o.visited.put(h.id, m.now()); return new Life.Visit(o); }, 0.1 + p.friendliness() * 0.15));
            break;
        }

        // ---------- bigger plans (the planner AI works out the steps)
        if (h.goal != null && !h.goal.done && !(h.task instanceof dev.timur.humans.plan.PlanTask)) {
            var gl = h.goal;
            out.add(new Option("resume_goal", "Get back to your goal: " + gl.title + (gl.from != null ? " (" + gl.from + " asked you)" : ""),
                () -> new dev.timur.humans.plan.PlanTask(gl), gl.from != null ? 1.1 : 0.6));
        } else if (h.goal == null && !h.isChild() && m.now() - h.lastSelfPlanTick > 20 * 60 * 4 && HumansPlugin.get().text().ready()) {
            String pr = h.project == null ? "something useful or fun you'd enjoy" : projectDesc(h.project);
            out.add(new Option("make_plan", "Think up a bigger plan of your own (" + pr + ") and work through it step by step",
                () -> {
                    h.lastSelfPlanTick = m.now();
                    h.goal = new dev.timur.humans.plan.Goal("Pick a concrete, achievable goal for the next few minutes that fits your project (" + pr
                        + ") and your situation, then do it. Ideas: a better house, a farm, an animal pen, tools or armour, a village with the others, mining iron, a decoration.", null, null, "", m.now());
                    return new dev.timur.humans.plan.PlanTask(h.goal);
                }, 0.32 + p.diligence() * 0.15 + (h.project != null ? 0.08 : 0)));
        }
        if (h.underground && !(h.task instanceof dev.timur.humans.tasks.Work.StripMine) && !(h.task instanceof dev.timur.humans.plan.PlanTask))
            out.add(new Option("go_outside", "You're underground in a cave: head back outside", dev.timur.humans.tasks.Work.GoOutside::new, 0.7, true));
        if (h.inv.countWhere(x -> x.name().endsWith("_BED")) > 0 && !dev.timur.humans.tasks.Work.bedOk(h))
            out.add(new Option("place_bed", "Set up the bed you're carrying (in your house if you have one)", dev.timur.humans.tasks.Work.PlaceBed::new, 0.45));
        if (!h.isChild()) for (Human o : others) {
            if (dev.timur.humans.tasks.Family.cantHaveBaby(h, o, m) != null) continue;
            out.add(new Option("baby_" + o.name.toLowerCase(Locale.ROOT), "Start a family: have a baby with " + o.name + " (you're both fed and have " +
                (dev.timur.humans.tasks.Family.foodItems(h) + dev.timur.humans.tasks.Family.foodItems(o)) + " food items to share)",
                () -> new dev.timur.humans.tasks.Family.HaveBaby(o), 0.12 + p.friendliness() * 0.12));
            break;
        }
        if (h.isChild() && !h.parents.isEmpty()) {
            Human parent = m.byName(h.parents.get(m.rnd().nextInt(h.parents.size())));
            if (parent != null && parent.valid() && parent.loc().getWorld() == w && parent.loc().distance(loc) < 48)
                out.add(new Option("visit_parent", "Go hang out with your parent " + parent.name, () -> new Life.Visit(parent), 0.35));
        }

        // ---------- projects
        if (h.project == null || m.now() - h.projectSince > 20 * 60 * 20) {
            List<String[]> pool = new ArrayList<>(Arrays.asList(PROJECTS));
            Collections.shuffle(pool, m.rnd());
            int added = 0;
            for (String[] pr : pool) {
                if (pr[0].equals(h.project) || added >= 3) continue;
                added++;
                String id = pr[0], desc = pr[1];
                out.add(new Option("project_" + id, "Start a new personal project: " + desc,
                    () -> { h.project = id; h.projectSince = m.now(); h.remember("I started a new project: " + desc); return new Tasks.Idle(40, "planning a new project"); },
                    h.project == null ? 0.3 : 0.15));
            }
        }

        // ---------- filler
        out.add(new Option("wander", "Go for a relaxed walk around the area", Tasks.Wander::new, 0.16));
        out.add(new Option("rest", players.isEmpty() ? "Take a short break and look around" : "Take a short break and hang out with the people nearby",
            () -> new Tasks.Idle(20 * 10, "taking a break"), 0.08 + (players.isEmpty() ? 0 : p.chattiness() * 0.1) + (1 - h.energy / 20) * 0.6));

        // children play, they don't work
        if (h.isChild()) out.removeIf(o -> Set.of("mine", "chop", "build", "make", "craft", "fight", "hunt", "explore", "project", "farm", "smelt", "feed", "baby", "gift", "help").contains(o.family()));
        // trapped: only things that make sense from inside a hole
        if (h.trapped) out.removeIf(o -> !Set.of("escape", "eat", "rest", "fight", "flee", "extinguish").contains(o.family()) && !o.id().equals("keep") && !o.id().equals("nap"));
        out.removeIf(o -> { Long f = h.failedOptions.get(o.id()); return f != null && m.now() - f < 20 * 45 && !o.id().equals("keep"); });

        // project focus + variety
        List<String> recent = new ArrayList<>(h.recentActs);
        for (int i = 0; i < out.size(); i++) {
            Option o = out.get(i);
            double bonus = projectBonus(h, o.id());
            int reps = 0;
            if (!NO_VARIETY.contains(o.family())) for (int k = 0; k < Math.min(4, recent.size()); k++) if (recent.get(k).equals(o.family())) reps++;
            if (bonus == 0 && reps == 0) continue;
            String note = (bonus > 0 ? " [fits your project]" : "") + (reps > 0 ? " [you've done this " + reps + "x recently - maybe do something else]" : "");
            out.set(i, o.withScore(o.score() + bonus - reps * 0.13, note));
        }
        out.sort(Comparator.comparingDouble(Option::score).reversed());
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    /** Closest chest/barrel within 16 blocks that we haven't looked into lately. */
    static Block nearestChest(Human h, HumanManager m) {
        Location loc = h.loc(); World w = loc.getWorld();
        Block best = null; double bd = 16 * 16;
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            if (!w.isChunkLoaded(x, z)) continue;
            for (org.bukkit.block.BlockState st : w.getChunkAt(x, z).getTileEntities()) {
                if (!(st instanceof org.bukkit.block.Chest) && !(st instanceof org.bukkit.block.Barrel)) continue;
                Block b = st.getBlock();
                double d = b.getLocation().distanceSquared(loc);
                if (d >= bd || Math.abs(b.getY() - loc.getBlockY()) > 6 || h.isBad(b, m.now())) continue;
                Long seen = h.seenChests.get(Human.blockKey(b));
                if (seen != null && m.now() - seen < 20 * 60 * 10) continue;
                bd = d; best = b;
            }
        }
        return best;
    }

    private static String weaponNote(Human h) {
        Material wpn = Items.weaponItem(h.inv);
        return wpn == Material.AIR ? " (you have no weapon)" : " (with your " + Items.nice(wpn) + ")";
    }

    private static Material giftFor(Human h) {
        Material flower = h.inv.any(Tag.SMALL_FLOWERS::isTagged).orElse(null);
        if (flower != null && ("garden".equals(h.project) || h.inv.count(flower) >= 3)) return flower;
        for (Material m : new Material[]{Material.DIAMOND, Material.IRON_INGOT, Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.BREAD, Material.COOKED_CHICKEN, Material.APPLE, Material.COAL})
            if (h.inv.count(m) >= (m == Material.DIAMOND ? 1 : 3)) return m;
        if (h.inv.countWhere(Tag.LOGS::isTagged) >= 16) return h.inv.any(Tag.LOGS::isTagged).orElse(null);
        return null;
    }

    private static double craftValue(Human h, Items.Recipe r) {
        Inventory inv = h.inv;
        Items.Tier pick = Items.pickaxeTier(inv);
        boolean noTools = pick == Items.Tier.NONE;
        return switch (r.id()) {
            case "planks" -> inv.countWhere(Tag.PLANKS::isTagged) < 12 || !h.hutBuilt && inv.countWhere(Items::isBuildingBlock) < 60 ? (noTools ? 0.6 : 0.35) : 0;
            case "sticks" -> inv.count(Material.STICK) < 4 ? 0.4 : 0;
            case "crafting_table" -> inv.has(Material.CRAFTING_TABLE, 1) ? 0 : 0.65;
            case "wooden_pickaxe" -> noTools ? 0.75 : 0;
            case "wooden_axe" -> Items.best(inv, "_AXE").isEmpty() ? 0.35 : 0;
            case "wooden_sword" -> Items.best(inv, "_SWORD").isEmpty() ? 0.4 : 0;
            case "stone_pickaxe" -> pick.ordinal() < Items.Tier.STONE.ordinal() ? 0.75 : 0;
            case "stone_axe" -> Items.best(inv, "_AXE").map(Items::tierOf).orElse(Items.Tier.NONE).ordinal() < 2 ? 0.3 : 0;
            case "stone_sword" -> Items.best(inv, "_SWORD").map(Items::tierOf).orElse(Items.Tier.NONE).ordinal() < 2 ? 0.45 : 0;
            case "furnace" -> inv.has(Material.FURNACE, 1) ? 0 : 0.5;
            case "iron_pickaxe" -> pick.ordinal() < Items.Tier.IRON.ordinal() ? 0.8 : 0;
            case "iron_sword" -> Items.best(inv, "_SWORD").map(Items::tierOf).orElse(Items.Tier.NONE).ordinal() < 3 ? 0.6 : 0;
            case "iron_axe" -> 0.1;
            case "shield" -> inv.has(Material.SHIELD, 1) ? 0 : 0.4;
            case "door" -> !h.hutBuilt && !inv.has(Material.OAK_DOOR, 1) && inv.countWhere(Items::isBuildingBlock) > 40 ? 0.35 : 0;
            case "torches" -> inv.count(Material.TORCH) < 8 ? 0.3 : 0;
            case "bed" -> inv.has(Material.WHITE_BED, 1) ? 0 : 0.3;
            case "bread" -> 0.5;
            case "diamond_sword", "diamond_pickaxe" -> 0.9;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- world scan

    public static Scan scan(Human h, HumanManager m) {
        Scan s = new Scan();
        Location loc = h.loc();
        World w = loc.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        int r = 18;
        var zones = HumansPlugin.get().zones();
        boolean colProtected = false;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                double hd2 = dx * dx + dz * dz;
                if (hd2 > r * r) continue;
                for (int dy = -6; dy <= 10; dy++) {
                    Block b = w.getBlockAt(x, by + dy, z);
                    Material t = b.getType();
                    if (t.isAir()) continue;
                    if ((dy & 3) == 0 && (dx & 3) == 0 && (dz & 3) == 0) colProtected = zones.isProtected(b, h);
                    if (colProtected && !t.name().endsWith("_ORE")) continue; // players' base: hands off
                    double d = Math.sqrt(hd2 + dy * dy);
                    if (Life.PickPlants.isPickable(t)) { if (d <= 12 && s.flowers.size() < 8 && !h.isBad(b, m.now())) s.flowers.add(b); continue; }
                    if (Life.matureCrop(b)) { if (d <= 16 && s.crops.size() < 24) s.crops.add(b); continue; }
                    if (Tag.LOGS.isTagged(t)) {
                        Material below = b.getRelative(BlockFace.DOWN).getType();
                        if (d < s.treeD && (Tag.DIRT.isTagged(below) || below == Material.GRASS_BLOCK || below == Material.PODZOL || below == Material.MYCELIUM)
                            && !h.isBad(b, m.now()) && naturalTree(b)) { s.tree = b; s.treeD = d; }
                    } else if (t.name().endsWith("_ORE")) {
                        if (d <= 14 && exposed(b) && !h.isBad(b, m.now())) {
                            String ore = t.name().replace("DEEPSLATE_", "").toLowerCase(Locale.ROOT).replace('_', ' ');
                            if (d < s.oreD.getOrDefault(ore, 1e9)) { s.ores.put(ore, b); s.oreD.put(ore, d); }
                        }
                    } else if ((t == Material.STONE || t == Material.DEEPSLATE || t == Material.ANDESITE || t == Material.DIORITE || t == Material.GRANITE) && d <= 12 && d < s.stoneD && dy >= -2 && exposed(b) && !h.isBad(b, m.now())) {
                        s.stone = b; s.stoneD = d;
                    }
                }
            }
        return s;
    }

    static boolean exposed(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.getType().isAir()) return true;
        }
        return false;
    }

    /** A log that's part of a natural tree (has natural, non-player-placed leaves near the top). */
    static boolean naturalTree(Block base) {
        Block top = base;
        for (int i = 0; i < 16 && Tag.LOGS.isTagged(top.getRelative(BlockFace.UP).getType()); i++) top = top.getRelative(BlockFace.UP);
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
            if (top.getRelative(dx, dy, dz).getBlockData() instanceof Leaves l && !l.isPersistent()) return true;
        return false;
    }

    public static List<Block> treeLogs(Block base) {
        List<Block> out = new ArrayList<>(); Deque<Block> q = new ArrayDeque<>(); Set<Block> seen = new HashSet<>();
        q.add(base); seen.add(base);
        while (!q.isEmpty() && out.size() < 40) {
            Block b = q.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (Tag.LOGS.isTagged(n.getType()) && Math.abs(n.getX() - base.getX()) <= 5 && Math.abs(n.getZ() - base.getZ()) <= 5 && seen.add(n)) q.add(n);
            }
        }
        return out;
    }

    public static List<Block> vein(Block start, int max) {
        List<Block> out = new ArrayList<>(); Deque<Block> q = new ArrayDeque<>(); Set<Block> seen = new HashSet<>();
        Material t = start.getType();
        q.add(start); seen.add(start);
        while (!q.isEmpty() && out.size() < max) {
            Block b = q.poll();
            if (nearLiquid(b)) continue;
            out.add(b);
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f);
                if (n.getType() == t && seen.add(n) && (t.name().endsWith("_ORE") || exposed(n))) q.add(n);
            }
        }
        return out;
    }

    private static boolean nearLiquid(Block b) {
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Material n = b.getRelative(f).getType();
            if (n == Material.LAVA || n == Material.WATER) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- player requests

    /** What a request turns into: an option to start, or a reason it can't be done. */
    public record Request(Option option, String cant) {}

    public static Request request(Human h, HumanManager m, Player req, String intent) {
        String who = req.getName();
        Location loc = h.loc();
        Supplier<Task> make = null; String what = null, cant = null;
        switch (intent) {
            case "request_follow" -> { make = () -> new Tasks.Follow(req, 20 * 150); what = "follow " + who; }
            case "request_stop" -> { make = () -> new Tasks.Idle(20 * 40, "waiting, as " + who + " asked"); what = "stop and wait"; }
            case "request_come" -> { make = () -> new Tasks.Greet(req, false, "coming over to " + who); what = "come over"; }
            case "request_go_home" -> { if (h.home != null) { make = () -> new Tasks.Goto(h.home, 2, false, "going home as " + who + " asked"); what = "go home"; } else cant = "I don't even have a home yet!"; }
            case "request_give" -> {
                Material g = giftFor(h);
                if (g == null) g = Items.bestFood(h.inv).orElse(null);
                if (g == null) g = h.inv.any(x -> true).orElse(null);
                if (g != null) { final Material gm = g; int n = Math.max(1, Math.min(h.inv.count(g) / 2, 8)); make = () -> new Tasks.Give(req, gm, n); what = "give " + who + " " + n + " " + Items.nice(g); }
                else cant = "I've got nothing to give, my pockets are empty!";
            }
            case "request_fight" -> {
                Monster foe = null; double fd = 1e9;
                for (Entity e : h.entity.getNearbyEntities(24, 10, 24)) if (e instanceof Monster mo && !mo.isDead() && e.getLocation().distanceSquared(loc) < fd) { foe = mo; fd = e.getLocation().distanceSquared(loc); }
                if (foe != null) { Monster f = foe; make = () -> new Tasks.Attack(f, false); what = "fight the " + HumanManager.nameOf(f); }
                else cant = "What monster? I don't see any.";
            }
            case "request_wood" -> {
                Scan s = scan(h, m);
                if (s.tree != null) { Block tb = s.tree; make = () -> new Tasks.Harvest(treeLogs(tb), "chopping a tree for " + who, "logs"); what = "chop a tree"; }
                else { make = () -> new Tasks.Explore("trees"); what = "go find trees"; }
            }
            case "request_mine" -> {
                Scan s = scan(h, m);
                Block b = s.ores.values().stream().findFirst().orElse(s.stone);
                if (Items.pickaxeTier(h.inv) == Items.Tier.NONE) cant = "I don't have a pickaxe!";
                else if (b != null && Items.pickaxeTier(h.inv).ordinal() >= Items.requiredTier(b.getType()).ordinal()) {
                    make = () -> new Tasks.Harvest(vein(b, 8), "mining for " + who, Items.nice(b.getType())); what = "go mining";
                } else { make = () -> new Tasks.Explore("stone"); what = "go look for stone"; }
            }
            case "request_build" -> {
                if (h.hutBuilt) cant = "I already have a house!";
                else if (h.inv.countWhere(Items::isBuildingBlock) < 10) cant = "I need more wood first.";
                else { make = Tasks.BuildHut::new; what = "work on the house"; }
            }
            case "request_escape" -> {
                if (h.trapped || dev.timur.humans.Awareness.trapped(h)) { h.trapped = true; make = Life.Escape::new; what = "dig out of the hole"; }
                else cant = dev.timur.humans.brain.Lines.pick(dev.timur.humans.brain.Lines.NOT_STUCK, h, who, null);
            }
            case "request_extinguish" -> {
                List<Block> f = dev.timur.humans.Awareness.fires(loc, 14, 3, 8);
                if (!f.isEmpty()) { make = () -> new Life.Extinguish(loc); what = "put out the fire"; }
                else if (h.hutCenter != null && h.hutCenter.getWorld() == loc.getWorld() && dev.timur.humans.Awareness.homeOnFire(h)) { Location c = h.hutCenter; make = () -> new Life.Extinguish(c); what = "put out the fire at home"; }
                else cant = "Fire? I don't see any fire.";
            }
            case "request_dance" -> { make = Life.Dance::new; what = "dance"; }
            case "request_sleep" -> {
                long t = loc.getWorld().getTime();
                boolean night = t > 12600 && t < 23400;
                make = night ? Tasks.Sleep::new : () -> new Tasks.Sleep(20 * 20); what = night ? "go to bed" : "take a nap";
            }
            case "request_eat" -> { if (Items.bestFood(h.inv).isPresent()) { make = Tasks.Eat::new; what = "eat something"; } else cant = "I don't have any food..."; }
            case "request_feed_animals" -> {
                for (Entity e : h.entity.getNearbyEntities(16, 6, 16))
                    if (e instanceof Animals a && a.isAdult() && Life.breedFood(h, a) != null) { EntityType ty = a.getType(); make = () -> new Life.FeedAnimals(ty); what = "feed the animals"; break; }
                if (make == null) cant = "I don't have anything they'd eat.";
            }
            case "request_farm" -> {
                Scan s = scan(h, m);
                if (!s.crops.isEmpty()) { List<Block> c = new ArrayList<>(s.crops); make = () -> new Life.FarmCrops(c); what = "harvest the crops"; }
                else cant = "Nothing's ripe around here.";
            }
            case "request_explore" -> { make = () -> new Tasks.Explore("something interesting"); what = "go exploring"; }
            case "request_go_outside" -> {
                if (dev.timur.humans.tasks.Work.GoOutside.outsideNow(h)) cant = "I'm already outside!";
                else { make = dev.timur.humans.tasks.Work.GoOutside::new; what = "go outside"; }
            }
            case "request_place_bed" -> {
                if (h.inv.countWhere(x -> x.name().endsWith("_BED")) == 0) cant = "I don't have a bed - give me one?";
                else { make = dev.timur.humans.tasks.Work.PlaceBed::new; what = "set up the bed"; }
            }
            case "request_baby" -> {
                Human best = null; String why = "there's nobody here to start a family with";
                for (Human o : m.all()) {
                    if (o == h || !o.valid()) continue;
                    String w = dev.timur.humans.tasks.Family.cantHaveBaby(h, o, m);
                    if (w == null) { best = o; break; } else if (o.loc().getWorld() == loc.getWorld() && o.loc().distance(loc) < 32) why = w;
                }
                if (best != null) { Human b = best; make = () -> new dev.timur.humans.tasks.Family.HaveBaby(b); what = "start a family with " + b.name; }
                else cant = "Hmm, not now - " + why + ".";
            }
            default -> cant = null;
        }
        if (make == null) return new Request(null, cant);
        return new Request(new Option("do_request", "Do what " + who + " asked: " + what, make, 1.0, false), null);
    }
}
