package nl.mtvehicles.core.infrastructure.vehicle;

import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds vehicles which are currently spawned in the worlds (only in loaded chunks), and moves/removes them.
 * <p>
 * Scanning the entities is relatively expensive, so it is done in <b>one pass</b> for all vehicles (instead of one pass per vehicle),
 * and the result is cached for a moment (see {@link #findSpawned(boolean)}).
 *
 * @since 2.5.9
 */
public final class VehicleFinder {
    private VehicleFinder() {}

    private static final String PREFIX = "MTVEHICLES_";
    private static final String SKIN_PREFIX = "MTVEHICLES_SKIN_";
    private static final String MAIN_PREFIX = "MTVEHICLES_MAIN_";
    private static final long CACHE_MILLIS = 1000L;

    private static Map<String, SpawnedVehicle> cache = new HashMap<>();
    private static long cacheTime = 0L;

    /**
     * A vehicle which is spawned in a (loaded part of a) world
     */
    public static final class SpawnedVehicle {
        private final String licensePlate;
        private Location location;
        private int locationPriority = 0;
        private boolean occupied = false;
        private final List<Entity> entities = new ArrayList<>();

        private SpawnedVehicle(String licensePlate) {
            this.licensePlate = licensePlate;
        }

        public String getLicensePlate() {
            return licensePlate;
        }

        /**
         * Location of the vehicle (of its skin armor stand)
         */
        public Location getLocation() {
            return location.clone();
        }

        /**
         * Whether someone is sitting in the vehicle
         */
        public boolean isOccupied() {
            return occupied;
        }

        /**
         * All armor stands of the vehicle
         */
        public List<Entity> getEntities() {
            return entities;
        }
    }

    /**
     * Find all spawned vehicles (only vehicles in loaded chunks can be found).
     * @param forceFresh If false, a result which is at most a second old may be returned
     * @return License plate -> spawned vehicle
     */
    public static Map<String, SpawnedVehicle> findSpawned(boolean forceFresh) {
        final long now = System.currentTimeMillis();
        if (!forceFresh && now - cacheTime < CACHE_MILLIS) return cache;

        final Map<String, SpawnedVehicle> found = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                final String name = stand.getCustomName();
                if (name == null || !name.startsWith(PREFIX)) continue;

                final String license = VehicleUtils.getLicensePlate(stand);
                if (license == null) continue;

                SpawnedVehicle vehicle = found.get(license);
                if (vehicle == null) {
                    vehicle = new SpawnedVehicle(license);
                    found.put(license, vehicle);
                }
                vehicle.entities.add(stand);
                if (!stand.isEmpty()) vehicle.occupied = true;

                // The skin is the best reference for the vehicle's location, then the main stand, then any other
                final int priority = name.startsWith(SKIN_PREFIX) ? 3 : name.startsWith(MAIN_PREFIX) ? 2 : 1;
                if (priority > vehicle.locationPriority) {
                    vehicle.locationPriority = priority;
                    vehicle.location = stand.getLocation();
                }
            }
        }

        cache = found;
        cacheTime = now;
        return found;
    }

    /**
     * Get a spawned vehicle (fresh data).
     * @return Null if the vehicle is not spawned (or its chunk is not loaded)
     */
    public static @Nullable SpawnedVehicle getSpawned(String licensePlate) {
        return findSpawned(true).get(licensePlate);
    }

    /**
     * Whether the vehicle is currently spawned in a world.
     */
    public static boolean isSpawned(String licensePlate) {
        return getSpawned(licensePlate) != null;
    }

    /**
     * Get the UUID of the owner of a vehicle (from vehicleData.yml).
     * @return Null if unknown
     */
    public static @Nullable UUID getOwner(String licensePlate) {
        final Object owner = ConfigModule.vehicleDataConfig.get(licensePlate, VehicleDataConfig.Option.OWNER);
        if (owner == null) return null;
        try {
            return UUID.fromString(owner.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Move a vehicle (all of its armor stands, keeping their positions relative to each other) so that it is at the given location.
     * @param vehicle Spawned vehicle - must not be occupied
     * @param target Where the vehicle's centre will be
     * @return True if successful
     */
    public static boolean moveVehicle(SpawnedVehicle vehicle, Location target) {
        if (vehicle.occupied || vehicle.location == null || target.getWorld() == null) return false;

        final Location reference = vehicle.location;
        for (Entity entity : vehicle.entities) {
            if (!entity.isValid()) continue;
            final Location current = entity.getLocation();
            final Location destination = new Location(
                    target.getWorld(),
                    target.getX() + (current.getX() - reference.getX()),
                    target.getY() + (current.getY() - reference.getY()),
                    target.getZ() + (current.getZ() - reference.getZ()),
                    current.getYaw(),
                    current.getPitch()
            );
            entity.teleport(destination);
        }
        cacheTime = 0L; // positions changed
        return true;
    }

    /**
     * Remove all armor stands of a spawned vehicle from the world (the vehicle stays in the database).
     * @param vehicle Spawned vehicle - must not be occupied
     * @return True if successful
     */
    public static boolean despawn(SpawnedVehicle vehicle) {
        if (vehicle.occupied) return false;
        for (Entity entity : vehicle.entities) {
            if (entity.isValid()) entity.remove();
        }
        // Forget the removed armor stands (they are created again when the vehicle is entered)
        final String suffix = "_" + vehicle.licensePlate;
        VehicleData.autostand.keySet().removeIf(key -> key.endsWith(suffix));
        VehicleData.autostand2.remove(vehicle.licensePlate);
        cacheTime = 0L;
        return true;
    }
}
