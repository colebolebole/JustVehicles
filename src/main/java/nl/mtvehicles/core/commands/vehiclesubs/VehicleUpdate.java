package nl.mtvehicles.core.commands.vehiclesubs;

import nl.mtvehicles.core.infrastructure.dataconfig.DefaultConfig;
import nl.mtvehicles.core.infrastructure.enums.Message;
import nl.mtvehicles.core.infrastructure.utils.PluginUpdater;
import nl.mtvehicles.core.infrastructure.models.MTVSubCommand;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;

/**
 * <b>/vehicle update</b> - update the plugin if a newer version is available.
 */
public class VehicleUpdate extends MTVSubCommand {
    @Override
    public boolean execute() {
        if (!checkPermission("mtvehicles.update")) return true;

        if (!PluginUpdater.isAvailable() || !(boolean) ConfigModule.defaultConfig.get(DefaultConfig.Option.AUTO_UPDATE)) {
            sendMessage("&cThe updater is turned off in JustVehicles: the original updater would replace it with the original MTVehicles. Install new versions of JustVehicles by hand.");
            return false;
        }

        PluginUpdater.updatePlugin(sender);
        return true;
    }
}
