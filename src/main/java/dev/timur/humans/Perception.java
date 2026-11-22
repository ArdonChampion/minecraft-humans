package dev.timur.humans;

import dev.timur.humans.tasks.Act;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * What a human notices around it, in words for the AI: what nearby players hold, look at and stand on,
 * items lying on the ground, and notable blocks it can actually see. Lets players say "this", "that", "here"
 * or "what I dropped" and be understood.
 */
public final class Perception {
    private Perception() {}

    public static String describe(Human h, HumanManager m) {
        if (!h.valid()) return "";
        StringBuilder s = new StringBuilder();
        Location l = h.loc();
        Location eye = h.entity.getEyeLocation();
        World w = l.getWorld();

        List<String> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            double d = p.getLocation().distance(l);
            if (d > 40) continue;
            StringBuilder ps = new StringBuilder("player " + p.getName() + " at " + xyz(p.getLocation()) + " (" + Math.round(d) + "m away");
            ps.append(h.entity.hasLineOfSight(p) ? ", in view" : ", out of sight").append(")");
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) ps.append(", holding ").append(hand.getAmount() > 1 ? hand.getAmount() + " " : "").append(Items.nice(hand.getType()));
            Entity te = null;
            try { te = p.getTargetEntity(16); } catch (Throwable ignored) {}
            if (te != null) {
                Human o = m.byEntity(te);
                ps.append(", looking at ").append(o == h ? "YOU" : o != null ? "human " + o.name : te instanceof Player tp ? "player " + tp.getName() : HumanManager.nameOf(te));
            } else {
                Block tb = p.getTargetBlockExact(32);
                if (tb != null && !tb.getType().isAir())
                    ps.append(", looking at ").append(Items.nice(tb.getType())).append(" at ").append(xyz(tb.getLocation()))
                        .append(Act.visibleFrom(eye, tb) ? " (you can see it)" : " (you can't see it from here)");
            }
            Block under = p.getLocation().getBlock().getRelative(0, -1, 0);
            if (!under.getType().isAir()) ps.append(", standing on ").append(Items.nice(under.getType()));
            players.add(ps.toString());
        }
        if (!players.isEmpty()) s.append("PLAYERS AROUND YOU:\n- ").append(String.join("\n- ", players)).append("\n");

        // items on the ground: count per type, nearest position
        Map<Material, int[]> items = new LinkedHashMap<>();
        Map<Material, Location> where = new HashMap<>();
        for (Entity e : h.entity.getNearbyEntities(14, 8, 14)) {
            if (!(e instanceof Item it)) continue;
            Material t = it.getItemStack().getType();
            items.computeIfAbsent(t, k -> new int[1])[0] += it.getItemStack().getAmount();
            Location cur = where.get(t);
            if (cur == null || cur.distanceSquared(l) > e.getLocation().distanceSquared(l)) where.put(t, e.getLocation());
        }
        if (!items.isEmpty()) {
            List<String> li = new ArrayList<>();
            items.forEach((t, c) -> { if (li.size() < 10) li.add(c[0] + " " + Items.nice(t) + " at " + xyz(where.get(t))); });
            s.append("ITEMS LYING ON THE GROUND NEAR YOU: ").append(String.join(", ", li)).append(" (collect_items picks them up)\n");
        }

        // notable blocks you can see (nearest of each kind)
        Map<String, Block> seen = new LinkedHashMap<>();
        Map<String, Double> dist = new HashMap<>();
        int r = 10, bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ(), checks = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!w.isChunkLoaded((bx + dx) >> 4, (bz + dz) >> 4)) continue;
            for (int dy = -5; dy <= 6; dy++) {
                Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                String kind = notable(b.getType());
                if (kind == null) continue;
                double d = dx * dx + dy * dy + dz * dz;
                if (d >= dist.getOrDefault(kind, 1e9) || checks > 60) continue;
                checks++;
                if (!Act.visibleFrom(eye, b)) continue;
                seen.put(kind, b); dist.put(kind, d);
            }
        }
        if (!seen.isEmpty()) {
            List<String> bl = new ArrayList<>();
            seen.forEach((k, b) -> bl.add(k + " at " + xyz(b.getLocation())));
            s.append("THINGS YOU CAN SEE NEARBY: ").append(String.join(", ", bl)).append("\n");
        }
        return s.toString();
    }

    private static String notable(Material t) {
        String n = t.name();
        if (t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL) return "chest";
        if (n.endsWith("SHULKER_BOX")) return "shulker box";
        if (t == Material.CRAFTING_TABLE) return "crafting table";
        if (t == Material.FURNACE || t == Material.BLAST_FURNACE || t == Material.SMOKER) return Items.nice(t);
        if (Tag.BEDS.isTagged(t)) return "bed";
        if (Tag.DOORS.isTagged(t)) return "door";
        if (t == Material.ANVIL || t == Material.ENCHANTING_TABLE || t == Material.BREWING_STAND || t == Material.CAMPFIRE) return Items.nice(t);
        if (n.endsWith("_ORE")) return Items.nice(t).replace("deepslate ", "");
        if (t == Material.WATER || t == Material.LAVA) return null;
        if (n.endsWith("_SAPLING") || t == Material.WHEAT || t == Material.CARROTS || t == Material.POTATOES) return Items.nice(t);
        return null;
    }

    public static String xyz(Location l) { return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }
}
