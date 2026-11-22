package dev.timur.humans.tasks;

import dev.timur.humans.*;
import dev.timur.humans.brain.Lines;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

/** Everyday-life tasks: escaping holes, fire fighting, animals, chests, flowers, farming, dancing, pondering, visiting. */
public final class Life {
    private Life() {}

    // ================================================================ awareness: get out of a hole

    /** Dig a staircase up and out (like a player would), placing a step block when needed. */
    public static final class Escape extends Task {
        private BlockFace dir; private Tasks.Breaker breaker; private int[] stepTo; private int moveTicks, turns;
        private final Location goal; // when set: dig towards it until we can walk there
        private static final BlockFace[] DIRS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        public Escape() { this(null); }
        public Escape(Location goal) { this.goal = goal; }
        public String label() { return goal == null ? "digging out of a hole" : "climbing out of this pit"; }

        /** Only dig through natural ground - never through someone's build. */
        public static boolean diggable(Material t) {
            if (t.getHardness() < 0 || t == Material.WATER || t == Material.LAVA) return false;
            String n = t.name();
            return Tag.MINEABLE_SHOVEL.isTagged(t) || Tag.LEAVES.isTagged(t) || n.endsWith("_ORE") || Tag.BASE_STONE_OVERWORLD.isTagged(t)
                || t == Material.CALCITE || t == Material.DRIPSTONE_BLOCK
                || t == Material.SANDSTONE || t == Material.RED_SANDSTONE || t == Material.TERRACOTTA || n.endsWith("_TERRACOTTA") && !n.contains("GLAZED")
                || t == Material.NETHERRACK || t == Material.MOSS_BLOCK || t == Material.ROOTED_DIRT || t == Material.SHORT_GRASS || t == Material.TALL_GRASS;
        }
        public int maxTicks() { return 20 * 120; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            if (turns > 6) { outcome = "couldn't dig out of the hole"; return Status.FAIL; }
            if (age % 20 == 0 && breaker == null && stepTo == null && (goal == null ? !Awareness.trapped(h) : age % 40 == 0 && Navigator.reachable(h.loc(), goal, 3))) {
                h.trapped = false; h.quickFails = 0; outcome = goal == null ? "got out of the hole" : "climbed out of the pit"; return Status.SUCCESS;
            }
            if (breaker != null) {
                if (breaker.target.getType().isAir() || breaker.tick(h, m)) breaker = null;
                return Status.RUNNING;
            }
            Location loc = h.loc();
            if (stepTo != null) {
                double tx = stepTo[0] + 0.5, tz = stepTo[2] + 0.5, dx = tx - loc.getX(), dz = tz - loc.getZ(), d = Math.hypot(dx, dz);
                if (d < 0.3 && loc.getY() >= stepTo[1] - 0.05 || ++moveTicks > 40) { stepTo = null; return Status.RUNNING; }
                double vy = h.entity.getVelocity().getY();
                if (stepTo[1] > loc.getY() + 0.4 && h.entity.isOnGround()) vy = 0.42;
                h.entity.setVelocity(new Vector(dx / Math.max(d, 0.01) * Math.min(0.15, d + 0.05), vy, dz / Math.max(d, 0.01) * Math.min(0.15, d + 0.05)));
                return Status.RUNNING;
            }
            Block feet = loc.getBlock();
            if (dir == null) dir = pickDir(feet, goal);
            Block f1 = feet.getRelative(dir), up1 = f1.getRelative(BlockFace.UP), up2 = up1.getRelative(BlockFace.UP), head = feet.getRelative(0, 2, 0);
            for (Block b : new Block[]{head, up2, up1}) {
                if (Navigator.passable(b)) continue;
                Material t = b.getType();
                // anything breakable if we must (slow without the right tool); inside a player's base only when really trapped
                if (!Act.breakable(b) || goal != null && HumansPlugin.get().zones().isProtected(b, h)) { turn(); return Status.RUNNING; }
                breaker = new Tasks.Breaker(); breaker.target = b;
                return Status.RUNNING;
            }
            if (!f1.getType().isSolid()) {
                Material mat = h.inv.any(x -> Items.isBuildingBlock(x) || x == Material.DIRT || x == Material.COBBLED_DEEPSLATE).orElse(null);
                if (mat != null && Act.place(h, f1, mat)) { h.inv.remove(mat, 1); return Status.RUNNING; }
                if (Navigator.standable(f1.getWorld(), f1.getX(), f1.getY(), f1.getZ())) { stepTo = new int[]{f1.getX(), f1.getY(), f1.getZ()}; moveTicks = 0; }
                else turn();
                return Status.RUNNING;
            }
            Act.lookAt(h, up1.getLocation().add(0.5, 0.5, 0.5));
            stepTo = new int[]{up1.getX(), up1.getY(), up1.getZ()}; moveTicks = 0;
            return Status.RUNNING;
        }
        private void turn() {
            if (++turns > 6) return;
            dir = DIRS[(Arrays.asList(DIRS).indexOf(dir) + 1) % 4];
        }
        /** Direction with the least digging (and no lava) along a staircase. */
        private static BlockFace pickDir(Block feet, Location goal) {
            BlockFace best = BlockFace.NORTH; int bestCost = Integer.MAX_VALUE;
            for (BlockFace f : DIRS) {
                int cost = 0;
                if (goal != null) { // prefer digging towards where we want to go
                    double gx = goal.getX() - feet.getX(), gz = goal.getZ() - feet.getZ(), gl = Math.max(0.01, Math.hypot(gx, gz));
                    cost += (int) Math.round((1 - (f.getModX() * gx + f.getModZ() * gz) / gl) * 4);
                }
                for (int i = 1; i <= 4; i++) {
                    Block b = feet.getRelative(f.getModX() * i, i, f.getModZ() * i);
                    for (Block x : new Block[]{b, b.getRelative(BlockFace.UP)}) {
                        if (x.getType() == Material.LAVA || x.getType() == Material.WATER) cost += 100;
                        else if (!Navigator.passable(x) && !Act.breakable(x)) cost += 1000;
                        else if (!Navigator.passable(x) && !diggable(x.getType())) cost += 8 + (int) (x.getType().getHardness() * 3); // someone's build: avoid if possible
                        else if (!Navigator.passable(x)) cost += 1 + (int) x.getType().getHardness();
                    }
                }
                if (cost < bestCost) { bestCost = cost; best = f; }
            }
            return best;
        }
    }

