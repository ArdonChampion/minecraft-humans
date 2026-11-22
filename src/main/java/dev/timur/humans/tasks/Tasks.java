package dev.timur.humans.tasks;

import dev.timur.humans.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.*;

/** All concrete tasks. Each one is deterministic Java; the AI only chooses between them. */
public final class Tasks {
    private Tasks() {}

    // ================================================================ idle / movement

    public static final class Idle extends Task {
        private final int ticks; private final String label; private Location lookAt;
        public Idle(int ticks, String label) { this.ticks = ticks; this.label = label; }
        public String label() { return label; }
        protected Status run(Human h, HumanManager m) {
            if (age % 30 == 1) {
                Player p = m.nearestPlayer(h, 8);
                lookAt = p != null ? p.getEyeLocation() : h.loc().add(m.rnd().nextGaussian() * 4, 1.2 + m.rnd().nextGaussian() * 0.5, m.rnd().nextGaussian() * 4);
            }
            if (lookAt != null && age % 3 == 0) Act.lookAt(h, lookAt);
            return age >= ticks ? Status.SUCCESS : Status.RUNNING;
        }
    }

    public static class Goto extends Task {
        protected final Location dest; protected final double within; protected final boolean sprint; protected final String label;
        private boolean started;
        public Goto(Location dest, double within, boolean sprint, String label) { this.dest = dest; this.within = within; this.sprint = sprint; this.label = label; }
        public String label() { return label; }
        protected Status run(Human h, HumanManager m) {
            if (!started) { started = true; if (!h.nav.moveTo(dest, within, sprint)) { outcome = "couldn't find a way while " + label; return Status.FAIL; } }
            return switch (h.nav.tick()) {
                case ARRIVED -> { outcome = "finished " + label; yield Status.SUCCESS; }
                case FAILED -> { outcome = "got stuck while " + label; yield Status.FAIL; }
                default -> Status.RUNNING;
            };
        }
    }

    public static final class Wander extends Task {
        private Goto go; private int legs; private int pause;
        public String label() { return "wandering around"; }
        public int maxTicks() { return 20 * 60; }
        protected Status run(Human h, HumanManager m) {
            if (pause > 0) { pause--; return Status.RUNNING; }
            if (go == null) {
                if (legs >= 3) { outcome = "went for a walk"; return Status.SUCCESS; }
                Location base = h.home != null && h.home.getWorld() == h.loc().getWorld() && h.home.distance(h.loc()) < 48 ? h.home : h.loc();
                for (int i = 0; i < 8 && go == null; i++) {
                    Location spot = Act.surfaceSpot(base.clone().add(m.rnd().nextInt(29) - 14, 0, m.rnd().nextInt(29) - 14));
                    if (spot != null && spot.distance(h.loc()) > 4) go = new Goto(spot, 1.2, false, "wandering around");
                }
                if (go == null) { outcome = "looked around"; return Status.SUCCESS; }
            }
            Status s = go.tick(h, m);
            if (s != Status.RUNNING) { go = null; legs++; pause = 40 + m.rnd().nextInt(80); }
            return Status.RUNNING;
        }
    }

    /** Walk far in one direction looking for trees/resources (keeps the direction between explorations). */
    public static class Explore extends Task {
        private Goto go; private int legs, fails; private final String goal;
        public Explore(String goal) { this.goal = goal; }
        public String label() { return "exploring to find " + goal; }
        @Override public boolean isWork() { return true; }
        public int maxTicks() { return 20 * 75; }
        protected Status run(Human h, HumanManager m) {
            if (go == null) {
                if (legs >= 4) { outcome = "explored far but didn't find " + goal + " yet"; return Status.SUCCESS; }
                if (h.exploreYaw == null || m.rnd().nextDouble() < 0.15) h.exploreYaw = m.rnd().nextDouble() * Math.PI * 2;
                Location spot = null;
                for (int i = 0; i < 6 && spot == null; i++) {
                    double yaw = h.exploreYaw + (i == 0 ? 0 : m.rnd().nextGaussian() * 0.8);
                    spot = Act.surfaceSpot(h.loc().add(Math.cos(yaw) * 14, 0, Math.sin(yaw) * 14));
                    if (spot != null && i > 0) h.exploreYaw = yaw;
                }
                if (spot == null) { h.exploreYaw = null; outcome = "couldn't find a way to explore"; return Status.FAIL; }
                go = new Goto(spot, 2, false, label());
            }
            Status s = go.tick(h, m);
            if (s != Status.RUNNING) {
                go = null;
                if (s == Status.FAIL) {
                    h.exploreYaw = null;
                    if (++fails > 5) { outcome = legs == 0 ? "couldn't find a way to explore" : "explored a bit, then got stuck"; return legs == 0 ? Status.FAIL : Status.SUCCESS; }
                    return Status.RUNNING;
                }
                legs++;
                // stop early when the thing we look for shows up
                if (h.task == this && m.sees(h, goal)) { outcome = "found " + goal + " while exploring!"; return Status.SUCCESS; }
            }
            return Status.RUNNING;
        }
    }

