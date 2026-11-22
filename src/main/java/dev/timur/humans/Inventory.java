package dev.timur.humans;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/** A human's inventory: plain material counts (no NBT) — enough for gathering, crafting and trading. */
public final class Inventory {
    private final EnumMap<Material, Integer> items = new EnumMap<>(Material.class);

    public int count(Material m) { return items.getOrDefault(m, 0); }
    public boolean has(Material m, int n) { return count(m) >= n; }
    public void add(Material m, int n) { if (n > 0) items.merge(m, n, Integer::sum); }
    public void add(ItemStack s) { if (s != null && !s.getType().isAir()) add(s.getType(), s.getAmount()); }

    public boolean remove(Material m, int n) {
        int c = count(m);
        if (c < n) return false;
        if (c == n) items.remove(m); else items.put(m, c - n);
        return true;
    }

    public Inventory copy() { Inventory c = new Inventory(); c.items.putAll(items); return c; }

    public Map<Material, Integer> view() { return Collections.unmodifiableMap(items); }
    public void clear() { items.clear(); }
    public boolean isEmpty() { return items.isEmpty(); }

    public int countWhere(java.util.function.Predicate<Material> p) {
        int n = 0;
        for (var e : items.entrySet()) if (p.test(e.getKey())) n += e.getValue();
        return n;
    }

    /** First material matching p that we have (most numerous first). */
    public Optional<Material> any(java.util.function.Predicate<Material> p) {
        return items.entrySet().stream().filter(e -> p.test(e.getKey()))
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    public boolean removeAny(java.util.function.Predicate<Material> p, int n) {
        if (countWhere(p) < n) return false;
        int left = n;
        for (Material m : new ArrayList<>(items.keySet())) {
            if (!p.test(m)) continue;
            int take = Math.min(left, count(m));
            remove(m, take);
            left -= take;
            if (left == 0) break;
        }
        return true;
    }

    public String describe() {
        if (items.isEmpty()) return "nothing";
        return items.entrySet().stream()
            .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
            .limit(14)
            .map(e -> e.getValue() + " " + Items.nice(e.getKey()))
            .collect(Collectors.joining(", "));
    }

    public Map<String, Integer> serialize() {
        Map<String, Integer> m = new LinkedHashMap<>();
        items.forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    public void deserialize(Map<String, Object> m) {
        items.clear();
        if (m == null) return;
        m.forEach((k, v) -> {
            Material mat = Material.matchMaterial(k);
            if (mat != null && v instanceof Number n) add(mat, n.intValue());
        });
    }
}
