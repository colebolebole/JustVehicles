package nl.mtvehicles.core.listeners.inventory;

import nl.mtvehicles.core.infrastructure.hvac.HvacManager;
import nl.mtvehicles.core.infrastructure.hvac.HvacMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;

/**
 * Opens the climate menu with the swap hands key (F) and handles clicks in it (see {@link HvacMenu}).
 *
 * @since 2.5.9
 */
public class HvacListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!HvacManager.isEnabled() || !HvacManager.opensWithSwapKey()) return;

        final Player player = event.getPlayer();
        if (!HvacManager.mayUse(player)) return;
        if (HvacManager.getClimateOf(player) == null) return; // not sitting in a vehicle with a climate control

        event.setCancelled(true);
        HvacMenu.open(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof HvacMenu.Holder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        final Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof HvacMenu.Holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;

        final Player player = (Player) event.getWhoClicked();
        final HvacManager.Climate climate = HvacManager.getClimateOf(player);
        if (climate == null) { // the player is not sitting in the vehicle anymore
            player.closeInventory();
            return;
        }

        final int slot = event.getSlot();
        final double stepMultiplier = event.isShiftClick() ? 5.0 : 1.0;

        switch (slot) {
            case HvacMenu.SLOT_POWER:
                HvacManager.setOn(climate, !climate.isOn());
                break;
            case HvacMenu.SLOT_MODE:
                HvacManager.setMode(climate, climate.getMode().next());
                break;
            case HvacMenu.SLOT_FAN:
                HvacManager.setFan(climate, (climate.getFan() + 1) % 4); // auto, 1, 2, 3
                break;
            case HvacMenu.SLOT_TARGET_DOWN:
                HvacManager.setTargetF(climate, climate.getTargetF() - HvacManager.getStepF() * stepMultiplier);
                break;
            case HvacMenu.SLOT_TARGET_UP:
                HvacManager.setTargetF(climate, climate.getTargetF() + HvacManager.getStepF() * stepMultiplier);
                break;
            case HvacMenu.SLOT_CLOSE:
                player.closeInventory();
                return;
            default:
                return;
        }

        HvacMenu.render(top, climate);
    }
}
