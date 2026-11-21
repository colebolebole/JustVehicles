package nl.mtvehicles.core.infrastructure.hvac;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import nl.mtvehicles.core.Main;
import nl.mtvehicles.core.infrastructure.dataconfig.DefaultConfig;
import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import nl.mtvehicles.core.infrastructure.dependencies.EzvacBridge;
import nl.mtvehicles.core.infrastructure.enums.VehicleType;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.utils.TextUtils;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleData;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleFinder;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Climate control (HVAC) of vehicles.
 * <p>
 * Every vehicle which is being driven has a <b>cabin</b> with its own temperature, which is simulated once a second:
 * <ul>
 *     <li>it slowly follows the air temperature around the vehicle - taken from EZvac if it is installed (outside, or the room of a garage),
 *     otherwise calculated from the biome, the time of day and the weather;</li>
 *     <li>the sun heats it up (on a clear day, when the vehicle is under the open sky), and every occupant warms it a little;</li>
 *     <li>while the engine is running (a driver is seated and there is fuel), the A/C or heater brings it to the target temperature.
 *     It costs a bit of extra fuel.</li>
 * </ul>
 * Temperatures are in °F internally.
 *
 * @since 2.5.9
 */
public final class HvacManager {
    private HvacManager() {}

    /**
     * Mode of the climate control
     */
    public enum Mode {
        /** Cools or heats, whichever is needed */
        AUTO("Auto"),
        /** Cools only (A/C) */
        COOL("A/C"),
        /** Heats only */
        HEAT("Heater");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Climate of one vehicle
     */
    public static final class Climate {
        private final String license;
        private double cabinF;
        private double ambientF;
        private boolean on;
        private Mode mode;
        private int fan;          // 0 = automatic, 1-3
        private double targetF;
        private boolean running;  // the A/C or heater is working right now
        private boolean cooling;  // (if running) cooling, otherwise heating
        private boolean linked;   // linked to a dashboard (see CarClimateApi)
        private long remoteUntil; // started remotely: runs without a driver until this time (millis), 0 = not
        private Entity remoteRef; // armor stand of the vehicle which is running remotely
        private long lastActive = System.currentTimeMillis();

        private Climate(String license) {
            this.license = license;
        }

        public String getLicense() { return license; }
        public double getCabinF() { return cabinF; }
        public double getAmbientF() { return ambientF; }
        public boolean isOn() { return on; }
        public Mode getMode() { return mode; }
        public int getFan() { return fan; }
        public double getTargetF() { return targetF; }
        public boolean isRunning() { return running; }
        public boolean isCooling() { return cooling; }
        public boolean isLinked() { return linked; }
        public boolean isRemoteRunning() { return remoteUntil > System.currentTimeMillis(); }
        public long getRemoteUntil() { return remoteUntil; }

        /**
         * Short description of what the climate control is doing
         */
        public String getStatus() {
            if (!on) return "off";
            if (!running) return "idle";
            return cooling ? "cooling" : "heating";
        }

        public String getFanText() {
            return fan == 0 ? "Auto" : String.valueOf(fan);
        }
    }

    // --- Tuning ---

    /** Time constant (seconds) of the cabin following its surroundings */
    private static final double LEAK_SECONDS = 150.0;
    /** How much hotter than the surroundings the cabin gets in full sun (°F) */
    private static final double SUN_HEAT_F = 14.0;
    /** Heat from each occupant (°F above the surroundings) */
    private static final double BODY_HEAT_F = 1.0;
    /** The A/C or heater works until the cabin is this close to the target (°F) */
    private static final double DEADBAND_F = 0.5;
    /** Power (°F per second) of fan levels 1, 2 and 3 */
    private static final double[] FAN_POWER = {0.0, 0.25, 0.5, 0.9};
    private static final long KEEP_STATE_MILLIS = 5 * 60 * 1000L;

    private static final Map<String, Climate> climates = new HashMap<>();
    private static BukkitTask task;
    private static boolean hudRegistered = false;

    // --- Lifecycle ---

    /**
     * Start simulating the vehicles' climate (called when the plugin is enabled).
     */
    public static void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(Main.instance, HvacManager::tick, 40L, 20L);

        if (EzvacBridge.isAvailable()) {
            hudRegistered = EzvacBridge.registerHud(Main.instance, "carclimate", HvacManager::hudLines);
        }
    }

