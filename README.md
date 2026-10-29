# Rockchip Proximity Sensor for Kiosk Satellite

Read the `vi530x` time-of-flight proximity sensor on Android panels that register it as a kernel input device. The plugin reports the distance in millimetres, derives a near/far state with hysteresis and publishes both to Home Assistant through Kiosk Satellite's ESPHome connection. It can also wake the screen when someone approaches.

Rockchip chipset branding alone does not establish compatibility. The panel must expose an input device named `vi530x` (check `/proc/bus/input/devices`). The plugin does not read any other sensor.

This plugin follows the structure of [davidcoulson/kiosk-satellite-rockchip-led-control](https://github.com/davidcoulson/kiosk-satellite-rockchip-led-control) (Apache-2.0): a packaged JNI library, an optional root helper and the SDK 1 entity API. It includes no vendor binaries.

## How the sensor is read

The panel kernel registers the sensor as an evdev device named `vi530x`. Every few milliseconds it emits `EV_ABS` events. The plugin uses `ABS_HAT1X` as the distance. The other codes (`ABS_HAT1Y`, `ABS_BRAKE`, `ABS_TILT_*`, `ABS_HAT0Y`) carry signal and timing data and are ignored.

The values were measured on one panel with a hand held in front of it:

| Hand position | `ABS_HAT1X` |
| --- | --- |
| Touching | 17 to 19 |
| About 5 cm | 53 to 54 |
| About 20 cm | 190 to 191 |
| Nothing in front | 2382 to 3300, noisy |

They match millimetres, but the unit is inferred from those measurements rather than taken from a datasheet. Treat the numbers as approximate and tune the thresholds on your panel. The driver reports changes only, so a missing update means the value did not change.

## Requirements

- Kiosk Satellite **2026.10.12 or newer** (plugin SDK 1 with gesture triggers). Older releases cannot use the **Someone approaches** and **Someone leaves** triggers.
- Android 7.0 or newer on arm64-v8a, armeabi-v7a or x86_64.
- A `vi530x` input device that this app may open, or a rooted panel with a `su` that accepts `su -c` (Magisk does; the stock AOSP `su` does not).
- For Home Assistant, enable Kiosk Satellite's ESPHome connection and native entities and add that device to Home Assistant.

On most panels `/dev/input/event*` is root-only and SELinux also blocks apps, so direct access fails with errno 13. Turn on **Allow root fallback**, or see [Permissions](#permissions). The plugin does not root the panel, change permissions or modify SELinux policy.

## Installation

### Community Plugins (Recommended)

Rockchip Proximity Sensor is listed in [Community Plugins](https://kiosksatellite.com/plugins/) once it has been accepted. Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=iiliev-nemetschek/kiosk-satellite-rockchip-proximity)

Or search for `Rockchip Proximity Sensor` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/iiliev-nemetschek/kiosk-satellite-rockchip-proximity` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Both routes need a stable GitHub release built by this repository's GitHub Actions workflow. **Developer Tools > Install from ZIP** is for testing your own local builds.

## Use

1. Enable **Rockchip Proximity Sensor** on its entry row and open the subpage.
2. Check the status line. Run **Show current reading** while waving a hand in front of the panel.
3. Use the **Proximity distance** and **Proximity near** readings, the two triggers or the Home Assistant entities in your automations.

## Data

The plugin reads the sensor on the panel and sends nothing over the network by itself. The two entities reach Home Assistant only through Kiosk Satellite's own ESPHome connection, and only while **Expose to Home Assistant** is on. The optional root helper is a local process with a fixed command set and does not use the network.

## Settings

| Setting | Behavior |
| --- | --- |
| Near threshold | Near when the distance is at or below this value. Default 400 mm |
| Far threshold | Far when the distance is at or above this value. Between the two the previous state holds. Default 600 mm |
| Maximum reported distance | Larger readings are reported as this value, which hides the noisy out-of-range values. Default 1000 mm |
| Wake screen on approach | Runs Kiosk Satellite's `stopScreensaver` on a far to near change. Needs the `host.control` capability. Default off |
| Expose to Home Assistant | Publishes the two entities, which also appear as readings on the plugin page, only while the sensor is available |
| Minimum change to report | The distance sensor updates only after a move of at least this much. Default 20 mm |
| Minimum update interval | The distance sensor updates at most this often. Near/far changes always go out immediately. Default 1000 ms |
| Allow root fallback | Reads the sensor through a fixed helper started with `su -c` when direct access is denied |
| Simulation mode | Sweeps a fake distance between 1500 and 50 mm without touching hardware. Entity names say "simulation" |

Commands: **Check hardware access** and **Show current reading**.

## Gesture triggers

The plugin declares two triggers, **Someone approaches** and **Someone leaves**. Choose them under **Settings > Gestures > Plugin trigger** and pick what each one runs: a dashboard view, a Home Assistant script or any other gesture action. A trigger fires on each far/near change after the first reading. Kiosk Satellite drops more than four fires per second, and the hysteresis band keeps a steady hand from reaching that. **Wake screen on approach** is a shortcut for the most common case and needs no gesture setup.

## Home Assistant

Two entities appear under the Kiosk Satellite ESPHome device:

- **Proximity distance**: a `distance` sensor in mm with state class `measurement`.
- **Proximity near**: an `occupancy` binary sensor that is on while someone is near.

Both are unknown until the first reading arrives. Turning **Expose to Home Assistant** off, losing sensor access and disabling the plugin remove them, and Kiosk Satellite briefly reconnects ESPHome to advertise the change. Individual entities can also be excluded in Kiosk Satellite's ESPHome entity picker.

## Permissions

If the status line says errno 13, the panel denied the input device to the app. Choose one:

1. **Allow root fallback**. The helper opens the device as root and streams readings to the plugin. Grant Kiosk Satellite in your root manager.
2. **Install the Magisk module** in `contrib/magisk/module`. Build it with `python3 tools/build_magisk.py`, copy `dist/magisk/rockchip-proximity-permissions-1.0.0.zip` to the panel and choose **Magisk > Modules > Install from storage**, then reboot. At every boot it finds the `vi530x` node by name, makes it world-readable and sets SELinux permissive. This weakens device security, so use it only on a dedicated kiosk.

The root fallback needs a `su` that supports `su -c "command"`. If `su` answers `invalid uid/gid '-c'`, it is the AOSP one and the fallback cannot work. Install Magisk or use option 2.

## Build and test

Install Python 3, JDK 17 or newer, a C compiler and an Android SDK with a platform, build-tools and NDK. Linux and macOS are supported.

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/check-sdk.py /path/to/kiosk-satellite
```

`tools/check-sdk.py` compares the vendored SDK in `sdk/` with a checkout of the Kiosk Satellite application. `dist/` receives the ZIP, its SHA-256 file, the release manifest and a compile-time SDK JAR. The ZIP holds the manifest, DEX JAR, license, icon and the native library for all three ABIs. The manifest's `version` is the local development version. The release workflow passes the release tag to `tools/build.py --version`, so the published package takes its version from the tag.

GitHub Actions runs the tests and the build when a release is published and attaches the assets to it. See [maintainer handoff](docs/maintainer-handoff.md#release).

The tests cover the near/far hysteresis, clamping and report throttling, a simulated sweep with entity publishing and wake gating, the root helper protocol using a stand-in process, plugin lifecycle cleanup and the native code with faked system calls (device lookup by name, non-blocking reads, error propagation, descriptor cleanup). They do not prove compatibility with a physical panel. See [hardware verification](docs/hardware-verification.md).

## Limitations

- Only the `vi530x` input device is supported. Another proximity chip needs a different device name and event code.
- The panel's own vendor service may use the same sensor. The plugin only reads and never configures the driver, so it cannot enable a sensor that the driver leaves idle.
- The distance unit is inferred from measurements on one panel.

Author: iiliev-nemetschek. Apache-2.0. The plugin ID is `rockchip-proximity-sensor` and should stay stable when ownership changes.
