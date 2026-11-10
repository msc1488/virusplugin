package ru.altergen.virus;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Хранит заражения, вычисляет стадии, обновляет эффекты и запускает задачи.
 * Все методы этого класса вызываются из основного потока Bukkit.
 */
public final class VirusManager {

    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long EFFECT_DURATION_TICKS = 60L;

    private final VirusPlugin plugin;
    private final Map<UUID, InfectionData> infections = new HashMap<>();

    private final double pvpChance;
    private final double zombieChance;
    private final long stageOneDurationMillis;
    private final long stageTwoDurationMillis;
    private final long stageOneMessagePeriodMillis;
    private final long coughPeriodMillis;
    private final long luckDurationMillis;

    private BukkitTask diseaseTask;
    private BukkitTask particleTask;
    private long particleTick;

    public VirusManager(VirusPlugin plugin) {
        this.plugin = plugin;

        pvpChance = clampChance(plugin.getConfig().getDouble("infection.pvp-chance", 0.30D));
        zombieChance = clampChance(plugin.getConfig().getDouble("infection.zombie-chance", 0.15D));

        long stageOneMinutes = positiveLong(
                plugin.getConfig().getLong("infection.stage-1-minutes", 10L), 10L
        );
        long stageTwoMinutes = positiveLong(
                plugin.getConfig().getLong("infection.stage-2-minutes", 15L), 15L
        );

        stageOneDurationMillis = stageOneMinutes * MILLIS_PER_MINUTE;
        stageTwoDurationMillis = stageTwoMinutes * MILLIS_PER_MINUTE;
        stageOneMessagePeriodMillis = positiveLong(
                plugin.getConfig().getLong("infection.stage-1-message-period-seconds", 60L), 60L
        ) * MILLIS_PER_SECOND;
        coughPeriodMillis = positiveLong(
                plugin.getConfig().getLong("infection.cough-period-seconds", 30L), 30L
        ) * MILLIS_PER_SECOND;
        luckDurationMillis = positiveLong(
                plugin.getConfig().getLong("infection.luck-duration-seconds", 30L), 30L
        ) * MILLIS_PER_SECOND;
    }

