package ru.altergen.virus;

import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.ThreadLocalRandom;

/** Передача вируса от зараженного игрока или зомби. */
public final class InfectionListener implements Listener {

    private final VirusManager virusManager;

    public InfectionListener(VirusManager virusManager) {
        this.virusManager = virusManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        virusManager.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || virusManager.isInfected(victim)) {
            return;
        }

        double chance;
        if (event.getDamager() instanceof Player attacker
                && virusManager.isInfected(attacker)) {
            chance = virusManager.getPvpChance();
        } else if (event.getDamager() instanceof Zombie) {
            chance = virusManager.getZombieChance();
        } else {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() < chance) {
            virusManager.infect(victim.getUniqueId());
        }
    }
}