    /**
     * Stop the simulation.
     */
    public static void stop() {
        if (task != null) task.cancel();
        task = null;
        climates.clear();
    }

    // --- Settings ---

    private static Object config(DefaultConfig.Option option) {
        return ConfigModule.defaultConfig.get(option);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public static boolean isEnabled() {
        final Object value = config(DefaultConfig.Option.HVAC_ENABLED);
        return !(value instanceof Boolean) || (Boolean) value;
    }

    /**
     * Whether a vehicle has a climate control (by its type).
     */
    public static boolean appliesTo(String license) {
        if (!isEnabled() || license == null || !VehicleUtils.existsByLicensePlate(license)) return false;
        try {
            final VehicleType type = ConfigModule.vehicleDataConfig.getType(license);
            final Object types = config(DefaultConfig.Option.HVAC_VEHICLE_TYPES);
            if (types instanceof List) {
                for (Object t : (List<?>) types) {
                    if (t != null && t.toString().equalsIgnoreCase(type.name())) return true;
                }
            }
        } catch (Exception ignored) {
            // malformed vehicle data
        }
        return false;
    }

    public static double getMinTargetF() {
        return number(config(DefaultConfig.Option.HVAC_MIN_TARGET), 60.0);
    }

    public static double getMaxTargetF() {
        return Math.max(getMinTargetF(), number(config(DefaultConfig.Option.HVAC_MAX_TARGET), 85.0));
    }

    private static double getDefaultTargetF() {
        return Math.max(getMinTargetF(), Math.min(getMaxTargetF(), number(config(DefaultConfig.Option.HVAC_DEFAULT_TARGET), 70.0)));
    }

    public static boolean opensWithSwapKey() {
        final Object value = config(DefaultConfig.Option.HVAC_OPEN_WITH_SWAP_KEY);
        return !(value instanceof Boolean) || (Boolean) value;
    }

    /**
     * Whether players may use the climate control (everyone, unless the permission 'mtvehicles.hvac' is explicitly denied).
     */
    public static boolean mayUse(Player player) {
        return !player.isPermissionSet("mtvehicles.hvac") || player.hasPermission("mtvehicles.hvac");
    }

    // --- Units ---

    public static boolean usesCelsius() {
        final Object units = config(DefaultConfig.Option.HVAC_UNITS);
        final String value = units != null ? units.toString().toUpperCase(Locale.ROOT) : "AUTO";
        if (value.equals("C")) return true;
        if (value.equals("F")) return false;
        final Boolean ezvac = EzvacBridge.usesCelsius();
        return ezvac != null && ezvac;
    }

    /**
     * Format a temperature (given in °F) in the units players see.
     */
    public static String format(double fahrenheit) {
        if (usesCelsius()) return Math.round((fahrenheit - 32.0) * 5.0 / 9.0) + "°C";
        return Math.round(fahrenheit) + "°F";
    }

    /**
     * Convert a temperature entered by a player (in the units players see) to °F.
     */
    public static double toFahrenheit(double displayed) {
        return usesCelsius() ? displayed * 9.0 / 5.0 + 32.0 : displayed;
    }

    /**
     * One step of a target change in °F (1 degree in the units players see).
     */
    public static double getStepF() {
        return usesCelsius() ? 1.8 : 1.0;
    }

    // --- Access ---

    /**
     * Get the license plate of the vehicle a player is sitting in.
     * @return Null if the player is not sitting in a vehicle
     */
    public static @Nullable String getLicenseOf(Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null || !VehicleUtils.isVehicle(vehicle)) return null;
        return VehicleUtils.getLicensePlate(vehicle);
    }

    /**
     * Get the climate of the vehicle a player is sitting in - it is created if necessary.
     * @return Null if the player is not sitting in a vehicle which has a climate control
     */
    public static @Nullable Climate getClimateOf(Player player) {
        final String license = getLicenseOf(player);
        if (license == null || !appliesTo(license)) return null;
        return getOrCreate(license, player.getVehicle().getLocation());
    }