    public void load() {
        infections.clear();

        ConfigurationSection players = plugin.getConfig().getConfigurationSection("players");
        if (players == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String uuidString : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                String path = uuidString;

                if (!players.getBoolean(path + ".infected", true)) {
                    continue;
                }

                long infectedAt = players.getLong(path + ".infected-at", now);
                int savedStage = players.getInt(path + ".stage", 1);
                Stage stage = Stage.fromNumber(savedStage);
                long luckUntil = players.getLong(path + ".luck-until", 0L);

                // Время заражения является источником истины, а stage сохраняется
                // дополнительно для удобства просмотра config.yml.
                Stage actualStage = calculateStage(infectedAt, now);
                if (stage != actualStage) {
                    stage = actualStage;
                }

                infections.put(uuid, new InfectionData(infectedAt, stage, luckUntil));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Пропущена некорректная UUID в config.yml: " + uuidString);
            }
        }
    }

    public void startTasks() {
        diseaseTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickDisease();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Bukkit API мира нельзя вызывать из async-задачи, поэтому частицы
        // создаются синхронно. world.spawnParticle отправляет их клиентам,
        // находящимся в радиусе стриминга мира.
        particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickParticles();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public void shutdown() {
        if (diseaseTask != null) {
            diseaseTask.cancel();
        }
        if (particleTask != null) {
            particleTask.cancel();
        }
        save();
    }

    public boolean isInfected(Player player) {
        return player != null && isInfected(player.getUniqueId());
    }

    public boolean isInfected(UUID uuid) {
        return infections.containsKey(uuid);
    }

    /**
     * Заражает игрока. Метод также работает для офлайн-игрока: его состояние
     * применится, когда он войдет на сервер.
     */
    public boolean infect(UUID uuid) {
        if (infections.containsKey(uuid)) {
            return false;
        }

        long now = System.currentTimeMillis();
        infections.put(uuid, new InfectionData(now, Stage.ONE, 0L));
        save();

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.sendMessage("§cВы заражены вирусом. Берегите себя и найдите лечебный отвар.");
        }
        return true;
    }

    /**
     * Полностью исцеляет игрока и удаляет все эффекты, которыми управляет
     * этот плагин. Частицы исчезают автоматически, так как игрок удаляется
     * из карты зараженных.
     */
    public boolean cure(UUID uuid) {
        InfectionData removed = infections.remove(uuid);
        if (removed == null) {
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            clearDiseaseEffects(player);
            player.sendMessage("§aВирус полностью вылечен!");
        }

        save();
        return true;
    }

    public void handleJoin(Player player) {
        InfectionData data = infections.get(player.getUniqueId());
        if (data == null) {
            return;
        }

        long now = System.currentTimeMillis();
        updateStage(data, player, now);
        applyCurrentEffects(player, data, now);
    }

    private void tickDisease() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, InfectionData> entry : infections.entrySet()) {
            InfectionData data = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());

            updateStage(data, player, now);

            if (player == null) {
                continue;
            }

            applyCurrentEffects(player, data, now);
            long elapsed = Math.max(0L, now - data.infectedAt);

            if (data.stage == Stage.ONE) {
                if (elapsed >= stageOneMessagePeriodMillis
                        && now - data.lastStageOneMessageAt >= stageOneMessagePeriodMillis) {
                    player.sendMessage("§eВы чувствуете легкое недомогание...");
                    data.lastStageOneMessageAt = now;
                }
            } else if (data.stage == Stage.TWO) {
                if (now - data.lastCoughAt >= coughPeriodMillis) {
                    player.playSound(
                            player.getLocation(),
                            Sound.ENTITY_PLAYER_HURT,
                            SoundCategory.PLAYERS,
                            0.8F,
                            0.8F
                    );
                    data.lastCoughAt = now;
                }
            }
        }
    }

    private void tickParticles() {
        particleTick++;

        for (Map.Entry<UUID, InfectionData> entry : infections.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }

            InfectionData data = entry.getValue();
            Stage stage = calculateStage(data.infectedAt, System.currentTimeMillis());
            World world = player.getWorld();
            Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);

            if (stage == Stage.TWO) {
                // Задача работает раз в 10 тиков, поэтому каждые 6 запусков = 1 секунда.
                if (particleTick % 6L == 0L) {
                    world.spawnParticle(
                            Particle.ENTITY_EFFECT,
                            location,
                            8,
                            0.45D,
                            0.75D,
                            0.45D,
                            1.0D,
                            Color.fromRGB(45, 190, 70)
                    );
                }
            } else if (stage == Stage.THREE) {
                // Темные SQUID_INK подходят для визуализации запущенной болезни.
                world.spawnParticle(
                        Particle.SQUID_INK,
                        location,
                        28,
                        0.65D,
                        1.0D,
                        0.65D,
                        0.02D
                );
            }
        }
    }

    private void updateStage(InfectionData data, Player player, long now) {
        Stage newStage = calculateStage(data.infectedAt, now);
        if (newStage == data.stage) {
            return;
        }

        Stage oldStage = data.stage;
        data.stage = newStage;

        if (newStage == Stage.TWO) {
            data.lastCoughAt = now;
        }
        if (newStage == Stage.THREE) {
            data.luckUntil = now + luckDurationMillis;
        }

        if (player == null) {
            return;
        }

        switch (newStage) {
            case TWO -> player.sendMessage("§6Стадия 2: вирус усиливается. Вам становится тяжелее двигаться.");
            case THREE -> player.sendMessage("§4Стадия 3 (Пик): вирус достиг максимальной силы!");
            case ONE -> player.sendMessage("§aСимптомы вируса ослабли.");
        }

        // oldStage используется явно, чтобы при переходе не оставались эффекты
        // предыдущей стадии. Само поддержание выполняется ниже каждую секунду.
        if (oldStage != newStage) {
            removeEffectsOfOtherStages(player, newStage);
        }
    }

    private void applyCurrentEffects(Player player, InfectionData data, long now) {
        switch (data.stage) {
            case ONE -> clearDiseaseEffects(player);
            case TWO -> {
                player.removePotionEffect(PotionEffectType.WEAKNESS);
                player.removePotionEffect(PotionEffectType.LUCK);
                addEffect(player, PotionEffectType.HUNGER, EFFECT_DURATION_TICKS);
                addEffect(player, PotionEffectType.SLOWNESS, EFFECT_DURATION_TICKS);
            }
            case THREE -> {
                player.removePotionEffect(PotionEffectType.HUNGER);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                addEffect(player, PotionEffectType.WEAKNESS, EFFECT_DURATION_TICKS);

                if (data.luckUntil > now) {
                    long remainingTicks = Math.max(1L, (data.luckUntil - now + 49L) / 50L);
                    addEffect(player, PotionEffectType.LUCK,
                            Math.min(Integer.MAX_VALUE, remainingTicks));
                } else {
                    player.removePotionEffect(PotionEffectType.LUCK);
                }
            }
        }
    }

    private void removeEffectsOfOtherStages(Player player, Stage newStage) {
        switch (newStage) {
            case ONE -> clearDiseaseEffects(player);
            case TWO -> {
                player.removePotionEffect(PotionEffectType.WEAKNESS);
                player.removePotionEffect(PotionEffectType.LUCK);
            }
            case THREE -> {
                player.removePotionEffect(PotionEffectType.HUNGER);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
            }
        }
    }

    private void addEffect(Player player, PotionEffectType type, long durationTicks) {
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, durationTicks));
        player.addPotionEffect(new PotionEffect(type, duration, 0, false, false, true), true);
    }

    private void clearDiseaseEffects(Player player) {
        player.removePotionEffect(PotionEffectType.HUNGER);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.WEAKNESS);
        player.removePotionEffect(PotionEffectType.LUCK);
    }

    private Stage calculateStage(long infectedAt, long now) {
        long elapsed = Math.max(0L, now - infectedAt);
        if (elapsed < stageOneDurationMillis) {
            return Stage.ONE;
        }
        if (elapsed < stageOneDurationMillis + stageTwoDurationMillis) {
            return Stage.TWO;
        }
        return Stage.THREE;
    }

    private void save() {
        plugin.getConfig().set("players", null);

        for (Map.Entry<UUID, InfectionData> entry : infections.entrySet()) {
            UUID uuid = entry.getKey();
            InfectionData data = entry.getValue();
            String path = "players." + uuid;
            Stage actualStage = calculateStage(data.infectedAt, System.currentTimeMillis());
            data.stage = actualStage;

            plugin.getConfig().set(path + ".infected", true);
            plugin.getConfig().set(path + ".infected-at", data.infectedAt);
            plugin.getConfig().set(path + ".stage", actualStage.number);
            plugin.getConfig().set(path + ".luck-until", data.luckUntil);
        }

        plugin.saveConfig();
    }

    private static double clampChance(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static long positiveLong(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    public double getPvpChance() {
        return pvpChance;
    }

    public double getZombieChance() {
        return zombieChance;
    }

    public enum Stage {
        ONE(1),
        TWO(2),
        THREE(3);

        private final int number;

        Stage(int number) {
            this.number = number;
        }

        private static Stage fromNumber(int number) {
            return switch (number) {
                case 2 -> TWO;
                case 3 -> THREE;
                default -> ONE;
            };
        }
    }

    private static final class InfectionData {
        private final long infectedAt;
        private Stage stage;
        private long luckUntil;
        private long lastStageOneMessageAt;
        private long lastCoughAt;

        private InfectionData(long infectedAt, Stage stage, long luckUntil) {
            this.infectedAt = infectedAt;
            this.stage = stage;
            this.luckUntil = luckUntil;
        }
    }
}
