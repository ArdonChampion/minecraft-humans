package dev.timur.humans;

import dev.timur.humans.brain.Option;
import dev.timur.humans.skin.Look;
import dev.timur.humans.tasks.Act;
import dev.timur.humans.tasks.Task;
import dev.timur.humans.tasks.Tasks;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.util.*;

/** Owns all humans: spawning, linking bodies, the per-tick loop, reflexes, persistence. */
public final class HumanManager {
    private final HumansPlugin plugin;
    private final Map<UUID, Human> humans = new LinkedHashMap<>();
    private final Map<UUID, Human> byEntity = new HashMap<>();
    private final Map<String, ResolvableProfile> profiles = new HashMap<>();
    private final Random rnd = new Random();
    private final NamespacedKey idKey;
    private final File dir;
    private long now;

    private static final String[] NAMES = {"Bob", "Ada", "Milo", "Rosa", "Finn", "Ivy", "Otto", "Luna", "Hugo", "Nora", "Theo", "Mira",
        "Basil", "Pip", "Greta", "Felix", "Juno", "Ezra", "Clara", "Rufus", "Wren", "Olga", "Sven", "Tilly", "Boris", "Yara", "Kit", "Dmitri", "Lev", "Zoya"};

    public HumanManager(HumansPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "human");
        this.dir = new File(plugin.getDataFolder(), "humans");
        dir.mkdirs();
    }

    public long now() { return now; }
    public Random rnd() { return rnd; }
    public Collection<Human> all() { return humans.values(); }
    public Human byEntity(Entity e) { return e == null ? null : byEntity.get(e.getUniqueId()); }

    public Human byName(String name) {
        for (Human h : humans.values()) if (h.name.equalsIgnoreCase(name)) return h;
        return null;
    }

    public static String nameOf(Entity e) {
        if (e instanceof Player p) return p.getName();
        HumansPlugin pl = HumansPlugin.get();
        if (pl != null) { Human h = pl.manager().byEntity(e); if (h != null) return h.name; }
        return e.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public String freshName() {
        List<String> free = new ArrayList<>();
        for (String n : NAMES) if (byName(n) == null) free.add(n);
        return free.isEmpty() ? "Human" + (humans.size() + 1) : free.get(rnd.nextInt(free.size()));
    }

    // ---------------------------------------------------------------- spawning & bodies

    public Human spawn(Location at, String name) {
        Human h = new Human(UUID.randomUUID(), name, Look.random(rnd), Personality.random(rnd));
        Location land = Act.wet(at.getBlock()) ? Act.nearestLand(at, 24) : null;
        if (land != null) at = land;
        h.home = at.clone();
        h.remember("I just moved to this place");
        humans.put(h.id, h);
        spawnBody(h, at);
        save(h);
        return h;
    }

    void spawnBody(Human h, Location at) {
        if (Act.wet(at.getBlock()) || !Navigator.standable(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            Location land = Act.nearestLand(at, 24);
            if (land != null) { at = land; if (h.home != null && h.home.distance(land) < 30) h.home = land.clone(); }
        }
        final Location spawnAt = at;
        Mannequin m = spawnAt.getWorld().spawn(spawnAt, Mannequin.class, e -> {
            e.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, h.id.toString());
            e.customName(Component.text(h.name, NamedTextColor.YELLOW));
            e.setCustomNameVisible(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setImmovable(false);
            e.setCanPickupItems(false);
            e.setProfile(profile(h.look, Expression.NEUTRAL));
            e.setDescription(Component.text("just arrived", NamedTextColor.GRAY));
        });
        link(h, m);
    }

    /** A baby: mix of both parents, starts small and grows up. */
    public Human spawnChild(Human a, Human b, Location at) {
        Personality pa = a.personality, pb = b.personality;
        java.util.function.DoubleBinaryOperator mix = (x, y) -> Math.max(0, Math.min(1, (x + y) / 2 + rnd.nextGaussian() * 0.15));
        Personality p = new Personality(mix.applyAsDouble(pa.bravery(), pb.bravery()), mix.applyAsDouble(pa.friendliness(), pb.friendliness()),
            mix.applyAsDouble(pa.diligence(), pb.diligence()), mix.applyAsDouble(pa.generosity(), pb.generosity()), mix.applyAsDouble(pa.chattiness(), pb.chattiness()),
            rnd.nextBoolean() ? pa.quirk() : pb.quirk(), rnd.nextBoolean() ? pa.speech() : pb.speech());
        Human c = new Human(UUID.randomUUID(), freshName(), rnd.nextBoolean() ? a.look : b.look, p);
        c.growth = 0.5;
        c.parents.add(a.name); c.parents.add(b.name);
        c.home = (a.home != null ? a.home : at).clone();
        c.village = a.village != null ? a.village : b.village;
        c.hunger = 20;
        c.remember("I was born! My parents are " + a.name + " and " + b.name);
        a.trust.forEach((k, v) -> c.trust.put(k, (v + b.trustOf(k)) / 2)); a.knownNames.forEach(c.knownNames::putIfAbsent);
        humans.put(c.id, c);
        spawnBody(c, at);
        save(c);
        Component msg = Component.text("♥ " + a.name + " and " + b.name + " had a baby: " + c.name + "!", NamedTextColor.LIGHT_PURPLE);
        for (Player pl : at.getWorld().getPlayers()) if (pl.getLocation().distance(at) < 64) pl.sendMessage(msg);
        at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1, 0), 8, 0.5, 0.5, 0.5);
        return c;
    }

    void applyScale(Human h) {
        if (!h.valid()) return;
        var attr = h.entity.getAttribute(Attribute.SCALE);
        if (attr != null) { double want = Math.max(0.5, Math.min(1, h.growth)); if (Math.abs(attr.getBaseValue() - want) > 0.005) attr.setBaseValue(want); }
    }

    /** Wear the best armour they carry. */
    void wearArmor(Human h) {
        var eq = h.entity.getEquipment();
        String[][] slots = {{"_HELMET", "h"}, {"_CHESTPLATE", "c"}, {"_LEGGINGS", "l"}, {"_BOOTS", "b"}};
        for (String[] sl : slots) {
            Material best = Items.best(h.inv, sl[0]).orElse(null);
            if (best == null) continue;
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(best);
            switch (sl[1]) {
                case "h" -> { if (eq.getHelmet() == null || eq.getHelmet().getType() != best) eq.setHelmet(it); }
                case "c" -> { if (eq.getChestplate() == null || eq.getChestplate().getType() != best) eq.setChestplate(it); }
                case "l" -> { if (eq.getLeggings() == null || eq.getLeggings().getType() != best) eq.setLeggings(it); }
                default -> { if (eq.getBoots() == null || eq.getBoots().getType() != best) eq.setBoots(it); }
            }
        }
    }

    public void link(Human h, Mannequin m) {
        if (h.entity != null && h.entity.isValid() && !h.entity.equals(m)) { m.remove(); return; } // duplicate body
        h.entity = m;
        h.shown = null;
        byEntity.put(m.getUniqueId(), h);
        h.updateFace(plugin, now);
        applyScale(h);
    }

    public void onEntityAdded(Entity e) {
        if (!(e instanceof Mannequin m)) return;
        String id = m.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (id == null) return;
        Human h = humans.get(UUID.fromString(id));
        if (h == null || h.dead) { m.remove(); return; } // orphaned body
        link(h, m);
    }

    public void onEntityRemoved(Entity e) {
        Human h = byEntity.remove(e.getUniqueId());
        if (h != null && e.equals(h.entity)) {
            if (h.task != null) { h.task.stop(h); h.task = null; }
            h.entity = null;
        }
    }

    public void remove(Human h) {
        if (h.task != null && h.valid()) h.task.stop(h);
        if (h.entity != null) { byEntity.remove(h.entity.getUniqueId()); h.entity.remove(); }
        humans.remove(h.id);
        new File(dir, h.id + ".yml").delete();
    }

    public ResolvableProfile profile(Look look, Expression e) {
        return profiles.computeIfAbsent(look.id() + "_" + e.id(), k -> ResolvableProfile.resolvableProfile()
            .skinPatch(sp -> sp.body(Key.key("humans", "skin/" + k)).model(PlayerTextures.SkinModel.CLASSIC))
            .build());
    }

    public void playExpressionSound(Human h, Expression e) {
        if (!h.valid()) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("expressions." + e.id());
        if (s == null) return;
        String key = s.getString("sound", "");
        if (key == null || key.isBlank()) return;
        h.entity.getWorld().playSound(h.loc(), key, (float) s.getDouble("volume", 0.8), (float) (s.getDouble("pitch", 1) * (0.93 + rnd.nextDouble() * 0.14)));
    }

    // ---------------------------------------------------------------- main loop

    public boolean isAwake(Human h) {
        if (!h.valid()) return false;
        if (plugin.getConfig().getBoolean("brain.always_awake", false)) return true;
        double r = plugin.getConfig().getDouble("brain.awake_radius", 96);
        for (Player p : h.entity.getWorld().getPlayers())
            if (p.getGameMode() != GameMode.SPECTATOR && p.getLocation().distanceSquared(h.loc()) < r * r) return true;
        return false;
    }

    public Player nearestPlayer(Human h, double r) {
        Player best = null; double bd = r * r;
        for (Player p : h.entity.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(h.loc());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    public void tick() {
        now++;
        for (Human h : new ArrayList<>(humans.values())) {
            try { tickHuman(h); }
            catch (Exception ex) {
                plugin.getLogger().warning("Error ticking " + h.name + ": " + ex);
                if (plugin.debug()) ex.printStackTrace();
                if (h.task != null) { try { h.task.stop(h); } catch (Exception ignored) {} h.task = null; }
                h.needsThink = true;
            }
        }
    }

    private void tickHuman(Human h) {
        if (h.dead) {
            if (System.currentTimeMillis() >= h.respawnAtMs && h.home != null && h.home.isWorldLoaded()
                && h.home.getWorld().isChunkLoaded(h.home.getBlockX() >> 4, h.home.getBlockZ() >> 4)) {
                h.dead = false;
                h.hunger = 20;
                spawnBody(h, h.home.clone().add(0, 0.2, 0));
                h.lastOutcome = "came back to life at home";
                h.needsThink = true;
                h.express(Expression.SAD, 100, plugin, now);
            }
            return;
        }
        if (!h.valid()) return;
        if (!h.entity.isTicking()) { if (h.nav.active()) h.nav.stop(); return; } // chunk loading/unloading: don't move them
        boolean awake = cachedAwake(h);
        if (!awake) { if (h.nav.active()) h.nav.stop(); return; }

        h.tickFace(plugin, now, rnd);
        if (now % 4 == 0) h.tickTag(now);

        // body needs
        if (now % 20 == 0) {
            h.hunger = Math.max(0, h.hunger - (h.task == null || h.sleeping ? 0.008 : 0.018));
            boolean resting = h.sleeping || h.task == null || h.task instanceof Tasks.Idle || h.task instanceof dev.timur.humans.tasks.Life.Ponder;
            double before = h.energy;
            h.energy = Math.max(0, Math.min(20, h.energy + (resting ? (h.sleeping ? 0.35 : 0.15) : h.task.isWork() ? -0.06 : -0.015)));
            if (before >= 5 && h.energy < 5) h.event("I'm getting really tired");
            if (h.isChild()) {
                h.growth = Math.min(1.0, h.growth + 0.5 / (60.0 * plugin.getConfig().getInt("family.grow_up_minutes", 20)));
                if (now % 200 == 0 || h.growth >= 1.0) applyScale(h);
                if (h.growth >= 1.0) { h.remember("I grew up!"); h.express(Expression.HAPPY, 80, plugin, now); h.event("I grew up and I'm an adult now"); }
            }
            if (now % 100 == 0) wearArmor(h);
            double max = h.entity.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (now % 80 == 0 && h.hunger >= 14 && h.entity.getHealth() < max) h.entity.setHealth(Math.min(max, h.entity.getHealth() + 1));
            if (now % 80 == 0 && h.hunger <= 0 && h.entity.getHealth() > 2) { h.entity.damage(1); h.event("is starving"); }
        }
        if (now % 4 == 0) pickupReflex(h);
        if (now % 40 == 0) eatReflex(h);
        swimReflex(h);
        if (now % 10 == 0) dangerReflex(h);
        if ((now + (h.id.hashCode() & 31)) % 40 == 0) Awareness.update(h, this, plugin);

        // task
        if (h.task != null) {
            Task t = h.task;
            Task.Status s = t.tick(h, this);
            if (s != Task.Status.RUNNING && h.task == t) finishTask(h, t, s);
            else if (h.task != null && !h.thinking && !h.needsThink && !(h.task instanceof Tasks.Sleep) && now > h.commitUntil
                && now - h.lastThinkTick > plugin.getConfig().getInt("brain.rethink_seconds", 30) * 20L * (h.task instanceof dev.timur.humans.plan.PlanTask ? 3 : 1)) h.needsThink = true;
        } else {
            h.needsThink = true;
            if (now % 10 == 0) { Player p = nearestPlayer(h, 6); if (p != null) Act.lookAt(h, p.getEyeLocation()); }
        }
        if (now % 10 == 0) h.setStatus(statusLine(h));
        if (plugin.debug() && now % 200 == 0 && h.task != null) {
            Location l = h.loc();
            plugin.getLogger().info("[debug] " + h.name + " @" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + " " + h.task.label() + " " + h.task.debugInfo() + " nav=" + h.nav.active());
        }
    }

    private final Map<UUID, Boolean> awakeCache = new HashMap<>();
    private boolean cachedAwake(Human h) {
        if (now % 20 == 0 || !awakeCache.containsKey(h.id)) awakeCache.put(h.id, isAwake(h));
        return awakeCache.get(h.id);
    }

    private String statusLine(Human h) {
        String face = switch (h.mood) { case HAPPY -> "☺ "; case SAD -> "☹ "; case ANGRY -> "☠ "; case SCARED -> "! "; default -> ""; };
        if (h.sleeping) return "Zzz...";
        if (h.thinking && h.task == null) return face + "thinking...";
        return face + (h.task == null ? "idle" : h.task.label());
    }

    public void startTask(Human h, Option o) {
        Task t;
        try { t = o.make().get(); } catch (Exception e) { t = new Tasks.Idle(40, "confused"); }
        if (t != h.task) {
            if (h.task != null) h.task.stop(h);
            h.task = t;
            h.taskStartTick = now;
        }
        h.needsThink = false;
        h.urgent = false;
        h.lastThinkTick = now;
        h.lastOptionId = o.id();
        if (!o.id().equals("keep")) {
            if (t != null && !o.id().equals("do_request")) h.commitUntil = 0;
            String fam = o.id().equals("do_request") ? "doing what a player asked" : o.family();
            if (h.recentActs.isEmpty() || !h.recentActs.peekFirst().equals(fam) || !o.id().startsWith("keep")) h.recentActs.addFirst(fam);
            while (h.recentActs.size() > 6) h.recentActs.removeLast();
        }
        h.setStatus(statusLine(h));
    }

    private void finishTask(Human h, Task t, Task.Status s) {
        t.stop(h);
        if (t instanceof dev.timur.humans.plan.PlanTask pt && (pt.goal().done || s == Task.Status.FAIL) && h.goal == pt.goal()) {
            h.goal = null;
            if (s == Task.Status.SUCCESS) { h.remember("I finished: " + pt.goal().title); h.express(Expression.HAPPY, 60, plugin, now); }
        }
        if (t instanceof Tasks.Goto g && g.label().equals("swimming to shore")) h.swimFails = s == Task.Status.FAIL ? h.swimFails + 1 : 0;
        h.task = null;
        h.commitUntil = 0;
        h.lastOutcome = (s == Task.Status.SUCCESS ? "" : "FAILED: ") + t.outcome();
        h.event(h.lastOutcome);
        if (s == Task.Status.FAIL) {
            h.failedOptions.put(h.lastOptionId, now);
            String o = t.outcome();
            boolean navFail = o.contains("couldn't reach") || o.contains("couldn't find a way") || o.contains("got stuck") || o.contains("couldn't get to");
            if (navFail) { h.navFails.addLast(now); while (!h.navFails.isEmpty() && now - h.navFails.peekFirst() > 20 * 90) h.navFails.removeFirst(); }
            if (navFail && h.navFails.size() >= 3 && !(t instanceof dev.timur.humans.tasks.Life.Escape) && h.home != null && h.home.getWorld() == h.loc().getWorld()
                && h.home.distance(h.loc()) > 6 && !Navigator.reachable(h.loc(), h.home, 3)) {
                h.navFails.clear();
                h.trapped = true; h.trappedOpenSky = true;
                h.event("I can't get anywhere from here - I'm stuck in a pit");
                h.task = new dev.timur.humans.tasks.Life.Escape(h.home.clone());
                h.taskStartTick = now;
                h.needsThink = false;
                if (plugin.debug()) plugin.getLogger().info("[debug] " + h.name + " is stuck in a pit -> digging out towards home");
                return;
            }
            if (t.age() < 40) {
                h.offlineNext = true;
                if (++h.quickFails >= 5) unstuck(h);
                else { h.task = new Tasks.Idle(25, "thinking it over"); h.taskStartTick = now; } // don't spin
            }
        } else {
            h.quickFails = 0;
            if (t.isWork() && t.age() > 100) { // a little breather after real work, like a person would
                int pause = 30 + rnd.nextInt(50) + (h.energy < 8 ? 60 : 0);
                h.task = new Tasks.Idle(pause, h.energy < 8 ? "catching their breath" : "taking a breather");
                h.taskStartTick = now;
                h.thinkNotBefore = now + pause - 10;
            }
        }
        if (plugin.debug()) plugin.getLogger().info("[debug] " + h.name + " done after " + t.age() / 20 + "s: " + h.lastOutcome);
        h.needsThink = true;
        Act.hold(h, Items.best(h.inv, "_PICKAXE").orElse(Material.AIR));
        String out = t.outcome();
        if (s == Task.Status.SUCCESS) {
            if (out.contains("FINISHED building")) { h.remember("I built my own house!"); h.express(Expression.HAPPY, 120, plugin, now); plugin.chat().speakUp(h, "You just finished building your own house!"); }
            else if (out.startsWith("crafted") && (out.contains("pickaxe") || out.contains("sword"))) h.express(Expression.HAPPY, 60, plugin, now);
            else if (out.startsWith("killed") || out.startsWith("defeated")) h.express(Expression.HAPPY, 40, plugin, now);
            else if (out.startsWith("got") && out.contains("diamond")) { h.express(Expression.SURPRISED, 60, plugin, now); h.remember("found diamonds!"); }
        } else if (rnd.nextDouble() < 0.5) h.express(Expression.SAD, 30, plugin, now);
    }

    /** Last resort when stuck in a hole/thicket: hop to the nearest open ground (like a player digging out). */
    private void unstuck(Human h) {
        h.quickFails = 0;
        Location land = Act.nearestLand(h.loc().add(0, 1, 0), 8);
        if (land == null || land.distance(h.loc()) < 1) return;
        h.entity.getWorld().spawnParticle(Particle.CLOUD, h.loc().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        h.entity.teleport(land);
        h.event("got stuck and climbed out");
        if (plugin.debug()) plugin.getLogger().info("[debug] " + h.name + " was stuck -> moved to " + land.getBlockX() + "," + land.getBlockY() + "," + land.getBlockZ());
    }

    /** Interrupt: something happened that deserves a new decision right away. */
    public void interrupt(Human h, String what) {
        h.event(what);
        h.thinkNotBefore = 0;
        h.needsThink = true;
        h.urgent = true;
        h.lastThinkTick = Math.min(h.lastThinkTick, now - 1000); // jump the queue
    }

    /** Can the human currently see what it was exploring for? */
    public boolean sees(Human h, String goal) {
        var s = dev.timur.humans.brain.OptionGenerator.scan(h, this);
        return switch (goal) { case "trees" -> s.tree != null; case "stone" -> s.stone != null || !s.ores.isEmpty(); default -> false; };
    }

    // ---------------------------------------------------------------- reflexes (no AI)

    public boolean mayPickUp(Human h, Item it) {
        UUID thrownBy = it.getThrower();
        if (plugin.zones().isProtected(it.getLocation(), h) && (thrownBy == null || Bukkit.getPlayer(thrownBy) == null)) return false; // players' stuff in their base
        UUID owner = it.getOwner();
        if (owner != null && !owner.equals(h.entity.getUniqueId())) return false;
        UUID thrower = it.getThrower();
        return thrower == null || !thrower.equals(h.entity.getUniqueId()) || it.getTicksLived() > 20 * 30;
    }

    private void pickupReflex(Human h) {
        for (Entity e : h.entity.getNearbyEntities(1.6, 1.2, 1.6)) {
            if (!(e instanceof Item it) || it.getPickupDelay() > 0 || !mayPickUp(h, it)) continue;
            ItemStack stack = it.getItemStack();
            UUID thrower = it.getThrower();
            Player giver = thrower == null ? null : Bukkit.getPlayer(thrower);
            h.inv.add(stack);
            it.remove();
            h.entity.getWorld().playSound(h.loc(), "entity.item.pickup", 0.3f, 1.4f + rnd.nextFloat() * 0.4f);
            if (giver != null && giver.getLocation().distance(h.loc()) < 12) receivedGift(h, giver, stack);
        }
    }

    public void receivedGift(Human h, Player giver, ItemStack stack) {
        String what = stack.getAmount() + " " + Items.nice(stack.getType());
        double value = Items.isFood(stack.getType()) ? 0.35 : stack.getType() == Material.DIAMOND ? 1.0 : stack.getType().name().contains("IRON") ? 0.5 : 0.2;
        h.addTrust(giver, value);
        h.remember(giver.getName() + " gave me " + what);
        h.express(Expression.HAPPY, 80, plugin, now);
        plugin.chat().thank(h, giver, what);
    }

    /** Like a player: when hungry, just eat something from the inventory (even in the middle of a job). */
    private void eatReflex(Human h) {
        if (h.hunger >= 9 || h.task instanceof Tasks.Eat || h.sleeping) {
            return;
        }
        Material f = Items.bestFood(h.inv).orElse(null);
        if (f == null) {
            if (h.hunger < 5 && now % 1200 == 0) h.event("I'm starving and have no food!");
            return;
        }
        h.inv.remove(f, 1);
        h.hunger = Math.min(20, h.hunger + Items.foodValue(f) * 1.5);
        h.entity.getWorld().playSound(h.loc(), "entity.generic.eat", 0.7f, 1f);
        h.entity.getWorld().playSound(h.loc(), "entity.player.burp", 0.5f, 1f);
        h.event("ate " + Items.nice(f) + " on the go");
    }

    /** Players hold space in water; so do humans. Low on air -> head for land. */
    private void swimReflex(Human h) {
        org.bukkit.block.Block feet = h.loc().getBlock();
        boolean inWater = Act.wet(feet) || Act.wet(feet.getRelative(org.bukkit.block.BlockFace.UP));
        if (!inWater) return;
        org.bukkit.util.Vector v = h.entity.getVelocity();
        if (!h.nav.active() || Act.wet(feet.getRelative(org.bukkit.block.BlockFace.UP))) h.entity.setVelocity(v.setY(Math.max(v.getY(), 0.09)));
        boolean goingToLand = h.task instanceof Tasks.Goto g && g.label().equals("swimming to shore");
        if (!goingToLand && h.swimFails >= 2) { h.swimFails = 0; unstuck(h); return; }
        if (!goingToLand && (h.task == null || h.entity.getRemainingAir() < h.entity.getMaximumAir() * 0.6 || now % 100 == 0 && !h.nav.active())) {
            Location land = Act.nearestLand(h.loc(), 16);
            if (land != null) {
                if (h.task != null) h.task.stop(h);
                h.task = new Tasks.Goto(land, 0.8, false, "swimming to shore");
                h.taskStartTick = now;
                if (h.entity.getRemainingAir() < h.entity.getMaximumAir() * 0.4) h.express(Expression.SCARED, 40, plugin, now);
            }
        }
    }

    private void dangerReflex(Human h) {
        if (h.task instanceof Tasks.Flee) return;
        for (Entity e : h.entity.getNearbyEntities(4, 3, 4)) {
            if (e instanceof Creeper c && (c.isIgnited() || c.getFuseTicks() > 0 || c.getLocation().distance(h.loc()) < 3)) {
                if (h.task != null) h.task.stop(h);
                h.task = new Tasks.Flee(c);
                h.taskStartTick = now;
                h.express(Expression.SCARED, 60, plugin, now);
                h.event("a creeper almost blew me up!");
                return;
            }
        }
        if (h.entity.getFireTicks() > 0 && !(h.task instanceof Tasks.Flee)) {
            h.express(Expression.SCARED, 30, plugin, now);
            // burning and hurt: jump into nearby water like a player would
            boolean goingToWater = h.task instanceof Tasks.Goto g && g.label().startsWith("jumping into water");
            if (h.entity.getHealth() < 12 && !goingToWater && !Act.wet(h.loc().getBlock())) {
                org.bukkit.block.Block water = nearestWater(h.loc(), 10);
                if (water != null) {
                    if (h.task != null) h.task.stop(h);
                    h.task = new Tasks.Goto(water.getLocation().add(0.5, 0, 0.5), 0.6, true, "jumping into water to put out the flames");
                    h.taskStartTick = now;
                    h.event("I'm on fire!");
                }
            }
        }
    }

    private static org.bukkit.block.Block nearestWater(Location c, int r) {
        org.bukkit.block.Block best = null; double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -3; dy <= 2; dy++) {
            org.bukkit.block.Block b = c.getWorld().getBlockAt(c.getBlockX() + dx, c.getBlockY() + dy, c.getBlockZ() + dz);
            if (b.getType() != Material.WATER || !b.getRelative(0, 1, 0).getType().isAir()) continue;
            double d = dx * dx + dz * dz + dy * dy;
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    // ---------------------------------------------------------------- events from listener

    public void onHurt(Human h, Entity attacker, double dmg) {
        if (h.sleeping && h.task != null) { h.task.stop(h); h.task = null; }
        h.express(Expression.HURT, 8, plugin, now);
        if (attacker instanceof Player p) {
            h.addTrust(p, -0.6);
            h.lastAttacker = p.getUniqueId(); h.lastAttackedTick = now;
            interrupt(h, p.getName() + " HIT me");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!h.valid()) return;
                h.setMood(h.personality.bravery() > 0.45 && h.entity.getHealth() > 7 ? Expression.ANGRY : Expression.SCARED, plugin, now);
            }, 9);
            if (h.trustOf(p.getUniqueId()) < 1) h.remember(p.getName() + " keeps attacking me");
        } else if (attacker != null && byEntity(attacker) == null) {
            h.lastAttacker = attacker.getUniqueId(); h.lastAttackedTick = now;
            if (!(h.task instanceof Tasks.Attack)) interrupt(h, "a " + nameOf(attacker) + " attacked me");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (h.valid() && h.entity.getHealth() < 8) h.express(Expression.SCARED, 60, plugin, now);
            }, 9);
        }
    }

    public void onDeath(Human h, Entity killer, List<ItemStack> drops) {
        drops.clear();
        Location at = h.loc();
        for (var e : h.inv.view().entrySet()) {
            int n = e.getValue();
            while (n > 0) { int s = Math.min(n, e.getKey().getMaxStackSize()); drops.add(new ItemStack(e.getKey(), s)); n -= s; }
        }
        h.inv.clear();
        if (h.task != null) { h.task.stop(h); h.task = null; }
        String by = killer == null ? "" : " (killed by " + nameOf(killer) + ")";
        h.remember("I died" + by + " and lost all my stuff");
        if (killer instanceof Player p) { h.addTrust(p, -1.5); }
        h.dead = true;
        h.respawnAtMs = System.currentTimeMillis() + 45_000;
        h.mood = Expression.SAD;
        Component msg = Component.text("☠ " + h.name + " died" + by, NamedTextColor.RED);
        for (Player p : at.getWorld().getPlayers()) if (p.getLocation().distance(at) < 64) p.sendMessage(msg);
        for (Human o : humans.values()) {
            if (o == h || !o.valid() || o.loc().getWorld() != at.getWorld() || o.loc().distance(at) > 32) continue;
            o.setMood(Expression.SAD, plugin, now);
            o.remember(h.name + " died" + by);
            interrupt(o, h.name + " just DIED" + by);
        }
        byEntity.remove(h.entity.getUniqueId());
        h.entity = null;
        save(h);
    }

    public void onPlayerAttacked(Player victim, Entity attacker) {
        for (Human h : humans.values()) {
            if (!h.valid() || h.loc().getWorld() != victim.getWorld() || h.loc().distance(victim.getLocation()) > 24) continue;
            if (h.trustOf(victim.getUniqueId()) < 2 || h.task instanceof Tasks.Attack) continue;
            if (h.helpTarget != null && h.helpTarget.equals(victim.getUniqueId()) && now - h.helpTick < 100) continue;
            h.helpTarget = victim.getUniqueId(); h.helpTick = now;
            interrupt(h, victim.getName() + " is being attacked by a " + nameOf(attacker));
        }
    }

    // ---------------------------------------------------------------- persistence

    public void loadAll() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) {
            try {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                UUID id = UUID.fromString(y.getString("id"));
                Human h = new Human(id, y.getString("name"), Look.byId(y.getString("look")),
                    Personality.deserialize(y.getConfigurationSection("personality") == null ? null : y.getConfigurationSection("personality").getValues(false), rnd));
                h.home = y.getLocation("home");
                h.hutBuilt = y.getBoolean("hut_built");
                h.hutCenter = y.getLocation("hut_center");
                h.hunger = y.getDouble("hunger", 20);
                h.energy = y.getDouble("energy", 20);
                h.village = y.getLocation("village");
                h.bed = y.getLocation("bed");
                h.growth = y.getDouble("growth", 1.0);
                h.parents.addAll(y.getStringList("parents"));
                if (y.isString("goal.text")) {
                    String fid = y.getString("goal.from_id");
                    h.goal = new dev.timur.humans.plan.Goal(y.getString("goal.text"), y.getString("goal.from"), fid == null ? null : UUID.fromString(fid), y.getString("goal.group", ""), 0);
                    h.goal.title = y.getString("goal.title", h.goal.title);
                    h.goal.note("(the server restarted - check where you are and continue)");
                }
                h.project = y.getString("project");
                h.dead = y.getBoolean("dead");
                if (h.dead) h.respawnAtMs = System.currentTimeMillis() + 10_000;
                h.mood = Expression.parse(y.getString("mood"), Expression.NEUTRAL);
                ConfigurationSection inv = y.getConfigurationSection("inventory");
                if (inv != null) h.inv.deserialize(inv.getValues(false));
                ConfigurationSection tr = y.getConfigurationSection("trust");
                if (tr != null) for (String k : tr.getKeys(false)) h.trust.put(UUID.fromString(k), tr.getDouble(k));
                ConfigurationSection nm = y.getConfigurationSection("names");
                if (nm != null) for (String k : nm.getKeys(false)) h.knownNames.put(UUID.fromString(k), nm.getString(k));
                for (String s : y.getStringList("memories")) h.memories.addLast(s);
                for (String s : y.getStringList("recent")) h.recent.addLast(s);
                humans.put(id, h);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not load " + f.getName() + ": " + e);
            }
        }
        // link bodies already loaded
        for (World w : Bukkit.getWorlds()) for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) onEntityAdded(m);
        plugin.getLogger().info("Loaded " + humans.size() + " humans.");
    }

    public void saveAll() { for (Human h : humans.values()) save(h); }

    public void save(Human h) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", h.id.toString()); y.set("name", h.name); y.set("look", h.look.id());
        y.createSection("personality", h.personality.serialize());
        y.set("home", h.home); y.set("hut_built", h.hutBuilt); y.set("hut_center", h.hutCenter);
        y.set("hunger", h.hunger); y.set("dead", h.dead); y.set("mood", h.mood.id());
        y.set("energy", h.energy); y.set("project", h.project);
        y.set("village", h.village); y.set("bed", h.bed); y.set("growth", h.growth); y.set("parents", new ArrayList<>(h.parents));
        if (h.goal != null && !h.goal.done) {
            y.set("goal.text", h.goal.text); y.set("goal.title", h.goal.title); y.set("goal.from", h.goal.from);
            y.set("goal.from_id", h.goal.fromId == null ? null : h.goal.fromId.toString()); y.set("goal.group", h.goal.group);
        }
        y.createSection("inventory", h.inv.serialize());
        Map<String, Object> tr = new LinkedHashMap<>(); h.trust.forEach((k, v) -> tr.put(k.toString(), v)); y.createSection("trust", tr);
        Map<String, Object> nm = new LinkedHashMap<>(); h.knownNames.forEach((k, v) -> nm.put(k.toString(), v)); y.createSection("names", nm);
        y.set("memories", new ArrayList<>(h.memories)); y.set("recent", new ArrayList<>(h.recent));
        try { y.save(new File(dir, h.id + ".yml")); } catch (Exception e) { plugin.getLogger().warning("Could not save " + h.name + ": " + e); }
    }
}
