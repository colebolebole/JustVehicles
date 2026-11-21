package nl.mtvehicles.core.api;

import nl.mtvehicles.core.infrastructure.hvac.HvacManager;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleFinder;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Public API of the vehicles' climate control (car HVAC), for dashboards such as EZStats.
 * <p>
 * It is made of static methods using only plain Java types (strings, numbers, maps and lists), so that another plugin can use it by reflection
 * without depending on this plugin's classes. <b>Call everything on the main server thread.</b>
 * All temperatures are in degrees Fahrenheit.
 * <p>
 * A vehicle's climate control is <i>linked</i> by its owner (<code>/vehicle hvac link</code>). Only linked vehicles can be read and changed through this API,
 * and only by their owner. See docs/car-climate-api.md.
 *
 * @since 2.5.9
 */
public final class CarClimateApi {
    private CarClimateApi() {}

    /**
     * Version of this API. It is raised when something is added; nothing that exists is changed without a new major version.
     */
    public static final int API_VERSION = 1;

    // --- Finding vehicles ---

    /**
     * Whether the climate control is switched on in the config (hvac.enabled).
     */
    public static boolean isAvailable() {
        return HvacManager.isEnabled();
    }

    /**
     * The license plate of the vehicle with a climate control a player is sitting in.
     * @return Null if the player is not sitting in such a vehicle
     */
    public static @Nullable String licenseOf(Player player) {
        final String license = HvacManager.getLicenseOf(player);
        return license != null && HvacManager.appliesTo(license) ? license : null;
    }

    /**
     * The owner of a vehicle.
     * @return Null if the vehicle does not exist
     */
    public static @Nullable UUID ownerOf(String license) {
        return VehicleFinder.getOwner(license);
    }

    /**
     * Whether a vehicle's climate control is linked to a dashboard.
     */
    public static boolean isLinked(String license) {
        return HvacManager.appliesTo(license) && HvacManager.isLinked(license);
    }

    /**
     * The license plates of all linked vehicles.
     */
    public static List<String> getLinked() {
        final List<String> linked = new ArrayList<>();
        for (String license : ConfigModule.vehicleDataConfig.getVehicles().keySet()) {
            if (isLinked(license)) linked.add(license);
        }
        return linked;
    }

    /**
     * The license plates of the linked vehicles of an owner.
     */
    public static List<String> getLinkedOf(UUID owner) {
        final List<String> linked = new ArrayList<>();
        for (String license : getLinked()) {
            if (owner.equals(VehicleFinder.getOwner(license))) linked.add(license);
        }
        return linked;
    }

    // --- Reading ---

    /**
     * Describe a vehicle's climate control. Keys (absent when unknown):
     * <pre>
     * license, name, owner (UUID as text), linked (boolean),
     * on (boolean), mode ("auto", "cool" or "heat"), fan ("auto", "1", "2" or "3"), targetF, minTargetF, maxTargetF,
     * status ("off", "idle", "cooling" or "heating"), running (boolean),
     * cabinF, ambientF (only while the vehicle has been driven or started remotely recently),
     * remote (boolean - running without a driver), remoteSecondsLeft, remoteStartMinutes,
     * loaded (boolean - the vehicle is spawned in a loaded chunk), occupied (boolean), world, x, y, z (only when loaded)
     * </pre>
     * @return Null if the vehicle doesn't exist or has no climate control
     */
    public static @Nullable Map<String, Object> describe(String license) {
        if (!HvacManager.appliesTo(license)) return null;
        final HvacManager.Climate climate = HvacManager.getForControl(license);
        if (climate == null) return null;

        final Map<String, Object> map = new HashMap<>();
        map.put("license", license);
        final Object name = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.NAME);
        if (name != null) map.put("name", name.toString());
        final UUID owner = VehicleFinder.getOwner(license);
        if (owner != null) map.put("owner", owner.toString());
        map.put("linked", climate.isLinked());

        map.put("on", climate.isOn());
        map.put("mode", climate.getMode().name().toLowerCase(Locale.ROOT));
        map.put("fan", climate.getFan() == 0 ? "auto" : String.valueOf(climate.getFan()));
        map.put("targetF", climate.getTargetF());
        map.put("minTargetF", HvacManager.getMinTargetF());
        map.put("maxTargetF", HvacManager.getMaxTargetF());
        map.put("status", climate.getStatus());
        map.put("running", climate.isRunning());
        if (!Double.isNaN(climate.getCabinF())) map.put("cabinF", climate.getCabinF());
        if (!Double.isNaN(climate.getAmbientF())) map.put("ambientF", climate.getAmbientF());

        final boolean remote = climate.isRemoteRunning();
        map.put("remote", remote);
        if (remote) map.put("remoteSecondsLeft", Math.max(0L, (climate.getRemoteUntil() - System.currentTimeMillis()) / 1000L));
        map.put("remoteStartMinutes", HvacManager.getRemoteStartMinutesSetting());

