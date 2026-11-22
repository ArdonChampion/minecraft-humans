package dev.timur.humans.tasks;

import dev.timur.humans.*;
import org.bukkit.Location;
import org.bukkit.Particle;

/** Humans can start a family: two fed adults with enough food have a baby, who grows up over time. */
public final class Family {
    private Family() {}

    public static int foodItems(Human h) { return h.inv.countWhere(Items::isFood); }

    /** Null when they can, otherwise the reason. */
    public static String cantHaveBaby(Human h, Human o, HumanManager m) {
        var cfg = HumansPlugin.get().getConfig();
        if (o == null || !o.valid()) return "that human isn't here";
        if (o == h) return "you need a partner";
        if (h.isChild() || o.isChild()) return "children can't have babies";
        if (h.loc().getWorld() != o.loc().getWorld() || h.loc().distance(o.loc()) > 32) return o.name + " is too far away";
        if (h.hunger < 12 || o.hunger < 12) return "you both need to be well fed first (hunger 12+)";
        if (foodItems(h) + foodItems(o) < cfg.getInt("family.food_needed", 6)) return "you need at least " + cfg.getInt("family.food_needed", 6) + " food items between you to feed a baby";
        long cd = 20L * 60 * cfg.getInt("family.cooldown_minutes", 20);
        if (m.now() - h.lastBabyTick < cd || m.now() - o.lastBabyTick < cd) return "you had a baby recently";
        long alive = m.all().stream().filter(x -> !x.dead).count();
        if (alive >= cfg.getInt("family.max_humans", 16)) return "there are already too many humans around";
        if (o.sleeping || o.commitUntil > m.now()) return o.name + " is busy right now";
        return null;
    }

    public static final class HaveBaby extends Task {
        private final Human partner; private int together;
        public HaveBaby(Human partner) { this.partner = partner; }
        public String label() { return "spending time with " + partner.name; }
        public int maxTicks() { return 20 * 60; }
        protected Status run(Human h, HumanManager m) {
            String why = together == 0 ? cantHaveBaby(h, partner, m) : null;
            if (why != null && age > 1) { outcome = "couldn't have a baby: " + why; return Status.FAIL; }
            if (age == 1 && why != null) { outcome = "couldn't have a baby: " + why; return Status.FAIL; }
            if (partner.loc().distance(h.loc()) > 2.4) {
                if (!h.nav.active() || age % 20 == 0) h.nav.moveTo(partner.loc(), 1.8, false);
                if (h.nav.tick() == Navigator.Result.FAILED && age > 100) { outcome = "couldn't get to " + partner.name; return Status.FAIL; }
                return Status.RUNNING;
            }
            h.nav.stop();
            if (together == 0) { // the partner stops what they're doing
                if (partner.task != null) partner.task.stop(partner);
                partner.task = new Tasks.Idle(20 * 8, "spending time with " + h.name);
                partner.taskStartTick = m.now();
            }
            together++;
            if (age % 4 == 0) { Act.lookAt(h, partner.entity.getEyeLocation()); Act.lookAt(partner, h.entity.getEyeLocation()); }
            if (together % 15 == 0) h.entity.getWorld().spawnParticle(Particle.HEART, h.loc().add(0, 2.1, 0).add(partner.loc()).multiply(0.5).add(0, 1.0, 0), 2, 0.3, 0.2, 0.3);
            if (together < 100) return Status.RUNNING;
            // feed the family: 3 food items each (from whoever has them)
            int need = HumansPlugin.get().getConfig().getInt("family.food_used", 6);
            for (int k = 0; k < need; k++) {
                Human from = foodItems(h) >= foodItems(partner) ? h : partner;
                Items.bestFood(from.inv).ifPresent(f -> from.inv.remove(f, 1));
            }
            Location at = h.loc().clone().add(partner.loc()).multiply(0.5);
            Human baby = m.spawnChild(h, partner, at);
            h.lastBabyTick = m.now(); partner.lastBabyTick = m.now();
            String msg = "had a baby with " + (baby == null ? partner.name : partner.name + " named " + baby.name);
            h.remember("I " + msg + "!");
            partner.remember("I had a baby with " + h.name + (baby == null ? "" : " named " + baby.name) + "!");
            h.express(Expression.HAPPY, 120, HumansPlugin.get(), m.now());
            partner.express(Expression.HAPPY, 120, HumansPlugin.get(), m.now());
            outcome = msg;
            return Status.SUCCESS;
        }
    }
}
