package dev.timur.humans;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.*;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Where humans must not break or build: players' bases.
 * - manual zones (/humans protect <radius>, /humans unprotect)
 * - automatic: around player storage/beds/furnaces (block entities), unless it's the human's own village/house
 */
public final class Zones {
    public record Zone(String world, int x, int z, int r, String name) {
        boolean contains(Location l) { return l.getWorld().getName().equals(world) && sq(l.getBlockX() - x) + sq(l.getBlockZ() - z) <= r * r; }
    }
    private static int sq(int v) { return v * v; }

    private final HumansPlugin plugin;
    private final File file;
    private final List<Zone> manual = new ArrayList<>();
    private final Map<Long, long[]> markerCache = new HashMap<>(); // chunk key -> [expiry, x1, z1, x2, z2, ...]

    public Zones(HumansPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "protected.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("zones")) {
            try {
                manual.add(new Zone(String.valueOf(m.get("world")), ((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue(),
                    ((Number) m.get("r")).intValue(), String.valueOf(m.get("name"))));
            } catch (Exception ignored) {}
        }
    }

    public List<Zone> manual() { return manual; }

    public void add(Location c, int r, String name) { manual.add(new Zone(c.getWorld().getName(), c.getBlockX(), c.getBlockZ(), r, name)); save(); }

    public int removeAt(Location l) {
        int before = manual.size();
        manual.removeIf(z -> z.contains(l));
        save();
        return before - manual.size();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Zone z : manual) list.add(Map.of("world", z.world(), "x", z.x(), "z", z.z(), "r", z.r(), "name", z.name()));
        y.set("zones", list);
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("Could not save zones: " + e.getMessage()); }
    }

    /** Is this spot inside the human's own house/village (where they may build and dig freely)? */
    public static boolean ownArea(Human h, Location l) {
        if (h == null) return false;
        if (h.village != null && h.village.getWorld() == l.getWorld() && h.village.distanceSquared(l) < 18 * 18) return true;
        return h.hutCenter != null && h.hutCenter.getWorld() == l.getWorld() && h.hutCenter.distanceSquared(l) < 7 * 7;
    }

    /** Players' stuff: humans must not break, build or pick things up here. */
    public boolean isProtected(Location l, Human h) {
        for (Zone z : manual) if (z.contains(l)) return true;
        int radius = plugin.getConfig().getInt("zones.auto_radius", 9);
        World w = l.getWorld();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        long now = System.currentTimeMillis();
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            if (!w.isChunkLoaded(x, z)) continue;
            long k = ((long) x << 32) ^ (z & 0xffffffffL) ^ ((long) w.getName().hashCode() << 52);
            long[] m = markerCache.get(k);
            if (m == null || m[0] < now) { m = scan(w, x, z, now); markerCache.put(k, m); }
            for (int i = 1; i + 1 < m.length; i += 2) {
                if (sq((int) m[i] - l.getBlockX()) + sq((int) m[i + 1] - l.getBlockZ()) > radius * radius) continue;
                if (h != null && ownArea(h, new Location(w, m[i], l.getY(), m[i + 1]))) continue;
                return true;
            }
        }
        if (markerCache.size() > 4000) markerCache.clear();
        return false;
    }

    public boolean isProtected(Block b, Human h) { return isProtected(b.getLocation(), h); }

    private static long[] scan(World w, int cx, int cz, long now) {
        List<Long> xs = new ArrayList<>();
        for (BlockState st : w.getChunkAt(cx, cz).getTileEntities()) {
            if (st instanceof Container || st instanceof Bed || st instanceof Sign || st instanceof EnchantingTable
                || st instanceof Lectern || st instanceof Jukebox || st instanceof Beacon || st instanceof DecoratedPot || st instanceof Banner) {
                xs.add((long) st.getX()); xs.add((long) st.getZ());
            }
        }
        long[] out = new long[1 + xs.size()];
        out[0] = now + 60_000;
        for (int i = 0; i < xs.size(); i++) out[i + 1] = xs.get(i);
        return out;
    }

    /** Short text for the AI: protected areas near a spot. */
    public String describeNear(Location l, Human h) {
        List<String> out = new ArrayList<>();
        for (Zone z : manual) if (z.world().equals(l.getWorld().getName()) && Math.hypot(z.x() - l.getX(), z.z() - l.getZ()) < 80)
            out.add(z.name() + " around x=" + z.x() + " z=" + z.z() + " (radius " + z.r() + ")");
        int found = 0;
        for (int dx = -48; dx <= 48 && found < 4; dx += 8) for (int dz = -48; dz <= 48 && found < 4; dz += 8) {
            Location p = l.clone().add(dx, 0, dz);
            if (!ownArea(h, p) && isProtected(p, h) && out.stream().noneMatch(s -> s.contains("x=" + (p.getBlockX() / 16 * 16)))) {
                out.add("a player's base near x=" + (p.getBlockX() / 16 * 16) + " z=" + (p.getBlockZ() / 16 * 16)); found++;
            }
        }
        return out.isEmpty() ? "none nearby" : String.join("; ", out);
    }
}
