package nl.mtvehicles.core.commands.vehiclesubs;

import nl.mtvehicles.core.infrastructure.hvac.HvacManager;
import nl.mtvehicles.core.infrastructure.hvac.HvacMenu;
import nl.mtvehicles.core.infrastructure.models.MTVSubCommand;

import java.util.Locale;

/**
 * <b>/vehicle hvac</b> (also <b>/vehicle ac</b> and <b>/vehicle climate</b>) - the climate control of the vehicle you are sitting in.
 * <ul>
 *     <li>/vehicle hvac - open the climate menu (you can also press the swap hands key)</li>
 *     <li>/vehicle hvac on|off|toggle</li>
 *     <li>/vehicle hvac mode auto|cool|heat</li>
 *     <li>/vehicle hvac fan auto|1|2|3</li>
 *     <li>/vehicle hvac temp &lt;temperature&gt;</li>
 *     <li>/vehicle hvac status</li>
 * </ul>
 * Everyone may use it unless the permission 'mtvehicles.hvac' is explicitly denied.
 *
 * @since 2.5.9
 */
public class VehicleHvac extends MTVSubCommand {
    public VehicleHvac() {
        this.setPlayerCommand(true);
    }

    @Override
    public boolean execute() {
        if (!HvacManager.isEnabled()) {
            sendMessage("&cThe climate control is disabled.");
            return true;
        }
        if (!HvacManager.mayUse(player)) {
            sendMessage("&cYou may not use the climate control.");
            return true;
        }

        final HvacManager.Climate climate = HvacManager.getClimateOf(player);
        if (climate == null) {
            sendMessage("&cYou must be sitting in a vehicle with a climate control.");
            return true;
        }

        if (arguments.length == 1) {
            HvacMenu.open(player);
            return true;
        }

        final String action = arguments[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on":
                HvacManager.setOn(climate, true);
                sendMessage("&aClimate control turned on.");
                return true;
            case "off":
                HvacManager.setOn(climate, false);
                sendMessage("&aClimate control turned off.");
                return true;
            case "toggle":
                HvacManager.setOn(climate, !climate.isOn());
                sendMessage("&aClimate control turned " + (climate.isOn() ? "on" : "off") + ".");
                return true;
            case "auto":
            case "cool":
            case "heat":
                HvacManager.setOn(climate, true);
                HvacManager.setMode(climate, HvacManager.Mode.valueOf(action.toUpperCase(Locale.ROOT)));
                sendMessage("&aMode: " + climate.getMode().getDisplayName() + ".");
                return true;
            case "mode":
                if (arguments.length < 3) break;
                try {
                    HvacManager.setMode(climate, HvacManager.Mode.valueOf(arguments[2].toUpperCase(Locale.ROOT)));
                    sendMessage("&aMode: " + climate.getMode().getDisplayName() + ".");
                } catch (IllegalArgumentException e) {
                    sendMessage("&cMode must be auto, cool or heat.");
                }
                return true;
            case "fan":
                if (arguments.length < 3) break;
                if (arguments[2].equalsIgnoreCase("auto")) {
                    HvacManager.setFan(climate, 0);
                } else {
                    try {
                        final int fan = Integer.parseInt(arguments[2]);
                        if (fan < 1 || fan > 3) throw new NumberFormatException();
                        HvacManager.setFan(climate, fan);
                    } catch (NumberFormatException e) {
                        sendMessage("&cFan must be auto, 1, 2 or 3.");
                        return true;
                    }
                }
                sendMessage("&aFan: " + climate.getFanText() + ".");
                return true;
            case "temp":
            case "target":
            case "set":
                if (arguments.length < 3) break;
                try {
                    final double value = Double.parseDouble(arguments[2]);
                    if (Double.isNaN(value) || Double.isInfinite(value)) throw new NumberFormatException();
                    HvacManager.setTargetF(climate, HvacManager.toFahrenheit(value));
                    sendMessage("&aTarget temperature: " + HvacManager.format(climate.getTargetF())
                            + " (between " + HvacManager.format(HvacManager.getMinTargetF()) + " and " + HvacManager.format(HvacManager.getMaxTargetF()) + ").");
                } catch (NumberFormatException e) {
                    sendMessage("&c'" + arguments[2] + "' is not a number.");
                }
                return true;
            case "status":
            case "info":
                sendMessage("&6Climate control: &f" + (climate.isOn() ? "on" : "off") + "&7, mode &f" + climate.getMode().getDisplayName()
                        + "&7, fan &f" + climate.getFanText() + "&7, " + climate.getStatus());
                sendMessage("&7Inside: &f" + HvacManager.format(climate.getCabinF()) + "&7, target: &f" + HvacManager.format(climate.getTargetF())
                        + "&7, outside: &f" + HvacManager.format(climate.getAmbientF()));
                return true;
            default:
                break;
        }

        sendMessage("&cUsage: /vehicle hvac [on|off|toggle|auto|cool|heat|mode <auto|cool|heat>|fan <auto|1|2|3>|temp <temperature>|status]");
        return true;
    }
}