    private static Climate getOrCreate(String license, Location location) {
        Climate climate = climates.get(license);
        if (climate == null) {
            climate = new Climate(license);
            loadSettings(climate);
            climate.ambientF = ambientF(location);
            climate.cabinF = climate.ambientF + solarGainF(location) + BODY_HEAT_F; // as if it has been parked for a while
            climates.put(license, climate);
        }
        climate.lastActive = System.currentTimeMillis();
        return climate;
    }

    private static void loadSettings(Climate climate) {
        final VehicleDataConfig data = ConfigModule.vehicleDataConfig;
        final Object on = data.get(climate.license, VehicleDataConfig.Option.HVAC_ON);
        climate.on = on instanceof Boolean && (Boolean) on;

        climate.mode = Mode.AUTO;
        final Object mode = data.get(climate.license, VehicleDataConfig.Option.HVAC_MODE);
        if (mode != null) {
            try {
                climate.mode = Mode.valueOf(mode.toString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }

        final Object fan = data.get(climate.license, VehicleDataConfig.Option.HVAC_FAN);
        climate.fan = fan instanceof Number ? Math.max(0, Math.min(3, ((Number) fan).intValue())) : 0;

        final Object target = data.get(climate.license, VehicleDataConfig.Option.HVAC_TARGET);
        climate.targetF = clampTarget(target instanceof Number ? ((Number) target).doubleValue() : getDefaultTargetF());

        climate.linked = isLinked(climate.license);
    }

    // --- Linking to a dashboard (see CarClimateApi) ---

    /**
     * Whether the climate control of a vehicle is linked to a dashboard (such as EZStats).
     */
    public static boolean isLinked(String license) {
        final Object linked = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.HVAC_LINKED);
        return linked instanceof Boolean && (Boolean) linked;
    }

    /**
     * Link the climate control of a vehicle to a dashboard, or unlink it. Unlinking also stops a remote start.
     */
    public static void setLinked(String license, boolean linked) {
        ConfigModule.vehicleDataConfig.set(license, VehicleDataConfig.Option.HVAC_LINKED, linked);
        ConfigModule.vehicleDataConfig.save();
        final Climate climate = climates.get(license);
        if (climate != null) {
            climate.linked = linked;
            if (!linked) stopRemote(climate);
        }
    }

    /**
     * Get the live climate of a vehicle without creating one (null if the vehicle has not been driven or started remotely recently).
     */
    public static @Nullable Climate peek(String license) {
        return climates.get(license);
    }

    /**
     * Get a climate to change settings of: the live one, or - for a vehicle which is not in use - one which only holds the saved settings
     * (changing it saves them, the vehicle is not simulated).
     * @return Null if the vehicle has no climate control
     */
    public static @Nullable Climate getForControl(String license) {
        if (!appliesTo(license)) return null;
        final Climate live = climates.get(license);
        if (live != null) return live;
        final Climate detached = new Climate(license);
        loadSettings(detached);
        detached.cabinF = Double.NaN;
        detached.ambientF = Double.NaN;
        return detached;
    }

    // --- Remote start ---

    private static int getRemoteStartMinutes() {
        return (int) Math.max(0, number(config(DefaultConfig.Option.HVAC_REMOTE_START_MINUTES), 10));
    }

    /**
     * How many minutes a remotely started climate control runs (0 = remote start is off).
     */
    public static int getRemoteStartMinutesSetting() {
        return getRemoteStartMinutes();
    }

    /**
     * Get the climate of a vehicle which is spawned and parked (nobody in it), without a driver - it is created if necessary.
     * @return Null if the vehicle has no climate control or is not spawned in a loaded chunk
     */
    public static @Nullable Climate getParkedClimate(String license) {
        if (!appliesTo(license)) return null;
        final Climate existing = climates.get(license);
        if (existing != null) return existing;

        final VehicleFinder.SpawnedVehicle spawned = VehicleFinder.getSpawned(license);
        if (spawned == null) return null;
        return getOrCreate(license, spawned.getLocation());
    }

    /**
     * Let the climate control of a parked vehicle run for the configured number of minutes (it uses fuel), so it is comfortable when its owner gets in.
     * Only for vehicles linked to a dashboard.
     * @return Null if started, otherwise why it could not be started (a sentence)
     */
    public static @Nullable String startRemote(String license) {
        final int minutes = getRemoteStartMinutes();
        if (minutes <= 0) return null; // remote start is off: only the setting changes

        final VehicleFinder.SpawnedVehicle spawned = VehicleFinder.getSpawned(license);
        if (spawned == null) return "The vehicle is not spawned (or its chunk is not loaded), so its climate control can't run now. The setting is saved.";
        if (spawned.isOccupied()) return null; // somebody is in it: it simply runs

        final Climate climate = getOrCreate(license, spawned.getLocation());
        if (isFuelUsed(license)) {
            final Double fuel = VehicleData.fuel.get(license);
            final Object stored = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.FUEL);
            final double level = fuel != null ? fuel : stored instanceof Number ? ((Number) stored).doubleValue() : 0.0;
            if (level < 1) return "The vehicle is out of fuel. The setting is saved.";
        }

        Entity reference = null;
        for (Entity entity : spawned.getEntities()) {
            final String name = entity.getCustomName();
            if (name != null && name.startsWith("MTVEHICLES_SKIN_")) reference = entity;
        }
        climate.remoteRef = reference != null ? reference : spawned.getEntities().get(0);
        climate.remoteUntil = System.currentTimeMillis() + minutes * 60_000L;
        return null;
    }

