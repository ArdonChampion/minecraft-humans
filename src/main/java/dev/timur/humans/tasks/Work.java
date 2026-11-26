package dev.timur.humans.tasks;

import dev.timur.humans.*;
import dev.timur.humans.brain.OptionGenerator;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

import java.util.*;

/** Skill tasks used by the planner: counted gathering, strip mining, caves, beds, positioning. */
public final class Work {
    private Work() {}

    static boolean prot(Human h, Block b) { return HumansPlugin.get().zones().isProtected(b, h); }

    // ================================================================ counted gathering

    public static final class ChopCount extends Task {
        private final int want; private int start = -1, misses, got; private Tasks.Harvest cur; private Tasks.Explore exp;
        public ChopCount(int want) { this.want = Math.max(1, Math.min(want, 128)); }
        private int logs(Human h) { return h.inv.countWhere(Tag.LOGS::isTagged); }
        public String label() { return "chopping trees (" + got + "/" + want + " logs)"; }
        public int maxTicks() { return 20 * 60 * 8; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            if (start < 0) start = logs(h);
            got = logs(h) - start;
            if (got >= want) { outcome = "chopped " + got + " logs"; return Status.SUCCESS; }
            if (exp != null) { if (exp.tick(h, m) != Status.RUNNING) { exp.stop(h); exp = null; } return Status.RUNNING; }
            if (cur != null) {
                Status s = cur.tick(h, m);
                if (s == Status.RUNNING) return s;
                cur.stop(h); cur = null;
                if (s == Status.FAIL) misses++;
                return Status.RUNNING;
            }
            OptionGenerator.Scan s = OptionGenerator.scan(h, m);
            if (s.tree == null) {
                if (++misses > 4) { outcome = "only found " + got + " of " + want + " logs - no more trees around"; return got > 0 ? Status.SUCCESS : Status.FAIL; }
                exp = new Tasks.Explore("trees");
                return Status.RUNNING;
            }
            cur = new Tasks.Harvest(OptionGenerator.treeLogs(s.tree), "chopping a tree (" + got + "/" + want + " logs)", "logs");
            return Status.RUNNING;
        }
        @Override public void stop(Human h) { super.stop(h); if (cur != null) cur.stop(h); if (exp != null) exp.stop(h); }
    }

    /** Which block types a mining request means, and what item they drop. */
    public static Set<Material> blockTypes(String what) {
        String w = what.toLowerCase(Locale.ROOT).replace(' ', '_').replaceAll("s$", "");
        Set<Material> out = EnumSet.noneOf(Material.class);
        switch (w) {
            case "stone", "cobblestone", "cobble" -> { out.add(Material.STONE); out.add(Material.COBBLESTONE); out.add(Material.ANDESITE); out.add(Material.DIORITE); out.add(Material.GRANITE); out.add(Material.DEEPSLATE); out.add(Material.TUFF); }
            case "dirt" -> { out.add(Material.DIRT); out.add(Material.GRASS_BLOCK); out.add(Material.COARSE_DIRT); }
            case "sand" -> { out.add(Material.SAND); out.add(Material.RED_SAND); }
            case "gravel" -> out.add(Material.GRAVEL);
            case "clay" -> out.add(Material.CLAY);
            case "diamond", "diamond_ore" -> { out.add(Material.DIAMOND_ORE); out.add(Material.DEEPSLATE_DIAMOND_ORE); }
            default -> {
                String base = w.endsWith("_ore") ? w : w + "_ore";
                Material a = Material.matchMaterial(base), b = Material.matchMaterial("deepslate_" + base);
                if (a != null) out.add(a); if (b != null) out.add(b);
                if (out.isEmpty()) { Material x = Material.matchMaterial(w); if (x != null && x.isBlock()) out.add(x); }
            }
        }
        return out;
    }

