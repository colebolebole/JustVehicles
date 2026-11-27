package nl.mtvehicles.core.movement;

import nl.mtvehicles.core.infrastructure.dataconfig.DefaultConfig;
import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lane assist: keeps a car centered on a road while the driver is not steering, so that they don't have to keep correcting it.
 * <p>
 * A "road" is whatever is made of the blocks in the config (laneAssist.roadBlocks). From the car's position the assist looks to the left and
 * to the right until the road ends, which gives the middle of the road - here and a few blocks ahead. It then turns the car gently
 * towards the point in the middle of the road ahead. The driver is always in charge: as soon as they steer, the assist backs off for a moment.
 * It does nothing where it can't tell where the road is (open areas, a road wider than laneAssist.maxHalfWidth, the road ending ahead...).
 *
 * @since 2.5.10
 */
public final class LaneAssist {
    private LaneAssist() {}

    /** The assist only reads the surroundings this often (millis), and applies its last result in between. */
    private static final long REFRESH_MILLIS = 100L;
    /** How long the assist stays off after the driver steered (millis). */
    private static final long BACK_OFF_MILLIS = 700L;
    /** Distance (blocks) the middle of the road must be off by before the car is turned at all. */
    private static final double DEAD_ZONE = 0.35;
    /** Resolution of the search for the edge of the road (blocks). */
    private static final double STEP = 0.5;

    private static final class State {
        long lastSteered;
        long lastRead;
        float yawStep;
    }

    private static final Map<String, State> states = new HashMap<>();

    private static Set<Material> roadBlocks;
    private static long roadBlocksRead;

    // --- Settings ---

    /**
     * Whether lane assist is switched on in the config at all.
     */
    public static boolean isAvailable() {
        return Boolean.TRUE.equals(ConfigModule.defaultConfig.get(DefaultConfig.Option.LANE_ASSIST_ENABLED));
    }

