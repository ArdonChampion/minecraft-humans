package dev.timur.humans;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;

/**
 * Lets chosen non-op players use every /humans command (permission humans.admin) without a permissions plugin.
 * The list lives in config.yml under "admins" (player UUIDs). Only ops / the console can change it.
 */
public final class AdminAccess implements Listener {
    private final HumansPlugin plugin;
    private final Map<UUID, PermissionAttachment> attached = new HashMap<>();

    public AdminAccess(HumansPlugin plugin) { this.plugin = plugin; }

    public Set<UUID> admins() {
        Set<UUID> out = new LinkedHashSet<>();
        for (String s : plugin.getConfig().getStringList("admins")) try { out.add(UUID.fromString(s.trim())); } catch (Exception ignored) {}
        return out;
    }

    private void saveList(Set<UUID> ids) {
        List<String> l = new ArrayList<>();
        for (UUID u : ids) l.add(u.toString());
        plugin.getConfig().set("admins", l);
        plugin.saveConfig();
    }

    public boolean add(OfflinePlayer p) {
        Set<UUID> ids = admins();
        boolean added = ids.add(p.getUniqueId());
        saveList(ids);
        if (p.isOnline()) grant(p.getPlayer());
        return added;
    }

    public boolean remove(OfflinePlayer p) {
        Set<UUID> ids = admins();
        boolean removed = ids.remove(p.getUniqueId());
        saveList(ids);
        PermissionAttachment a = attached.remove(p.getUniqueId());
        if (a != null && p.isOnline()) { p.getPlayer().removeAttachment(a); p.getPlayer().updateCommands(); }
        return removed;
    }

    void grant(Player p) {
        if (attached.containsKey(p.getUniqueId())) return;
        PermissionAttachment a = p.addAttachment(plugin);
        a.setPermission("humans.admin", true);
        attached.put(p.getUniqueId(), a);
        p.updateCommands();
    }

    public void applyOnline() { for (Player p : Bukkit.getOnlinePlayers()) if (admins().contains(p.getUniqueId())) grant(p); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        attached.remove(e.getPlayer().getUniqueId()); // attachments die with the old session
        if (admins().contains(e.getPlayer().getUniqueId())) grant(e.getPlayer());
    }
}
