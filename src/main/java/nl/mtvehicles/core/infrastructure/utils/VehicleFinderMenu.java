package nl.mtvehicles.core.infrastructure.utils;

import de.tr7zw.changeme.nbtapi.NBTItem;
import nl.mtvehicles.core.infrastructure.dataconfig.VehicleDataConfig;
import nl.mtvehicles.core.infrastructure.enums.Message;
import nl.mtvehicles.core.infrastructure.modules.ConfigModule;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleFinder;
import nl.mtvehicles.core.infrastructure.vehicle.VehicleUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The vehicle finder GUI (<b>/vehicle find</b>): lists the spawned vehicles with their positions,
 * and allows to teleport to a vehicle, to bring it to you, or to point your compass at it.
 * <p>
 * The menu is recognised by its {@link Holder}, not by its title.
 *
 * @since 2.5.9
 */
public final class VehicleFinderMenu {
    private VehicleFinderMenu() {}

    /**
     * Number of vehicles on one page
     */
    public static final int PAGE_SIZE = 36;
    public static final int SLOT_PREVIOUS = 45;
    public static final int SLOT_CLOSE = 47;
    public static final int SLOT_REFRESH = 49;
    public static final int SLOT_NEXT = 53;

    public static final String PERMISSION_OPEN = "mtvehicles.find";
    public static final String PERMISSION_OTHERS = "mtvehicles.find.others";
    public static final String PERMISSION_TELEPORT = "mtvehicles.find.teleport";
    public static final String PERMISSION_BRING = "mtvehicles.find.bring";

    /**
     * Inventory holder of the finder menu - it also remembers which vehicle is in which slot.
     */
    public static final class Holder implements InventoryHolder {
        private final @Nullable UUID owner;
        private final int page;
        private final Map<Integer, String> vehicleSlots = new HashMap<>();
        private Inventory inventory;

        private Holder(@Nullable UUID owner, int page) {
            this.owner = owner;
            this.page = page;
        }

        /**
         * Owner whose vehicles are listed (null = all vehicles)
         */
        public @Nullable UUID getOwner() {
            return owner;
        }

        public int getPage() {
            return page;
        }

        /**
         * Get the license plate of the vehicle in a slot
         * @return Null if the slot does not contain a vehicle
         */
        public @Nullable String getLicensePlate(int slot) {
            return vehicleSlots.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Open the finder menu to a player.
     * @param viewer Player
     * @param owner Owner whose vehicles are listed (null = all vehicles)
     * @param page Page (starts at 1)
     */
    public static void open(Player viewer, @Nullable UUID owner, int page) {
        // Only the vehicles which are spawned in loaded chunks can be found. One scan for all vehicles.
        final Map<String, VehicleFinder.SpawnedVehicle> spawned = VehicleFinder.findSpawned(true);

        final List<VehicleFinder.SpawnedVehicle> list = new ArrayList<>();
        for (VehicleFinder.SpawnedVehicle vehicle : spawned.values()) {
            if (!VehicleUtils.existsByLicensePlate(vehicle.getLicensePlate())) continue;
            if (owner != null && !owner.equals(VehicleFinder.getOwner(vehicle.getLicensePlate()))) continue;
            list.add(vehicle);
        }

        // Nearest first
        final Location viewerLocation = viewer.getLocation();
        list.sort((a, b) -> {
            final int byDistance = Double.compare(distanceSquared(viewerLocation, a), distanceSquared(viewerLocation, b));
            return byDistance != 0 ? byDistance : a.getLicensePlate().compareToIgnoreCase(b.getLicensePlate());
        });

        final int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));

        final Holder holder = new Holder(owner, page);
        final Inventory inv = Bukkit.createInventory(holder, 54, TextUtils.colorize("&8Vehicle finder (" + page + "/" + pages + ")"));
        holder.inventory = inv;

        final boolean canTeleport = viewer.hasPermission(PERMISSION_TELEPORT);
        final boolean canBring = viewer.hasPermission(PERMISSION_BRING);

        final int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(list.size(), from + PAGE_SIZE); i++) {
            final VehicleFinder.SpawnedVehicle vehicle = list.get(i);
            final ItemStack item = createVehicleItem(vehicle, viewerLocation, canTeleport, canBring);
            if (item == null) continue;
            final int slot = i - from;
            inv.setItem(slot, item);
            holder.vehicleSlots.put(slot, vehicle.getLicensePlate());
        }