    /**
     * Whether lane assist is on for a vehicle: its own setting (/vehicle laneassist), or else the default from the config.
     */
    public static boolean isOn(String license) {
        final Object own = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.LANE_ASSIST);
        if (own instanceof Boolean) return (Boolean) own;
        return Boolean.TRUE.equals(ConfigModule.defaultConfig.get(DefaultConfig.Option.LANE_ASSIST_DEFAULT_ON));
    }

    public static void setOn(String license, boolean on) {
        ConfigModule.vehicleDataConfig.set(license, VehicleDataConfig.Option.LANE_ASSIST, on);
        ConfigModule.vehicleDataConfig.save();
        states.remove(license);
    }

    private static double number(DefaultConfig.Option option, double fallback) {
        final Object value = ConfigModule.defaultConfig.get(option);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    // --- Using it ---

    /**
     * Tell the assist that the driver is steering (it backs off for a moment).
     */
    public static void steered(String license) {
        state(license).lastSteered = System.currentTimeMillis();
    }

    /**
     * How far (in degrees of yaw) the car should be turned this tick. Positive is to the right, negative to the left.
     * Call it every tick the driver is not steering.
     *
     * @param location Location of the car (its yaw is the direction it is going)
     * @param maxStep The most it may turn in one tick (degrees)
     * @return 0 if the car should not be turned
     */
    public static float yawStep(String license, Location location, float maxStep) {
        final State state = state(license);
        final long now = System.currentTimeMillis();
        if (now - state.lastSteered < BACK_OFF_MILLIS) {
            state.yawStep = 0f;
            return 0f;
        }

        if (now - state.lastRead >= REFRESH_MILLIS) {
            state.lastRead = now;
            state.yawStep = read(location, maxStep);
        }
        return state.yawStep;
    }

    /**
     * Forget a vehicle (nobody is driving it anymore).
     */
    public static void forget(String license) {
        states.remove(license);
    }

    private static State state(String license) {
        return states.computeIfAbsent(license, k -> new State());
    }

    // --- Reading the road ---

    private static float read(Location location, float maxStep) {
        final World world = location.getWorld();
        if (world == null) return 0f;
        final Set<Material> roads = roads();
        if (roads.isEmpty()) return 0f;

        final double maxHalfWidth = Math.max(2.0, Math.min(16.0, number(DefaultConfig.Option.LANE_ASSIST_MAX_HALF_WIDTH, 8.0)));
        final double lookAhead = Math.max(2.0, Math.min(16.0, number(DefaultConfig.Option.LANE_ASSIST_LOOK_AHEAD, 6.0)));
        final double strength = Math.max(0.05, Math.min(1.0, number(DefaultConfig.Option.LANE_ASSIST_STRENGTH, 0.5)));

        final double yaw = Math.toRadians(location.getYaw());
        final double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        final double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        final int floor = (int) Math.floor(location.getY() - 0.2);

        // Where is the middle of the road: right here, and ahead?
        final double here = centerOffset(world, roads, location.getX(), location.getZ(), rightX, rightZ, floor, maxHalfWidth);
        if (Double.isNaN(here)) return 0f; // not on a road (or not able to tell)

        double ahead = Double.NaN;
        for (double distance = lookAhead; distance >= 2.0 && Double.isNaN(ahead); distance -= 2.0) {
            ahead = centerOffset(world, roads, location.getX() + forwardX * distance, location.getZ() + forwardZ * distance, rightX, rightZ, floor, maxHalfWidth);
            if (!Double.isNaN(ahead)) {
                // Aim at the middle of the road at that distance (that takes care of being off the middle now, and of the road bending)
                return step(Math.toDegrees(Math.atan2(ahead, distance)), strength, maxStep);
            }
        }

        // The road doesn't go on straight ahead (a bend, a junction, the end): only drift back to the middle if we are well off it
        return step(Math.toDegrees(Math.atan2(here, lookAhead)), strength * 0.5, maxStep);
    }

    private static float step(double angle, double strength, float maxStep) {
        if (Math.abs(angle) < Math.toDegrees(Math.atan2(DEAD_ZONE, 6.0))) return 0f;
        final double wanted = angle * strength;
        return (float) Math.max(-maxStep, Math.min(maxStep, wanted));
    }

    /**
     * How far the middle of the road is from a point, sideways: positive to the right.
     * @return NaN if the point isn't on the road, or one of the edges of the road is too far away to find
     */
    private static double centerOffset(World world, Set<Material> roads, double x, double z, double rightX, double rightZ, int floor, double maxHalfWidth) {
        if (!isRoad(world, roads, x, z, floor)) return Double.NaN;

        double right = -1, left = -1;
        for (double t = STEP; t <= maxHalfWidth; t += STEP) {
            if (right < 0 && !isRoad(world, roads, x + rightX * t, z + rightZ * t, floor)) right = t - STEP / 2.0;
            if (left < 0 && !isRoad(world, roads, x - rightX * t, z - rightZ * t, floor)) left = t - STEP / 2.0;
            if (right >= 0 && left >= 0) break;
        }
        if (right < 0 || left < 0) return Double.NaN;
        return (right - left) / 2.0;
    }

    /**
     * Whether there is a road under a point. A step up or down (slabs, slopes) is allowed.
     */
    private static boolean isRoad(World world, Set<Material> roads, double x, double z, int floor) {
        final int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        for (int dy = 0; dy >= -1; dy--) {
            if (roads.contains(world.getBlockAt(bx, floor + dy, bz).getType())) return true;
        }
        return roads.contains(world.getBlockAt(bx, floor + 1, bz).getType());
    }

    private static Set<Material> roads() {
        final long now = System.currentTimeMillis();
        if (roadBlocks != null && now - roadBlocksRead < 10_000L) return roadBlocks;

        final Set<Material> set = EnumSet.noneOf(Material.class);
        final Object configured = ConfigModule.defaultConfig.get(DefaultConfig.Option.LANE_ASSIST_ROAD_BLOCKS);
        final List<String> names = new ArrayList<>();
        if (configured instanceof List) {
            for (Object name : (List<?>) configured) if (name != null) names.add(name.toString());
        }
        for (String name : names) {
            final Material material = Material.matchMaterial(name.trim());
            if (material != null) set.add(material);
        }
        roadBlocks = set;
        roadBlocksRead = now;
        return set;
    }
}
