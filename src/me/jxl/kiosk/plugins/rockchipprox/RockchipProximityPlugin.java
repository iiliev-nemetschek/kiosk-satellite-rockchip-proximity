// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** Owns sensor access, near/far state, Home Assistant entities and root helper lifetime. */
public final class RockchipProximityPlugin implements KioskPlugin {
    private static final String LIBRARY = "rockchip_proximity";
    private static final String DISTANCE_KEY = "distance";
    private static final String NEAR_KEY = "near";
    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ExecutorService worker;
    private volatile ProximitySource source;
    private Map<String, Object> settings = new HashMap<>();
    private boolean loaded;
    // Bumped on every detect() so readings and failures queued by a replaced source are ignored.
    private long generation;
    private Boolean near;
    private int distance = -1;
    private int lastPublished = -1;
    private long lastPublishNanos;

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        requireDevice(settings);
        alive.set(true);
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "rockchip-proximity"); t.setDaemon(true); return t; });
        configure(settings);
    }

    public void configure(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        submit(() -> {
            boolean recheck = source == null
                || !Objects.equals(settings.get("simulation"), copy.get("simulation"))
                || !Objects.equals(settings.get("allowRoot"), copy.get("allowRoot"));
            settings = copy;
            if (recheck) detect();
            else publishEntities();
        });
    }

    public void execute(String command, Map<String, Object> args) {
        submit(() -> {
            switch (command) {
                case "detect": detect(); break;
                case "reading":
                    if (source == null) status("No proximity sensor access. Check hardware access first.", true);
                    else if (distance < 0) status("No reading yet.", false);
                    else status("Distance " + distance + " mm, " + (Boolean.TRUE.equals(near) ? "near" : "far") + ".", false);
                    break;
                default: throw new IllegalArgumentException("Unknown proximity command");
            }
        });
    }

    public void onEvent(String event, Map<String, Object> payload) {}

    /**
     * Refuse to start on a panel this plugin cannot read, so Kiosk Satellite does not mark it
     * enabled. Simulation never opens the device and root fallback defers to detect(), because the
     * root helper may reach a node this process cannot and asking here would block the host's
     * enable call behind a root-manager prompt. A native library that will not load also defers.
     */
    private void requireDevice(Map<String, Object> settings) {
        if (Boolean.TRUE.equals(settings.get("simulation"))) return;
        if (Boolean.TRUE.equals(settings.get("allowRoot"))) return;
        try {
            if (!loaded) { NativeProximity.load(host.nativeLibraryPath(LIBRARY)); loaded = true; }
        } catch (Throwable unavailable) { return; }
        int result = NativeProximity.probe();
        if (result == 0) return;
        throw new IllegalStateException(
            (result == NativeProximity.ENODEV
                ? "This panel has no vi530x proximity input device, so there is no sensor for this plugin to read. "
                : NativeProximity.describe(result) + " ")
            + "A Rockchip chipset alone does not imply proximity sensor support. Turn on Root fallback below if this "
            + "panel is rooted, or Simulation mode to enable the plugin without hardware -- both stay "
            + "reachable while the plugin is off.");
    }

    private void detect() throws Exception {
        closeSource();
        generation++;
        near = null; distance = -1; lastPublished = -1;
        removeEntities();
        ProximitySource candidate;
        if (Boolean.TRUE.equals(settings.get("simulation"))) candidate = new ProximitySource.Simulation();
        else {
            if (!loaded) { NativeProximity.load(host.nativeLibraryPath(LIBRARY)); loaded = true; }
            ProximitySource direct = new ProximitySource.Direct();
            int result = direct.probe();
            if (result == 0) candidate = direct;
            else if (result == NativeProximity.ENODEV) {
                status("This panel has no accessible vi530x proximity input device. A Rockchip chipset alone does not imply proximity sensor support.", true);
                return;
            } else if (Boolean.TRUE.equals(settings.get("allowRoot"))) {
                status("Waiting for root access. Approve Kiosk Satellite in your root manager if prompted.", false);
                ProximitySource.Root root = new ProximitySource.Root(host.packagePath(), host.nativeLibraryPath(LIBRARY));
                try {
                    result = root.probe();
                    if (result != 0) throw new IllegalStateException(NativeProximity.describe(result));
                    candidate = root;
                } catch (Exception e) { root.close(); throw e; }
            } else {
                status(NativeProximity.describe(result) + " Enable root fallback only if this panel is rooted.", true);
                return;
            }
        }
        long mine = generation;
        try {
            candidate.start(new ProximitySource.Listener() {
                public void onDistance(int mm) { submit(() -> { if (mine == generation) handle(mm); }); }
                public void onFailure(String message) { submit(() -> { if (mine == generation) fail(message); }); }
            });
        } catch (Exception e) { candidate.close(); throw e; }
        source = candidate;
        publishEntities();
        status(candidate.label(), false);
    }

    private void handle(int mm) {
        if (source == null) return;
        int nearMm = number("nearThreshold", 400);
        int farMm = Math.max(number("farThreshold", 600), nearMm + 1);
        int value = ProximityMath.clamp(mm, number("maxRange", 1000));
        Boolean previous = near;
        boolean now = ProximityMath.near(Boolean.TRUE.equals(previous), value, nearMm, farMm);
        distance = value;
        near = now;
        boolean changed = previous == null || previous != now;
        long time = System.nanoTime();
        boolean due = lastPublished < 0
            || (ProximityMath.significant(lastPublished, value, number("minChange", 20))
                && TimeUnit.NANOSECONDS.toMillis(time - lastPublishNanos) >= number("updateInterval", 1000));
        if (changed || due) publishState(time);
        if (previous != null && !previous && now) wake();
    }

    private void wake() {
        if (!Boolean.TRUE.equals(settings.get("wakeOnApproach"))) return;
        try {
            host.executeCommand("stopScreensaver", Collections.<String, Object>emptyMap(),
                (ok, data, error) -> { if (!ok) host.log("Could not wake the screen: " + error); });
        } catch (Throwable e) { host.log("Could not wake the screen: " + e.getMessage()); }
    }

    private void publishEntities() {
        if (source == null || !Boolean.TRUE.equals(settings.get("exposeSensors"))) { removeEntities(); return; }
        publishState(System.nanoTime());
    }

    private void publishState(long time) {
        if (source == null || !Boolean.TRUE.equals(settings.get("exposeSensors"))) return;
        boolean simulated = Boolean.TRUE.equals(settings.get("simulation"));
        String suffix = simulated ? " (simulation)" : "";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("unit", "mm");
        metadata.put("deviceClass", "distance");
        metadata.put("stateClass", "measurement");
        metadata.put("accuracyDecimals", 0);
        host.publishSensor(DISTANCE_KEY, "Proximity distance" + suffix, metadata, distance < 0 ? null : Double.valueOf(distance));
        host.publishBinarySensor(NEAR_KEY, "Proximity near" + suffix, "occupancy", near);
        lastPublished = distance;
        lastPublishNanos = time;
    }

    private void removeEntities() {
        host.removeSensor(DISTANCE_KEY);
        host.removeBinarySensor(NEAR_KEY);
    }

    private void fail(String message) {
        closeSource();
        removeEntities();
        status(message, true);
    }

    private int number(String key, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private void status(String text, boolean error) { if (alive.get()) host.status(text, error); }

    private interface Task { void run() throws Exception; }

    private void safe(Task task) {
        if (!alive.get()) return;
        try { task.run(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Throwable e) {
            closeSource();
            if (alive.get()) {
                removeEntities();
                status(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
            }
        }
    }

    private void submit(Task task) {
        if (!alive.get()) return;
        try { worker.execute(() -> safe(task)); }
        catch (RejectedExecutionException stopping) { /* stop() is shutting the worker down. */ }
    }

    private void closeSource() {
        ProximitySource old = source;
        source = null;
        if (old != null) old.close();
    }

    public void stop() throws Exception {
        alive.set(false);
        worker.shutdownNow();
        if (!worker.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
            ProximitySource old = source;
            if (old instanceof ProximitySource.Root) old.close();
            throw new IllegalStateException("Proximity worker did not stop");
        }
        closeSource();
    }
}
