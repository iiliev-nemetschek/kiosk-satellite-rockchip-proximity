# Changelog

## 0.1.0

- Initial release. Reads the `vi530x` time-of-flight sensor through its input device and reports the distance in millimetres.
- Near/far state with a hysteresis band, a maximum reported distance and report throttling.
- Home Assistant distance sensor and near/far binary sensor.
- Optional wake of the screen on approach.
- Optional root fallback through a fixed helper, and a simulation mode.
- Optional Magisk module that opens the sensor's input node at boot (`contrib/magisk/module`, built with `tools/build_magisk.py`).
- Not yet verified on physical hardware. See `docs/hardware-verification.md`.
