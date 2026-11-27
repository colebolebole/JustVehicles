<h1 align="center">JustVehicles</h1>
<p align="center"><em>MinetopiaVehicles (MTVehicles) for Minecraft 26.3, with a climate control in every vehicle, a vehicle finder, lane assist and more.</em></p>

> **JustVehicles is a fork of [MinetopiaVehicles](https://github.com/MTVehicles/MinetopiaVehicles) (MTVehicles)**, the vehicles plugin by **GamerJoep_** and the [MTVehicles team](https://github.com/MTVehicles). All the vehicles, the driving, the resource pack, the models and nearly all of the code are theirs. This fork only adds the changes listed below on top of it. It is not affiliated with or supported by the MTVehicles team, so **please don't ask them for support with this build**.
>
> - Original plugin: https://github.com/MTVehicles/MinetopiaVehicles
> - Website: https://mtvehicles.eu/ - Wiki: https://wiki.mtvehicles.eu - Discord: https://discord.gg/vehicle
> - Resource pack (still needed, vehicles won't look right without it): https://mtvehicles.nl/resourcepack/
> - The original README is kept as [README-MTVehicles.md](README-MTVehicles.md). Everything about setting up and using the plugin that isn't mentioned here works as it does there.
>
> Licensed under the [MIT license](LICENSE), copyright (c) 2020 GamerJoep_, as the original.

---

## What this fork changes

> Status: these changes compile and were put together with a lot of care, but most of them have only been tried on one server. Back up your `vehicleData.yml` before you start using it.

### Minecraft 26.3
- Runs on **Minecraft 26.3** (the new version numbering, `movement_modern`).

### Bug fixes
- `/vehicle restore` crashed on vehicles with incomplete data (a `NullPointerException` in the menu). Such vehicles are skipped now, and a warning (once per vehicle) says what is wrong with their data.
- A scan of the code fixed a number of other confirmed bugs (see the commit "Fix multiple confirmed bugs found in a code scan").

### Restore menu (`/vehicle restore`)
- **Restoring a vehicle that is still spawned in the world removes it from the world**, so you don't end up with two. Those vehicles are marked in the menu, and a vehicle somebody is driving can't be restored.
- **Delete vehicles from the menu:** *Shift + right-click* a vehicle, then do it again within 10 seconds to confirm. Needs `mtvehicles.delete` (the same as `/vehicle delete`). Vehicles with broken data show up as a barrier item that can be deleted the same way.

### Vehicle finder (`/vehicle find`)
- A GUI with your spawned vehicles, nearest first: **left-click** teleports you to a vehicle, **right-click** brings it to you, **shift-click** points your compass at it.
- `/vehicle find <player>` and `/vehicle find all` for admins.
- Permissions: `mtvehicles.find`, `mtvehicles.find.others`, `mtvehicles.find.teleport`, `mtvehicles.find.bring`.

### Less lag with many vehicles
- Cached config lookups, the vehicle data file is saved after a short delay instead of on every change, the boss bar and the packet handling do less work every tick, and the restore menu no longer re-reads every file on each page.

### Climate control (HVAC) in every vehicle
- Every vehicle has a **climate control**: the temperature inside (the cabin) slowly follows the temperature outside, the sun heats it, passengers add a little, and the **A/C and heater** bring it to your target while the engine runs (using a little extra fuel).
- Open it with the **swap hands key (F)** or `/vehicle hvac` (also `/vehicle ac`, `/vehicle climate`): on/off, mode (auto, A/C, heater), fan (auto, 1-3) and target temperature. Settings are saved per vehicle.
  - `/vehicle hvac on|off|toggle`, `mode auto|cool|heat`, `fan auto|1|2|3`, `temp <temperature>`, `status`
  - `/vehicle hvac rename <name>` gives it its own name (up to 32 characters; empty removes it).
- It works like [EZvac](https://github.com/EZvac-Plugin/EZvac)'s thermostat, so it doesn't keep clicking on and off: it starts 1 °F away from the target, runs until it is a little past it, runs at least 15 s, rests at least 10 s and slows down near the target. With EZvac installed it **reads EZvac's own timings** (`controller.*` in its config); otherwise it uses the values under `hvac.cycle` in `config.yml`.
- Settings in `config.yml` under `hvac:` (units, display, min/max target, fuel use, vehicle types to leave out...).

### EZvac and EZStats
- **[EZvac](https://github.com/EZvac-Plugin/EZvac) (optional):** the temperature around a vehicle is read from EZvac (so it follows biomes, seasons, weather and the rooms of your garages), and the cabin is shown on EZvac's climate HUD. Done by reflection, so EZvac isn't needed to run the plugin.
- **[EZStats](https://github.com/colebolebole/EZStats) (optional):** `/vehicle hvac link` puts a vehicle's climate control on your EZStats web page next to your thermostats: set the target, mode and fan from the website, give it a nickname, and **start it remotely** while nobody is in it (it runs for `hvac.remoteStartMinutes` and uses fuel). Needs the EZStats build with vehicle support: the [`v0.8.2-mtvehicles-hvac` pre-release](https://github.com/colebolebole/EZStats/releases/tag/v0.8.2-mtvehicles-hvac). Update both plugins together.
- The API behind it is documented in [docs/car-climate-api.md](docs/car-climate-api.md).

### Lane assist (cars)
- Keeps a car **in the middle of its lane while you aren't steering**, so you don't have to keep correcting it. Steering always takes over (the assist backs off for a moment), and it does nothing where it can't tell where the lane is (not on a road, a very wide road or open area, the road ending ahead).
- It finds the lane by looking left and right of the car for **lane lines** (`laneAssist.laneLines`, e.g. white/yellow/lime concrete; dashed lines work) or the end of the **road** (`laneAssist.roadBlocks`), here and a few blocks ahead.
- `/vehicle laneassist [on|off]` (also `/vehicle lane`) switches it for the vehicle you're in. **`/vehicle laneassist debug`** shows the blocks around the car as the assist sees them, so you can add your own road and line blocks to the config.
- Settings in `config.yml` under `laneAssist:` (`enabled`, `defaultOn`, `strength`, `lookAhead`, `maxHalfWidth`, `roadBlocks`, `laneLines`). It is on by default - set `defaultOn: false` if you'd rather have drivers turn it on.

---

## Installing

1. Download the jar from the releases of this repository (or build it: `mvn package` with JDK 21 - the jar is `target/JustVehicles.jar`).
2. Put it in your server's `plugins/` folder, replacing MTVehicles, and use the MTVehicles resource pack linked above.
3. Restart the server (not a reload). Options missing from an existing `config.yml` simply use their defaults. To change them, copy the `hvac:` and `laneAssist:` blocks from the [config.yml](src/main/resources/config.yml) in this repository (or delete your `config.yml` to get a fresh one).

The plugin is still called **MTVehicles** inside (plugin name, commands and permissions), so addons and Skript scripts written for MTVehicles keep working.

## Credits

- **[MTVehicles / MinetopiaVehicles](https://github.com/MTVehicles/MinetopiaVehicles)** by GamerJoep_ and contributors - the plugin this is built on.
- **[EZvac](https://github.com/EZvac-Plugin/EZvac)** - the climate plugin the climate control is modelled on and can read from.
- **[NBT-API](https://github.com/tr7zw/Item-NBT-API)** by tr7zw, bundled as in the original.
