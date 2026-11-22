package dev.timur.humans;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

import java.util.*;

/** Block-grid A* pathfinding + velocity-based walking (real physics, jumps, swimming, doors). */
public final class Navigator {
    public enum Result { MOVING, ARRIVED, FAILED }

    private final Human h;
    private List<int[]> path = List.of();
    private int idx;
    private Location target;
    private double within;
    private boolean sprint;
    private int stuck, repaths;
    private Location lastPos;
    private Block openedDoor;
    private int leafCooldown;
    private int sinceCheck; private double checkDist = Double.MAX_VALUE;

    Navigator(Human h) { this.h = h; }

    public boolean active() { return target != null; }

    public void stop() {
        target = null; path = List.of();
        closeDoor();
    }

    /** Start walking to target (stop when within `within` blocks). Returns false if no path. */
    public boolean moveTo(Location dest, double within, boolean sprint) {
        this.within = within; this.sprint = sprint; this.repaths = 0;
        this.sinceCheck = 0; this.checkDist = Double.MAX_VALUE;
        this.target = dest.clone();
        return plan();
    }

    private boolean plan() {
        Mannequin e = h.entity;
        if (e == null || target == null || target.getWorld() != e.getWorld()) return false;
        List<int[]> p = astar(e.getWorld(), e.getLocation(), target, within);
        if (p == null) { target = null; return false; }
        path = p; idx = 0; stuck = 0; lastPos = e.getLocation();
        return true;
    }

    public Result tick() {
        Mannequin e = h.entity;
        if (target == null || e == null) return Result.FAILED;
        Location loc = e.getLocation();
        if (horizDist(loc, target) <= within && Math.abs(loc.getY() - target.getY()) <= within + 1.5) { stop(); halt(e); return Result.ARRIVED; }
        if (++sinceCheck >= 100) { // every 5s we must have got at least a bit closer
            double hd = horizDist(loc, target) + Math.abs(loc.getY() - target.getY());
            if (hd > checkDist - 0.6) { stop(); halt(e); return Result.FAILED; }
            checkDist = hd; sinceCheck = 0;
        }
        if (idx >= path.size()) {
            // path exhausted but not close enough: replan once in a while
            if (++repaths > 4 || !plan()) { stop(); halt(e); return Result.FAILED; }
            return Result.MOVING;
        }
        int[] n = path.get(idx);
        double tx = n[0] + 0.5, ty = n[1], tz = n[2] + 0.5;
        double dx = tx - loc.getX(), dz = tz - loc.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.35 && Math.abs(loc.getY() - ty) < 1.1) { idx++; return Result.MOVING; }

        openDoorAt(e.getWorld().getBlockAt(n[0], n[1], n[2]));
        if (leafCooldown > 0) { leafCooldown--; return Result.MOVING; }
        for (int dy = 0; dy <= (n[1] > loc.getBlockY() ? 2 : 1); dy++) {
            Block lb = e.getWorld().getBlockAt(n[0], n[1] + dy, n[2]);
            if (soft(lb)) {
                halt(e);
                e.swingMainHand();
                lb.breakNaturally(new org.bukkit.inventory.ItemStack(Material.AIR), true);
                leafCooldown = 4;
                return Result.MOVING;
            }
        }
        if (openedDoor != null && openedDoor.getLocation().add(0.5, 0, 0.5).distanceSquared(loc) > 6.25) closeDoor();

        boolean water = loc.getBlock().getType() == Material.WATER;
        double speed = water ? 0.11 : sprint ? 0.25 : 0.15;
        Vector v = e.getVelocity();
        double vx = dx / Math.max(d, 0.01) * Math.min(speed, d + 0.05);
        double vz = dz / Math.max(d, 0.01) * Math.min(speed, d + 0.05);
        double vy = v.getY();
        if (water) vy = ty > loc.getY() - 0.3 ? 0.1 : Math.max(v.getY(), -0.05);
        else if (ty > loc.getY() + 0.4 && e.isOnGround()) vy = 0.42;
        e.setVelocity(new Vector(vx, vy, vz));
        face(e, dx, dz);

