package nl.mtvehicles.core.infrastructure.dependencies;

import nl.mtvehicles.core.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Optional integration with <a href="https://github.com/EZvac-Plugin/EZvac">EZvac</a> (a climate/HVAC plugin), using its public API (docs/api.md).
 * <p>
 * EZvac's API is built for Java 21 and uses Adventure types, so everything is done by reflection: this plugin does not need EZvac to be compiled or to run.
 * <ul>
 *     <li>{@link #temperatureAt(Location)} - the air temperature at a location (outside, or the room of a conditioned garage)</li>
 *     <li>{@link #registerHud(Plugin, String, HudLines)} - adds a section to EZvac's climate HUD</li>
 * </ul>
 * EZvac's API is read-only for the climate, so the cabin of a vehicle can't be made part of EZvac's own systems - the vehicle's climate is simulated by this plugin.
 *
 * @since 2.5.9
 */
public final class EzvacBridge {
    private EzvacBridge() {}

    private static final String API_PACKAGE = "com.github.blade.hvac.api";

    /**
     * Lines of an addon section on EZvac's HUD (legacy colour codes using '&amp;').
     */
    public interface HudLines {
        /**
         * @param player Player whose HUD is drawn
         * @param temperature Formats a temperature given in °F the way the HUD does (the server's units)
         * @return Lines to show - an empty list hides the section for this player
         */
        List<String> lines(Player player, Function<Double, String> temperature);
    }

    private static boolean available = false;
    private static Object api;
    private static Method readingAtMethod;
    private static Method unitsMethod;
    private static Method hudMethod;
    private static ClassLoader ezvacLoader;

    // Cached accessors of the returned (record) objects
    private static Method temperatureFMethod;
    private static Class<?> temperatureFMethodFor;

    // Adventure (for the HUD lines)
    private static Object legacySerializer;
    private static Method deserializeMethod;
    private static Method emptyComponentMethod;

    /**
     * Connect to EZvac if it is installed and enabled. Never throws.
     */
    public static void init() {
        available = false;
        try {
            final Plugin ezvac = Bukkit.getPluginManager().getPlugin("EZvac");
            if (ezvac == null || !ezvac.isEnabled()) return;

            ezvacLoader = ezvac.getClass().getClassLoader();
            final Class<?> apiClass = Class.forName(API_PACKAGE + ".EzvacApi", true, ezvacLoader);
            api = apiClass.getMethod("get").invoke(null);
            readingAtMethod = apiClass.getMethod("readingAt", Location.class);
            unitsMethod = apiClass.getMethod("units");
            hudMethod = apiClass.getMethod("hud");
            available = api != null;

            if (available) Main.logInfo("EZvac found - using it for the outside temperature of vehicles (and showing the car climate on its HUD).");
        } catch (Throwable t) {
            available = false;
            Main.logWarning("EZvac was found, but its API could not be used (" + t + "). The climate of vehicles is calculated without it.");
        }
    }

    /**
     * Whether EZvac is installed and its API is in use.
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Get the air temperature at a location, as EZvac reads it (like '/hvac temperature').
     * @return Temperature in °F, null if EZvac is not available or fails
     */
    public static @Nullable Double temperatureAt(Location location) {
        if (!available || location.getWorld() == null) return null;
        try {
            final Object reading = readingAtMethod.invoke(api, location);
            if (reading == null) return null;

            Method method = temperatureFMethod;
            if (method == null || temperatureFMethodFor != reading.getClass()) {
                method = reading.getClass().getMethod("temperatureF");
                temperatureFMethod = method;
                temperatureFMethodFor = reading.getClass();
            }
            final double value = (double) method.invoke(reading);
            return Double.isNaN(value) ? null : value;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether the server shows temperatures in °C.
     * @return Null if EZvac is not available
     */
    public static @Nullable Boolean usesCelsius() {
        if (!available) return null;
        try {
            final Object unit = unitsMethod.invoke(api);
            return unit != null && unit.toString().equalsIgnoreCase("C");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Add a section to EZvac's climate HUD (shown to every player who has the HUD switched on).
     * It is removed by EZvac when this plugin is disabled.
     *
     * @param owner Plugin
     * @param key Key of the section (the HUD id becomes 'pluginname:key')
     * @param lines Provider of the lines
     * @return True if registered
     */
    public static boolean registerHud(Plugin owner, String key, HudLines lines) {
        if (!available) return false;
        try {
            final Class<?> hudSectionClass = Class.forName(API_PACKAGE + ".hud.HudSection", true, ezvacLoader);
            final Class<?> hudContextClass = Class.forName(API_PACKAGE + ".hud.HudContext", true, ezvacLoader);
            final Class<?> hudClass = Class.forName(API_PACKAGE + ".hud.EzvacHud", true, ezvacLoader);

            final Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            emptyComponentMethod = componentClass.getMethod("empty");
            final Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            legacySerializer = serializerClass.getMethod("legacyAmpersand").invoke(null);
            deserializeMethod = null;
            for (Method method : serializerClass.getMethods()) {
                if (method.getName().equals("deserialize") && method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class) {
                    deserializeMethod = method;
                    break;
                }
            }
            if (deserializeMethod == null) throw new NoSuchMethodException("LegacyComponentSerializer#deserialize(String)");

            final Method playerMethod = hudContextClass.getMethod("player");
            final Method temperatureMethod = hudContextClass.getMethod("temperature", double.class);

            final InvocationHandler handler = (proxy, method, args) -> {
                final String name = method.getName();
                if (name.equals("lines") || name.equals("inline")) {
                    List<String> text = Collections.emptyList();
                    try {
                        final Object context = args[0];
                        final Player player = (Player) playerMethod.invoke(context);
                        final Function<Double, String> temperature = fahrenheit -> {
                            try {
                                return (String) temperatureMethod.invoke(context, fahrenheit);
                            } catch (Throwable t) {
                                return Math.round(fahrenheit) + "°F";
                            }
                        };
                        text = lines.lines(player, temperature);
                    } catch (Throwable t) {
                        // a broken section simply shows nothing
                    }

                    if (name.equals("inline")) {
                        return text.isEmpty() ? emptyComponentMethod.invoke(null) : component(String.join(" ", text));
                    }
                    final List<Object> components = new ArrayList<>();
                    for (String line : text) components.add(component(line));
                    return components;
                }
                switch (name) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "MTVehicles HUD section";
                    default: return null;
                }
            };

            final Object section = Proxy.newProxyInstance(hudSectionClass.getClassLoader(), new Class<?>[]{hudSectionClass}, handler);
            final Object hud = hudMethod.invoke(api);
            hudClass.getMethod("register", Plugin.class, String.class, hudSectionClass).invoke(hud, owner, key, section);
            return true;
        } catch (Throwable t) {
            Main.logWarning("Could not add the car climate to EZvac's HUD (" + t + ").");
            return false;
        }
    }

    private static Object component(String legacyText) throws Exception {
        return deserializeMethod.invoke(legacySerializer, legacyText);
    }
}
