package nl.mtvehicles.core.infrastructure.utils;

import nl.mtvehicles.core.infrastructure.dataconfig.DefaultConfig;
import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import nl.mtvehicles.core.infrastructure.enums.Message;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleData;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;

/**
 * Methods for BossBars
 */
public class BossBarUtils {
    /**
     * Fuel bossbar
     */
    public static HashMap<String, BossBar> Fuelbar = new HashMap<>();

    /**
     * Last displayed percentage of each bossbar. Updating a bossbar sends a packet to its viewers,
     * and {@link #setBossBarValue(double, String)} is called every tick for each driven vehicle - so only changes are sent.
     */
    private static final HashMap<String, Integer> lastPercent = new HashMap<>();
    private static final HashMap<String, BarColor> lastColor = new HashMap<>();

    private static BarColor getColor(double fuel) {
        if (fuel < 30) return BarColor.RED;
        if (fuel < 60) return BarColor.YELLOW;
        return BarColor.GREEN;
    }

    /**
     * Set bossbar's fuel amount
     * @param counter Fuel amount
     * @param licensePlate Vehicle's license plate (this vehicle's fuel is displayed)
     */
    public static void setBossBarValue(double counter, String licensePlate) {
        if ((boolean) ConfigModule.defaultConfig.get(DefaultConfig.Option.FUEL_ENABLED) && (boolean) ConfigModule.vehicleDataConfig.get(licensePlate, VehicleDataConfig.Option.FUEL_ENABLED)) {

            final BossBar bar = Fuelbar.get(licensePlate);
            if (bar == null) return;

            counter = Math.max(0.0, Math.min(1.0, counter)); // setProgress() throws an exception for values outside of 0-1

            final int percent = (int) Math.round(counter * 100.0D);
            final Integer previousPercent = lastPercent.get(licensePlate);
            if (previousPercent == null || previousPercent != percent) {
                bar.setProgress(counter);
                bar.setTitle(percent + "% " + TextUtils.colorize(ConfigModule.messagesConfig.getMessage(Message.BOSSBAR_FUEL)));
                lastPercent.put(licensePlate, percent);
            }

            final Double fuel = VehicleData.fuel.get(licensePlate);
            if (fuel == null || fuel >= 100) return;

            final BarColor color = getColor(fuel);
            if (lastColor.get(licensePlate) != color) {
                bar.setColor(color);
                lastColor.put(licensePlate, color);
            }
        }
    }

    /**
     * Remove fuel bossbar from player
     * @param player Player
     * @param licensePlate Vehicle's license plate (this vehicle's fuel is displayed)
     */
    public static void removeBossBar(Player player, String licensePlate) {
        if ((boolean) ConfigModule.defaultConfig.get(DefaultConfig.Option.FUEL_ENABLED) && (boolean) ConfigModule.vehicleDataConfig.get(licensePlate, VehicleDataConfig.Option.FUEL_ENABLED)) {
            final BossBar bar = Fuelbar.get(licensePlate);
            if (bar == null) return;
            bar.removePlayer(player);
            if (bar.getPlayers().isEmpty()) { // nobody sees it anymore - don't keep it in memory
                Fuelbar.remove(licensePlate);
                lastPercent.remove(licensePlate);
                lastColor.remove(licensePlate);
            }
        }
    }

    /**
     * Show fuel bossbar for a player
     * @param player Player
     * @param licensePlate Vehicle's license plate (this vehicle's fuel is displayed)
     */
    public static void addBossBar(Player player, String licensePlate) {
        if ((boolean) ConfigModule.defaultConfig.get(DefaultConfig.Option.FUEL_ENABLED) && (boolean) ConfigModule.vehicleDataConfig.get(licensePlate, VehicleDataConfig.Option.FUEL_ENABLED)) {
            Object fuelValue = ConfigModule.vehicleDataConfig.get(licensePlate, VehicleDataConfig.Option.FUEL);
            double fuel = fuelValue instanceof Number ? ((Number) fuelValue).doubleValue() : 0.0;
            final BarColor color = getColor(fuel); // (the colours used to be overwritten, so the bar was always green)
            BossBar bar = Bukkit.createBossBar(Math.round(fuel) + "% " + TextUtils.colorize(ConfigModule.messagesConfig.getMessage(Message.BOSSBAR_FUEL)), color, BarStyle.SOLID);
            Fuelbar.put(licensePlate, bar);
            lastPercent.remove(licensePlate);
            lastColor.put(licensePlate, color);
            bar.addPlayer(player);
        }
    }
}
