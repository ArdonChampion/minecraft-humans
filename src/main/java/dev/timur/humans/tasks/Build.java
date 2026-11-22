package dev.timur.humans.tasks;

import dev.timur.humans.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;

import java.util.*;

/** Parametric structures (house, farm, pen, wall, path) and AI-designed custom layouts, built block by block. */
public final class Build {
    private Build() {}

    /** One block of a blueprint, relative to the origin (dx east, dy up, dz south). mat = material name or category. */
    public record Piece(int dx, int dy, int dz, String mat) {}

    public record Blueprint(String name, int w, int d, List<Piece> pieces, boolean isHouse) {}

    // ---------------------------------------------------------------- templates

    public static Blueprint house(int w, int d, int h, String roof, String mat) {
        w = clamp(w, 5, 11); d = clamp(d, 5, 11); h = clamp(h, 3, 5);
        List<Piece> p = new ArrayList<>();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) p.add(new Piece(x, -1, z, "foundation:" + mat));
        int doorX = w / 2, doorZ = d - 1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
            if (!edgeX && !edgeZ) continue;
            if (x == doorX && z == doorZ && y < 2) { if (y == 0) p.add(new Piece(x, 0, z, "door")); continue; }
            boolean corner = edgeX && edgeZ;
            boolean window = y == 1 && !corner && (x + z) % 3 == 1 && !(x == doorX && z == doorZ);
            p.add(new Piece(x, y, z, window ? "window:" + mat : mat));
        }
        if ("peaked".equals(roof)) { // gable roof sloping east-west, ridge running north-south
            for (int layer = 0; ; layer++) {
                int left = layer - 1, right = w - layer;
                if (left > right) break;
                for (int z = -1; z <= d; z++) { p.add(new Piece(left, h + layer, z, mat)); p.add(new Piece(right, h + layer, z, mat)); }
                for (int x = left + 1; x < right; x++) { p.add(new Piece(x, h + layer, 0, mat)); p.add(new Piece(x, h + layer, d - 1, mat)); }
            }
        } else {
            for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) p.add(new Piece(x, h, z, mat));
        }
        p.add(new Piece(1, 0, 1, "torch_wall"));
        return new Blueprint("house", w, d, dedupe(p), true);
    }

    public static Blueprint farm(int w, int d) {
        w = clamp(w, 3, 9); d = clamp(d, 3, 9);
        List<Piece> p = new ArrayList<>();
        int wx = w % 2 == 1 && w >= 3 ? w / 2 : -1, wz = d % 2 == 1 && d >= 3 ? d / 2 : -1;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            if (x == wx && z == wz) { p.add(new Piece(x, -1, z, "water")); continue; }
            p.add(new Piece(x, -1, z, "farmland"));
            p.add(new Piece(x, 0, z, "seed"));
        }
        return new Blueprint("farm", w, d, p, false);
    }

    public static Blueprint pen(int w, int d) {
        w = clamp(w, 4, 12); d = clamp(d, 4, 12);
        List<Piece> p = new ArrayList<>();
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            if (!(x == 0 || z == 0 || x == w - 1 || z == d - 1)) continue;
            p.add(new Piece(x, 0, z, x == w / 2 && z == d - 1 ? "fence_gate" : "fence"));
        }
        return new Blueprint("animal pen", w, d, p, false);
    }

    public static Blueprint line(String kind, int len, String dir, int h, String mat) {
        len = clamp(len, 2, 40); h = clamp(h, 1, 4);
        int dx = 0, dz = 0;
        switch (dir == null ? "east" : dir) { case "north" -> dz = -1; case "south" -> dz = 1; case "west" -> dx = -1; default -> dx = 1; }
        List<Piece> p = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            if ("path".equals(kind)) p.add(new Piece(i * dx, -1, i * dz, "path"));
            else for (int y = 0; y < h; y++) p.add(new Piece(i * dx, y, i * dz, mat));
        }
        int minX = Math.min(0, (len - 1) * dx), minZ = Math.min(0, (len - 1) * dz);
        List<Piece> shifted = new ArrayList<>();
        for (Piece q : p) shifted.add(new Piece(q.dx() - minX, q.dy(), q.dz() - minZ, q.mat()));
        return new Blueprint(kind, Math.abs(len * dx) + 1, Math.abs(len * dz) + 1, shifted, false);
    }

    /** layers bottom->top; each layer = rows north->south; each row = chars west->east. */
    public static Blueprint custom(String name, List<List<String>> layers, Map<Character, String> legend) {
        List<Piece> p = new ArrayList<>();
        int w = 0, d = 0;
        for (int y = 0; y < layers.size(); y++) {
            List<String> rows = layers.get(y);
            d = Math.max(d, rows.size());
            for (int z = 0; z < rows.size(); z++) {
                String row = rows.get(z);
                w = Math.max(w, row.length());
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == ' ' || c == '.' || c == '_') continue;
                    String mat = legend.get(c);
                    if (mat != null) p.add(new Piece(x, y, z, mat));
                }
                if (p.size() > 300) break;
            }
        }
        return new Blueprint(name == null || name.isBlank() ? "build" : name, Math.max(w, 1), Math.max(d, 1), p.size() > 300 ? p.subList(0, 300) : p, false);
    }

    private static List<Piece> dedupe(List<Piece> p) {
        Map<String, Piece> m = new LinkedHashMap<>();
        for (Piece q : p) m.putIfAbsent(q.dx() + "," + q.dy() + "," + q.dz(), q);
        return new ArrayList<>(m.values());
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------------------------------------------------------------- materials

    /** Which inventory item a piece needs (null = nothing / special), and how to check a placed block is right. */
    static Material pickMaterial(Human h, String mat) {
        String m = mat.toLowerCase(Locale.ROOT);
        if (m.startsWith("foundation:")) m = m.substring(11);
        if (m.startsWith("window:")) { if (h.inv.count(Material.GLASS) > 0) return Material.GLASS; m = m.substring(7); }
        switch (m) {
            case "planks", "wood" -> { return h.inv.any(Tag.PLANKS::isTagged).orElse(null); }
            case "logs", "log" -> { return h.inv.any(Tag.LOGS::isTagged).orElse(null); }
            case "cobblestone", "stone", "cobble" -> { return h.inv.any(x -> x == Material.COBBLESTONE || x == Material.COBBLED_DEEPSLATE || x == Material.STONE || x == Material.STONE_BRICKS).orElse(null); }
            case "any", "auto", "" -> { return h.inv.any(x -> Items.isBuildingBlock(x) || Tag.LOGS.isTagged(x) || x == Material.DIRT).orElse(null); }
            case "fence" -> { return h.inv.any(x -> Tag.FENCES.isTagged(x) && x != Material.NETHER_BRICK_FENCE).orElse(null); }
            case "fence_gate" -> { return h.inv.any(Tag.FENCE_GATES::isTagged).orElse(null); }
            case "door" -> { return h.inv.any(Tag.WOODEN_DOORS::isTagged).orElse(null); }
            case "torch", "torch_wall" -> { return h.inv.has(Material.TORCH, 1) ? Material.TORCH : null; }
            case "bed" -> { return h.inv.any(x -> x.name().endsWith("_BED")).orElse(null); }
            default -> {
                Material x = Material.matchMaterial(m);
                if (x == null) return h.inv.any(y -> Items.isBuildingBlock(y)).orElse(null); // unknown name: use what we have
                if (Tag.PLANKS.isTagged(x) && !h.inv.has(x, 1)) return h.inv.any(Tag.PLANKS::isTagged).orElse(null);
                if (Tag.LOGS.isTagged(x) && !h.inv.has(x, 1)) return h.inv.any(Tag.LOGS::isTagged).orElse(null);
                return h.inv.has(x, 1) ? x : null;
            }
        }
    }

    // ---------------------------------------------------------------- site

    /** Choose the build origin (north-west corner, at ground level). */
    public static Location site(Human h, Blueprint bp, String at) {
        World w = h.loc().getWorld();
        Location center;
        if (at == null || at.isBlank() || at.equals("here")) {
            Location l = h.loc();
            org.bukkit.util.Vector f = l.getDirection().setY(0);
            if (f.lengthSquared() < 0.01) f = new org.bukkit.util.Vector(0, 0, 1);
            f.normalize().multiply(2 + Math.max(bp.w(), bp.d()) / 2.0);
            center = l.clone().add(f);
        } else if (at.equals("home") && h.home != null) center = h.home.clone();
        else if (at.equals("village") && h.village != null) center = h.village.clone();
        else if (at.matches("-?\\d+\\s*,\\s*-?\\d+")) {
            String[] xz = at.split(",");
            int x = Integer.parseInt(xz[0].trim()), z = Integer.parseInt(xz[1].trim());
            center = new Location(w, x, w.getHighestBlockYAt(x, z) + 1, z);
        } else center = h.loc();
        Location best = null; int bestCost = Integer.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx += 2) for (int dz = -8; dz <= 8; dz += 2) {
            Location spot = Act.surfaceSpot(center.clone().add(dx - bp.w() / 2.0, 0, dz - bp.d() / 2.0));
            if (spot == null) continue;
            int cost = cost(h, w, spot.getBlockX(), spot.getBlockY(), spot.getBlockZ(), bp);
            if (cost < 0) continue;
            cost += (Math.abs(dx) + Math.abs(dz)) / 2;
            if (cost < bestCost) { bestCost = cost; best = new Location(w, spot.getBlockX(), spot.getBlockY(), spot.getBlockZ()); }
        }
        return best;
    }

    static int cost(Human h, World w, int ox, int oy, int oz, Blueprint bp) {
        int cost = 0, fill = 0;
        int ht = 0; for (Piece p : bp.pieces()) ht = Math.max(ht, p.dy());
        for (int x = 0; x < bp.w(); x++) for (int z = 0; z < bp.d(); z++) {
            Block g = w.getBlockAt(ox + x, oy - 1, oz + z);
            if (HumansPlugin.get().zones().isProtected(g, h)) return -1;
            if (!g.getType().isSolid()) { if (!w.getBlockAt(ox + x, oy - 2, oz + z).getType().isSolid()) return -1; fill++; }
            if (g.getType() == Material.WATER || g.getType() == Material.LAVA) return -1;
            for (int y = 0; y <= Math.min(ht, 6); y++) {
                Block b = w.getBlockAt(ox + x, oy + y, oz + z);
                Material t = b.getType();
                if (t.isAir()) continue;
                if (Tag.LEAVES.isTagged(t)) { cost++; continue; }
                if (b.isReplaceable() && !t.isSolid() && t != Material.WATER && t != Material.LAVA) continue;
                return -1;
            }
        }
        if (fill > bp.w() * bp.d() / 3) return -1;
        return cost + fill * 2;
    }

    // ---------------------------------------------------------------- the build task

    public static final class BuildTask extends Task {
        private final Blueprint bp; private final String at; private Location origin;
        private List<Block> clear; private List<Block> targets; private List<Piece> pieces; private int ci, idx, cooldown, placed, passes;
        private Tasks.Breaker breaker; private boolean steppingAside;
        public BuildTask(Blueprint bp, String at) { this.bp = bp; this.at = at; }
        public String label() { return "building a " + bp.name() + (targets == null ? "" : " (" + placed + "/" + targets.size() + ")"); }
        public int maxTicks() { return 20 * 60 * 10; }
        @Override public boolean isWork() { return true; }
        public Location origin() { return origin; }

        protected Status run(Human h, HumanManager m) {
            if (targets == null) {
                origin = site(h, bp, at);
                if (origin == null) { outcome = "couldn't find a flat open spot for the " + bp.name() + " (outside players' bases)"; return Status.FAIL; }
                World w = origin.getWorld();
                targets = new ArrayList<>(); pieces = new ArrayList<>();
                List<Piece> sorted = new ArrayList<>(bp.pieces());
                sorted.sort(Comparator.comparingInt(Piece::dy));
                for (Piece p : sorted) { targets.add(w.getBlockAt(origin.getBlockX() + p.dx(), origin.getBlockY() + p.dy(), origin.getBlockZ() + p.dz())); pieces.add(p); }
                clear = new ArrayList<>();
                int ht = 0; for (Piece p : bp.pieces()) ht = Math.max(ht, p.dy());
                for (int y = Math.min(ht, 6); y >= 0; y--) for (int x = 0; x < bp.w(); x++) for (int z = 0; z < bp.d(); z++) {
                    Block b = w.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                    if (!b.getType().isAir() && (Tag.LEAVES.isTagged(b.getType()) || b.isReplaceable())) clear.add(b);
                }
                h.event("started building a " + bp.name() + " at x=" + origin.getBlockX() + " z=" + origin.getBlockZ());
            }
            if (--cooldown > 0) return Status.RUNNING;
            if (breaker != null) { if (breaker.target.getType().isAir() || breaker.tick(h, m)) { breaker = null; cooldown = 2; } return Status.RUNNING; }
            while (ci < clear.size() && (clear.get(ci).getType().isAir() || clear.get(ci).getType() == Material.WATER)) ci++;
            if (ci < clear.size()) {
                Block b = clear.get(ci);
                if (!Act.inReach(h, b)) { if (!h.nav.active()) h.nav.moveTo(b.getLocation().add(0.5, 0, 0.5), 3.0, false); if (h.nav.tick() == Navigator.Result.FAILED) ci++; return Status.RUNNING; }
                h.nav.stop();
                breaker = new Tasks.Breaker(); breaker.target = b;
                return Status.RUNNING;
            }
            while (idx < targets.size() && done(targets.get(idx), pieces.get(idx).mat())) idx++;
            if (idx >= targets.size()) {
                if (++passes < 3 && anyMissing()) { idx = 0; return Status.RUNNING; }
                return finish(h, m);
            }
            Block b = targets.get(idx); String mat = pieces.get(idx).mat();
            if (steppingAside) { if (h.nav.tick() != Navigator.Result.MOVING) steppingAside = false; return Status.RUNNING; }
            Location feet = h.loc();
            if (b.getX() == feet.getBlockX() && b.getZ() == feet.getBlockZ() && b.getY() >= feet.getBlockY() - 1 && b.getY() <= feet.getBlockY() + 1 && !mat.equals("farmland") && !mat.equals("path")) {
                Set<Block> planned = new HashSet<>(targets);
                for (int[] d : new int[][]{{2,0},{-2,0},{0,2},{0,-2},{3,0},{0,3},{-3,0},{0,-3},{3,3},{-3,-3}}) {
                    Location spot = Act.surfaceSpot(feet.clone().add(d[0], 0, d[1]));
                    if (spot != null && !planned.contains(spot.getBlock()) && h.nav.moveTo(spot, 0.4, false)) { steppingAside = true; break; }
                }
                if (!steppingAside) idx++;
                return Status.RUNNING;
            }
            if (!Act.inReach(h, b)) {
                if (!h.nav.active()) h.nav.moveTo(b.getLocation().add(0.5, 0, 0.5), 3.0, false);
                if (h.nav.tick() == Navigator.Result.FAILED) idx++;
                return Status.RUNNING;
            }
            h.nav.stop();
            String r = placeOne(h, b, mat);
            if (r != null) {
                if (mat.equals("torch_wall") || mat.equals("water")) { idx++; return Status.RUNNING; } // nice-to-have
                outcome = r + " (" + bp.name() + " " + placed + "/" + targets.size() + " done)";
                return placed > 0 ? Status.FAIL : Status.FAIL;
            }
            placed++; idx++;
            cooldown = 8 + m.rnd().nextInt(4);
            return Status.RUNNING;
        }

        private boolean anyMissing() { for (int i = 0; i < targets.size(); i++) if (!done(targets.get(i), pieces.get(i).mat())) return true; return false; }

        private static boolean done(Block b, String mat) {
            Material t = b.getType();
            return switch (mat) {
                case "farmland" -> t == Material.FARMLAND;
                case "seed" -> t == Material.WHEAT || t == Material.CARROTS || t == Material.POTATOES || t == Material.BEETROOTS;
                case "water" -> t == Material.WATER;
                case "path" -> t == Material.DIRT_PATH;
                case "door" -> Tag.DOORS.isTagged(t);
                case "torch_wall" -> t == Material.TORCH || t == Material.WALL_TORCH;
                case "fence" -> Tag.FENCES.isTagged(t);
                case "fence_gate" -> Tag.FENCE_GATES.isTagged(t);
                default -> mat.startsWith("foundation:") ? t.isSolid() : t.isSolid() || t == Material.TORCH || Tag.DOORS.isTagged(t) || t.name().endsWith("_BED");
            };
        }

        /** Place one piece. Returns null on success, or what's missing. */
        private String placeOne(Human h, Block b, String mat) {
            switch (mat) {
                case "farmland" -> {
                    if (Items.best(h.inv, "_HOE").isEmpty()) return "needs a hoe to make farmland";
                    Material t = b.getType();
                    if (t != Material.DIRT && t != Material.GRASS_BLOCK && t != Material.DIRT_PATH && t != Material.COARSE_DIRT) {
                        if (t.isSolid()) return null; // can't till this, skip it quietly
                        Material dirt = h.inv.has(Material.DIRT, 1) ? Material.DIRT : null;
                        if (dirt == null) return "needs dirt to fill the farm";
                        h.inv.remove(Material.DIRT, 1);
                    }
                    if (!b.getRelative(BlockFace.UP).getType().isAir() && !b.getRelative(BlockFace.UP).isReplaceable()) return null;
                    b.getRelative(BlockFace.UP).setType(Material.AIR);
                    Act.hold(h, Items.best(h.inv, "_HOE").get()); Act.lookAt(h, b.getLocation().add(0.5, 1, 0.5)); Act.swing(h);
                    b.setType(Material.FARMLAND);
                    b.getWorld().playSound(b.getLocation(), "item.hoe.till", 1f, 1f);
                    return null;
                }
                case "seed" -> {
                    if (b.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return null;
                    Material seed = h.inv.has(Material.WHEAT_SEEDS, 1) ? Material.WHEAT_SEEDS : h.inv.has(Material.CARROT, 1) ? Material.CARROT
                        : h.inv.has(Material.POTATO, 1) ? Material.POTATO : h.inv.has(Material.BEETROOT_SEEDS, 1) ? Material.BEETROOT_SEEDS : null;
                    if (seed == null) return "ran out of seeds (wheat seeds come from breaking grass)";
                    h.inv.remove(seed, 1);
                    Act.hold(h, seed); Act.lookAt(h, b.getLocation().add(0.5, 0.2, 0.5)); Act.swing(h);
                    b.setType(switch (seed) { case CARROT -> Material.CARROTS; case POTATO -> Material.POTATOES; case BEETROOT_SEEDS -> Material.BEETROOTS; default -> Material.WHEAT; });
                    b.getWorld().playSound(b.getLocation(), "item.crop.plant", 1f, 1f);
                    return null;
                }
                case "water" -> {
                    if (!h.inv.has(Material.WATER_BUCKET, 1)) return "needs a water bucket";
                    b.setType(Material.WATER); h.inv.remove(Material.WATER_BUCKET, 1); h.inv.add(Material.BUCKET, 1);
                    return null;
                }
                case "path" -> {
                    if (b.getType() == Material.GRASS_BLOCK || b.getType() == Material.DIRT) {
                        if (!b.getRelative(BlockFace.UP).getType().isAir()) b.getRelative(BlockFace.UP).setType(Material.AIR);
                        Act.lookAt(h, b.getLocation().add(0.5, 1, 0.5)); Act.swing(h);
                        b.setType(Material.DIRT_PATH);
                        b.getWorld().playSound(b.getLocation(), "item.shovel.flatten", 1f, 1f);
                    }
                    return null;
                }
            }
            if (HumansPlugin.get().zones().isProtected(b, h)) return "that spot is inside a player's base";
            Material item = pickMaterial(h, mat);
            if (item == null) return "ran out of " + mat.replaceAll("^(foundation|window):", "") + " - need more";
            if (mat.equals("door")) {
                if (!b.getType().isAir() || !b.getRelative(BlockFace.UP).getType().isAir()) return null;
                Door low = (Door) item.createBlockData(); low.setHalf(Bisected.Half.BOTTOM); low.setFacing(BlockFace.SOUTH);
                Door up = (Door) item.createBlockData(); up.setHalf(Bisected.Half.TOP); up.setFacing(BlockFace.SOUTH);
                b.setBlockData(low, false); b.getRelative(BlockFace.UP).setBlockData(up, false);
                h.inv.remove(item, 1);
                Act.lookAt(h, b.getLocation().add(0.5, 0.5, 0.5)); Act.swing(h);
                b.getWorld().playSound(b.getLocation(), "block.wood.place", 1f, 1f);
                return null;
            }
            if (mat.equals("fence_gate") && Act.place(h, b, item)) {
                if (b.getBlockData() instanceof Gate g) { g.setFacing(BlockFace.SOUTH); b.setBlockData(g); }
                h.inv.remove(item, 1); return null;
            }
            if (Act.place(h, b, item)) { h.inv.remove(item, 1); return null; }
            return null; // occupied by something (an entity...) - skip
        }

        private Status finish(Human h, HumanManager m) {
            Location c = origin.clone().add(bp.w() / 2.0, 0, bp.d() / 2.0);
            if (bp.isHouse()) {
                if (!h.hutBuilt || h.hutCenter == null) { h.hutBuilt = true; h.hutCenter = c.clone(); h.home = c.clone(); }
                h.remember("I built a " + bp.w() + "x" + bp.d() + " house at x=" + c.getBlockX() + " z=" + c.getBlockZ());
            } else h.remember("I built a " + bp.name() + " at x=" + c.getBlockX() + " z=" + c.getBlockZ());
            outcome = "FINISHED building a " + bp.name() + " at x=" + c.getBlockX() + " z=" + c.getBlockZ();
            return Status.SUCCESS;
        }
    }
}
