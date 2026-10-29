# Maintainer handoff

The plugin is self-contained. Device-specific source, JNI code, root helper, tests and build tooling live in this repository. Kiosk Satellite contains only the reusable SDK support and generic entity integration.

## Ownership

Keep `rockchip-proximity-sensor` as the plugin ID. Change the manifest author to the maintainer's preferred attribution and update repository links when ownership changes. A GitHub repository transfer preserves history and redirects, but Kiosk Satellite records the repository URL used for installation, so users moving to a different URL should uninstall and reinstall. Uninstalling deletes saved settings.

## Release

1. Validate the hardware checklist in `hardware-verification.md`.
2. Update `version` in `kiosk-satellite-plugin.json` and document the change in `CHANGELOG.md`.
3. Run `python3 tools/test.py` and `python3 tools/build.py --android-platform 35`.
4. Commit the source, manifest and documentation and create a tag that matches the version, with an optional `v` prefix.
5. Publish a stable GitHub release for the tag. The Release workflow tests and builds the tagged source and attaches `kiosk-satellite-plugin.json`, `rockchip-proximity-sensor-VERSION.zip` and its `.sha256`. Wait for it to succeed.
6. Test installation through the repository URL on a Kiosk Satellite build that supports SDK 1.

Kiosk Satellite reads the README from the release tag. Commits and pull requests do not trigger builds. To retry, run `gh workflow run build.yml --ref vVERSION` on the existing release tag. A rerun uses the source from that tag, so publish a new version after fixing build code. Kiosk Satellite rejects manually uploaded release assets.

## SDK maintenance

The vendored interfaces under `sdk/` are compile-time dependencies licensed under Apache-2.0, copied from the LED plugin repository. Compare them with the host interfaces when updating SDK support.

## Layout

- `native/proximity.c`: device lookup by name, non-blocking read, newest distance.
- `src/.../ProximitySource.java`: direct, simulation and root sources behind one interface.
- `src/.../RockchipProximityPlugin.java`: state, hysteresis, entities and wake on approach.
- `src/.../ProximityRootMain.java`: the fixed-protocol root helper.
- `test/shim/linux/input.h`: a stand-in for the NDK header so the native test builds on macOS. It shadows the real header only in tests.
- `contrib/magisk/module/`: an optional Magisk module that opens the sensor's input node at boot. `tools/build_magisk.py` zips it into `dist/magisk/`, which the release workflow does not upload.
