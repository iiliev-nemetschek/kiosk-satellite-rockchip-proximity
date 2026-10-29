# Hardware verification

The plugin is written from measurements on one panel and has been tested against a simulated host and faked system calls. It has not yet been run on the panel by this build. Use this checklist to verify it.

## Protocol

The sensor is an evdev device named `vi530x`. The plugin scans `/dev/input/event0` to `event63`, opens each node read-only and non-blocking and compares its `EVIOCGNAME` with `vi530x`. It polls the descriptor and keeps the newest `EV_ABS` `ABS_HAT1X` value from each batch as the distance in millimetres. Negative values become 0. It never writes to the device.

If every node that could not be opened was denied, the result is errno 13, because the sensor may be among them. If no node matches and none were denied, the device is absent.

## Verify a panel

1. Record the model, Android version, firmware build and the output of `cat /proc/bus/input/devices` (look for `Name="vi530x"`).
2. Install and enable the plugin with simulation off and root fallback off. A panel that denies the node refuses to enable and explains why. Turn on **Allow root fallback** or open the node as described in the README.
3. Run **Show current reading** with nothing in front of the panel. The value should equal the maximum reported distance.
4. Hold a hand at about 5 cm, 10 cm and 20 cm. The reading should fall with distance and cross the thresholds. Record the values.
5. Check **Proximity near** switches on at or below the near threshold and off at or above the far threshold, with no flicker in between.
6. Enable **Wake screen on approach**, let the screensaver start, approach the panel and confirm it dismisses.
7. In Home Assistant check both entities, their units and update rate. Raise **Minimum change to report** if the distance sensor is too busy.
8. If the status shows root helper errors, record the exact message. Confirm `su -c id` works on that panel.
9. Disable and re-enable the plugin. Confirm the entities disappear and return and no reader thread keeps running.
10. Update the plugin while enabled and confirm it resumes with its settings.

## Things to confirm that the tests cannot

- That `ABS_HAT1X` is millimetres on this panel and not only on the one measured.
- That the driver keeps emitting events while only this plugin has the device open, instead of depending on a vendor service to enable it.
- That the kernel does not report the same `vi530x` name for more than one input node.

## Report results

Include model and firmware, direct or root status, exact errors, the readings at each distance, the thresholds used and whether Home Assistant matched the physical behavior. Do not include authentication tokens or root-manager credentials.
