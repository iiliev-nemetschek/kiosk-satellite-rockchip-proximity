# Maintainer handoff

The plugin is self-contained. Device-specific source, JNI code, root helper, tests and build tooling live in this repository. Kiosk Satellite contains only the reusable SDK support and generic entity integration.

## Ownership

Keep `rockchip-proximity-sensor` as the plugin ID. Change the manifest author to the maintainer's preferred attribution and update repository links when ownership changes. A GitHub repository transfer preserves history and redirects, but Kiosk Satellite records the repository URL used for installation, so users moving to a different URL should uninstall and reinstall. Uninstalling deletes saved settings.

## Release

1. Validate the hardware checklist in `hardware-verification.md`.
2. Document the change in `CHANGELOG.md`. The `version` in `kiosk-satellite-plugin.json` is only the local development version, because the release tag supplies the published one.
3. Raise `minAppVersion` if the release starts using something newer than Kiosk Satellite 2026.10.12. It must name a published Kiosk Satellite release.
4. Run `python3 tools/test.py` and `python3 tools/build.py --android-platform 35`.
5. Commit the source, manifest and documentation and create a tag such as `v0.1.0`. Tags start with a letter or digit and contain only letters, digits, periods, underscores or hyphens.
6. Publish a stable (not draft, not prerelease) GitHub release for the tag. The Release workflow tests and builds the tagged source with `--version` taken from the tag and attaches `kiosk-satellite-plugin.json`, `rockchip-proximity-sensor-VERSION.zip` and its `.sha256`. Wait for it to succeed.
7. Test installation through the repository URL on a Kiosk Satellite build that supports SDK 1.

Kiosk Satellite reads the README from the release tag. Commits and pull requests do not trigger builds. To retry, run `gh workflow run build.yml --ref vVERSION` on the existing release tag. A rerun uses the source from that tag, so publish a new version after fixing build code. Kiosk Satellite rejects manually uploaded release assets, and so does the Community Plugins check.

## Community Plugins

To list the plugin on kiosksatellite.com and in Plugin Manager, follow [Submit your plugin](https://github.com/jxlarrea/kiosk-satellite-plugins#submit-your-plugin). Before submitting, clone [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) and run:

```sh
python3 tools/community_plugins.py check https://github.com/iiliev-nemetschek/kiosk-satellite-rockchip-proximity
```

The check needs a published release. The submission must come from the repository owner's GitHub account. Keep the plugin ID stable once listed.

## SDK maintenance

The vendored interfaces under `sdk/` are compile-time dependencies licensed under Apache-2.0, copied from [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins). They compile against the Android platform because the overlay classes use Android views. Compare them with the host interfaces using `python3 tools/check-sdk.py /path/to/kiosk-satellite` when updating SDK support, and copy `tools/build.py`, `tools/android_sdk.py`, `tools/plugin_manifest.py` and `tools/plugin_assets.py` from that repository together with workflow changes.

## Layout

- `native/proximity.c`: device lookup by name, non-blocking read, newest distance.
- `src/.../ProximitySource.java`: direct, simulation and root sources behind one interface.
- `src/.../RockchipProximityPlugin.java`: state, hysteresis, entities and wake on approach.
- `src/.../ProximityRootMain.java`: the fixed-protocol root helper.
- `test/shim/linux/input.h`: a stand-in for the NDK header so the native test builds on macOS. It shadows the real header only in tests.
- `contrib/magisk/module/`: an optional Magisk module that opens the sensor's input node at boot. `tools/build_magisk.py` zips it into `dist/magisk/`, which the release workflow does not upload.
