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
 * Lane assist: keeps a car centered in its lane while the driver is not steering, so that they don't have to keep correcting it.
 * <p>
 * Two kinds of blocks matter (both set in the config):
 * <ul>
 *     <li><b>road blocks</b> (laneAssist.roadBlocks) - what the road is made of (the asphalt, and the curbs if you like)</li>
 *     <li><b>lane lines</b> (laneAssist.laneLines) - the paint on it. A line is the edge of a lane, so the car is centered between the lines
 *     (or between a line and the edge of the road), not in the middle of the whole road. Dashed lines work too.</li>
 * </ul>
 * From the car's position the assist looks to the left and right until it meets a line or the end of the road, here and a few blocks ahead,
 * and turns the car gently towards the middle of the lane ahead. The driver is always in charge: as soon as they steer, the assist backs off
 * for a moment. It does nothing where it can't tell where the lane is (open areas, a road wider than laneAssist.maxHalfWidth, the road ending ahead...).
 *
 * @since 2.5.10
 */
public final class LaneAssist {
    private LaneAssist() {}

    /** The assist only reads the surroundings this often (millis), and applies its last result in between. */
    private static final long REFRESH_MILLIS = 100L;
    /** How long the assist stays off after the driver steered (millis). */
    private static final long BACK_OFF_MILLIS = 700L;
    /** How long a line which was seen stays "seen" when it isn't there for a moment (a gap in a dashed line) (millis). */
    private static final long MEMORY_MILLIS = 800L;
    /** A boundary which is suddenly this much further away than it was is taken to be a gap in a dashed line (blocks). */
    private static final double JUMP = 1.0;
    /** Distance (blocks) the middle of the lane must be off by before the car is turned at all. */
    private static final double DEAD_ZONE = 0.35;
    /** Resolution of the search for the edge of the lane (blocks). */
    private static final double STEP = 0.5;
    /** The surroundings are read at this many blocks in front of and behind each point, so that a gap in a dashed line doesn't matter. */
    private static final int WINDOW = 3;

    private static final int NONE = 0, ROAD = 1, LINE = 2;

    private static final class State {
        long lastSteered;
        long lastRead;
        float yawStep;
        // The last distance to the line/edge on each side (0 = left, 1 = right) for the point here (0) and ahead (1)
        final double[][] distance = new double[2][2];
        final long[][] seen = new long[2][2];
    }

    private static final Map<String, State> states = new HashMap<>();

