package dev.timur.humans;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class HumanListener implements Listener {
    private final HumansPlugin plugin;

    public HumanListener(HumansPlugin plugin) { this.plugin = plugin; }

    private HumanManager m() { return plugin.manager(); }

    @EventHandler public void onAdd(EntityAddToWorldEvent e) { m().onEntityAdded(e.getEntity()); }
    @EventHandler public void onRemove(EntityRemoveFromWorldEvent e) { m().onEntityRemoved(e.getEntity()); }

    private static Entity source(Entity damager) {
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) return shooter;
        if (damager instanceof TNTPrimed t && t.getSource() != null) return t.getSource();
        return damager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        Entity src = source(e.getDamager());
        Human victim = m().byEntity(e.getEntity());
        if (victim != null) {
            if (m().byEntity(src) != null) return; // humans don't hold grudges against each other (yet)
            m().onHurt(victim, src, e.getFinalDamage());
            return;
        }
        if (e.getEntity() instanceof Player p && src instanceof Monster) m().onPlayerAttacked(p, src);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        Human h = m().byEntity(e.getEntity());
        if (h != null) m().onHurt(h, null, e.getFinalDamage());
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Human h = m().byEntity(e.getEntity());
        if (h == null) return;
        Entity killer = e.getEntity().getKiller();
        if (killer == null && e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent by) killer = source(by.getDamager());
        m().onDeath(h, killer, e.getDrops());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Human h = m().byEntity(e.getRightClicked());
        if (h == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && !hand.getType().isAir()) {
            ItemStack gift = hand.clone();
            p.getInventory().setItemInMainHand(null);
            h.inv.add(gift);
            m().receivedGift(h, p, gift);
            return;
        }
        // info card
        double tr = h.trustOf(p.getUniqueId());
        p.sendMessage(Component.text("— " + h.name + " —", NamedTextColor.GOLD));
        p.sendMessage(Component.text(h.personality.describe(), NamedTextColor.GRAY));
        p.sendMessage(Component.text(String.format("Health %.0f/20  Hunger %.0f/20  Mood: %s  Feels: %s you",
            h.entity.getHealth(), h.hunger, h.mood.id(), Human.trustWord(tr)), NamedTextColor.WHITE));
        p.sendMessage(Component.text("Doing: " + (h.task == null ? "thinking" : h.task.label()) + (h.hutBuilt ? "  |  has a house" : ""), NamedTextColor.WHITE));
        p.sendMessage(Component.text("Carrying: " + h.inv.describe(), NamedTextColor.DARK_GRAY));
        p.sendMessage(Component.text("Tip: sneak + right-click to give the item in your hand. Talk to " + h.name + " in chat.", NamedTextColor.DARK_AQUA));
        dev.timur.humans.tasks.Act.lookAt(h, p.getEyeLocation());
        if (tr >= 2.5) h.express(Expression.HAPPY, 30, plugin, m().now());
        else if (tr < 1.2) h.express(Expression.ANGRY, 30, plugin, m().now());
        else h.express(Expression.SURPRISED, 20, plugin, m().now());
    }
}
