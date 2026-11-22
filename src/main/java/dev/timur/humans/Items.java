package dev.timur.humans;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.*;

/** Item knowledge: tool tiers, food, fuel, simple crafting recipes. */
public final class Items {
    private Items() {}

    public static String nice(Material m) { return m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }

    // ---- tools
    public enum Tier { NONE, WOOD, STONE, IRON, DIAMOND }

    public static Tier tierOf(Material m) {
        String n = m.name();
        if (n.startsWith("WOODEN_")) return Tier.WOOD;
        if (n.startsWith("STONE_")) return Tier.STONE;
        if (n.startsWith("IRON_")) return Tier.IRON;
        if (n.startsWith("DIAMOND_") || n.startsWith("NETHERITE_")) return Tier.DIAMOND;
        return Tier.NONE;
    }

    public static Optional<Material> best(Inventory inv, String suffix) {
        Material best = null; Tier bt = Tier.NONE;
        for (Material m : inv.view().keySet()) {
            if (!m.name().endsWith(suffix)) continue;
            Tier t = tierOf(m);
            if (best == null || t.ordinal() > bt.ordinal()) { best = m; bt = t; }
        }
        return Optional.ofNullable(best);
    }

    public static Tier pickaxeTier(Inventory inv) { return best(inv, "_PICKAXE").map(Items::tierOf).orElse(Tier.NONE); }

    /** Minimum pickaxe tier needed to get drops from a block. */
    public static Tier requiredTier(Material block) {
        if (Tag.NEEDS_DIAMOND_TOOL.isTagged(block)) return Tier.DIAMOND;
        if (Tag.NEEDS_IRON_TOOL.isTagged(block)) return Tier.IRON;
        if (Tag.NEEDS_STONE_TOOL.isTagged(block)) return Tier.STONE;
        if (Tag.MINEABLE_PICKAXE.isTagged(block)) return Tier.WOOD;
        return Tier.NONE;
    }

    public static double weaponDamage(Inventory inv) {
        Optional<Material> sword = best(inv, "_SWORD");
        if (sword.isPresent()) return switch (tierOf(sword.get())) { case WOOD -> 4; case STONE -> 5; case IRON -> 6; case DIAMOND -> 7; default -> 2; };
        Optional<Material> axe = best(inv, "_AXE");
        if (axe.isPresent()) return switch (tierOf(axe.get())) { case WOOD -> 3.5; case STONE -> 4.5; case IRON -> 5.5; case DIAMOND -> 6.5; default -> 2; };
        return 1.5;
    }

    public static Material weaponItem(Inventory inv) {
        return best(inv, "_SWORD").or(() -> best(inv, "_AXE")).orElse(Material.AIR);
    }

    // ---- food
    private static final Map<Material, Integer> FOOD = new EnumMap<>(Material.class);
    static {
        FOOD.put(Material.COOKED_BEEF, 8); FOOD.put(Material.COOKED_PORKCHOP, 8); FOOD.put(Material.COOKED_MUTTON, 6);
        FOOD.put(Material.COOKED_CHICKEN, 6); FOOD.put(Material.BREAD, 5); FOOD.put(Material.BAKED_POTATO, 5);
        FOOD.put(Material.APPLE, 4); FOOD.put(Material.CARROT, 3); FOOD.put(Material.SWEET_BERRIES, 2);
        FOOD.put(Material.BEEF, 3); FOOD.put(Material.PORKCHOP, 3); FOOD.put(Material.MUTTON, 2); FOOD.put(Material.CHICKEN, 2);
        FOOD.put(Material.COOKED_COD, 5); FOOD.put(Material.COOKED_SALMON, 6); FOOD.put(Material.GOLDEN_APPLE, 4);
        FOOD.put(Material.POTATO, 1); FOOD.put(Material.MELON_SLICE, 2); FOOD.put(Material.PUMPKIN_PIE, 8);
    }
    public static int foodValue(Material m) { return FOOD.getOrDefault(m, 0); }
    public static boolean isFood(Material m) { return FOOD.containsKey(m); }
    public static boolean isRawMeat(Material m) { return m == Material.BEEF || m == Material.PORKCHOP || m == Material.MUTTON || m == Material.CHICKEN; }

    public static Optional<Material> bestFood(Inventory inv) {
        return inv.view().keySet().stream().filter(Items::isFood)
            .max(Comparator.comparingInt(m -> foodValue(m) * 10 - (isRawMeat(m) ? 5 : 0)));
    }