    private static Set<Material> roadBlocks;
    private static Set<Material> laneLines;
    private static long blocksRead;

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
            state.yawStep = read(state, location, maxStep, now);
        }
        return state.yawStep;
    }

    private static State state(String license) {
        return states.computeIfAbsent(license, k -> new State());
    }

    // --- Reading the road ---

    private static float read(State state, Location location, float maxStep, long now) {
        final World world = location.getWorld();
        if (world == null) return 0f;
        loadBlocks();
        if (roadBlocks.isEmpty() && laneLines.isEmpty()) return 0f;

        final double maxHalfWidth = Math.max(2.0, Math.min(16.0, number(DefaultConfig.Option.LANE_ASSIST_MAX_HALF_WIDTH, 8.0)));
        final double lookAhead = Math.max(2.0, Math.min(16.0, number(DefaultConfig.Option.LANE_ASSIST_LOOK_AHEAD, 6.0)));
        final double strength = Math.max(0.05, Math.min(1.0, number(DefaultConfig.Option.LANE_ASSIST_STRENGTH, 0.5)));

        final double yaw = Math.toRadians(location.getYaw());
        final double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        final double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        final int floor = (int) Math.floor(location.getY() - 0.2);

        // Where is the middle of the lane: right here, and ahead?
        final double here = laneOffset(state, 0, now, world, location.getX(), location.getZ(), forwardX, forwardZ, rightX, rightZ, floor, maxHalfWidth);
        if (Double.isNaN(here)) return 0f; // not on a road (or not able to tell)

        for (double distance = lookAhead; distance >= 2.0; distance -= 2.0) {
            final double ahead = laneOffset(state, 1, now, world, location.getX() + forwardX * distance, location.getZ() + forwardZ * distance,
                    forwardX, forwardZ, rightX, rightZ, floor, maxHalfWidth);
            if (!Double.isNaN(ahead)) {
                // Aim at the middle of the lane at that distance (that takes care of being off the middle now, and of the road bending)
                return step(Math.toDegrees(Math.atan2(ahead, distance)), strength, maxStep);
            }
        }

        // The lane doesn't go on straight ahead (a bend, a junction, the end): only drift back to the middle if we are well off it
        return step(Math.toDegrees(Math.atan2(here, lookAhead)), strength * 0.5, maxStep);
    }

    private static float step(double angle, double strength, float maxStep) {
        if (Math.abs(angle) < Math.toDegrees(Math.atan2(DEAD_ZONE, 6.0))) return 0f;
        final double wanted = angle * strength;
        return (float) Math.max(-maxStep, Math.min(maxStep, wanted));
    }

    /**
     * How far the middle of the lane is from a point, sideways: positive to the right.
     * @param slot 0 for the point where the car is, 1 for the point ahead (the lines seen there are remembered for a moment)
     * @return NaN if the point isn't on the road, or the edges of the lane can't be found
     */
    private static double laneOffset(State state, int slot, long now, World world, double x, double z, double forwardX, double forwardZ,
                                     double rightX, double rightZ, int floor, double maxHalfWidth) {
        if (kind(world, x, z, floor) == NONE) return Double.NaN;

        // The nearest line or road edge on each side, anywhere in the window (so a gap in a dashed line is skipped)
        final double[] nearest = {Double.MAX_VALUE, Double.MAX_VALUE};
        for (int k = -WINDOW; k <= WINDOW; k++) {
            final double px = x + forwardX * k, pz = z + forwardZ * k;
            if (kind(world, px, pz, floor) == NONE) continue;
            for (int side = 0; side < 2; side++) {
                final double sign = side == 0 ? -1.0 : 1.0;
                for (double t = STEP; t <= maxHalfWidth; t += STEP) {
                    if (kind(world, px + sign * rightX * t, pz + sign * rightZ * t, floor) != ROAD) {
                        nearest[side] = Math.min(nearest[side], t - STEP / 2.0);
                        break;
                    }
                }
            }
        }

        final double[] boundary = new double[2];
        for (int side = 0; side < 2; side++) {
            double found = nearest[side];
            final boolean remembered = now - state.seen[slot][side] < MEMORY_MILLIS;
            if (found == Double.MAX_VALUE || (remembered && found > state.distance[slot][side] + JUMP)) {
                // Nothing there at the moment, or the line was much closer a moment ago: a gap in a dashed line
                if (!remembered) return Double.NaN;
                found = state.distance[slot][side];
            } else {
                state.distance[slot][side] = found;
                state.seen[slot][side] = now;
            }
            boundary[side] = found;
        }
        return (boundary[1] - boundary[0]) / 2.0;
    }

    /**
     * What is under a point: nothing, road or a lane line. A step up or down (slabs, slopes) is allowed.
     */
    private static int kind(World world, double x, double z, int floor) {
        final int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return NONE;
        for (int dy : new int[]{0, -1, 1}) {
            final int found = classify(world.getBlockAt(bx, floor + dy, bz).getType());
            if (found != NONE) return found;
        }
        return NONE;
    }

    private static int classify(Material material) {
        if (laneLines.contains(material)) return LINE;
        if (roadBlocks.contains(material)) return ROAD;
        return NONE;
    }

    private static void loadBlocks() {
        final long now = System.currentTimeMillis();
        if (roadBlocks != null && now - blocksRead < 10_000L) return;
        roadBlocks = materials(ConfigModule.defaultConfig.get(DefaultConfig.Option.LANE_ASSIST_ROAD_BLOCKS));
        laneLines = materials(ConfigModule.defaultConfig.get(DefaultConfig.Option.LANE_ASSIST_LANE_LINES));
        blocksRead = now;
    }

    private static Set<Material> materials(Object configured) {
        final Set<Material> set = EnumSet.noneOf(Material.class);
        if (configured instanceof List) {
            for (Object name : (List<?>) configured) {
                if (name == null) continue;
                final Material material = Material.matchMaterial(name.toString().trim());
                if (material != null) set.add(material);
            }
        }
        return set;
    }

    // --- Help for setting it up ---

    /**
     * Describe what the assist sees around a car (/vehicle laneassist debug): the blocks under it and to its left and right, and where it thinks the lane is.
     * @return Lines of text (with colour codes)
     */
    public static List<String> describe(Location location) {
        final List<String> lines = new ArrayList<>();
        final World world = location.getWorld();
        if (world == null) return lines;
        loadBlocks();

        final double yaw = Math.toRadians(location.getYaw());
        final double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        final int floor = (int) Math.floor(location.getY() - 0.2);

        lines.add("&6Lane assist - what it sees (road = " + roadBlocks.size() + " block types, lines = " + laneLines.size() + "):");
        lines.add("&7Under the car: " + label(world, location.getX(), location.getZ(), floor));
        for (int side = 0; side < 2; side++) {
            final double sign = side == 0 ? -1.0 : 1.0;
            final StringBuilder text = new StringBuilder(side == 0 ? "&7Left:  " : "&7Right: ");
            for (int i = 1; i <= 6; i++) {
                if (i > 1) text.append("&8, ");
                text.append(label(world, location.getX() + sign * rightX * i, location.getZ() + sign * rightZ * i, floor));
            }
            lines.add(text.toString());
        }
        // Where it thinks the edges of the lane are (straight left and right of the car, as it reads them)
        final double maxHalfWidth = Math.max(2.0, Math.min(16.0, number(DefaultConfig.Option.LANE_ASSIST_MAX_HALF_WIDTH, 8.0)));
        final double[] edge = {Double.NaN, Double.NaN};
        final String[] edgeText = {"nothing within " + (int) maxHalfWidth + " blocks", "nothing within " + (int) maxHalfWidth + " blocks"};
        for (int side = 0; side < 2; side++) {
            final double sign = side == 0 ? -1.0 : 1.0;
            for (double t = STEP; t <= maxHalfWidth; t += STEP) {
                final double px = location.getX() + sign * rightX * t, pz = location.getZ() + sign * rightZ * t;
                if (kind(world, px, pz, floor) != ROAD) {
                    edge[side] = t - STEP / 2.0;
                    edgeText[side] = label(world, px, pz, floor);
                    break;
                }
            }
        }
        lines.add("&7Left edge of the lane: &f" + (Double.isNaN(edge[0]) ? "-" : String.format(java.util.Locale.ROOT, "%.1f blocks", edge[0])) + " &8-> " + edgeText[0]);
        lines.add("&7Right edge of the lane: &f" + (Double.isNaN(edge[1]) ? "-" : String.format(java.util.Locale.ROOT, "%.1f blocks", edge[1])) + " &8-> " + edgeText[1]);
        if (Double.isNaN(edge[0]) || Double.isNaN(edge[1])) {
            lines.add("&cOne side has no edge in range, so the assist does nothing here.");
        } else {
            final double offset = (edge[1] - edge[0]) / 2.0;
            lines.add(Math.abs(offset) < DEAD_ZONE
                    ? "&aThe car is in the middle of the lane."
                    : String.format(java.util.Locale.ROOT, "&eThe middle of the lane is %.1f blocks to the %s - the assist steers there.", Math.abs(offset), offset > 0 ? "right" : "left"));
        }
        lines.add("&7Blocks marked &c(not set)&7 are neither road nor line: add them to laneAssist.roadBlocks or laneAssist.laneLines.");
        return lines;
    }

    private static String label(World world, double x, double z, int floor) {
        final int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return "&8(not loaded)";
        for (int dy : new int[]{0, -1, 1}) {
            final Material material = world.getBlockAt(bx, floor + dy, bz).getType();
            final int found = classify(material);
            if (found == ROAD) return "&f" + material.name() + " &a(road)";
            if (found == LINE) return "&f" + material.name() + " &e(line)";
        }
        return "&f" + world.getBlockAt(bx, floor, bz).getType().name() + " &c(not set)";
    }
}