    /**
     * Stop a remote start.
     */
    public static void stopRemote(Climate climate) {
        climate.remoteUntil = 0L;
        climate.remoteRef = null;
        climate.running = false;
    }

    private static void saveSettings(Climate climate) {
        final VehicleDataConfig data = ConfigModule.vehicleDataConfig;
        data.set(climate.license, VehicleDataConfig.Option.HVAC_ON, climate.on);
        data.set(climate.license, VehicleDataConfig.Option.HVAC_MODE, climate.mode.name());
        data.set(climate.license, VehicleDataConfig.Option.HVAC_FAN, climate.fan);
        data.set(climate.license, VehicleDataConfig.Option.HVAC_TARGET, climate.targetF);
        data.save();
    }

    private static double clampTarget(double targetF) {
        return Math.max(getMinTargetF(), Math.min(getMaxTargetF(), targetF));
    }

    // --- Controls ---

    public static void setOn(Climate climate, boolean on) {
        climate.on = on;
        saveSettings(climate);
    }

    public static void setMode(Climate climate, Mode mode) {
        climate.mode = mode;
        saveSettings(climate);
    }

    /**
     * @param fan 0 = automatic, 1-3
     */
    public static void setFan(Climate climate, int fan) {
        climate.fan = Math.max(0, Math.min(3, fan));
        saveSettings(climate);
    }

    public static void setTargetF(Climate climate, double targetF) {
        climate.targetF = clampTarget(targetF);
        saveSettings(climate);
    }

    // --- Simulation ---

