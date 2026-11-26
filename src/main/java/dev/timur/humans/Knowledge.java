package dev.timur.humans;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.timur.humans.tasks.Act;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * What the humans know about the world, shared by all of them (they talk to each other) and saved across restarts:
 * chests and what was in them, crafting tables, furnaces, beds, ores and dangers they've seen, and places they marked.
 * This is what lets them use what already exists instead of starting every job from scratch.
 */
public final class Knowledge {
    public static final class Place {
        public String kind, name, world, note, by, owner;
        public int x, y, z;
        public long seenMs, contentsMs;
        public Map<String, Integer> contents;
        public Location loc(World w) { return new Location(w, x + 0.5, y, z + 0.5); }
        public String xyz() { return x + "," + y + "," + z; }
    }

    private final HumansPlugin plugin;
    private final File file;
    private final Gson gson = new GsonBuilder().create();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private boolean dirty;

    public Knowledge(HumansPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "knowledge.json");
        load();
    }

    // ---------------------------------------------------------------- learning

    private static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ(); }

    /** Who owns a spot, in words the AI understands (decides whether taking is OK). */
    public String ownerOf(Location l) {
        for (Human o : plugin.manager().all()) if (Zones.ownArea(o, l)) return "humans' village (free to use)";
        if (plugin.zones().isProtected(l, null)) return "a player's base (ask the owner before taking)";
        return "wild (free to use)";
    }

    /** Remember a notable block (chest, crafting table, ore...) the human just saw. */
    public void noteBlock(Human h, Block b, String kind) {
        Place p = places.get(key(b));
        if (p == null) {
            p = new Place();
            p.kind = kind; p.world = b.getWorld().getName(); p.x = b.getX(); p.y = b.getY(); p.z = b.getZ();
            p.by = h.name; p.owner = ownerOf(b.getLocation());
            places.put(key(b), p);
            HumanLog.log("learn", h, "kind", kind, "at", p.xyz(), "owner", p.owner);
        }
        p.kind = kind;
        p.seenMs = System.currentTimeMillis();
        dirty = true;
        trim();
    }

    /** A human looked into (or used) a chest: remember exactly what's inside now. */
    public void noteChest(Human h, Block b) {
        if (!(b.getState() instanceof Container c)) { forget(b); return; }
        noteBlock(h, b, "chest");
        Place p = places.get(key(b));
        p.contents = contents(c.getInventory());
        p.contentsMs = System.currentTimeMillis();
        p.by = h.name;
        p.owner = ownerOf(b.getLocation());
        dirty = true;
    }

    public void forget(Block b) { if (places.remove(key(b)) != null) dirty = true; }

    /** A place the human chose to remember ("good spot for a tower", "cave with iron"). */
    public void mark(Human h, Location l, String name, String note) {
        String k = "mark:" + name.toLowerCase(Locale.ROOT).trim();
        Place p = places.getOrDefault(k, new Place());
        p.kind = "marked"; p.name = name; p.note = note; p.by = h.name;
        p.world = l.getWorld().getName(); p.x = l.getBlockX(); p.y = l.getBlockY(); p.z = l.getBlockZ();
        p.seenMs = System.currentTimeMillis(); p.owner = ownerOf(l);
        places.put(k, p);
        dirty = true;
        HumanLog.log("learn", h, "kind", "marked", "name", name, "at", p.xyz(), "note", note);
    }

    private static Map<String, Integer> contents(Inventory inv) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) m.merge(Items.nice(it.getType()), it.getAmount(), Integer::sum);
        return m;
    }

    /** What kind of notable thing a block is (null = not worth remembering). */
    public static String kindOf(Block b) {
        Material t = b.getType();
        String n = t.name();
        if (t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL) return "chest";
        if (t == Material.CRAFTING_TABLE) return "crafting table";
        if (t == Material.FURNACE || t == Material.BLAST_FURNACE || t == Material.SMOKER) return "furnace";
        if (Tag.BEDS.isTagged(t)) return "bed";
        if (t == Material.ANVIL || t == Material.ENCHANTING_TABLE || t == Material.BREWING_STAND) return Items.nice(t);
        if (t == Material.SPAWNER) return "monster spawner (danger)";
        if (n.endsWith("_ORE") && t != Material.COAL_ORE) return Items.nice(t).replace("deepslate ", "");
        if (t == Material.LAVA) return "lava (danger)";
        return null;
    }

    /** Look around (only what's actually visible) and remember notable things. Called every ~10 s per awake human. */
    public void notice(Human h) {
        if (!h.valid()) return;
        Location l = h.loc(), eye = h.entity.getEyeLocation();
        World w = l.getWorld();
        int r = 10, bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ(), checks = 0;
        Set<String> lavaSeen = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!w.isChunkLoaded((bx + dx) >> 4, (bz + dz) >> 4)) continue;
            for (int dy = -5; dy <= 6; dy++) {
                Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                String kind = kindOf(b);
                if (kind == null) continue;
                if (kind.startsWith("lava")) { // one note per lava pool, not one per block
                    String cell = (b.getX() >> 3) + "," + (b.getZ() >> 3);
                    if (!lavaSeen.add(cell)) continue;
                }
                Place known = places.get(key(b));
                if (known != null && System.currentTimeMillis() - known.seenMs < 60_000) continue;
                if (++checks > 40) return;
                if (!Act.visibleFrom(eye, b)) continue;
                noteBlock(h, b, kind);
            }
        }
    }

    // ---------------------------------------------------------------- telling the AI

    /** The most useful things this human knows, nearest first, chests with contents up front. */
    public String describeFor(Human h, int max) {
        if (!h.valid()) return "";
        Location l = h.loc();
        World w = l.getWorld();
        long now = System.currentTimeMillis();
        List<Place> list = new ArrayList<>();
        for (Iterator<Place> it = places.values().iterator(); it.hasNext(); ) {
            Place p = it.next();
            if (!p.world.equals(w.getName())) continue;
            // gone? (only checkable when that chunk is loaded)
            if (!"marked".equals(p.kind) && w.isChunkLoaded(p.x >> 4, p.z >> 4)) {
                String k = kindOf(w.getBlockAt(p.x, p.y, p.z));
                if (k == null || !k.equals(p.kind) && !(p.kind.equals("chest") && "chest".equals(k))) { it.remove(); dirty = true; continue; }
            }
            list.add(p);
        }
        list.sort(Comparator.comparingDouble((Place p) -> rank(p) * 1000 + Math.hypot(p.x - l.getX(), p.z - l.getZ()) + Math.abs(p.y - l.getY()) * 0.5));
        Map<String, Integer> perKind = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (Place p : list) {
            if (out.size() >= max) break;
            int d = (int) Math.round(Math.sqrt(Math.pow(p.x - l.getX(), 2) + Math.pow(p.y - l.getY(), 2) + Math.pow(p.z - l.getZ(), 2)));
            if (d > 400) continue;
            String kind = p.kind.equals("chest") && p.contents == null ? "chest (contents unknown)" : p.kind;
            if (perKind.merge(kind, 1, Integer::sum) > (p.kind.equals("chest") ? 8 : 3)) continue;
            StringBuilder s = new StringBuilder();
            if ("marked".equals(p.kind)) s.append("PLACE \"").append(p.name).append("\"");
            else s.append(kind);
            s.append(" at ").append(p.xyz()).append(" (").append(d).append("m");
            if (p.owner != null && (p.kind.equals("chest") || "marked".equals(p.kind))) s.append(", ").append(p.owner);
            s.append(")");
            if (p.contents != null) {
                s.append(p.contents.isEmpty() ? " - was EMPTY" : " contains: " + summarize(p.contents));
                s.append(" [checked ").append(ago(now - p.contentsMs)).append(" by ").append(p.by).append("]");
            }
            if (p.note != null && !p.note.isBlank()) s.append(" - ").append(p.note).append(" [marked by ").append(p.by).append("]");
            out.add(s.toString());
        }
        return String.join("\n- ", out);
    }

    /** Known chests that (last time we looked) had this item, nearest first. */
    public List<Place> chestsWith(Human h, String itemNice) {
        List<Place> out = new ArrayList<>();
        Location l = h.loc();
        for (Place p : places.values())
            if (p.contents != null && p.world.equals(l.getWorld().getName()) && p.contents.containsKey(itemNice)) out.add(p);
        out.sort(Comparator.comparingDouble(p -> Math.hypot(p.x - l.getX(), p.z - l.getZ())));
        return out;
    }

    /** All known chests in this world, nearest first. */
    public List<Place> chests(Human h) {
        List<Place> out = new ArrayList<>();
        Location l = h.loc();
        for (Place p : places.values()) if ("chest".equals(p.kind) && p.world.equals(l.getWorld().getName())) out.add(p);
        out.sort(Comparator.comparingDouble(p -> Math.hypot(p.x - l.getX(), p.z - l.getZ())));
        return out;
    }

    public Place markNamed(String name) { return places.get("mark:" + name.toLowerCase(Locale.ROOT).trim()); }

    private static int rank(Place p) {
        if ("marked".equals(p.kind)) return 0;
        if (p.kind.equals("chest")) return p.contents != null ? 0 : 1;
        if (p.kind.contains("danger")) return 2;
        return 1;
    }

    private static String summarize(Map<String, Integer> c) {
        List<Map.Entry<String, Integer>> e = new ArrayList<>(c.entrySet());
        e.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(10, e.size()); i++) s.append(i == 0 ? "" : ", ").append(e.get(i).getValue()).append(' ').append(e.get(i).getKey());
        if (e.size() > 10) s.append(" and ").append(e.size() - 10).append(" more kinds");
        return s.toString();
    }

    private static String ago(long ms) {
        long m = ms / 60_000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? m / 60 + " h ago" : m / 1440 + " days ago";
    }

    public int size() { return places.size(); }

    private void trim() {
        if (places.size() <= 800) return;
        // forget the oldest plain sightings first (never marked places or chests with contents)
        List<Map.Entry<String, Place>> e = new ArrayList<>(places.entrySet());
        e.sort(Comparator.comparingLong(x -> x.getValue().seenMs));
        for (var x : e) {
            if (places.size() <= 700) break;
            Place p = x.getValue();
            if ("marked".equals(p.kind) || p.contents != null) continue;
            places.remove(x.getKey());
        }
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        if (!file.exists()) return;
        try {
            Map<String, Place> m = gson.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Place>>() {}.getType());
            if (m != null) places.putAll(m);
            plugin.getLogger().info("Knowledge: " + places.size() + " remembered places.");
        } catch (Exception e) { plugin.getLogger().warning("Could not read knowledge.json: " + e); }
    }

    public void save() {
        if (!dirty) return;
        try {
            Files.writeString(file.toPath(), gson.toJson(places), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) { plugin.getLogger().warning("Could not save knowledge.json: " + e); }
    }
}
