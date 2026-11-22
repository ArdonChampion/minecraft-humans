package dev.timur.humans.tasks;

import dev.timur.humans.Human;
import dev.timur.humans.Items;
import dev.timur.humans.Navigator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Low-level physical actions shared by tasks. */
public final class Act {
    private Act() {}

    public static final double REACH = 4.8;

    public static Location eye(Human h) { return h.entity.getLocation().add(0, 1.62, 0); }

    public static boolean inReach(Human h, Block b) {
        return eye(h).distance(b.getLocation().add(0.5, 0.5, 0.5)) <= REACH;
    }

    public static void lookAt(Human h, Location l) {
        h.entity.lookAt(l.getX(), l.getY(), l.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        h.entity.setBodyYaw(h.entity.getLocation().getYaw());
    }

    public static void hold(Human h, Material m) {
        ItemStack cur = h.entity.getEquipment().getItemInMainHand();
        if (m == null || m == Material.AIR) { if (!cur.getType().isAir()) h.entity.getEquipment().setItemInMainHand(null); return; }
        if (cur.getType() != m) h.entity.getEquipment().setItemInMainHand(new ItemStack(m));
    }

    public static void swing(Human h) { h.entity.swingMainHand(); }

    public static void sound(Location l, String key, float vol, float pitch) { l.getWorld().playSound(l, key, vol, pitch); }

    public static void crack(Block b, float progress, int sourceId) {
        for (Player p : b.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) p.sendBlockDamage(b.getLocation(), progress, sourceId);
    }

    /** Ticks needed to break a block with the human's best tool. */
    public static int breakTicks(Human h, Block b) {
        float hard = b.getType().getHardness();
        if (hard < 0) return Integer.MAX_VALUE;
        double speed = 1;
        Material tool = toolFor(h, b);
        if (tool != null) speed = switch (Items.tierOf(tool)) { case WOOD -> 2; case STONE -> 4; case IRON -> 6; case DIAMOND -> 8; default -> 1; };
        return Math.max(6, (int) Math.ceil(hard * 45 / speed));
    }

    public static Material toolFor(Human h, Block b) {
        Material t = b.getType();
        String suffix = org.bukkit.Tag.MINEABLE_PICKAXE.isTagged(t) ? "_PICKAXE"
            : org.bukkit.Tag.MINEABLE_AXE.isTagged(t) ? "_AXE"
            : org.bukkit.Tag.MINEABLE_SHOVEL.isTagged(t) ? "_SHOVEL" : null;
        return suffix == null ? null : Items.best(h.inv, suffix).orElse(null);
    }

    /** Breaks the block like a player would (drops fall to the ground; the pickup reflex collects them). */
    public static void breakBlock(Human h, Block b) {
        Material tool = toolFor(h, b);
        b.breakNaturally(tool == null ? new ItemStack(Material.AIR) : new ItemStack(tool), true);
        crack(b, 0f, h.entity.getEntityId());
    }

    public static void attack(Human h, LivingEntity target) {
        Material weapon = Items.weaponItem(h.inv);
        hold(h, weapon);
        lookAt(h, target.getLocation().add(0, target.getHeight() * 0.7, 0));
        swing(h);
        DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(h.entity).withDirectEntity(h.entity).build();
        target.damage(Items.weaponDamage(h.inv), src);
        Vector kb = target.getLocation().toVector().subtract(h.entity.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 0.001) target.setVelocity(target.getVelocity().add(kb.normalize().multiply(0.35).setY(0.22)));
        sound(h.entity.getLocation(), "entity.player.attack.strong", 0.8f, 1f);
    }

    /** Find a free floor spot next to the human to put a temporary block (crafting table, furnace). */
    public static Block freeSpotNear(Human h) {
        Block feet = h.entity.getLocation().getBlock();
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
            BlockFace.NORTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST, BlockFace.NORTH_WEST}) {
            Block b = feet.getRelative(f);
            if (b.getType().isAir() && b.getRelative(BlockFace.DOWN).getType().isSolid() && b.getRelative(BlockFace.UP).getType().isAir()) return b;
        }
        return null;
    }

    public static boolean place(Human h, Block b, Material m) {
        if (!b.getType().isAir() && !b.isReplaceable()) return false;
        for (Entity e : b.getWorld().getNearbyEntities(b.getBoundingBox().expand(0.05)))
            if (e instanceof LivingEntity) return false;
        lookAt(h, b.getLocation().add(0.5, 0.5, 0.5));
        hold(h, m);
        swing(h);
        b.setType(m);
        b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getPlaceSound(), 1f, 0.9f);
        return true;
    }

    public static boolean wet(org.bukkit.block.Block b) {
        return b.getType() == Material.WATER || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged();
    }

    /** Closest standable, dry spot within r blocks (spiral search). */
    public static Location nearestLand(Location around, int r) {
        var w = around.getWorld();
        int x0 = around.getBlockX(), y0 = around.getBlockY(), z0 = around.getBlockZ();
        for (int d = 0; d <= r; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    if (!w.isChunkLoaded((x0 + dx) >> 4, (z0 + dz) >> 4)) continue;
                    for (int dy = 3; dy >= -4; dy--) {
                        int x = x0 + dx, y = y0 + dy, z = z0 + dz;
                        if (Navigator.standable(w, x, y, z) && !wet(w.getBlockAt(x, y, z)) && !wet(w.getBlockAt(x, y - 1, z)))
                            return new Location(w, x + 0.5, y, z + 0.5);
                    }
                }
        return null;
    }

    /** A nearby standable spot (searching around a column), used for wandering/fleeing. */
    public static Location surfaceSpot(Location around) {
        var w = around.getWorld();
        int x = around.getBlockX(), z = around.getBlockZ();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y0 = around.getBlockY();
        for (int dy = 4; dy >= -6; dy--) {
            if (Navigator.standable(w, x, y0 + dy, z) && !wet(w.getBlockAt(x, y0 + dy, z))) return new Location(w, x + 0.5, y0 + dy, z + 0.5);
        }
        return null;
    }
}
