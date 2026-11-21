# Car climate API

MTVehicles gives every vehicle (by default: cars) a climate control - see the `hvac` section of `config.yml`.
Dashboards such as [EZStats](https://github.com/colebolebole/EZStats) can show and control it through
`nl.mtvehicles.core.api.CarClimateApi`.

- It is a class of **static methods which only use plain Java types** (strings, numbers, booleans, `UUID`, maps and lists),
  so another plugin can call it by reflection and does not need to depend on MTVehicles' classes.
- **Call everything on the main server thread.**
- **All temperatures are in °F.**
- `API_VERSION` (an `int`) is raised when something is added.

## Linking

A vehicle can only be read and changed through the API once its owner has **linked** it, in game:

```
/vehicle hvac link      (sitting in the vehicle, or holding its item)
/vehicle hvac unlink
```

Only the vehicle's owner (the owner in vehicleData.yml) can link it, and only the owner can change it afterwards - the API is
given the UUID of the player who acts and checks it against the owner.

## Reading

| Method | Gives |
| --- | --- |
| `isAvailable()` | Whether `hvac.enabled` is on. |
| `licenseOf(Player)` | The license plate of the vehicle with a climate control the player sits in, or `null`. |
| `ownerOf(String license)` | The owner's `UUID`, or `null`. |
| `isLinked(String license)` | Whether it is linked. |
| `getLinked()`, `getLinkedOf(UUID owner)` | The license plates of the linked vehicles (of everyone, or of one owner). |
| `describe(String license)` | A map describing it (below), or `null` if the vehicle doesn't exist or has no climate control. |

`describe` keys (a key is absent when unknown):

| Key | |
| --- | --- |
| `license`, `name`, `owner`, `linked` | The vehicle, its owner (UUID as text), and whether it is linked. |
| `on`, `mode`, `fan` | The setting: on/off, `auto` / `cool` / `heat`, and `auto` / `1` / `2` / `3`. |
| `targetF`, `minTargetF`, `maxTargetF` | The target temperature and the range it can be set in. |
| `status`, `running` | `off`, `idle`, `cooling` or `heating`, and whether it is working right now. |
| `cabinF`, `ambientF` | The temperature inside and around the vehicle. Only while the vehicle has been driven or started remotely recently. |
| `remote`, `remoteSecondsLeft`, `remoteStartMinutes` | Whether it runs without a driver, for how much longer, and how long a remote start lasts (`hvac.remoteStartMinutes`, 0 = off). |
| `loaded`, `occupied` | Whether the vehicle is spawned in a loaded chunk, and whether someone sits in it. |
| `world`, `x`, `y`, `z` | Where it is (only when `loaded`). |

## Changing

Each returns a **result** map: `ok` (`Boolean`), `status` (`Integer`, an HTTP status such as 200, 400, 403 or 404),
`message` (a sentence fit to show a player) and `adjusted` (`Boolean`: done, but not exactly as asked).

| Method | |
| --- | --- |
| `link(UUID actor, String license, boolean linked)` | Links or unlinks (the owner only). |
| `setEnabled(UUID actor, String license, boolean on)` | Switches it on or off. Switching it on while nobody sits in the vehicle starts it **remotely** for `remoteStartMinutes` (it uses fuel). |
| `setTargetF(UUID actor, String license, double targetF)` | Sets the target, kept within the range (`adjusted` is then `true`). |
| `setMode(UUID actor, String license, String mode)` | `auto`, `cool` or `heat`. |
| `setFan(UUID actor, String license, String fan)` | `auto`, `1`, `2` or `3`. |

Changing a vehicle which is not spawned (or whose chunk is not loaded) only saves the setting; it takes effect when somebody gets in.
