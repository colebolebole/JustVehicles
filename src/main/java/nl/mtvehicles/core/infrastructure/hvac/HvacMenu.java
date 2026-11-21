package nl.mtvehicles.core.infrastructure.hvac;

import nl.mtvehicles.core.infrastructure.utils.ItemUtils;
import nl.mtvehicles.core.infrastructure.utils.MenuUtils;
import nl.mtvehicles.core.infrastructure.utils.TextUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The climate control menu of a vehicle (opened with the swap hands key, or <b>/vehicle hvac</b>).
 * It is recognised by its {@link Holder}, not by its title.
 *
 * @since 2.5.9
 */
public final class HvacMenu {
    private HvacMenu() {}

    public static final int SLOT_INFO = 4;
    public static final int SLOT_POWER = 10;
    public static final int SLOT_MODE = 11;
    public static final int SLOT_FAN = 12;
    public static final int SLOT_TARGET_DOWN = 14;
    public static final int SLOT_TARGET = 15;
    public static final int SLOT_TARGET_UP = 16;
    public static final int SLOT_CLOSE = 22;

    /**
     * Inventory holder of the climate menu
     */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Open the climate menu to a player who is sitting in a vehicle.
     * @return False if the player is not sitting in a vehicle with a climate control
     */
    public static boolean open(Player player) {
        final HvacManager.Climate climate = HvacManager.getClimateOf(player);
        if (climate == null) return false;

        final Holder holder = new Holder();
        final Inventory inv = Bukkit.createInventory(holder, 27, TextUtils.colorize("&8" + shorten(climate.getDisplayName(), 30)));
        holder.inventory = inv;
        render(inv, climate);
        player.openInventory(inv);
        return true;
    }

    /**
     * Fill (or update) the menu according to the climate
     */
    public static void render(Inventory inv, HvacManager.Climate climate) {
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, ItemUtils.getMenuItem(ItemUtils.getStainedGlassPane(), 1, "&c", "&c"));
        }

        inv.setItem(SLOT_INFO, ItemUtils.getMenuItem("COMPASS", "COMPASS", (short) 0, 1, "&6" + climate.getDisplayName(), infoLore(climate)));

        inv.setItem(SLOT_POWER, climate.isOn()
                ? ItemUtils.getMenuItem("LIME_WOOL", "WOOL", (short) 5, 1, "&aClimate control: ON", Arrays.asList("&7Click to turn it off"))
                : ItemUtils.getMenuItem("RED_WOOL", "WOOL", (short) 14, 1, "&cClimate control: OFF", Arrays.asList("&7Click to turn it on", "&8(it only works while the engine is running)")));

        final String modeMaterial = climate.getMode() == HvacManager.Mode.COOL ? "PACKED_ICE" : climate.getMode() == HvacManager.Mode.HEAT ? "BLAZE_POWDER" : "NETHER_STAR";
        inv.setItem(SLOT_MODE, ItemUtils.getMenuItem(modeMaterial, modeMaterial, (short) 0, 1, "&eMode: &f" + climate.getMode().getDisplayName(),
                Arrays.asList("&7Auto: cools or heats as needed", "&7A/C: cools only", "&7Heater: heats only", "&a", "&eClick to change")));

        final int fan = climate.getFan();
        inv.setItem(SLOT_FAN, ItemUtils.getMenuItem("FEATHER", "FEATHER", (short) 0, Math.max(1, fan), "&eFan: &f" + climate.getFanText(),
                Arrays.asList("&7Auto, or level 1 - 3", "&a", "&eClick to change")));

        inv.setItem(SLOT_TARGET_DOWN, ItemUtils.getMenuItem("LIGHT_BLUE_WOOL", "WOOL", (short) 3, 1, "&bColder",
                Arrays.asList("&7Click: -1 degree", "&7Shift-click: -5 degrees")));
        inv.setItem(SLOT_TARGET, ItemUtils.getMenuItem(Material.NAME_TAG, 1, "&eTarget: &f" + HvacManager.format(climate.getTargetF()),
                Arrays.asList("&7The temperature the vehicle aims for")));
        inv.setItem(SLOT_TARGET_UP, ItemUtils.getMenuItem("ORANGE_WOOL", "WOOL", (short) 1, 1, "&6Warmer",
                Arrays.asList("&7Click: +1 degree", "&7Shift-click: +5 degrees")));

        inv.setItem(SLOT_CLOSE, MenuUtils.getCloseItem());
    }

    /** Titles of inventories are limited to 32 characters on older versions. */
    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static List<String> infoLore(HvacManager.Climate climate) {
        final List<String> lore = new ArrayList<>();
        lore.add("&a");
        lore.add("&7Inside: &f" + HvacManager.format(climate.getCabinF()));
        lore.add("&7Outside: &f" + HvacManager.format(climate.getAmbientF()));
        lore.add("&7Target: &f" + HvacManager.format(climate.getTargetF()));
        lore.add("&7Status: &f" + climate.getStatus());
        return lore;
    }
}