        for (int i = 36; i <= 44; i++) {
            inv.setItem(i, ItemUtils.getMenuItem(ItemUtils.getStainedGlassPane(), 1, "&c", "&c"));
        }
        if (page > 1) {
            inv.setItem(SLOT_PREVIOUS, ItemUtils.getMenuItem(Material.SPECTRAL_ARROW, 1, "&c" + ConfigModule.messagesConfig.getMessage(Message.PREVIOUS_PAGE), "&c"));
        }
        if (page < pages) {
            inv.setItem(SLOT_NEXT, ItemUtils.getMenuItem(Material.SPECTRAL_ARROW, 1, "&c" + ConfigModule.messagesConfig.getMessage(Message.NEXT_PAGE), "&c"));
        }
        inv.setItem(SLOT_CLOSE, MenuUtils.getCloseItem());
        inv.setItem(SLOT_REFRESH, createInfoItem(list.size(), owner));

        viewer.openInventory(inv);
    }

    private static double distanceSquared(Location from, VehicleFinder.SpawnedVehicle vehicle) {
        final Location location = vehicle.getLocation();
        if (location.getWorld() == null || !location.getWorld().equals(from.getWorld())) return Double.MAX_VALUE;
        return location.distanceSquared(from);
    }

    private static @Nullable ItemStack createVehicleItem(VehicleFinder.SpawnedVehicle vehicle, Location viewerLocation, boolean canTeleport, boolean canBring) {
        final String license = vehicle.getLicensePlate();
        ItemStack item = ConfigModule.vehicleDataConfig.get(license, VehicleDataConfig.Option.NBT_VALUE) == null
                ? ItemUtils.getVehicleItem(license)
                : ItemUtils.getVehicleItem(license, true);
        if (item == null) return null; // incomplete vehicle data

        // This is only a display item - it must never be usable as a vehicle.
        final NBTItem nbt = new NBTItem(item);
        nbt.removeKey("mtvehicles.kenteken");
        nbt.removeKey("mtvehicles.naam");
        item = nbt.getItem();

        final Location location = vehicle.getLocation();
        final List<String> lore = new ArrayList<>();
        lore.add("&a");
        lore.add("&7License plate: &f" + license);
        lore.add("&7World: &f" + (location.getWorld() != null ? location.getWorld().getName() : "?"));
        lore.add(String.format(Locale.ROOT, "&7Position: &f%d, %d, %d", location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        if (location.getWorld() != null && location.getWorld().equals(viewerLocation.getWorld())) {
            lore.add("&7Distance: &f" + Math.round(Math.sqrt(location.distanceSquared(viewerLocation))) + " blocks");
        }
        lore.add(vehicle.isOccupied() ? "&7Status: &eIn use" + getDriverText(vehicle) : "&7Status: &aParked");
        lore.add("&a");
        lore.add(canTeleport ? "&eLeft-click: &7teleport to the vehicle" : "&8Left-click: teleport (no permission)");
        if (!vehicle.isOccupied()) {
            lore.add(canBring ? "&eRight-click: &7bring the vehicle to you" : "&8Right-click: bring (no permission)");
        }
        lore.add("&eShift-click: &7point your compass at it");

        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        final List<String> colored = new ArrayList<>();
        for (String line : lore) colored.add(TextUtils.colorize(line));
        meta.setLore(colored);
        item.setItemMeta(meta);
        return item;
    }

    private static String getDriverText(VehicleFinder.SpawnedVehicle vehicle) {
        for (Entity entity : vehicle.getEntities()) {
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof Player) return " &7(" + passenger.getName() + ")";
            }
        }
        return "";
    }

    private static ItemStack createInfoItem(int found, @Nullable UUID owner) {
        final List<String> lore = new ArrayList<>();
        lore.add("&7Vehicles found: &f" + found);
        if (owner != null) {
            int total = 0;
            for (String license : ConfigModule.vehicleDataConfig.getVehicles().keySet()) {
                if (owner.equals(VehicleFinder.getOwner(license))) total++;
            }
            if (total > found) {
                lore.add("&7Not found: &f" + (total - found));
                lore.add("&8(in the inventory of a player, or in an unloaded chunk)");
            }
        }
        lore.add("&a");
        lore.add("&eClick to refresh");
        return ItemUtils.getMenuItem(Material.COMPASS, 1, "&6Vehicle finder", lore);
    }

}