    // ================================================================ fire fighting

    public static final class Extinguish extends Task {
        private final Location around; private Block cur; private int put, cooldown, misses;
        public Extinguish(Location around) { this.around = around; }
        public String label() { return "putting out the fire"; }
        public int maxTicks() { return 20 * 90; }
        @Override public boolean isWork() { return true; }
        protected Status run(Human h, HumanManager m) {
            // don't burn to death for a house: back off when burning or badly hurt
            if (h.entity.getHealth() < 7) { outcome = "got badly burned and had to back off"; h.express(Expression.HURT, 40, HumansPlugin.get(), m.now()); return Status.FAIL; }
            if (h.entity.getFireTicks() > 0 && cur != null) {
                Vector away = h.loc().toVector().subtract(cur.getLocation().add(0.5, 0, 0.5).toVector()).setY(0);
                if (away.lengthSquared() > 0.01) h.entity.setVelocity(away.normalize().multiply(0.2).setY(h.entity.getVelocity().getY()));
                h.nav.stop();
                return Status.RUNNING;
            }
            if (--cooldown > 0) return Status.RUNNING;
            if (cur == null || cur.getType() != Material.FIRE && cur.getType() != Material.SOUL_FIRE) {
                cur = null;
                for (Block b : Awareness.fires(around, 12, 3, 8)) if (!h.isBad(b, m.now())) { cur = b; break; }
                if (cur == null) {
                    outcome = put == 0 ? "the fire was already out" : "put out the fire (" + put + " flames)";
                    if (put > 0) { h.remember("I put out a fire" + (h.fireAtHome ? " at my house" : "")); h.express(Expression.HAPPY, 40, HumansPlugin.get(), m.now()); }
                    h.fireAtHome = false;
                    return Status.SUCCESS;
                }
                if (!h.nav.moveTo(cur.getLocation().add(0.5, 0, 0.5), 2.2, true)) { h.markBad(cur, m.now()); cur = null; if (++misses > 6) { outcome = "couldn't reach the flames"; return Status.FAIL; } return Status.RUNNING; }
            }
            if (Act.eye(h).distance(cur.getLocation().add(0.5, 0.5, 0.5)) <= 3.6 && Act.canSee(h, cur)) {
                h.nav.stop();
                Act.lookAt(h, cur.getLocation().add(0.5, 0.3, 0.5));
                Act.swing(h);
                cur.setType(Material.AIR);
                cur.getWorld().playSound(cur.getLocation(), "block.fire.extinguish", 0.7f, 1.2f);
                put++; cur = null; cooldown = 5;
                return Status.RUNNING;
            }
            if (!h.nav.active()) h.nav.moveTo(cur.getLocation().add(0.5, 0, 0.5), 2.2, true);
            if (h.nav.tick() == Navigator.Result.FAILED) { h.markBad(cur, m.now()); cur = null; misses++; }
            return Status.RUNNING;
        }
    }

