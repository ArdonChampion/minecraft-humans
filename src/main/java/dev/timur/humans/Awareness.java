package dev.timur.humans;

import dev.timur.humans.brain.Lines;
import dev.timur.humans.tasks.Act;
import dev.timur.humans.tasks.Tasks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

/** What a human notices about its situation without any AI: being trapped, fire, its house burning. */
public final class Awareness {
    private Awareness() {}

    /** True when the human can't walk more than a few blocks away (in a hole, walled in). */
    public static boolean trapped(Human h) {
        Location l = h.loc();
        World w = l.getWorld();
        if (Act.wet(l.getBlock())) return false;
        int[] s = Navigator.standSpot(l);
        if (s == null) s = new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()};
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(s); seen.add(key(s[0], s[1], s[2]));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && seen.size() < 350) {
            int[] n = q.poll();
            if (Math.abs(n[0] - s[0]) + Math.abs(n[2] - s[2]) >= 6) return false;
            for (int[] d : dirs) {
                int nx = n[0] + d[0], nz = n[2] + d[1];
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    int ny = n[1] + dy;
                    if (dy == 1 && !Navigator.passableNav(w.getBlockAt(n[0], n[1] + 2, n[2]))) continue;
                    if (dy < 0 && !(Navigator.passableNav(w.getBlockAt(nx, n[1], nz)) && Navigator.passableNav(w.getBlockAt(nx, n[1] + 1, nz)))) break;
                    if (!Navigator.standableNav(w, nx, ny, nz)) continue;
                    if (seen.add(key(nx, ny, nz))) q.add(new int[]{nx, ny, nz});
                    break;
                }
            }
        }
        return seen.size() < 350; // explored everything reachable and it's tiny
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }

    /** Burning fire blocks around a spot (ignores fireplaces on netherrack etc.). */
    public static List<Block> fires(Location c, int r, int down, int up) {
        List<Block> out = new ArrayList<>();
        World w = c.getWorld();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (!w.isChunkLoaded((cx + dx) >> 4, (cz + dz) >> 4)) continue;
                for (int dy = -down; dy <= up; dy++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material t = b.getType();
                    if (t != Material.FIRE && t != Material.SOUL_FIRE) continue;
                    Material below = b.getRelative(0, -1, 0).getType();
                    if (below == Material.NETHERRACK || below == Material.SOUL_SAND || below == Material.SOUL_SOIL || below == Material.MAGMA_BLOCK || below == Material.BEDROCK) continue;
                    out.add(b);
                    if (out.size() >= 60) return out;
                }
            }
        out.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(c)));
        return out;
    }

    public static boolean homeOnFire(Human h) {
        Location c = h.hutCenter != null ? h.hutCenter : null;
        if (c == null || c.getWorld() != h.loc().getWorld() || !c.getWorld().isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) return false;
        long now = HumansPlugin.get().manager().now();
        return fires(c, 5, 1, 4).stream().anyMatch(b -> !h.isBad(b, now));
    }

    /** Called every ~2s per awake human. */
    public static void update(Human h, HumanManager m, HumansPlugin plugin) {
        if (h.sleeping) return;
        long now = m.now();
        boolean tr = h.task instanceof dev.timur.humans.tasks.Life.Escape ? h.trapped : trapped(h);
        if (tr && !h.trapped) {
            h.trapped = true; h.trappedSince = now;
            Location l = h.loc();
            h.trappedOpenSky = l.getWorld().getHighestBlockYAt(l) < l.getBlockY();
            h.express(Expression.SURPRISED, 60, plugin, now);
            if (m.nearestPlayer(h, 24) != null && m.rnd().nextDouble() < 0.7) plugin.chat().broadcast(h, Lines.pick(Lines.TRAPPED, h, null, null));
            m.interrupt(h, h.trappedOpenSky ? "I'm TRAPPED in a hole and can't walk out" : "I'm TRAPPED, walled in and can't walk out");
        } else if (!tr && h.trapped) {
            h.trapped = false;
            h.event("got out, free to move again");
        }
        List<Block> f = fires(h.loc(), 10, 3, 6);
        f.removeIf(b -> h.isBad(b, now)); // flames we already know we can't reach
        boolean home = homeOnFire(h) && (h.hutCenter.distance(h.loc()) < 40 || h.alertTick > now - 20 * 120);
        int prev = h.fireNear; boolean prevHome = h.fireAtHome;
        h.fireNear = f.size(); h.fireAtHome = home;
        if (home && !prevHome) {
            h.express(Expression.SCARED, 80, plugin, now);
            h.remember("my house caught fire!");
            if (m.nearestPlayer(h, 32) != null) plugin.chat().broadcast(h, Lines.pick(Lines.FIRE, h, null, null));
            m.interrupt(h, "MY HOUSE IS ON FIRE!");
        } else if (!f.isEmpty() && prev == 0) {
            h.express(Expression.SURPRISED, 40, plugin, now);
            m.interrupt(h, "there is fire burning " + Math.round(f.getFirst().getLocation().distance(h.loc())) + " blocks away");
        }
    }
}
