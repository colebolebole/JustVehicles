package nl.mtvehicles.core.commands.vehiclesubs;

import nl.mtvehicles.core.infrastructure.enums.Message;
import nl.mtvehicles.core.infrastructure.models.MTVSubCommand;
import nl.mtvehicles.core.infrastructure.utils.VehicleFinderMenu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * <b>/vehicle find (%player%|all)</b> - open the vehicle finder: a GUI with your spawned vehicles, where you can teleport to a vehicle, bring it to you or point your compass at it.
 * <ul>
 *     <li>mtvehicles.find - open the finder (own vehicles)</li>
 *     <li>mtvehicles.find.others - see vehicles of other players (/vehicle find &lt;player&gt;, /vehicle find all)</li>
 *     <li>mtvehicles.find.teleport - teleport to a vehicle</li>
 *     <li>mtvehicles.find.bring - bring a vehicle to you</li>
 * </ul>
 * @since 2.5.9
 */
public class VehicleFind extends MTVSubCommand {
    public VehicleFind() {
        this.setPlayerCommand(true);
    }

    @Override
    public boolean execute() {
        if (!checkPermission(VehicleFinderMenu.PERMISSION_OPEN)) return true;

        UUID owner = player.getUniqueId();

        if (arguments.length >= 2) {
            if (!checkPermission(VehicleFinderMenu.PERMISSION_OTHERS)) return true;

            if (arguments[1].equalsIgnoreCase("all")) {
                owner = null;
            } else {
                final Player online = Bukkit.getPlayerExact(arguments[1]);
                final OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(arguments[1]);
                if (!target.isOnline() && !target.hasPlayedBefore()) {
                    sendMessage(Message.OFFLINE_PLAYER_NOT_FOUND);
                    return true;
                }
                owner = target.getUniqueId();
            }
        }

        VehicleFinderMenu.open(player, owner, 1);
        return true;
    }
}
