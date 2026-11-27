package nl.mtvehicles.core.commands.vehiclesubs;

import nl.mtvehicles.core.infrastructure.hvac.HvacManager;
import nl.mtvehicles.core.infrastructure.models.MTVSubCommand;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleUtils;
import nl.mtvehicles.core.movement.LaneAssist;

import java.util.Locale;

/**
 * <b>/vehicle laneassist (on|off)</b> (also <b>/vehicle lane</b>) - lane assist of the car you are sitting in: it keeps the car in the middle of the road
 * while you are not steering. Without an argument it switches it on or off. The setting is saved for the vehicle.
 * Everyone may use it unless the permission 'mtvehicles.laneassist' is explicitly denied.
 *
 * @since 2.5.10
 */
public class VehicleLaneAssist extends MTVSubCommand {
    public VehicleLaneAssist() {
        this.setPlayerCommand(true);
    }

    @Override
    public boolean execute() {
        if (!LaneAssist.isAvailable()) {
            sendMessage("&cLane assist is disabled.");
            return true;
        }
        if (player.isPermissionSet("mtvehicles.laneassist") && !player.hasPermission("mtvehicles.laneassist")) {
            sendMessage("&cYou may not use lane assist.");
            return true;
        }

        final String license = HvacManager.getLicenseOf(player);
        if (license == null || !VehicleUtils.existsByLicensePlate(license)) {
            sendMessage("&cYou must be sitting in a vehicle.");
            return true;
        }

        final boolean current = LaneAssist.isOn(license);
        final boolean on;
        if (arguments.length >= 2) {
            switch (arguments[1].toLowerCase(Locale.ROOT)) {
                case "on": on = true; break;
                case "off": on = false; break;
                default:
                    sendMessage("&cUse: /vehicle laneassist [on|off]");
                    return true;
            }
        } else {
            on = !current;
        }

        LaneAssist.setOn(license, on);
        sendMessage(on
                ? "&aLane assist is on: the car keeps itself in the middle of the road while you are not steering. Steering always takes over."
                : "&aLane assist is off.");
        return true;
    }
}