        final VehicleFinder.SpawnedVehicle spawned = VehicleFinder.findSpawned(false).get(license);
        map.put("loaded", spawned != null);
        if (spawned != null) {
            map.put("occupied", spawned.isOccupied());
            final Location location = spawned.getLocation();
            if (location.getWorld() != null) {
                map.put("world", location.getWorld().getName());
                map.put("x", location.getBlockX());
                map.put("y", location.getBlockY());
                map.put("z", location.getBlockZ());
            }
        }
        return map;
    }

    // --- Changing ---

    /**
     * Link a vehicle's climate control to a dashboard, or unlink it. Only the owner may.
     * @return Result (see {@link #result(boolean, int, String, boolean)})
     */
    public static Map<String, Object> link(UUID actor, String license, boolean linked) {
        if (!HvacManager.appliesTo(license)) return result(false, 404, "That vehicle has no climate control.", false);
        if (!actor.equals(VehicleFinder.getOwner(license))) return result(false, 403, "That vehicle isn't yours.", false);

        HvacManager.setLinked(license, linked);
        return result(true, 200, linked ? "Linked." : "Unlinked.", false);
    }

    /**
     * Switch the climate control on or off. Switching it on while nobody sits in the (spawned) vehicle starts it remotely for a while.
     */
    public static Map<String, Object> setEnabled(UUID actor, String license, boolean on) {
        final Map<String, Object> failure = check(actor, license);
        if (failure != null) return failure;

        final HvacManager.Climate climate = HvacManager.getForControl(license);
        HvacManager.setOn(climate, on);

        if (!on) {
            if (HvacManager.peek(license) != null) HvacManager.stopRemote(HvacManager.peek(license));
            return result(true, 200, "Switched off.", false);
        }
        final String problem = HvacManager.startRemote(license);
        return problem == null ? result(true, 200, "Switched on.", false) : result(true, 200, problem, true);
    }

    /**
     * Set the target temperature (kept within the configured range).
     */
    public static Map<String, Object> setTargetF(UUID actor, String license, double targetF) {
        final Map<String, Object> failure = check(actor, license);
        if (failure != null) return failure;
        if (Double.isNaN(targetF) || Double.isInfinite(targetF)) return result(false, 400, "That is not a temperature.", false);

        final HvacManager.Climate climate = HvacManager.getForControl(license);
        HvacManager.setTargetF(climate, targetF);
        final boolean adjusted = Math.abs(climate.getTargetF() - targetF) > 0.001;
        return result(true, 200, adjusted ? "Kept within its range: " + Math.round(climate.getTargetF()) + "°F." : "Done.", adjusted);
    }

    /**
     * Set the mode: "auto", "cool" or "heat".
     */
    public static Map<String, Object> setMode(UUID actor, String license, String mode) {
        final Map<String, Object> failure = check(actor, license);
        if (failure != null) return failure;

        final HvacManager.Mode parsed;
        try {
            parsed = HvacManager.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return result(false, 400, "The mode must be auto, cool or heat.", false);
        }
        HvacManager.setMode(HvacManager.getForControl(license), parsed);
        return result(true, 200, "Done.", false);
    }

    /**
     * Set the fan: "auto", "1", "2" or "3".
     */
    public static Map<String, Object> setFan(UUID actor, String license, String fan) {
        final Map<String, Object> failure = check(actor, license);
        if (failure != null) return failure;

        final int level;
        if (fan.equalsIgnoreCase("auto")) {
            level = 0;
        } else {
            try {
                level = Integer.parseInt(fan);
            } catch (NumberFormatException e) {
                return result(false, 400, "The fan must be auto, 1, 2 or 3.", false);
            }
            if (level < 1 || level > 3) return result(false, 400, "The fan must be auto, 1, 2 or 3.", false);
        }
        HvacManager.setFan(HvacManager.getForControl(license), level);
        return result(true, 200, "Done.", false);
    }

    // --- Helpers ---

    /**
     * Check that a vehicle can be changed by an actor: it has a climate control, is linked, and is theirs.
     * @return Null if the change may be made, otherwise the failure
     */
    private static @Nullable Map<String, Object> check(UUID actor, String license) {
        if (!HvacManager.appliesTo(license)) return result(false, 404, "That vehicle has no climate control any more.", false);
        if (!actor.equals(VehicleFinder.getOwner(license))) return result(false, 403, "That vehicle isn't yours.", false);
        if (!HvacManager.isLinked(license)) return result(false, 403, "That vehicle's climate control isn't linked. Use /vehicle hvac link in game.", false);
        return null;
    }

    /**
     * A result: ok (Boolean), status (Integer - an HTTP status), message (String) and adjusted (Boolean - done, but not exactly as asked).
     */
    public static Map<String, Object> result(boolean ok, int status, String message, boolean adjusted) {
        final Map<String, Object> map = new HashMap<>();
        map.put("ok", ok);
        map.put("status", status);
        map.put("message", message);
        map.put("adjusted", adjusted);
        return map;
    }
}
