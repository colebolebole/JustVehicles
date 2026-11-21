package nl.mtvehicles.core.listeners.inventory;

import nl.mtvehicles.core.infrastructure.enums.Message;
import nl.mtvehicles.core.infrastructure.enums.RegionAction;
import nl.mtvehicles.core.infrastructure.enums.VehicleType;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.utils.TextUtils;
import nl.mtvehicles.core.infrastructure.utils.VehicleFinderMenu;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleFinder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Handles clicks in the vehicle finder menu (see {@link VehicleFinderMenu}).
 *
 * @since 2.5.9
 */
public class VehicleFinderListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof VehicleFinderMenu.Holder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        final Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof VehicleFinderMenu.Holder)) return;

        event.setCancelled(true); // nothing can be taken out of or put into this menu
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;

        final Player player = (Player) event.getWhoClicked();
        final VehicleFinderMenu.Holder holder = (VehicleFinderMenu.Holder) top.getHolder();
        final int slot = event.getSlot();

        if (slot == VehicleFinderMenu.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == VehicleFinderMenu.SLOT_REFRESH) {
            VehicleFinderMenu.open(player, holder.getOwner(), holder.getPage());
            return;
        }
        if (slot == VehicleFinderMenu.SLOT_PREVIOUS && event.getCurrentItem() != null) {
            VehicleFinderMenu.open(player, holder.getOwner(), holder.getPage() - 1);
            return;
        }
        if (slot == VehicleFinderMenu.SLOT_NEXT && event.getCurrentItem() != null) {
            VehicleFinderMenu.open(player, holder.getOwner(), holder.getPage() + 1);
            return;
        }

        final String license = holder.getLicensePlate(slot);
        if (license == null) return;

        handleVehicleClick(player, holder, license, event.getClick());
    }

    private void handleVehicleClick(Player player, VehicleFinderMenu.Holder holder, String license, ClickType click) {
        final VehicleFinder.SpawnedVehicle vehicle = VehicleFinder.getSpawned(license);
        if (vehicle == null) {
            send(player, "&cThis vehicle is not spawned (or its chunk is not loaded) anymore.");
            VehicleFinderMenu.open(player, holder.getOwner(), holder.getPage());
            return;
        }
        final Location location = vehicle.getLocation();

        if (click.isShiftClick()) { // Point the compass at the vehicle
            if (location.getWorld() != null && location.getWorld().equals(player.getWorld())) {
                player.setCompassTarget(location);
                send(player, "&aYour compass now points at the vehicle &f" + license + "&a (" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ").");
            } else {
                send(player, "&eThe vehicle is in another world (&f" + (location.getWorld() != null ? location.getWorld().getName() : "?") + "&e): " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ".");
            }
            return;
        }

        if (click.isLeftClick()) { // Teleport the player to the vehicle
            if (!player.hasPermission(VehicleFinderMenu.PERMISSION_TELEPORT)) {
                ConfigModule.messagesConfig.sendMessage(player, Message.NO_PERMISSION);
                return;
            }
            player.closeInventory();
            player.teleport(location.add(0, 0.5, 0));
            send(player, "&aTeleported to the vehicle &f" + license + "&a.");
            return;
        }

        if (click.isRightClick()) { // Bring the vehicle to the player
            if (!player.hasPermission(VehicleFinderMenu.PERMISSION_BRING)) {
                ConfigModule.messagesConfig.sendMessage(player, Message.NO_PERMISSION);
                return;
            }
            if (!player.getUniqueId().equals(VehicleFinder.getOwner(license)) && !player.hasPermission(VehicleFinderMenu.PERMISSION_OTHERS)) {
                send(player, "&cYou can only bring your own vehicles.");
                return;
            }
            if (vehicle.isOccupied()) {
                send(player, "&cYou can't move a vehicle which is being used.");
                return;
            }

            // A spot in front of the player (not inside a block)
            Location target = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(2.5));
            if (target.getBlock().getType().isSolid()) target = player.getLocation();

            final VehicleType type = ConfigModule.vehicleDataConfig.getType(license);
            if (!ConfigModule.defaultConfig.canProceedWithAction(RegionAction.PLACE, type, target, player)) {
                ConfigModule.messagesConfig.sendMessage(player, Message.CANNOT_DO_THAT_HERE);
                return;
            }

            if (VehicleFinder.moveVehicle(vehicle, target)) {
                send(player, "&aThe vehicle &f" + license + "&a has been moved to you.");
            } else {
                send(player, "&cThe vehicle could not be moved.");
            }
            VehicleFinderMenu.open(player, holder.getOwner(), holder.getPage());
        }
    }

    private static void send(Player player, String message) {
        player.sendMessage(TextUtils.colorize(message));
    }
}