    private static void tick() {
        if (!isEnabled()) {
            climates.clear();
            return;
        }

        final long now = System.currentTimeMillis();
        final Set<String> active = new HashSet<>();

        // Vehicles with a driver: those have a main seat armor stand with a passenger
        for (Map.Entry<String, ArmorStand> entry : new ArrayList<>(VehicleData.autostand2.entrySet())) {
            final String license = entry.getKey();
            final ArmorStand mainSeat = entry.getValue();
            if (mainSeat == null || !mainSeat.isValid() || mainSeat.getPassengers().isEmpty()) continue;
            if (!appliesTo(license)) continue;

            final List<Player> occupants = getOccupants(license, mainSeat);
            if (occupants.isEmpty()) continue;

            active.add(license);
            final Climate climate = getOrCreate(license, mainSeat.getLocation());
            if (climate.remoteUntil != 0L) stopRemote(climate); // there is a driver now, the remote start is not needed anymore
            simulate(climate, mainSeat.getLocation(), occupants.size(), true);

            if (shouldUseActionBar() && climate.on) {
                final String bar = actionBarText(climate);
                for (Player player : occupants) sendActionBar(player, bar);
            }
        }

        // Parked vehicles whose climate control has been started remotely (from a dashboard)
        for (Climate climate : new ArrayList<>(climates.values())) {
            if (climate.remoteUntil == 0L || active.contains(climate.license)) continue;

            final Entity reference = climate.remoteRef;
            if (now > climate.remoteUntil || reference == null || !reference.isValid() || !climate.on || !climate.linked) {
                stopRemote(climate);
                continue;
            }
            climate.lastActive = now;
            simulate(climate, reference.getLocation(), 0, true);
            if (!climate.running && climate.on && isFuelUsed(climate.license)) {
                // out of fuel: the engine stops
                final Double fuel = currentFuel(climate.license);
                if (fuel != null && fuel < 1) stopRemote(climate);
            }
        }

        // Forget vehicles nobody has been in for a while (their cabin has cooled down/warmed up anyway)
        final java.util.Iterator<Map.Entry<String, Climate>> iterator = climates.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Climate> entry = iterator.next();
            if (!active.contains(entry.getKey()) && now - entry.getValue().lastActive > KEEP_STATE_MILLIS) iterator.remove();
        }
    }

    private static List<Player> getOccupants(String license, ArmorStand mainSeat) {
        final List<Player> players = new ArrayList<>();
        for (Entity passenger : mainSeat.getPassengers()) {
            if (passenger instanceof Player) players.add((Player) passenger);
        }
        final Integer seats = VehicleData.seatsize.get(license);
        if (seats != null) {
            for (int i = 2; i <= seats; i++) {
                final ArmorStand seat = VehicleData.autostand.get("MTVEHICLES_SEAT" + i + "_" + license);
                if (seat == null || !seat.isValid()) continue;
                for (Entity passenger : seat.getPassengers()) {
                    if (passenger instanceof Player) players.add((Player) passenger);
                }
            }
        }
        return players;
    }

    /**
     * Simulate one second.
     */
    private static void simulate(Climate climate, Location location, int occupants, boolean engineRunning) {
        climate.ambientF = ambientF(location);

        // The cabin follows its surroundings: the air around it, plus the sun and the people in it
        final double equilibrium = climate.ambientF + solarGainF(location) + occupants * BODY_HEAT_F;
        climate.cabinF += (equilibrium - climate.cabinF) / LEAK_SECONDS;

        climate.running = false;
        if (!climate.on) return;

        // The engine must be running (there is a driver, or the vehicle was started remotely - checked by the caller) and there must be fuel
        if (!engineRunning) return;
        final boolean fuelUsed = isFuelUsed(climate.license);
        final Double fuel = fuelUsed ? currentFuel(climate.license) : null;
        if (fuelUsed && (fuel == null || fuel < 1)) return;

        final double error = climate.targetF - climate.cabinF;
        final boolean wantCooling = error < -DEADBAND_F && climate.mode != Mode.HEAT;
        final boolean wantHeating = error > DEADBAND_F && climate.mode != Mode.COOL;
        if (!wantCooling && !wantHeating) return;

        final int fan = climate.fan != 0 ? climate.fan : autoFan(Math.abs(error));

        // An A/C works worse in extreme heat
        double power = FAN_POWER[fan];
        if (wantCooling && climate.ambientF > 100.0) power *= Math.max(0.5, 1.0 - (climate.ambientF - 100.0) / 100.0);

        final double change = Math.min(Math.abs(error), power);
        climate.cabinF += wantCooling ? -change : change;
        climate.running = true;
        climate.cooling = wantCooling;

        // Extra fuel
        final double usage = number(config(DefaultConfig.Option.HVAC_FUEL_USAGE), 0.02) * (fan / 2.0);
        if (fuelUsed && usage > 0 && fuel != null) setCurrentFuel(climate.license, Math.max(0.0, fuel - usage));
    }

    /**
     * Fuel level (%) of a vehicle: the live value while it has been driven since the server started, otherwise the saved one.
     */
    private static @Nullable Double currentFuel(String license) {
        final Double live = VehicleData.fuel.get(license);
        if (live != null) return live;
        final Object stored = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.FUEL);
        return stored instanceof Number ? ((Number) stored).doubleValue() : null;
    }

    private static void setCurrentFuel(String license, double fuel) {
        if (VehicleData.fuel.containsKey(license)) {
            VehicleData.fuel.put(license, fuel);
        } else {
            ConfigModule.vehicleDataConfig.set(license, VehicleDataConfig.Option.FUEL, fuel);
            ConfigModule.vehicleDataConfig.save();
        }
    }

    private static int autoFan(double errorF) {
        if (errorF > 15.0) return 3;
        if (errorF > 6.0) return 2;
        return 1;
    }

    private static boolean isFuelUsed(String license) {
        final Object global = config(DefaultConfig.Option.FUEL_ENABLED);
        final Object vehicle = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.FUEL_ENABLED);
        return Boolean.TRUE.equals(global) && Boolean.TRUE.equals(vehicle);
    }

    // --- Surroundings ---

    /**
     * Temperature (°F) of the air around a location: from EZvac if available, otherwise from the biome, time of day and weather.
     */
    public static double ambientF(Location location) {
        final Double ezvac = EzvacBridge.temperatureAt(location);
        if (ezvac != null) return ezvac;
        return vanillaTemperatureF(location);
    }

    private static double vanillaTemperatureF(Location location) {
        final World world = location.getWorld();
        if (world == null) return 70.0;
        switch (world.getEnvironment()) {
            case NETHER: return 105.0;
            case THE_END: return 45.0;
            default: break;
        }

        // Biome temperatures: 0.0 = snowy (14°F), 0.8 = plains (72°F), 2.0 = desert (105°F)
        final double biome = location.getBlock().getTemperature();
        double fahrenheit = biome <= 0.8 ? 14.0 + 72.5 * biome : 72.0 + (biome - 0.8) * 27.5;

        // Colder at night, warmer in the afternoon
        final double time = world.getTime();
        fahrenheit += 8.0 * Math.cos(2.0 * Math.PI * (time - 6000.0) / 24000.0);
        if (world.hasStorm()) fahrenheit -= 6.0;
        return fahrenheit;
    }

    /**
     * How much hotter than the air the sun makes the cabin (°F).
     */
    private static double solarGainF(Location location) {
        final World world = location.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) return 0.0;

        final long time = world.getTime();
        if (time >= 12000) return 0.0; // night
        if (location.getBlock().getLightFromSky() < 14) return 0.0; // not under the open sky

        final double sun = Math.sin(Math.PI * time / 12000.0); // 0 at dawn/dusk, 1 at noon
        return (world.hasStorm() ? SUN_HEAT_F * 0.25 : SUN_HEAT_F) * Math.max(0.0, sun);
    }

    // --- Display ---

    private static boolean shouldUseActionBar() {
        final Object display = config(DefaultConfig.Option.HVAC_DISPLAY);
        final String value = display != null ? display.toString().toUpperCase(Locale.ROOT) : "AUTO";
        switch (value) {
            case "ACTIONBAR": return true;
            case "AUTO": return !hudRegistered;
            default: return false; // EZVAC (shown on EZvac's HUD) or NONE
        }
    }

    private static boolean shouldUseEzvacHud() {
        final Object display = config(DefaultConfig.Option.HVAC_DISPLAY);
        final String value = display != null ? display.toString().toUpperCase(Locale.ROOT) : "AUTO";
        return value.equals("AUTO") || value.equals("EZVAC");
    }

    private static String actionBarText(Climate climate) {
        final String status;
        if (!climate.on) status = "&8off";
        else if (!climate.running) status = "&7idle";
        else status = climate.cooling ? "&bcooling" : "&6heating";
        return "&7Cabin &f" + format(climate.cabinF) + " &8» &f" + format(climate.targetF) + " &8| " + status + " &8| &7Fan &f" + climate.getFanText();
    }

    private static void sendActionBar(Player player, String legacyText) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(TextUtils.colorize(legacyText)));
        } catch (Throwable ignored) {
            // very old/unusual server - the menu still shows the climate
        }
    }

    /**
     * Lines of the section on EZvac's HUD
     */
    private static List<String> hudLines(Player player, Function<Double, String> temperature) {
        final List<String> lines = new ArrayList<>();
        if (!shouldUseEzvacHud()) return lines;

        final String license = getLicenseOf(player);
        if (license == null) return lines;
        final Climate climate = climates.get(license);
        if (climate == null) return lines;

        if (!climate.on) {
            lines.add("&7Car cabin: &f" + temperature.apply(climate.cabinF) + " &8(climate off)");
            return lines;
        }
        final String status = !climate.running ? "&7idle" : climate.cooling ? "&bcooling" : "&6heating";
        lines.add("&7Car cabin: &f" + temperature.apply(climate.cabinF) + " &8» &f" + temperature.apply(climate.targetF) + " &8(" + status + "&8)");
        lines.add("&7Fan &f" + climate.getFanText() + "&7, outside &f" + temperature.apply(climate.ambientF));
        return lines;
    }
}