    public static final class Flee extends Task {
        private final Entity threat; private Goto go;
        public Flee(Entity threat) { this.threat = threat; }
        public String label() { return "running away from " + HumanManager.nameOf(threat); }
        public int maxTicks() { return 20 * 15; }
        protected Status run(Human h, HumanManager m) {
            if (!threat.isValid() || threat.getLocation().getWorld() != h.loc().getWorld() || threat.getLocation().distance(h.loc()) > 18) {
                outcome = "escaped from " + HumanManager.nameOf(threat); return Status.SUCCESS;
            }
            if (go == null || age % 40 == 0) {
                Vector away = h.loc().toVector().subtract(threat.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                away.normalize().multiply(16).rotateAroundY(m.rnd().nextGaussian() * 0.5);
                Location spot = Act.surfaceSpot(h.loc().add(away));
                if (spot != null) go = new Goto(spot, 1.5, true, label());
            }
            if (go != null) go.tick(h, m);
            return Status.RUNNING;
        }
    }

    public static final class Follow extends Task {
        private final Player p; private final int ticks;
        public Follow(Player p, int ticks) { this.p = p; this.ticks = ticks; }
        public String label() { return "following " + p.getName(); }
        public int maxTicks() { return ticks + 20; }
        protected Status run(Human h, HumanManager m) {
            if (!p.isOnline() || p.getWorld() != h.loc().getWorld()) { outcome = "lost " + p.getName(); return Status.FAIL; }
            double d = p.getLocation().distance(h.loc());
            if (d > 48) { outcome = "lost track of " + p.getName(); return Status.FAIL; }
            if (d > 4) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(p.getLocation(), 2.5, d > 9);
                h.nav.tick();
            } else {
                h.nav.stop();
                if (age % 3 == 0) Act.lookAt(h, p.getEyeLocation());
            }
            if (age >= ticks) { outcome = "followed " + p.getName() + " for a while"; return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    /** Walk over to a player. wave=true: say hi and leave (never chase someone who runs off); false: come over and stay a moment. */
    public static final class Greet extends Task {
        private final Player p; private final boolean wave; private final String label; private int waved, near;
        public Greet(Player p) { this(p, true, "going to say hi to " + p.getName()); }
        public Greet(Player p, boolean wave, String label) { this.p = p; this.wave = wave; this.label = label; }
        public String label() { return label; }
        public int maxTicks() { return wave ? 20 * 12 : 20 * 35; }
        protected Status run(Human h, HumanManager m) {
            if (!p.isOnline() || p.getWorld() != h.loc().getWorld()) return Status.FAIL;
            double d = p.getLocation().distance(h.loc());
            if (wave && (age > 60 && d > 7 || d > 24)) { // they walked off: wave from here instead of chasing them
                Act.lookAt(h, p.getEyeLocation()); Act.swing(h);
                outcome = "waved at " + p.getName() + " from a distance"; return Status.SUCCESS;
            }
            if (!wave && d > 40) { outcome = p.getName() + " was too far away"; return Status.FAIL; }
            if (d > 3.2) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(p.getLocation(), 2.6, !wave && d > 10);
                if (h.nav.tick() == Navigator.Result.FAILED && age > 40) { outcome = "couldn't get to " + p.getName(); return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (age % 3 == 0) Act.lookAt(h, p.getEyeLocation());
            if (wave) {
                if (age % 7 == 0) { Act.swing(h); waved++; }
                if (waved >= 2) { outcome = "said hi to " + p.getName(); return Status.SUCCESS; }
            } else if (++near > 80) { outcome = "came over to " + p.getName(); return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    // ================================================================ gathering

    /** Shared block-breaking state machine: walk to a block, break it with crack animation. */
    static final class Breaker {
        Block target; int progress, total;
        boolean tick(Human h, HumanManager m) {
            if (total == 0) total = Act.breakTicks(h, target);
            Material tool = Act.toolFor(h, target);
            Act.hold(h, tool);
            Act.lookAt(h, target.getLocation().add(0.5, 0.5, 0.5));
            progress++;
            if (progress % 5 == 1) {
                Act.swing(h);
                target.getWorld().playSound(target.getLocation(), target.getBlockData().getSoundGroup().getHitSound(), 0.5f, 0.8f);
            }
            Act.crack(target, Math.min(0.95f, progress / (float) total), h.entity.getEntityId());
            if (progress >= total) { Act.breakBlock(h, target); return true; }
            return false;
        }
    }

    /** Break a set of blocks of a kind (a tree, an ore vein, a patch of stone). */
    public static class Harvest extends Task {
        protected final List<Block> blocks; protected final String label, what; protected final Material kindHint;
        private Breaker breaker; private int broken, approachFails, pause; private Block approaching; private CollectItems collect; private final List<Block> all;
        public Harvest(List<Block> blocks, String label, String what) {
            this.blocks = new ArrayList<>(blocks); this.all = new ArrayList<>(blocks); this.label = label; this.what = what; this.kindHint = blocks.isEmpty() ? Material.AIR : blocks.getFirst().getType();
        }
        public String label() { return label; }
        public int maxTicks() { return 20 * 150; }
        @Override public boolean isWork() { return true; }
        /** Is this block still something we want to break? */
        protected boolean wanted(Block b) { return !b.getType().isAir() && (b.getType() == kindHint || Tag.LOGS.isTagged(kindHint) && Tag.LOGS.isTagged(b.getType())); }
        /** Called right after a block was broken. */
        protected void onBroken(Human h, Block b) {}
        protected Status run(Human h, HumanManager m) {
            if (pause > 0) { pause--; return Status.RUNNING; }
            if (collect != null) {
                Status cs = collect.tick(h, m);
                return cs == Status.RUNNING && collect.age() < 20 * 15 ? Status.RUNNING : Status.SUCCESS;
            }
            if (breaker != null) {
                if (breaker.target.getType().isAir()) { breaker = null; return Status.RUNNING; }
                if (breaker.tick(h, m)) { broken++; blocks.remove(breaker.target); onBroken(h, breaker.target); breaker = null; pause = 6 + m.rnd().nextInt(8); }
                return Status.RUNNING;
            }
            blocks.removeIf(b -> !wanted(b));
            if (blocks.isEmpty()) return finish(h, m);
            // pick a reachable block (lowest first so trees fall top-down naturally)
            Block feetBelow = h.loc().getBlock().getRelative(BlockFace.DOWN);
            Block reachable = blocks.stream().filter(b -> Act.inReach(h, b) && !b.equals(feetBelow))
                .min(Comparator.comparingInt(Block::getY)).orElse(null);
            if (reachable != null) {
                h.nav.stop();
                breaker = new Breaker(); breaker.target = reachable;
                return Status.RUNNING;
            }
            // walk towards the lowest block (a tree's trunk base, not a branch up in the canopy)
            Block next = blocks.stream().min(Comparator.<Block>comparingInt(Block::getY)
                .thenComparingDouble(b -> b.getLocation().distanceSquared(h.loc()))).orElseThrow();
            if (approaching != next || !h.nav.active()) {
                approaching = next;
                if (!h.nav.moveTo(next.getLocation().add(0.5, 0, 0.5), 2.2, false)) {
                    h.markBad(next, m.now());
                    blocks.remove(next);
                    if (++approachFails > 3) return finish(h, m);
                    return Status.RUNNING;
                }
            }
            if (h.nav.tick() == Navigator.Result.FAILED) { h.markBad(next, m.now()); blocks.remove(next); approachFails++; }
            return Status.RUNNING;
        }
        private Status finish(Human h, HumanManager m) {
            h.nav.stop();
            outcome = broken == 0 ? "couldn't reach the " + what : "got " + broken + " " + what;
            if (broken == 0) { for (Block b : all) h.markBad(b, m.now()); return Status.FAIL; }
            collect = new CollectItems(); // pick up the drops before moving on
            return Status.RUNNING;
        }
    }

    public static final class CollectItems extends Task {
        private final Set<UUID> unreachable = new HashSet<>();
        private UUID current; private int startCount = -1;
        public String label() { return "picking up items"; }
        public int maxTicks() { return 20 * 30; }
        protected Status run(Human h, HumanManager m) {
            if (startCount < 0) startCount = h.inv.view().values().stream().mapToInt(Integer::intValue).sum();
            Item nearest = null; double best = 1e9;
            for (Entity e : h.entity.getNearbyEntities(14, 10, 14))
                if (e instanceof Item it && it.getPickupDelay() < 20 && !unreachable.contains(it.getUniqueId()) && !h.badItems.contains(it.getUniqueId()) && m.mayPickUp(h, it)) {
                    double d = it.getLocation().distanceSquared(h.loc()); if (d < best) { best = d; nearest = it; }
                }
            if (nearest == null) {
                int got = h.inv.view().values().stream().mapToInt(Integer::intValue).sum() - startCount;
                h.badItems.addAll(unreachable);
                if (h.badItems.size() > 200) h.badItems.clear();
                if (got <= 0) { outcome = "couldn't reach the items"; return Status.FAIL; }
                outcome = "picked up " + got + " items"; return Status.SUCCESS;
            }
            if (!nearest.getUniqueId().equals(current) || !h.nav.active() || age % 15 == 0) {
                current = nearest.getUniqueId();
                if (!h.nav.moveTo(nearest.getLocation(), 0.8, false)) { unreachable.add(current); return Status.RUNNING; }
            }
            if (h.nav.tick() == Navigator.Result.FAILED) unreachable.add(current);
            return Status.RUNNING;
        }
    }

    // ================================================================ combat

    public static final class Attack extends Task {
        private final LivingEntity target; private final boolean hunting; private int cooldown;
        public Attack(LivingEntity target, boolean hunting) { this.target = target; this.hunting = hunting; }
        public String label() { return (hunting ? "hunting a " : "fighting ") + HumanManager.nameOf(target); }
        @Override public boolean isWork() { return true; }
        public int maxTicks() { return 20 * 40; }
        protected Status run(Human h, HumanManager m) {
            if (target.isDead() || !target.isValid()) {
                outcome = (hunting ? "killed a " : "defeated ") + HumanManager.nameOf(target);
                return Status.SUCCESS;
            }
            if (target.getWorld() != h.loc().getWorld()) return Status.FAIL;
            double d = target.getLocation().distance(h.loc());
            if (d > 32) { outcome = HumanManager.nameOf(target) + " got away"; return Status.FAIL; }
            if (!hunting && h.entity.getHealth() < 6 && h.personality.bravery() < 0.6) { outcome = "was losing the fight against " + HumanManager.nameOf(target) + " and backed off"; return Status.FAIL; }
            cooldown--;
            if (d > 2.6) {
                if (!h.nav.active() || age % 10 == 0) h.nav.moveTo(target.getLocation(), 1.8, true);
                h.nav.tick();
            } else {
                h.nav.stop();
                Act.lookAt(h, target.getEyeLocation());
                if (cooldown <= 0) { Act.attack(h, target); cooldown = 12; }
                else if (!hunting && age % 8 == 0) { // strafe a little
                    Vector side = target.getLocation().toVector().subtract(h.loc().toVector()).setY(0).normalize().rotateAroundY(Math.PI / 2).multiply(m.rnd().nextBoolean() ? 0.25 : -0.25);
                    h.entity.setVelocity(h.entity.getVelocity().add(side));
                }
            }
            return Status.RUNNING;
        }
    }

    // ================================================================ survival

    public static final class Eat extends Task {
        private Material food;
        public String label() { return "eating"; }
        public int maxTicks() { return 80; }
        protected Status run(Human h, HumanManager m) {
            if (food == null) {
                food = Items.bestFood(h.inv).orElse(null);
                if (food == null) { outcome = "wanted to eat but had no food"; return Status.FAIL; }
                h.nav.stop();
                Act.hold(h, food);
                try { h.entity.startUsingItem(EquipmentSlot.HAND); } catch (Throwable ignored) {}
            }
            if (age % 4 == 0) h.entity.getWorld().playSound(h.loc(), "entity.generic.eat", 0.6f, 0.9f + m.rnd().nextFloat() * 0.2f);
            if (age >= 32) {
                try { h.entity.clearActiveItem(); } catch (Throwable ignored) {}
                h.inv.remove(food, 1);
                h.hunger = Math.min(20, h.hunger + Items.foodValue(food) * 1.5);
                h.entity.getWorld().playSound(h.loc(), "entity.player.burp", 0.6f, 1f);
                Act.hold(h, Material.AIR);
                outcome = "ate " + Items.nice(food);
                return Status.SUCCESS;
            }
            return Status.RUNNING;
        }
    }

    public static final class Sleep extends Task {
        private Goto go; private boolean lying; private final int napTicks;
        public Sleep() { this(0); }
        /** napTicks > 0: lie down right here for that long, day or night. */
        public Sleep(int napTicks) { this.napTicks = napTicks; }
        public String label() { return lying ? (napTicks > 0 ? "taking a nap" : "sleeping") : "going to bed"; }
        public int maxTicks() { return napTicks > 0 ? napTicks + 40 : 20 * 60 * 12; }
        protected Status run(Human h, HumanManager m) {
            long t = h.loc().getWorld().getTime();
            boolean night = t > 12600 && t < 23400;
            if (napTicks > 0) {
                if (!lying) { lying = true; h.sleeping = true; h.nav.stop(); h.entity.setPose(Pose.SLEEPING, true); Act.hold(h, Material.AIR); }
                if (age % 60 == 0) h.entity.getWorld().spawnParticle(Particle.NOTE, h.loc().add(0, 0.8, 0), 1);
                if (age >= napTicks) { outcome = "had a nice nap"; return Status.SUCCESS; }
                return Status.RUNNING;
            }
            if (!night) { outcome = lying ? "slept well and woke up" : "it wasn't night anymore"; return Status.SUCCESS; }
            if (!lying) {
                Location bed = Work.bedOk(h) ? h.bed.clone().add(0.5, 0, 0.5) : h.hutBuilt && h.hutCenter != null ? h.hutCenter : h.home;
                if (bed != null && bed.getWorld() == h.loc().getWorld() && bed.distance(h.loc()) > 2) {
                    if (go == null) go = new Goto(bed, 1.0, false, "going to bed");
                    Status s = go.tick(h, m);
                    if (s == Status.RUNNING) return s;
                }
                lying = true;
                h.sleeping = true;
                if (Work.bedOk(h) && h.bed.distance(h.loc()) < 3) h.entity.teleport(h.bed.clone().add(0.5, 0.56, 0.5));
                h.entity.setPose(Pose.SLEEPING, true);
                Act.hold(h, Material.AIR);
            }
            if (age % 60 == 0) h.entity.getWorld().spawnParticle(Particle.NOTE, h.loc().add(0, 0.8, 0), 1);
            return Status.RUNNING;
        }
        @Override public void stop(Human h) {
            super.stop(h);
            if (lying && h.entity != null) { h.entity.setPose(Pose.STANDING, false); }
            h.sleeping = false;
        }
    }

    // ================================================================ crafting

    /** Places a temporary work block (crafting table / furnace) next to the human, uses it, picks it back up. */
    abstract static class WorkAtBlock extends Task {
        private Block station; private final Material stationType;
        WorkAtBlock(Material stationType) { this.stationType = stationType; }
        protected boolean placed() { return station != null; }
        protected Block station(Human h) {
            if (station == null && stationType != null) {
                Block spot = Act.freeSpotNear(h);
                if (spot != null && h.inv.remove(stationType, 1)) { Act.place(h, spot, stationType); station = spot; }
            }
            return station;
        }
        @Override public void stop(Human h) {
            super.stop(h);
            if (station != null && station.getType() == stationType) {
                station.setType(Material.AIR);
                h.inv.add(stationType, 1);
                station.getWorld().playSound(station.getLocation(), "entity.item.pickup", 0.6f, 1f);
            } else if (station != null) h.inv.add(stationType, 1); // someone broke it; give it back anyway
            station = null;
        }
    }

    /** Crafts a chain of recipes (e.g. planks -> sticks -> table -> pickaxe). */
    public static final class Craft extends WorkAtBlock {
        private final List<Items.Recipe> steps; private final String what; private int step, wait;
        public Craft(List<Items.Recipe> steps, String what) {
            super(steps.stream().anyMatch(Items.Recipe::needsTable) ? Material.CRAFTING_TABLE : null);
            this.steps = steps; this.what = what;
        }
        public Craft(Items.Recipe r) { this(List.of(r), r.nice()); }
        public String label() { return "crafting " + what; }
        public int maxTicks() { return 60 + steps.size() * 34; }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            Items.Recipe r = steps.get(step);
            Block st = r.needsTable() && (h.inv.has(Material.CRAFTING_TABLE, 1) || placed()) ? station(h) : null;
            if (st != null) Act.lookAt(h, st.getLocation().add(0.5, 1, 0.5));
            if (age % 6 == 0) Act.swing(h);
            if (++wait < 22) return Status.RUNNING;
            wait = 0;
            boolean tableOut = st != null; // the table is placed in the world: count it as available
            if (tableOut) h.inv.add(Material.CRAFTING_TABLE, 1);
            boolean ok = Items.canCraft(h.inv, r);
            if (ok) Items.consume(h.inv, r);
            if (tableOut) h.inv.remove(Material.CRAFTING_TABLE, 1);
            if (!ok) { outcome = "didn't have the materials for " + r.nice(); return step > 0 ? Status.SUCCESS : Status.FAIL; }
            h.entity.getWorld().playSound(h.loc(), "ui.stonecutter.take_result", 0.7f, 1.1f + step * 0.05f);
            Act.hold(h, r.result());
            if (++step >= steps.size()) { outcome = "crafted " + what; return Status.SUCCESS; }
            return Status.RUNNING;
        }
    }

    public static final class Smelt extends WorkAtBlock {
        private int done; private final int max = 8;
        public Smelt() { super(Material.FURNACE); }
        public String label() { return "cooking/smelting at a furnace"; }
        public int maxTicks() { return 20 * 40; }
        protected Status run(Human h, HumanManager m) {
            h.nav.stop();
            Block f = station(h);
            if (f == null) { outcome = "had no space or furnace to smelt"; return Status.FAIL; }
            if (age == 1 && f.getBlockData() instanceof Lightable l) { l.setLit(true); f.setBlockData(l); }
            Act.lookAt(h, f.getLocation().add(0.5, 0.5, 0.5));
            if (age % 10 == 0) f.getWorld().spawnParticle(Particle.SMOKE, f.getLocation().add(0.5, 1.1, 0.5), 3, 0.15, 0.05, 0.15, 0.01);
            if (age % 50 == 0) {
                Material raw = h.inv.view().keySet().stream().filter(x -> Items.cooked(x) != null)
                    .min(Comparator.comparingInt(x -> x == Material.COBBLESTONE || x == Material.SAND || x == Material.RED_SAND ? 1 : 0)).orElse(null);
                if (raw == null || done >= max) { outcome = done == 0 ? "had nothing to smelt" : "smelted/cooked " + done + " items"; return done == 0 ? Status.FAIL : Status.SUCCESS; }
                if (done % 8 == 0) { // fuel per 8 items
                    if (h.inv.remove(Material.COAL, 1) || h.inv.remove(Material.CHARCOAL, 1) || h.inv.removeAny(Tag.LOGS::isTagged, 1) || h.inv.removeAny(Tag.PLANKS::isTagged, 2)) {}
                    else { outcome = done == 0 ? "had no fuel for the furnace" : "smelted " + done + " items, then ran out of fuel"; return done == 0 ? Status.FAIL : Status.SUCCESS; }
                }
                h.inv.remove(raw, 1);
                h.inv.add(Items.cooked(raw), 1);
                done++;
                f.getWorld().playSound(f.getLocation(), "block.furnace.fire_crackle", 0.8f, 1f);
            }
            return Status.RUNNING;
        }
    }

    // ================================================================ social

    public static final class Give extends Task {
        private final Player p; private final Material mat; private final int amount;
        public Give(Player p, Material mat, int amount) { this.p = p; this.mat = mat; this.amount = amount; }
        public String label() { return "bringing " + amount + " " + Items.nice(mat) + " to " + p.getName(); }
        public int maxTicks() { return 20 * 40; }
        protected Status run(Human h, HumanManager m) {
            if (!p.isOnline() || p.getWorld() != h.loc().getWorld()) return Status.FAIL;
            double d = p.getLocation().distance(h.loc());
            if (d > 2.8) {
                if (!h.nav.active() || age % 15 == 0) h.nav.moveTo(p.getLocation(), 2.0, d > 10);
                h.nav.tick();
                return Status.RUNNING;
            }
            h.nav.stop();
            int n = Math.min(amount, h.inv.count(mat));
            if (n <= 0) { outcome = "wanted to give " + p.getName() + " " + Items.nice(mat) + " but had none"; return Status.FAIL; }
            h.inv.remove(mat, n);
            Act.lookAt(h, p.getEyeLocation());
            Act.swing(h);
            Location from = h.entity.getEyeLocation().subtract(0, 0.3, 0);
            Item it = h.entity.getWorld().dropItem(from, new org.bukkit.inventory.ItemStack(mat, n), i -> {
                i.setPickupDelay(10);
                i.setOwner(p.getUniqueId());
                i.setThrower(h.entity.getUniqueId());
            });
            it.setVelocity(p.getLocation().toVector().subtract(from.toVector()).normalize().multiply(0.3).setY(0.2));
            outcome = "gave " + p.getName() + " " + n + " " + Items.nice(mat);
            return Status.SUCCESS;
        }
    }

    // ================================================================ building

    public static final class BuildHut extends Task {
        private List<Block> clear, plan; private int ci, idx, cooldown, placed; private Breaker breaker; private boolean steppingAside; private int passes;
        public String label() { return "building a house" + (plan == null ? "" : ci < clear.size() ? " (clearing the spot)" : " (" + idx + "/" + plan.size() + ")"); }
        @Override public String debugInfo() {
            return "center=" + (plan == null ? "?" : clear == null ? "?" : "") + "ci=" + ci + "/" + (clear == null ? 0 : clear.size()) + " idx=" + idx + "/" + (plan == null ? 0 : plan.size())
                + (ci < (clear == null ? 0 : clear.size()) ? " clearTarget=" + clear.get(ci).getType() + "@" + clear.get(ci).getX() + "," + clear.get(ci).getY() + "," + clear.get(ci).getZ() : "")
                + (plan != null && idx < plan.size() ? " placeTarget=" + plan.get(idx).getType() + "@" + plan.get(idx).getX() + "," + plan.get(idx).getY() + "," + plan.get(idx).getZ() : "")
                + " breaker=" + (breaker != null) + " cooldown=" + cooldown;
        }
        public int maxTicks() { return 20 * 300; }
        @Override public boolean isWork() { return true; }

        public static int planSize() { return 55; }

        protected Status run(Human h, HumanManager m) {
            if (plan == null) {
                if (h.hutCenter == null) h.hutCenter = findSite(h);
                if (h.hutCenter == null) { h.noSiteUntil = m.now() + 20 * 60 * 4; outcome = "couldn't find an open spot for a house near home"; return Status.FAIL; }
                clear = clearList(h.hutCenter);
                plan = plan(h.hutCenter);
            }
            if (--cooldown > 0) return Status.RUNNING;
            // 1) clear leaves / plants in the way
            if (breaker != null) { if (breaker.target.getType().isAir() || breaker.tick(h, m)) { breaker = null; cooldown = 2; } return Status.RUNNING; }
            while (ci < clear.size() && (clear.get(ci).getType().isAir() || clear.get(ci).isReplaceable() && !clear.get(ci).getType().isSolid())) ci++;
            if (ci < clear.size()) {
                Block b = clear.get(ci);
                if (!Act.inReach(h, b)) { if (!h.nav.active()) h.nav.moveTo(b.getLocation().add(0.5, 0, 0.5), 3.0, false); if (h.nav.tick() == Navigator.Result.FAILED) ci++; return Status.RUNNING; }
                h.nav.stop();
                breaker = new Breaker(); breaker.target = b;
                return Status.RUNNING;
            }
            // 2) foundation, walls, roof
            while (idx < plan.size() && plan.get(idx).getType().isSolid()) idx++;
            if (idx >= plan.size()) {
                if (++passes < 3 && plan.stream().anyMatch(x -> !x.getType().isSolid())) { idx = 0; return Status.RUNNING; } // fill skipped gaps
                return finishHut(h, m);
            }
            Block b = plan.get(idx);
            if (steppingAside) {
                Navigator.Result r = h.nav.tick();
                if (r != Navigator.Result.MOVING) steppingAside = false;
                return Status.RUNNING;
            }
            Location feet = h.loc();
            if (b.getX() == feet.getBlockX() && b.getZ() == feet.getBlockZ() && b.getY() >= feet.getBlockY() - 1 && b.getY() <= feet.getBlockY() + 1) {
                // standing where the block goes: step to a free spot that isn't part of the house
                Set<Block> planned = new HashSet<>(plan);
                for (int[] d : new int[][]{{2,0},{-2,0},{0,2},{0,-2},{2,2},{-2,-2},{2,-2},{-2,2},{3,0},{0,3},{-3,0},{0,-3}}) {
                    Location spot = Act.surfaceSpot(feet.clone().add(d[0], 0, d[1]));
                    if (spot != null && !planned.contains(spot.getBlock()) && !planned.contains(spot.getBlock().getRelative(BlockFace.DOWN)) && h.nav.moveTo(spot, 0.4, false)) { steppingAside = true; break; }
                }
                if (!steppingAside) idx++; // can't move away: skip this block for now
                return Status.RUNNING;
            }
            if (!Act.inReach(h, b)) {
                if (!h.nav.active()) h.nav.moveTo(b.getLocation().add(0.5, 0, 0.5), 3.0, false);
                if (h.nav.tick() == Navigator.Result.FAILED) { idx++; }
                return Status.RUNNING;
            }
            h.nav.stop();
            Material mat = h.inv.any(Items::isBuildingBlock).orElse(null);
            if (mat == null) { outcome = "ran out of building blocks (house " + idx + "/" + plan.size() + " done)"; return placed > 0 ? Status.SUCCESS : Status.FAIL; }
            if (Act.place(h, b, mat)) { h.inv.remove(mat, 1); placed++; idx++; }
            else idx++;
            cooldown = 9 + m.rnd().nextInt(4);
            return Status.RUNNING;
        }

        private Status finishHut(Human h, HumanManager m) {
            Block c = h.hutCenter.getBlock();
            Block doorLow = c.getRelative(0, 0, 2);
            if (h.inv.has(Material.OAK_DOOR, 1) && doorLow.getType().isAir() && doorLow.getRelative(0, 1, 0).getType().isAir()) {
                h.inv.remove(Material.OAK_DOOR, 1);
                Door low = (Door) Material.OAK_DOOR.createBlockData(); low.setHalf(Bisected.Half.BOTTOM); low.setFacing(BlockFace.SOUTH);
                Door up = (Door) Material.OAK_DOOR.createBlockData(); up.setHalf(Bisected.Half.TOP); up.setFacing(BlockFace.SOUTH);
                doorLow.setBlockData(low, false); doorLow.getRelative(0, 1, 0).setBlockData(up, false);
                doorLow.getWorld().playSound(doorLow.getLocation(), "block.wood.place", 1f, 1f);
            }
            if (h.inv.has(Material.TORCH, 1)) { Block t = c.getRelative(1, 0, -1); if (t.getType().isAir()) { h.inv.remove(Material.TORCH, 1); t.setType(Material.TORCH); } }
            h.hutBuilt = true;
            h.home = h.hutCenter.clone();
            outcome = "FINISHED building their house!";
            return Status.SUCCESS;
        }

        /** Leaves / plants / snow inside the 5x5x3 volume that must be removed first. */
        static List<Block> clearList(Location center) {
            List<Block> out = new ArrayList<>();
            World w = center.getWorld();
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            for (int y = 2; y >= 0; y--) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                Block b = w.getBlockAt(cx + dx, cy + y, cz + dz);
                if (!b.getType().isAir()) out.add(b);
            }
            return out;
        }

        /** Foundation (fills dips), 5x5 walls 2 high with a door gap on the south side, flat roof. */
        static List<Block> plan(Location center) {
            List<Block> out = new ArrayList<>();
            World w = center.getWorld();
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                Block g = w.getBlockAt(cx + dx, cy - 1, cz + dz);
                if (!g.getType().isSolid()) out.add(g);
            }
            for (int y = 0; y < 2; y++)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        if (!edge || dx == 0 && dz == 2) continue; // door gap
                        out.add(w.getBlockAt(cx + dx, cy + y, cz + dz));
                    }
            List<Block> roof = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) roof.add(w.getBlockAt(cx + dx, cy + 2, cz + dz));
            roof.sort(Comparator.comparingInt(b -> -(Math.max(Math.abs(b.getX() - cx), Math.abs(b.getZ() - cz)))));
            out.addAll(roof);
            return out;
        }

        /** Cheapest buildable spot within 14 blocks of home (fewest dips to fill / leaves to clear). */
        static Location findSite(Human h) {
            Location base = h.home != null ? h.home : h.loc();
            World w = base.getWorld();
            Location best = null; int bestCost = Integer.MAX_VALUE;
            for (int dx = -14; dx <= 14; dx += 2)
                for (int dz = -14; dz <= 14; dz += 2) {
                    Location spot = Act.surfaceSpot(base.clone().add(dx, 0, dz));
                    if (spot == null) continue;
                    int cost = siteCost(w, spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
                    if (cost < 0) continue;
                    cost += (Math.abs(dx) + Math.abs(dz)) / 4;
                    if (cost < bestCost) { bestCost = cost; best = new Location(w, spot.getBlockX() + 0.5, spot.getBlockY(), spot.getBlockZ() + 0.5); }
                }
            return best;
        }

        /** -1 = impossible; otherwise roughly how much extra work the spot needs. */
        static int siteCost(World w, int cx, int cy, int cz) {
            int cost = 0, fill = 0;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    Block g = w.getBlockAt(cx + dx, cy - 1, cz + dz);
                    if (!g.getType().isSolid()) {
                        if (!w.getBlockAt(cx + dx, cy - 2, cz + dz).getType().isSolid()) return -1; // dip deeper than 1
                        fill++;
                    }
                    for (int y = 0; y < 3; y++) {
                        Block b = w.getBlockAt(cx + dx, cy + y, cz + dz);
                        Material t = b.getType();
                        if (t.isAir()) continue;
                        if (Tag.LEAVES.isTagged(t)) { cost += 1; continue; }
                        if (b.isReplaceable() && !t.isSolid() && t != Material.WATER && t != Material.LAVA) continue;
                        return -1; // logs, stone, someone's build...
                    }
                }
            if (fill > 8) return -1;
            boolean door = Navigator.standable(w, cx, cy, cz + 3) || Navigator.standable(w, cx, cy + 1, cz + 3) || Navigator.standable(w, cx, cy - 1, cz + 3);
            return door ? cost + fill * 2 : -1;
        }
    }
}