    public static final class MineCount extends Task {
        private final Set<Material> types; private final String what; private final int want; private int broken, misses; private Tasks.Harvest cur;
        public MineCount(String what, int want) { this.what = what; this.types = blockTypes(what); this.want = Math.max(1, Math.min(want, 64)); }
        public String label() { return "mining " + what.replace('_', ' ') + " (" + broken + "/" + want + ")"; }
        public int maxTicks() { return 20 * 60 * 6; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            if (types.isEmpty()) { outcome = "doesn't know what '" + what + "' is"; return Status.FAIL; }
            if (broken >= want) { outcome = "mined " + broken + " " + what; return Status.SUCCESS; }
            if (cur != null) {
                Status s = cur.tick(h, m);
                if (s == Status.RUNNING) return s;
                cur.stop(h);
                String o = cur.outcome();
                if (o.startsWith("got ")) try { broken += Integer.parseInt(o.split(" ")[1]); } catch (Exception ignored) {}
                cur = null;
                if (s == Status.FAIL) misses++;
                return Status.RUNNING;
            }
            Material sample = types.iterator().next();
            Items.Tier need = Items.requiredTier(sample);
            if (need != Items.Tier.NONE && Items.pickaxeTier(h.inv).ordinal() < need.ordinal()) {
                outcome = "needs a " + need.name().toLowerCase(Locale.ROOT) + " pickaxe (or better) to mine " + what; return Status.FAIL;
            }
            Block b = find(h, m);
            if (b == null || misses > 4) {
                outcome = broken > 0 ? "mined " + broken + " " + what + ", no more visible nearby" : "no " + what + " visible nearby (try explore or strip_mine)";
                return broken > 0 ? Status.SUCCESS : Status.FAIL;
            }
            cur = new Tasks.Harvest(OptionGenerator.vein(b, Math.min(want - broken + 2, 12)), "mining " + what.replace('_', ' ') + " (" + broken + "/" + want + ")", what);
            return Status.RUNNING;
        }
        private Block find(Human h, HumanManager m) {
            Location l = h.loc(); World w = l.getWorld(); Location eye = Act.realEye(h);
            Block best = null; double bd = 24 * 24;
            for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
                if (!w.isChunkLoaded((l.getBlockX() + dx) >> 4, (l.getBlockZ() + dz) >> 4)) continue;
                for (int dy = -10; dy <= 10; dy++) {
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d >= bd) continue;
                    Block b = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
                    if (!types.contains(b.getType()) || h.isBad(b, m.now()) || !exposed(b) || prot(h, b) || !Act.visibleFrom(eye, b)) continue;
                    bd = d; best = b;
                }
            }
            return best;
        }
        static boolean exposed(Block b) {
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN})
                if (b.getRelative(f).getType().isAir()) return true;
            return false;
        }
        @Override public void stop(Human h) { super.stop(h); if (cur != null) cur.stop(h); }
    }

    public static final class HuntCount extends Task {
        private final EntityType type; private final int want; private int kills; private Tasks.Attack cur;
        public HuntCount(EntityType type, int want) { this.type = type; this.want = Math.max(1, Math.min(want, 10)); }
        public String label() { return "hunting " + type.name().toLowerCase(Locale.ROOT) + "s (" + kills + "/" + want + ")"; }
        public int maxTicks() { return 20 * 60 * 4; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            if (kills >= want) { outcome = "hunted " + kills + " " + type.name().toLowerCase(Locale.ROOT) + "s"; return Status.SUCCESS; }
            if (cur != null) {
                Status s = cur.tick(h, m);
                if (s == Status.RUNNING) return s;
                cur.stop(h); if (s == Status.SUCCESS) kills++; cur = null;
                return Status.RUNNING;
            }
            LivingEntity t = null; double bd = 1e9;
            for (Entity e : h.entity.getNearbyEntities(dev.timur.humans.plan.Planner.SEE, dev.timur.humans.plan.Planner.SEE_Y, dev.timur.humans.plan.Planner.SEE))
                if (e.getType() == type && e instanceof LivingEntity le && !le.isDead() && e.getLocation().distanceSquared(h.loc()) < bd && !prot(h, e.getLocation().getBlock())) { bd = e.getLocation().distanceSquared(h.loc()); t = le; }
            if (t == null) { outcome = kills > 0 ? "hunted " + kills + ", no more around" : "no " + type.name().toLowerCase(Locale.ROOT) + "s around (outside players' bases)"; return kills > 0 ? Status.SUCCESS : Status.FAIL; }
            cur = new Tasks.Attack(t, true);
            return Status.RUNNING;
        }
        @Override public void stop(Human h) { super.stop(h); if (cur != null) cur.stop(h); }
    }

    // ================================================================ strip mining

    /** Staircase down to a height, then a 1x2 tunnel, mining every ore that shows up in the walls. */
    public static final class StripMine extends Task {
        private final int targetY, length; private BlockFace dir; private Tasks.Breaker breaker; private int[] stepTo; private int moveTicks, steps, turns, startOres = -1;
        private boolean tunnel; private final Deque<Block> ores = new ArrayDeque<>();
        private static final BlockFace[] DIRS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        public StripMine(int targetY, int length) { this.targetY = targetY; this.length = Math.max(4, Math.min(length, 80)); }
        public String label() { return tunnel ? "strip-mining at y=" + targetY + " (" + steps + "/" + length + ")" : "digging down to y=" + targetY; }
        public int maxTicks() { return 20 * 60 * 12; }
        @Override public boolean isWork() { return true; }
        private int oreCount(Human h) { return h.inv.countWhere(x -> x == Material.RAW_IRON || x == Material.DIAMOND || x == Material.COAL || x == Material.RAW_GOLD || x == Material.RAW_COPPER || x == Material.REDSTONE || x == Material.LAPIS_LAZULI || x == Material.EMERALD); }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            if (startOres < 0) startOres = oreCount(h);
            if (Items.pickaxeTier(h.inv) == Items.Tier.NONE) { outcome = "needs a pickaxe to strip-mine"; return Status.FAIL; }
            if (turns > 8) { outcome = "hit lava, water or bedrock too often and stopped (at y=" + h.loc().getBlockY() + ")"; return result(h); }
            if (breaker != null) {
                if (breaker.target.getType().isAir() || breaker.tick(h, m)) breaker = null;
                return Status.RUNNING;
            }
            Location loc = h.loc();
            if (stepTo != null) {
                double tx = stepTo[0] + 0.5, tz = stepTo[2] + 0.5, dx = tx - loc.getX(), dz = tz - loc.getZ(), d = Math.hypot(dx, dz);
                if (d < 0.3 && Math.abs(loc.getY() - stepTo[1]) < 0.6 || ++moveTicks > 50) { stepTo = null; return Status.RUNNING; }
                double vy = h.entity.getVelocity().getY();
                if (stepTo[1] > loc.getY() + 0.4 && h.entity.isOnGround()) vy = 0.42;
                h.entity.setVelocity(new Vector(dx / Math.max(d, 0.01) * Math.min(0.15, d + 0.05), vy, dz / Math.max(d, 0.01) * Math.min(0.15, d + 0.05)));
                return Status.RUNNING;
            }
            Block feet = loc.getBlock();
            // ores showing in the walls first
            if (age % 10 == 0) for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++) {
                Block b = feet.getRelative(dx, dy, dz);
                if (b.getType().name().endsWith("_ORE") && !ores.contains(b) && !prot(h, b)
                    && Items.pickaxeTier(h.inv).ordinal() >= Items.requiredTier(b.getType()).ordinal()) ores.add(b);
            }
            while (!ores.isEmpty()) {
                Block b = ores.poll();
                if (b.getType().name().endsWith("_ORE") && Act.inReach(h, b) && Act.canSee(h, b) && !nearLiquid(b)) { breaker = new Tasks.Breaker(); breaker.target = b; return Status.RUNNING; }
            }
            if (!tunnel && feet.getY() <= targetY) tunnel = true;
            if (tunnel && steps >= length) { outcome = "strip-mined " + steps + " blocks at y=" + feet.getY(); return result(h); }
            if (dir == null) dir = DIRS[m.rnd().nextInt(4)];
            Block f1 = feet.getRelative(dir);
            Block[] clear = tunnel ? new Block[]{f1.getRelative(BlockFace.UP), f1}
                : new Block[]{f1.getRelative(BlockFace.UP), f1, f1.getRelative(BlockFace.DOWN)};
            Block newFeet = tunnel ? f1 : f1.getRelative(BlockFace.DOWN);
            for (Block b : clear) if (nearLiquid(b) || b.getType() == Material.LAVA || b.getType() == Material.WATER) { turn(); return Status.RUNNING; }
            for (Block b : clear) {
                if (Navigator.passable(b)) continue;
                if (!Act.breakable(b) || prot(h, b)) { turn(); return Status.RUNNING; }
                breaker = new Tasks.Breaker(); breaker.target = b;
                return Status.RUNNING;
            }
            Block floor = newFeet.getRelative(BlockFace.DOWN);
            if (!floor.getType().isSolid()) {
                if (floor.getType() == Material.LAVA || floor.getRelative(BlockFace.DOWN).getType() == Material.LAVA) { turn(); return Status.RUNNING; }
                Material mat = h.inv.any(x -> Items.isBuildingBlock(x) || x == Material.DIRT).orElse(null);
                if (mat != null && Act.place(h, floor, mat)) h.inv.remove(mat, 1);
                else if (!floor.getRelative(BlockFace.DOWN).getType().isSolid()) { turn(); return Status.RUNNING; }
            }
            if (steps % 8 == 4 && h.inv.has(Material.TORCH, 1) && feet.getType().isAir() && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                feet.setType(Material.TORCH); h.inv.remove(Material.TORCH, 1);
            }
            stepTo = new int[]{newFeet.getX(), newFeet.getY(), newFeet.getZ()}; moveTicks = 0;
            if (tunnel) steps++;
            return Status.RUNNING;
        }
        private Status result(Human h) {
            int got = oreCount(h) - startOres;
            outcome += got > 0 ? ", found " + got + " ore items" : ", found no ores";
            return Status.SUCCESS;
        }
        private void turn() { turns++; dir = DIRS[(Arrays.asList(DIRS).indexOf(dir) + 1 + (turns % 2) * 2) % 4]; }
        private static boolean nearLiquid(Block b) {
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Material t = b.getRelative(f).getType();
                if (t == Material.LAVA || t == Material.WATER) return true;
            }
            return false;
        }
    }

    // ================================================================ breaking specific blocks

    /** Walk up to some blocks and break them like a player: only what you can see, any tool (slow without the right one). */
    public static final class BreakBlocks extends Task {
        private final Deque<Block> todo; private final boolean allowProtected; private Tasks.Breaker breaker; private Block cur; private int broken, skipped, waitSee;
        private final List<String> notes = new ArrayList<>();
        public BreakBlocks(List<Block> blocks, boolean allowProtected) { this.todo = new ArrayDeque<>(blocks); this.allowProtected = allowProtected; }
        public String label() { return "breaking blocks (" + broken + "/" + (broken + skipped + todo.size() + (cur != null ? 1 : 0)) + ")"; }
        public int maxTicks() { return 20 * 60 * 4; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            if (breaker != null) {
                if (breaker.target.getType().isAir()) { breaker = null; cur = null; return Status.RUNNING; }
                if (breaker.tick(h, m)) { broken++; breaker = null; cur = null; }
                return Status.RUNNING;
            }
            if (cur == null || cur.getType().isAir()) {
                cur = null;
                while (!todo.isEmpty() && cur == null) {
                    Block b = todo.poll();
                    if (b.getType().isAir() || b.isLiquid()) continue;
                    if (!Act.breakable(b)) { skipped++; note(b, "can't be broken (too hard, or it's storage/a bed)"); continue; }
                    if (!allowProtected && prot(h, b)) { skipped++; note(b, "is in a player's base"); continue; }
                    cur = b; waitSee = 0;
                }
                if (cur == null) return done(h);
                if (!Act.rightTool(h, cur)) notes.add(cur.getType().name().toLowerCase(Locale.ROOT) + " without the right tool takes long and drops nothing");
            }
            if (Act.inReach(h, cur)) {
                Block ob = Act.obstruction(h, cur);
                if (ob == null) { h.nav.stop(); breaker = new Tasks.Breaker(); breaker.target = cur; return Status.RUNNING; }
                if (++waitSee > 60) { skipped++; note(cur, "can't be seen, " + ob.getType().name().toLowerCase(Locale.ROOT) + " is in the way at " + ob.getX() + " " + ob.getY() + " " + ob.getZ()); cur = null; return Status.RUNNING; }
            }
            if (!h.nav.active() && !h.nav.moveTo(cur.getLocation().add(0.5, 0, 0.5), 1.5, false)) { skipped++; note(cur, "can't walk there"); cur = null; return Status.RUNNING; }
            if (h.nav.tick() == Navigator.Result.FAILED && !Act.inReach(h, cur)) { skipped++; note(cur, "can't walk there"); cur = null; }
            return Status.RUNNING;
        }
        private void note(Block b, String why) { if (notes.size() < 4) notes.add(b.getType().name().toLowerCase(Locale.ROOT) + " at " + b.getX() + " " + b.getY() + " " + b.getZ() + " " + why); }
        private Status done(Human h) {
            h.nav.stop();
            outcome = "broke " + broken + " blocks" + (skipped > 0 ? ", skipped " + skipped : "") + (notes.isEmpty() ? "" : " (" + String.join("; ", new LinkedHashSet<>(notes)) + ")");
            return broken > 0 || skipped == 0 ? Status.SUCCESS : Status.FAIL;
        }
    }

    // ================================================================ adventures

    /**
     * Go along with a player like a real companion: keep up, fight what threatens them or us, pick up loot on the way.
     * Like tamed pets, catches up by teleporting when the player got far away and walking can't keep up.
     */
    public static final class Accompany extends Task {
        private final Player p; private final int ticks; private Tasks.Attack fight; private int farTicks, fights, lostTicks;
        public Accompany(Player p, int minutes) { this.p = p; this.ticks = 20 * 60 * Math.max(1, Math.min(minutes, 30)); }
        public String label() { return fight != null ? "fighting alongside " + p.getName() : "adventuring with " + p.getName(); }
        public int maxTicks() { return ticks + 40; }
        @Override public boolean isWork() { return false; }
        protected Status run(Human h, HumanManager m) {
            if (!p.isOnline()) { outcome = p.getName() + " left the game" + (fights > 0 ? " (we fought " + fights + " monsters together)" : ""); return Status.FAIL; }
            if (age >= ticks) { outcome = "went adventuring with " + p.getName() + " for " + ticks / 1200 + " min" + (fights > 0 ? ", fought " + fights + " monsters" : ""); return Status.SUCCESS; }
            if (fight != null) {
                Status s = fight.tick(h, m);
                if (s == Status.RUNNING && fight.age() < 20 * 25) return s;
                fight.stop(h); if (s == Status.SUCCESS) fights++; fight = null;
            }
            boolean sameWorld = p.getWorld() == h.loc().getWorld();
            double d = sameWorld ? p.getLocation().distance(h.loc()) : 1e9;
            // threats near the player or near us
            if (age % 10 == 0 && sameWorld && h.entity.getHealth() > 7) {
                Monster best = null; double bd = 1e9;
                for (Entity e : p.getNearbyEntities(10, 5, 10)) {
                    if (!(e instanceof Monster mo) || mo.isDead() || e.getLocation().distance(h.loc()) > 16) continue;
                    double md = e.getLocation().distanceSquared(p.getLocation());
                    if (md < bd && h.entity.hasLineOfSight(mo)) { bd = md; best = mo; }
                }
                if (best != null) { fight = new Tasks.Attack(best, false); h.event("fought a " + HumanManager.nameOf(best) + " next to " + p.getName()); return Status.RUNNING; }
            }
            // keep up
            if (d > 40 || !sameWorld) {
                if (++farTicks > 20 * 8 && HumansPlugin.get().getConfig().getBoolean("companion.teleport_to_catch_up", true)) {
                    Location spot = Act.nearestLand(p.getLocation().clone().add(p.getLocation().getDirection().setY(0).multiply(-2.5)), 4);
                    if (spot != null) { h.nav.stop(); h.entity.teleport(spot); farTicks = 0; h.event("caught up with " + p.getName()); }
                    else if (++lostTicks > 20 * 60) { outcome = "lost " + p.getName() + " on the way"; return Status.FAIL; }
                }
            } else farTicks = 0;
            if (d > 4.5 && sameWorld) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(p.getLocation(), 3, d > 9);
                h.nav.tick();
            } else {
                h.nav.stop();
                if (age % 4 == 0) Act.lookAt(h, p.getEyeLocation());
            }
            return Status.RUNNING;
        }
        @Override public void stop(Human h) { super.stop(h); if (fight != null) fight.stop(h); }
    }

    /** Travel a long way in a direction (or towards x,z), leg by leg over the surface. */
    public static final class Travel extends Task {
        private final double yaw0; private final int dist; private final String where; private Location start; private Tasks.Goto go; private int fails, legs;
        private double yaw;
        public Travel(double yaw, int dist, String where) { this.yaw0 = yaw; this.yaw = yaw; this.dist = Math.max(16, Math.min(dist, 600)); this.where = where; }
        public String label() { return "travelling " + where; }
        public int maxTicks() { return 20 * 60 * 10; }
        @Override public boolean isWork() { return true; }
        @Override public String debugInfo() { return "legs=" + legs + " fails=" + fails; }
        protected Status run(Human h, HumanManager m) {
            Location here = h.loc();
            if (start == null) start = here.clone();
            double gone = Math.hypot(here.getX() - start.getX(), here.getZ() - start.getZ());
            if (gone >= dist - 4) { outcome = "travelled " + (int) gone + " blocks " + where + ", now at " + here.getBlockX() + "," + here.getBlockY() + "," + here.getBlockZ(); return Status.SUCCESS; }
            if (go == null) {
                Location spot = null;
                for (int i = 0; i < 8 && spot == null; i++) {
                    double y = yaw0 + (i == 0 ? 0 : (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 0.35); // fan out around the heading
                    spot = Act.surfaceSpot(here.clone().add(Math.cos(y) * 16, 0, Math.sin(y) * 16));
                    if (spot != null && Act.wet(spot.getBlock())) spot = null;
                    if (spot != null) yaw = y;
                }
                if (spot == null) {
                    if (++fails > 6) return stuck(h, gone);
                    // take a step sideways and try again
                    Location side = Act.surfaceSpot(here.clone().add(Math.cos(yaw0 + Math.PI / 2) * 6 * (fails % 2 == 0 ? 1 : -1), 0, Math.sin(yaw0 + Math.PI / 2) * 6 * (fails % 2 == 0 ? 1 : -1)));
                    if (side == null) return stuck(h, gone);
                    go = new Tasks.Goto(side, 1.5, false, label());
                    return Status.RUNNING;
                }
                go = new Tasks.Goto(spot, 2, false, label());
            }
            Status s = go.tick(h, m);
            if (s != Status.RUNNING) {
                go = null;
                if (s == Status.FAIL) { if (++fails > 6) return stuck(h, gone); }
                else legs++;
            }
            return Status.RUNNING;
        }
        private Status stuck(Human h, double gone) {
            Location l = h.loc();
            outcome = "travelled " + (int) gone + " blocks " + where + " then the way was blocked (at " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")";
            return gone > 16 ? Status.SUCCESS : Status.FAIL;
        }
        public static double yawFor(String dir) {
            return switch (dir.toLowerCase(Locale.ROOT).trim()) {
                case "east", "e" -> 0; case "south", "s" -> Math.PI / 2; case "west", "w" -> Math.PI; case "north", "n" -> -Math.PI / 2;
                case "southeast", "se" -> Math.PI / 4; case "southwest", "sw" -> 3 * Math.PI / 4; case "northwest", "nw" -> -3 * Math.PI / 4; case "northeast", "ne" -> -Math.PI / 4;
                default -> Double.NaN;
            };
        }
    }

    /** Wait near someone for an item we asked for (it arrives via a gift or a drop we pick up). */
    public static final class WaitForItem extends Task {
        private final Material mat; private final int want, seconds; private final Entity near; private int start = -1;
        public WaitForItem(Material mat, int want, int seconds, Entity near) { this.mat = mat; this.want = want; this.seconds = seconds; this.near = near; }
        public String label() { return "waiting for " + Items.nice(mat); }
        public int maxTicks() { return seconds * 20 + 40; }
        protected Status run(Human h, HumanManager m) {
            if (start < 0) start = h.inv.count(mat);
            int got = h.inv.count(mat) - start;
            if (got >= want || got > 0 && age > 20 * 8) { outcome = "got " + got + " " + Items.nice(mat); return Status.SUCCESS; }
            if (age >= seconds * 20) { outcome = got > 0 ? "got " + got + " " + Items.nice(mat) : "nobody gave me " + Items.nice(mat); return got > 0 ? Status.SUCCESS : Status.FAIL; }
            // walk over to items lying around (the pickup reflex grabs them), otherwise stay close and look at them
            if (age % 20 == 0) for (Entity e : h.entity.getNearbyEntities(6, 3, 6))
                if (e instanceof Item it && it.getItemStack().getType() == mat) { h.nav.moveTo(it.getLocation(), 0.6, false); break; }
            if (h.nav.active()) { h.nav.tick(); return Status.RUNNING; }
            if (near != null && near.isValid() && near.getWorld() == h.loc().getWorld()) {
                double d = near.getLocation().distance(h.loc());
                if (d > 4 && d < 40) { h.nav.moveTo(near.getLocation(), 3, false); h.nav.tick(); }
                else if (age % 4 == 0) Act.lookAt(h, near.getLocation().add(0, 1.6, 0));
            }
            return Status.RUNNING;
        }
    }

    // ================================================================ caves

    public static final class GoOutside extends Task {
        private boolean started; private Life.Escape dig;
        public String label() { return "heading outside"; }
        public int maxTicks() { return 20 * 150; }
        @Override public boolean isWork() { return true; }
        public static boolean outsideNow(Human h) { return outside(h); }
        static boolean outside(Human h) {
            return !Awareness.isUnderground(h.loc());
        }
        protected Status run(Human h, HumanManager m) {
            if (outside(h)) { h.underground = false; outcome = "got outside"; return Status.SUCCESS; }
            if (dig != null) {
                Status s = dig.tick(h, m);
                if (s == Status.RUNNING) return age % 20 == 0 && outside(h) ? Status.SUCCESS : s;
                dig.stop(h); dig = null;
                if (outside(h)) { outcome = "dug my way outside"; return Status.SUCCESS; }
                outcome = "couldn't get outside"; return Status.FAIL;
            }
            if (!started) {
                started = true;
                int[] sky = Awareness.findSky(h, 1800);
                if (sky != null && h.nav.moveTo(new Location(h.loc().getWorld(), sky[0] + 0.5, sky[1], sky[2] + 0.5), 1.0, false)) return Status.RUNNING;
                Location l = h.loc();
                dig = new Life.Escape(new Location(l.getWorld(), l.getBlockX() + 0.5, l.getWorld().getHighestBlockYAt(l) + 1, l.getBlockZ() + 0.5));
                return Status.RUNNING;
            }
            Navigator.Result r = h.nav.tick();
            if (r == Navigator.Result.FAILED) {
                Location l = h.loc();
                dig = new Life.Escape(new Location(l.getWorld(), l.getBlockX() + 0.5, l.getWorld().getHighestBlockYAt(l) + 1, l.getBlockZ() + 0.5));
            }
            return Status.RUNNING;
        }
        @Override public void stop(Human h) { super.stop(h); if (dig != null) dig.stop(h); }
    }

    // ================================================================ positioning

    public static final class StandAt extends Task {
        private final Location spot; private final Player face; private final int ticks; private int there;
        public StandAt(Location spot, Player face, int seconds) { this.spot = spot; this.face = face; this.ticks = Math.max(1, Math.min(seconds, 120)) * 20; }
        public String label() { return "standing in position"; }
        public int maxTicks() { return ticks + 20 * 40; }
        protected Status run(Human h, HumanManager m) {
            if (Navigator.horizDist(h.loc(), spot) > 0.7) {
                if (!h.nav.active() || age % 30 == 0) if (!h.nav.moveTo(spot, 0.5, false) && age > 40) { outcome = "couldn't get to that spot"; return Status.FAIL; }
                if (h.nav.tick() == Navigator.Result.FAILED && age > 200) { outcome = "couldn't get to that spot"; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (age % 4 == 0 && face != null && face.isOnline()) Act.lookAt(h, face.getEyeLocation());
            if (++there >= ticks) { outcome = "stood in position"; return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    public static final class GiveHuman extends Task {
        private final Human to; private final Material mat; private final int n;
        public GiveHuman(Human to, Material mat, int n) { this.to = to; this.mat = mat; this.n = n; }
        public String label() { return "bringing " + Items.nice(mat) + " to " + to.name; }
        public int maxTicks() { return 20 * 45; }
        protected Status run(Human h, HumanManager m) {
            if (!to.valid()) { outcome = to.name + " isn't around"; return Status.FAIL; }
            if (to.loc().distance(h.loc()) > 2.8) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(to.loc(), 2.2, false);
                if (h.nav.tick() == Navigator.Result.FAILED && age > 80) { outcome = "couldn't reach " + to.name; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            int k = Math.min(n, h.inv.count(mat));
            if (k <= 0) { outcome = "had no " + Items.nice(mat) + " to give"; return Status.FAIL; }
            h.inv.remove(mat, k); to.inv.add(mat, k);
            Act.lookAt(h, to.entity.getEyeLocation()); Act.swing(h);
            to.event(h.name + " gave me " + k + " " + Items.nice(mat));
            h.entity.getWorld().playSound(h.loc(), "entity.item.pickup", 0.5f, 1.2f);
            outcome = "gave " + to.name + " " + k + " " + Items.nice(mat);
            return Status.SUCCESS;
        }
    }

    // ================================================================ beds

    public static final class PlaceBed extends Task {
        private Block foot; private BlockFace face; private boolean walking;
        public String label() { return "setting up a bed"; }
        public int maxTicks() { return 20 * 40; }
        protected Status run(Human h, HumanManager m) {
            Material bedMat = h.inv.any(x -> x.name().endsWith("_BED")).orElse(null);
            if (bedMat == null) { outcome = "has no bed to place"; return Status.FAIL; }
            if (foot == null) {
                List<Location> centers = new ArrayList<>();
                if (h.hutCenter != null) centers.add(h.hutCenter);
                if (h.village != null) centers.add(h.village);
                centers.add(h.loc());
                for (Location c : centers) {
                    if (c.getWorld() != h.loc().getWorld()) continue;
                    outer:
                    for (int r = 0; r <= 3; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -1; dy <= 1; dy++)
                        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                            Block a = c.getBlock().getRelative(dx, dy, dz), b = a.getRelative(f);
                            if (a.getType().isAir() && b.getType().isAir() && a.getRelative(BlockFace.DOWN).getType().isSolid() && b.getRelative(BlockFace.DOWN).getType().isSolid()
                                && !(dx == 0 && dz == 0 && c == h.loc()) && !prot(h, a)) { foot = a; face = f; break outer; }
                        }
                    if (foot != null) break;
                }
                if (foot == null) { outcome = "couldn't find space for a bed"; return Status.FAIL; }
            }
            if (Act.eye(h).distance(foot.getLocation().add(0.5, 0.5, 0.5)) > 4) {
                if (!walking || !h.nav.active()) { walking = true; if (!h.nav.moveTo(foot.getLocation().add(0.5, 0, 0.5), 2.5, false)) { outcome = "couldn't reach the bed spot"; return Status.FAIL; } }
                if (h.nav.tick() == Navigator.Result.FAILED) { outcome = "couldn't reach the bed spot"; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            Block head = foot.getRelative(face);
            if (!foot.getType().isAir() || !head.getType().isAir()) { foot = null; return Status.RUNNING; }
            Bed f = (Bed) bedMat.createBlockData(); f.setPart(Bed.Part.FOOT); f.setFacing(face);
            Bed hd = (Bed) bedMat.createBlockData(); hd.setPart(Bed.Part.HEAD); hd.setFacing(face);
            foot.setBlockData(f, false); head.setBlockData(hd, false);
            Act.lookAt(h, foot.getLocation().add(0.5, 0.3, 0.5)); Act.swing(h);
            foot.getWorld().playSound(foot.getLocation(), "block.wool.place", 1f, 1f);
            h.inv.remove(bedMat, 1);
            h.bed = foot.getLocation();
            h.remember("I set up my own bed");
            outcome = "placed a bed";
            return Status.SUCCESS;
        }
    }

    /** Is the stored bed still there? */
    public static boolean bedOk(Human h) {
        return h.bed != null && h.bed.getWorld() == h.loc().getWorld() && h.bed.getBlock().getType().name().endsWith("_BED");
    }
}