    public static Material cooked(Material raw) {
        return switch (raw) {
            case BEEF -> Material.COOKED_BEEF; case PORKCHOP -> Material.COOKED_PORKCHOP;
            case MUTTON -> Material.COOKED_MUTTON; case CHICKEN -> Material.COOKED_CHICKEN;
            case RAW_IRON -> Material.IRON_INGOT; case RAW_GOLD -> Material.GOLD_INGOT;
            case POTATO -> Material.BAKED_POTATO; case SAND, RED_SAND -> Material.GLASS; case COBBLESTONE -> Material.STONE;
            case RAW_COPPER -> Material.COPPER_INGOT; case COD -> Material.COOKED_COD; case SALMON -> Material.COOKED_SALMON; default -> null;
        };
    }

    public static boolean isFuel(Material m) {
        return m == Material.COAL || m == Material.CHARCOAL || Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m);
    }

    public static boolean isBuildingBlock(Material m) {
        return Tag.PLANKS.isTagged(m) || m == Material.COBBLESTONE || m == Material.COBBLED_DEEPSLATE || m == Material.STONE_BRICKS;
    }

    // ---- crafting (virtual recipes; 3x3 ones need a crafting table)
    public record Recipe(String id, Material result, int amount, Map<Object, Integer> inputs, boolean needsTable) {
        public String nice() { return (amount > 1 ? amount + " " : "") + Items.nice(result); }
    }

    /** Input keys are either a Material or a Tag<Material>. */
    public static boolean canCraft(Inventory inv, Recipe r) {
        if (r.needsTable && !inv.has(Material.CRAFTING_TABLE, 1)) return false;
        for (var e : r.inputs.entrySet()) if (countFor(inv, e.getKey()) < e.getValue()) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    public static int countFor(Inventory inv, Object key) {
        if (key instanceof Material m) return inv.count(m);
        Tag<Material> t = (Tag<Material>) key;
        return inv.countWhere(t::isTagged);
    }

    @SuppressWarnings("unchecked")
    public static void consume(Inventory inv, Recipe r) {
        for (var e : r.inputs.entrySet()) {
            if (e.getKey() instanceof Material m) inv.remove(m, e.getValue());
            else inv.removeAny(((Tag<Material>) e.getKey())::isTagged, e.getValue());
        }
        inv.add(r.result, r.amount);
    }

    public static Recipe byId(String id) { for (Recipe r : RECIPES) if (r.id().equals(id)) return r; return null; }

    /** Steps to craft target from what we have, auto-making planks/sticks/crafting table on the way (null = impossible). */
    public static List<Recipe> plan(Inventory inv, Recipe target) {
        Inventory sim = inv.copy();
        List<Recipe> steps = new ArrayList<>();
        return ensure(sim, target, steps, 0) ? steps : null;
    }

    private static boolean ensure(Inventory sim, Recipe r, List<Recipe> steps, int depth) {
        if (depth > 5 || steps.size() > 20) return false;
        if (r.needsTable() && !sim.has(Material.CRAFTING_TABLE, 1) && !ensure(sim, byId("crafting_table"), steps, depth + 1)) return false;
        for (var e : r.inputs().entrySet()) {
            int guard = 0;
            while (countFor(sim, e.getKey()) < e.getValue()) {
                Recipe sub = e.getKey() == Tag.PLANKS ? byId("planks") : e.getKey() == Material.STICK ? byId("sticks") : null;
                if (sub == null || ++guard > 10 || !ensure(sim, sub, steps, depth + 1)) return false;
            }
        }
        consume(sim, r);
        steps.add(r);
        return true;
    }

    private static Map<Object, Integer> in(Object... kv) {
        Map<Object, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], (Integer) kv[i + 1]);
        return m;
    }

    public static final List<Recipe> RECIPES = List.of(
        new Recipe("planks", Material.OAK_PLANKS, 4, in(Tag.LOGS, 1), false),
        new Recipe("sticks", Material.STICK, 4, in(Tag.PLANKS, 2), false),
        new Recipe("crafting_table", Material.CRAFTING_TABLE, 1, in(Tag.PLANKS, 4), false),
        new Recipe("wooden_pickaxe", Material.WOODEN_PICKAXE, 1, in(Tag.PLANKS, 3, Material.STICK, 2), true),
        new Recipe("wooden_axe", Material.WOODEN_AXE, 1, in(Tag.PLANKS, 3, Material.STICK, 2), true),
        new Recipe("wooden_sword", Material.WOODEN_SWORD, 1, in(Tag.PLANKS, 2, Material.STICK, 1), true),
        new Recipe("stone_pickaxe", Material.STONE_PICKAXE, 1, in(Material.COBBLESTONE, 3, Material.STICK, 2), true),
        new Recipe("stone_axe", Material.STONE_AXE, 1, in(Material.COBBLESTONE, 3, Material.STICK, 2), true),
        new Recipe("stone_sword", Material.STONE_SWORD, 1, in(Material.COBBLESTONE, 2, Material.STICK, 1), true),
        new Recipe("furnace", Material.FURNACE, 1, in(Material.COBBLESTONE, 8), true),
        new Recipe("iron_pickaxe", Material.IRON_PICKAXE, 1, in(Material.IRON_INGOT, 3, Material.STICK, 2), true),
        new Recipe("iron_sword", Material.IRON_SWORD, 1, in(Material.IRON_INGOT, 2, Material.STICK, 1), true),
        new Recipe("iron_axe", Material.IRON_AXE, 1, in(Material.IRON_INGOT, 3, Material.STICK, 2), true),
        new Recipe("shield", Material.SHIELD, 1, in(Tag.PLANKS, 6, Material.IRON_INGOT, 1), true),
        new Recipe("door", Material.OAK_DOOR, 3, in(Tag.PLANKS, 6), true),
        new Recipe("torches", Material.TORCH, 4, in(Material.COAL, 1, Material.STICK, 1), false),
        new Recipe("bed", Material.WHITE_BED, 1, in(Tag.WOOL, 3, Tag.PLANKS, 3), true),
        new Recipe("bread", Material.BREAD, 1, in(Material.WHEAT, 3), true),
        new Recipe("diamond_sword", Material.DIAMOND_SWORD, 1, in(Material.DIAMOND, 2, Material.STICK, 1), true),
        new Recipe("diamond_pickaxe", Material.DIAMOND_PICKAXE, 1, in(Material.DIAMOND, 3, Material.STICK, 2), true),
        new Recipe("wooden_hoe", Material.WOODEN_HOE, 1, in(Tag.PLANKS, 2, Material.STICK, 2), true),
        new Recipe("stone_hoe", Material.STONE_HOE, 1, in(Material.COBBLESTONE, 2, Material.STICK, 2), true),
        new Recipe("iron_hoe", Material.IRON_HOE, 1, in(Material.IRON_INGOT, 2, Material.STICK, 2), true),
        new Recipe("wooden_shovel", Material.WOODEN_SHOVEL, 1, in(Tag.PLANKS, 1, Material.STICK, 2), true),
        new Recipe("stone_shovel", Material.STONE_SHOVEL, 1, in(Material.COBBLESTONE, 1, Material.STICK, 2), true),
        new Recipe("iron_shovel", Material.IRON_SHOVEL, 1, in(Material.IRON_INGOT, 1, Material.STICK, 2), true),
        new Recipe("fence", Material.OAK_FENCE, 3, in(Tag.PLANKS, 4, Material.STICK, 2), true),
        new Recipe("fence_gate", Material.OAK_FENCE_GATE, 1, in(Tag.PLANKS, 2, Material.STICK, 4), true),
        new Recipe("chest", Material.CHEST, 1, in(Tag.PLANKS, 8), true),
        new Recipe("ladder", Material.LADDER, 3, in(Material.STICK, 7), true),
        new Recipe("bucket", Material.BUCKET, 1, in(Material.IRON_INGOT, 3), true),
        new Recipe("iron_helmet", Material.IRON_HELMET, 1, in(Material.IRON_INGOT, 5), true),
        new Recipe("iron_chestplate", Material.IRON_CHESTPLATE, 1, in(Material.IRON_INGOT, 8), true),
        new Recipe("iron_leggings", Material.IRON_LEGGINGS, 1, in(Material.IRON_INGOT, 7), true),
        new Recipe("iron_boots", Material.IRON_BOOTS, 1, in(Material.IRON_INGOT, 4), true),
        new Recipe("stone_bricks", Material.STONE_BRICKS, 4, in(Material.STONE, 4), true),
        new Recipe("glass_pane", Material.GLASS_PANE, 16, in(Material.GLASS, 6), true),
        new Recipe("campfire", Material.CAMPFIRE, 1, in(Material.STICK, 3, Material.COAL, 1, Tag.LOGS, 3), true),
        new Recipe("boat", Material.OAK_BOAT, 1, in(Tag.PLANKS, 5), true)
    );
}