    // ================================================================ animals

    public static final class PetAnimal extends Task {
        private final Animals a; private int near;
        public PetAnimal(Animals a) { this.a = a; }
        public String label() { return "petting the " + HumanManager.nameOf(a); }
        public int maxTicks() { return 20 * 30; }
        protected Status run(Human h, HumanManager m) {
            if (!a.isValid() || a.getWorld() != h.loc().getWorld()) { outcome = "the " + HumanManager.nameOf(a) + " wandered off"; return Status.FAIL; }
            double d = a.getLocation().distance(h.loc());
            if (d > 2.3) {
                if (!h.nav.active() || age % 15 == 0) h.nav.moveTo(a.getLocation(), 1.6, false);
                if (h.nav.tick() == Navigator.Result.FAILED && age > 60) { outcome = "couldn't catch the " + HumanManager.nameOf(a); return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (age % 3 == 0) Act.lookAt(h, a.getLocation().add(0, a.getHeight() * 0.6, 0));
            if (near % 25 == 0) {
                a.getWorld().spawnParticle(Particle.HEART, a.getLocation().add(0, a.getHeight() + 0.3, 0), 1);
                try { a.lookAt(h.entity); } catch (Throwable ignored) {}
            }
            if (++near > 110) { outcome = "petted a " + HumanManager.nameOf(a); return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    public static final Material[] BREED_FOODS = {Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT,
        Material.BEETROOT_SEEDS, Material.MELON_SEEDS, Material.PUMPKIN_SEEDS, Material.DANDELION};

    /** Food from the inventory that this animal type eats (or null). */
    public static Material breedFood(Human h, Animals a) {
        for (Material f : BREED_FOODS) if (h.inv.count(f) >= 1 && a.isBreedItem(f)) return f;
        return null;
    }

    public static final class FeedAnimals extends Task {
        private final EntityType type; private Animals cur; private int fed;
        public FeedAnimals(EntityType type) { this.type = type; }
        public String label() { return "feeding the " + type.name().toLowerCase(Locale.ROOT) + "s"; }
        public int maxTicks() { return 20 * 45; }
        protected Status run(Human h, HumanManager m) {
            if (cur == null || !cur.isValid() || cur.isLoveMode()) {
                cur = null;
                if (fed >= 2) return done(h);
                double bd = 1e9;
                for (Entity e : h.entity.getNearbyEntities(16, 6, 16))
                    if (e instanceof Animals a && a.getType() == type && a.isAdult() && a.canBreed() && !a.isLoveMode() && breedFood(h, a) != null) {
                        double d = e.getLocation().distanceSquared(h.loc()); if (d < bd) { bd = d; cur = a; }
                    }
                if (cur == null) return done(h);
            }
            double d = cur.getLocation().distance(h.loc());
            if (d > 2.2) {
                if (!h.nav.active() || age % 15 == 0) h.nav.moveTo(cur.getLocation(), 1.5, false);
                h.nav.tick();
                return Status.RUNNING;
            }
            h.nav.stop();
            Material food = breedFood(h, cur);
            if (food == null) return done(h);
            Act.hold(h, food);
            Act.lookAt(h, cur.getLocation().add(0, cur.getHeight() * 0.6, 0));
            Act.swing(h);
            h.inv.remove(food, 1);
            cur.setLoveModeTicks(600);
            cur.getWorld().spawnParticle(Particle.HEART, cur.getLocation().add(0, cur.getHeight() + 0.3, 0), 3, 0.3, 0.2, 0.3);
            cur.getWorld().playSound(cur.getLocation(), "entity.generic.eat", 0.6f, 1.2f);
            fed++; cur = null;
            return Status.RUNNING;
        }
        private Status done(Human h) {
            Act.hold(h, Material.AIR);
            String kind = type.name().toLowerCase(Locale.ROOT);
            outcome = fed == 0 ? "couldn't feed any " + kind + "s" : "fed " + fed + " " + kind + (fed > 1 ? "s - babies on the way!" : "");
            if (fed >= 2) h.remember("I bred some " + kind + "s");
            return fed == 0 ? Status.FAIL : Status.SUCCESS;
        }
    }

    // ================================================================ chests

    static String describe(org.bukkit.inventory.Inventory inv) {
        Map<Material, Integer> c = new HashMap<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) c.merge(it.getType(), it.getAmount(), Integer::sum);
        if (c.isEmpty()) return "it was empty";
        List<Map.Entry<Material, Integer>> l = new ArrayList<>(c.entrySet());
        l.sort((x, y) -> y.getValue() - x.getValue());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(6, l.size()); i++) s.append(i == 0 ? "" : ", ").append(l.get(i).getValue()).append(' ').append(Items.nice(l.get(i).getKey()));
        if (l.size() > 6) s.append(" and more");
        return s.toString();
    }

    /** Walk to a chest/barrel, open it, have a look, close it. Never takes anything without asking. */
    public static class InspectChest extends Task {
        protected final Block chest; protected int open; private boolean opened;
        public InspectChest(Block chest) { this.chest = chest; }
        public String label() { return "looking inside a chest"; }
        public int maxTicks() { return 20 * 35; }
        protected Status run(Human h, HumanManager m) {
            if (!(chest.getState() instanceof Container)) { outcome = "the chest was gone"; return Status.FAIL; }
            if (Act.eye(h).distance(chest.getLocation().add(0.5, 0.5, 0.5)) > 3.4) {
                if (!h.nav.active()) if (!h.nav.moveTo(chest.getLocation().add(0.5, 0, 0.5), 2.2, false)) { h.markBad(chest, m.now()); outcome = "couldn't reach the chest"; return Status.FAIL; }
                if (h.nav.tick() == Navigator.Result.FAILED) { h.markBad(chest, m.now()); outcome = "couldn't reach the chest"; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (age % 3 == 0) Act.lookAt(h, chest.getLocation().add(0.5, 0.6, 0.5));
            if (!opened) {
                opened = true;
                BlockState st = chest.getState();
                if (st instanceof Lidded l) l.open();
                chest.getWorld().playSound(chest.getLocation(), st instanceof org.bukkit.block.Barrel ? "block.barrel.open" : "block.chest.open", 0.6f, 1f);
                Act.swing(h);
            }
            if (++open < 50) return Status.RUNNING;
            return finishLook(h, m);
        }
        protected Status finishLook(Human h, HumanManager m) {
            BlockState st = chest.getState();
            String inside = st instanceof Container c ? describe(c.getInventory()) : "nothing";
            close();
            h.seenChests.put(Human.blockKey(chest), m.now());
            outcome = "looked inside a chest: " + inside;
            // hungry with nothing to eat and there's food in here: ask a nearby player first
            if (st instanceof Container c && h.hunger < 12 && h.inv.countWhere(Items::isFood) == 0) {
                Material food = null;
                for (ItemStack it : c.getInventory().getContents()) if (it != null && Items.isFood(it.getType())) { food = it.getType(); break; }
                Player p = m.nearestPlayer(h, 20);
                if (food != null && p != null) {
                    h.ask = new Human.Ask(chest, food, p.getUniqueId(), m.now());
                    HumansPlugin.get().chat().broadcast(h, Lines.pick(Lines.ASK_CHEST, h, p.getName(), Items.nice(food)));
                    h.event("asked " + p.getName() + " if I can take " + Items.nice(food) + " from the chest");
                }
            }
            return Status.SUCCESS;
        }
        protected void close() {
            if (chest.getState() instanceof Lidded l) l.close();
            chest.getWorld().playSound(chest.getLocation(), chest.getState() instanceof org.bukkit.block.Barrel ? "block.barrel.close" : "block.chest.close", 0.6f, 1f);
        }
        @Override public void stop(Human h) { super.stop(h); if (opened && open < 50) close(); }
    }

    /** The player said yes: take a few of the item. */
    public static final class TakeFromChest extends InspectChest {
        private final Material mat; private final String who;
        public TakeFromChest(Block chest, Material mat, String who) { super(chest); this.mat = mat; this.who = who; }
        @Override public String label() { return "taking some " + Items.nice(mat) + " from " + who + "'s chest"; }
        @Override protected Status finishLook(Human h, HumanManager m) {
            int took = 0;
            if (chest.getState() instanceof Container c) {
                int have = 0;
                for (ItemStack it : c.getInventory().getContents()) if (it != null && it.getType() == mat) have += it.getAmount();
                took = Math.min(4, have);
                if (took > 0) { c.getInventory().removeItem(new ItemStack(mat, took)); h.inv.add(mat, took); }
            }
            close();
            outcome = took == 0 ? "the " + Items.nice(mat) + " was gone" : "took " + took + " " + Items.nice(mat) + " from " + who + "'s chest (with permission)";
            return took == 0 ? Status.FAIL : Status.SUCCESS;
        }
    }

    // ================================================================ plants

    /** Pick flowers (and grass, which sometimes gives seeds). */
    public static final class PickPlants extends Tasks.Harvest {
        public PickPlants(List<Block> plants) { super(plants, "picking flowers", "flowers"); }
        @Override protected boolean wanted(Block b) { return isPickable(b.getType()); }
        @Override public boolean isWork() { return false; }
        public static boolean isPickable(Material t) { return Tag.SMALL_FLOWERS.isTagged(t) || t == Material.SHORT_GRASS; }
    }

    public static boolean matureCrop(Block b) {
        Material t = b.getType();
        return (t == Material.WHEAT || t == Material.CARROTS || t == Material.POTATOES || t == Material.BEETROOTS)
            && b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    /** Harvest ripe crops and replant them straight away. */
    public static final class FarmCrops extends Tasks.Harvest {
        public FarmCrops(List<Block> crops) { super(crops, "harvesting the farm", "crops"); }
        @Override protected boolean wanted(Block b) { return matureCrop(b); }
        @Override protected void onBroken(Human h, Block b) {
            Material crop = b.getType().isAir() ? null : b.getType();
            if (b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND && b.getType().isAir() && lastCrop != null) {
                b.setType(lastCrop);
                b.getWorld().playSound(b.getLocation(), "item.crop.plant", 0.6f, 1f);
            }
        }
        private Material lastCrop;
        @Override protected Status run(Human h, HumanManager m) {
            // remember which crop we're about to break so it can be replanted
            for (Block b : blocks) if (matureCrop(b)) { lastCrop = b.getType(); break; }
            return super.run(h, m);
        }
    }

    // ================================================================ fun & social

    public static final class Dance extends Task {
        private boolean crouch;
        public String label() { return "dancing"; }
        public int maxTicks() { return 20 * 10; }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            if (age % 8 == 0) { crouch = !crouch; h.entity.setPose(crouch ? Pose.SNEAKING : Pose.STANDING, true); }
            if (age % 20 == 10 && h.entity.isOnGround()) h.entity.setVelocity(new Vector(0, 0.42, 0));
            if (age % 2 == 0) { float yaw = h.entity.getLocation().getYaw() + 24; h.entity.setRotation(yaw, 0); h.entity.setBodyYaw(yaw); }
            if (age % 7 == 0) Act.swing(h);
            if (age % 10 == 0) h.entity.getWorld().spawnParticle(Particle.NOTE, h.loc().add(0, 2.2, 0), 1);
            if (age >= 160) { outcome = "danced"; return Status.SUCCESS; }
            return Status.RUNNING;
        }
        @Override public void stop(Human h) { super.stop(h); if (h.entity != null) h.entity.setPose(Pose.STANDING, false); }
    }

    /** Sit back and think / watch the sunset. Restores energy. */
    public static final class Ponder extends Task {
        private final String label; private final int ticks; private final boolean sunset; private Location look;
        public Ponder(String label, int ticks, boolean sunset) { this.label = label; this.ticks = ticks; this.sunset = sunset; }
        public String label() { return label; }
        public int maxTicks() { return ticks + 20; }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            if (look == null || age % 80 == 0) {
                Location l = h.loc();
                look = sunset ? l.clone().add(-30, 6, m.rnd().nextGaussian() * 4) : l.clone().add(m.rnd().nextGaussian() * 8, 2 + m.rnd().nextDouble() * 6, m.rnd().nextGaussian() * 8);
            }
            if (age % 4 == 0) Act.lookAt(h, look);
            if (age >= ticks) { outcome = sunset ? "watched the sunset" : "had some deep thoughts"; return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    /** Walk over to another human and have a little chat (canned lines, no AI). */
    public static final class Visit extends Task {
        private final Human other; private int near;
        public Visit(Human other) { this.other = other; }
        public String label() { return "chatting with " + other.name; }
        public int maxTicks() { return 20 * 30; }
        protected Status run(Human h, HumanManager m) {
            if (!other.valid() || other.loc().getWorld() != h.loc().getWorld()) { outcome = other.name + " wasn't around"; return Status.FAIL; }
            double d = other.loc().distance(h.loc());
            if (d > 2.8) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(other.loc(), 2.2, false);
                if (h.nav.tick() == Navigator.Result.FAILED && age > 60) { outcome = "couldn't get to " + other.name; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (age % 4 == 0) {
                Act.lookAt(h, other.entity.getEyeLocation());
                if (other.task == null || other.task instanceof Tasks.Idle || other.task instanceof Ponder || other.task instanceof Tasks.Wander) Act.lookAt(other, h.entity.getEyeLocation());
            }
            near++;
            var chat = HumansPlugin.get().chat();
            if (near == 10) chat.broadcast(h, Lines.pick(Lines.SMALLTALK_A, h, other.name, null));
            if (near == 60 && other.valid()) chat.broadcast(other, Lines.pick(Lines.SMALLTALK_B, other, h.name, null));
            if (near >= 110) {
                outcome = "had a chat with " + other.name;
                other.event("had a chat with " + h.name);
                return Status.SUCCESS;
            }
            return Status.RUNNING;
        }
    }
}