        // stuck detection
        if (lastPos != null && lastPos.getWorld() == loc.getWorld() && lastPos.distanceSquared(loc) < 0.0009) {
            stuck++;
            if (stuck % 10 == 0 && e.isOnGround()) e.setVelocity(new Vector(vx, 0.42, vz));
            if (stuck > 40) {
                if (++repaths > 4 || !plan()) { stop(); halt(e); return Result.FAILED; }
            }
        } else stuck = 0;
        lastPos = loc;
        return Result.MOVING;
    }

    static void face(Mannequin e, double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        e.setRotation(yaw, 0);
        e.setBodyYaw(yaw);
    }

    private static void halt(Mannequin e) {
        Vector v = e.getVelocity();
        e.setVelocity(new Vector(0, v.getY(), 0));
    }

    private void openDoorAt(Block b) {
        for (Block x : new Block[]{b, b.getRelative(0, 1, 0)}) {
            if ((Tag.WOODEN_DOORS.isTagged(x.getType()) || Tag.FENCE_GATES.isTagged(x.getType())) && x.getBlockData() instanceof Openable o && !o.isOpen()) {
                o.setOpen(true); x.setBlockData(o);
                x.getWorld().playSound(x.getLocation(), "block.wooden_door.open", 0.8f, 1f);
                openedDoor = x;
                return;
            }
        }
    }

    private void closeDoor() {
        if (openedDoor != null && openedDoor.getBlockData() instanceof Openable o && o.isOpen()) {
            o.setOpen(false); openedDoor.setBlockData(o);
            openedDoor.getWorld().playSound(openedDoor.getLocation(), "block.wooden_door.close", 0.8f, 1f);
        }
        openedDoor = null;
    }

    static double horizDist(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---------------------------------------------------------------- A*

    /** Natural leaves: humans punch through them like players do. */
    static boolean soft(Block b) {
        return b.getBlockData() instanceof org.bukkit.block.data.type.Leaves l && !l.isPersistent();
    }

    static boolean passableNav(Block b) { return soft(b) || passable(b); }

    /** Standable for pathfinding: feet/head may be natural leaves (we break them), but never walk ON leaves. */
    static boolean standableNav(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) return false;
        Block feet = w.getBlockAt(x, y, z);
        Block below = feet.getRelative(0, -1, 0);
        return passableNav(feet) && passableNav(feet.getRelative(0, 1, 0))
            && (ground(below) && !org.bukkit.Tag.LEAVES.isTagged(below.getType()) || feet.getType() == Material.WATER);
    }

    public static boolean passable(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS
            || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.COBWEB || m == Material.MAGMA_BLOCK) return false;
        if (Tag.WOODEN_DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return true;
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m)) return false;
        return b.isPassable();
    }

    static boolean ground(Block b) {
        Material m = b.getType();
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || m == Material.MAGMA_BLOCK || m == Material.CACTUS) return false;
        return m.isSolid() || m == Material.WATER;
    }

    public static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) return false;
        Block feet = w.getBlockAt(x, y, z);
        return passable(feet) && passable(feet.getRelative(0, 1, 0)) && (ground(feet.getRelative(0, -1, 0)) || feet.getType() == Material.WATER);
    }

    /** Snap a location to a standable block position nearby (or null). */
    public static int[] standSpot(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int dy : new int[]{0, 1, -1, 2, -2}) if (standable(w, x, y + dy, z)) return new int[]{x, y + dy, z};
        return null;
    }

    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private record Node(int x, int y, int z, double g, double f, Node parent) {}

    static List<int[]> astar(World w, Location from, Location to, double within) {
        int[] s = standSpot(from);
        if (s == null) s = new int[]{from.getBlockX(), from.getBlockY(), from.getBlockZ()};
        double tx = to.getX(), ty = to.getY(), tz = to.getZ();
        if (Math.abs(s[0] - tx) > 80 || Math.abs(s[2] - tz) > 80) return null;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(s[0], s[1], s[2], 0, 0, null);
        open.add(start);
        best.put(key(s[0], s[1], s[2]), 0.0);
        Node bestNode = start; double bestH = Double.MAX_VALUE;
        int expanded = 0;
        while (!open.isEmpty() && expanded < 4000) {
            Node n = open.poll();
            expanded++;
            double hx = n.x + 0.5 - tx, hz = n.z + 0.5 - tz, hy = n.y - ty;
            double hd = Math.sqrt(hx * hx + hz * hz);
            if (hd <= within && Math.abs(hy) <= within + 1.5) return build(n);
            double h = hd + Math.abs(hy);
            if (h < bestH) { bestH = h; bestNode = n; }
            for (int[] d : DIRS) {
                int nx = n.x + d[0], nz = n.z + d[1];
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && !(clear(w, n.x + d[0], n.y, n.z) && clear(w, n.x, n.y, n.z + d[1]))) continue;
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    int ny = n.y + dy;
                    if (dy == 1 && !passableNav(w.getBlockAt(n.x, n.y + 2, n.z))) continue;
                    if (dy < 0 && !clear(w, nx, n.y, nz)) break;
                    if (!standableNav(w, nx, ny, nz)) continue;
                    double cost = (diag ? 1.414 : 1) + (dy > 0 ? 0.6 : dy < 0 ? -dy * 0.3 : 0)
                        + (w.getBlockAt(nx, ny, nz).getType() == Material.WATER ? 2.5 : 0)
                        + (soft(w.getBlockAt(nx, ny, nz)) ? 2 : 0) + (soft(w.getBlockAt(nx, ny + 1, nz)) ? 2 : 0);
                    double g = n.g + cost;
                    long k = key(nx, ny, nz);
                    if (g >= best.getOrDefault(k, Double.MAX_VALUE)) break;
                    best.put(k, g);
                    double ex = nx + 0.5 - tx, ez = nz + 0.5 - tz;
                    open.add(new Node(nx, ny, nz, g, g + Math.sqrt(ex * ex + ez * ez) + Math.abs(ny - ty) * 0.8, n));
                    break;
                }
            }
        }
        // couldn't reach: go to the closest point if it is meaningfully closer
        double startH = Math.hypot(s[0] + 0.5 - tx, s[2] + 0.5 - tz);
        if (bestNode != start && bestH < startH - 2) return build(bestNode);
        return null;
    }

    /** Can we walk all the way there (not just get closer)? */
    public static boolean reachable(Location from, Location to, double within) {
        if (from.getWorld() != to.getWorld()) return false;
        List<int[]> p = astar(from.getWorld(), from, to, within);
        if (p == null) return false;
        if (p.isEmpty()) return true;
        int[] last = p.getLast();
        return Math.hypot(last[0] + 0.5 - to.getX(), last[2] + 0.5 - to.getZ()) <= within + 0.5 && Math.abs(last[1] - to.getY()) <= within + 1.5;
    }

    private static boolean clear(World w, int x, int y, int z) {
        return passableNav(w.getBlockAt(x, y, z)) && passableNav(w.getBlockAt(x, y + 1, z));
    }

    private static List<int[]> build(Node n) {
        LinkedList<int[]> out = new LinkedList<>();
        for (Node c = n; c != null; c = c.parent) out.addFirst(new int[]{c.x, c.y, c.z});
        if (!out.isEmpty()) out.removeFirst(); // skip start node
        return new ArrayList<>(out);
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }
}
