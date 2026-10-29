// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.PluginHost;

public final class ProximityTest {
    private static final class Host implements PluginHost {
        volatile String status = "";
        volatile boolean statusError;
        volatile Double distance;
        volatile Boolean near;
        volatile boolean sensorsPublished;
        volatile String distanceName = "";
        final List<Boolean> nearHistory = new CopyOnWriteArrayList<>();
        final List<Double> distances = new CopyOnWriteArrayList<>();
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger wakes = new AtomicInteger();
        public void showWindow(String a, String b, String c) { throw new AssertionError("No overlay required"); }
        public void hideWindow() {}
        public void log(String value) {}
        public void status(String value, boolean error) { status = value; statusError = error; changes.incrementAndGet(); }
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            assert command.equals("stopScreensaver") : command;
            wakes.incrementAndGet();
            callback.onResult(true, null, null);
        }
        public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) {
            assert key.equals("distance");
            assert "mm".equals(metadata.get("unit")) && "distance".equals(metadata.get("deviceClass"));
            assert "measurement".equals(metadata.get("stateClass"));
            distanceName = name; sensorsPublished = true; distance = state;
            if (state != null) distances.add(state);
            changes.incrementAndGet();
        }
        public void removeSensor(String key) { assert key.equals("distance"); sensorsPublished = false; distance = null; changes.incrementAndGet(); }
        public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) {
            assert key.equals("near");
            assert "occupancy".equals(deviceClass);
            near = state;
            if (state != null && (nearHistory.isEmpty() || nearHistory.get(nearHistory.size() - 1) != state)) nearHistory.add(state);
            changes.incrementAndGet();
        }
        public void removeBinarySensor(String key) { assert key.equals("near"); near = null; changes.incrementAndGet(); }
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> s = new HashMap<>();
        s.put("nearThreshold", 400); s.put("farThreshold", 600); s.put("maxRange", 1000);
        s.put("wakeOnApproach", false); s.put("exposeSensors", true);
        s.put("minChange", 1); s.put("updateInterval", 100);
        s.put("allowRoot", false); s.put("simulation", true);
        return s;
    }

    private interface Condition { boolean done(); }
    private static void waitFor(Condition condition) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.done() && System.nanoTime() < end) Thread.sleep(10);
        if (!condition.done()) throw new AssertionError("Timed out");
    }

    public static void main(String[] args) throws Exception {
        // Hysteresis: near at or below the near threshold, far at or above the far threshold, sticky between.
        assert ProximityMath.near(false, 400, 400, 600);
        assert ProximityMath.near(false, 0, 400, 600);
        assert !ProximityMath.near(true, 600, 400, 600);
        assert ProximityMath.near(true, 500, 400, 600);
        assert !ProximityMath.near(false, 500, 400, 600);
        assert ProximityMath.clamp(3300, 1000) == 1000 && ProximityMath.clamp(-4, 1000) == 0 && ProximityMath.clamp(54, 1000) == 54;
        assert ProximityMath.significant(-1, 5, 20);
        assert !ProximityMath.significant(100, 119, 20) && ProximityMath.significant(100, 120, 20) && ProximityMath.significant(100, 80, 20);
        assert NativeProximity.describe(NativeProximity.ENODEV).contains("vi530x");
        assert NativeProximity.describe(-13).contains("denied access");
        assert ProximitySource.Root.quote("a'b").equals("'a'\\''b'");

        // The simulation sweeps through the near and far thresholds, publishes both entities and
        // wakes the screen on approach.
        ProximitySource.Simulation.periodMs = 800;
        Host host = new Host();
        RockchipProximityPlugin plugin = new RockchipProximityPlugin();
        Map<String, Object> settings = defaults();
        settings.put("wakeOnApproach", true);
        plugin.start(host, settings);
        waitFor(() -> host.sensorsPublished && host.distance != null && host.near != null);
        assert host.status.startsWith("Simulation");
        assert host.distanceName.contains("simulation");
        waitFor(() -> host.nearHistory.contains(true) && host.nearHistory.contains(false) && host.wakes.get() >= 1);
        for (Double value : host.distances) assert value >= 0 && value <= 1000 : "distance outside 0..maxRange: " + value;
        plugin.execute("reading", Collections.emptyMap());
        waitFor(() -> host.status.startsWith("Distance "));

        // Wake stays off unless asked for.
        int wakesBefore = host.wakes.get();
        settings = defaults();
        plugin.configure(settings);
        Thread.sleep(2000);
        assert host.wakes.get() == wakesBefore : "wakeOnApproach off must not wake the screen";

        // Turning the exposure off removes the entities and keeps the source running.
        settings.put("exposeSensors", false);
        plugin.configure(settings);
        waitFor(() -> !host.sensorsPublished && host.near == null);
        plugin.stop();
        int count = host.changes.get();
        Thread.sleep(300);
        assert host.changes.get() == count : "no callbacks after stop";

        // A host that cannot hand over a native library path defers to detect() instead of blocking enable.
        Host bare = new Host();
        RockchipProximityPlugin deferred = new RockchipProximityPlugin();
        Map<String, Object> real = defaults();
        real.put("simulation", false);
        deferred.start(bare, real);
        waitFor(() -> !bare.status.isEmpty());
        assert bare.statusError;
        deferred.stop();
        System.out.println("PASS: a host without a native library path defers to detect() instead of blocking enable (" + bare.status + ")");

        // Root fallback defers the probe, so it never touches the native library during enable.
        Host rooted = new Host();
        RockchipProximityPlugin viaRoot = new RockchipProximityPlugin();
        Map<String, Object> rootSettings = defaults();
        rootSettings.put("simulation", false);
        rootSettings.put("allowRoot", true);
        viaRoot.start(rooted, rootSettings);
        viaRoot.stop();
        System.out.println("PASS: root fallback defers the probe to detect() rather than refusing the enable");

        // The root helper protocol, driven by a stand-in process instead of su.
        Process fake = new ProcessBuilder("sh", "-c", "printf 'OPEN 0\\nnoise\\nDIST 190\\nDIST 54\\nERR -19\\n'; sleep 30").start();
        ProximitySource.Root root = new ProximitySource.Root(fake);
        assert root.probe() == 0;
        List<Integer> received = new CopyOnWriteArrayList<>();
        List<String> failures = new CopyOnWriteArrayList<>();
        root.start(new ProximitySource.Listener() {
            public void onDistance(int mm) { received.add(mm); }
            public void onFailure(String message) { failures.add(message); }
        });
        waitFor(() -> received.contains(54) && !failures.isEmpty());
        assert failures.get(0).contains("vi530x") : failures.get(0);
        root.close();
        assert fake.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) : "close() must stop the helper";
        Process denied = new ProcessBuilder("sh", "-c", "printf 'OPEN -13\\n'").start();
        ProximitySource.Root refused = new ProximitySource.Root(denied);
        assert refused.probe() == -13;
        refused.close();
        System.out.println("PASS: root helper protocol parsed, failures reported and the helper stopped on close");

        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assert !thread.isAlive() || !(thread.getName().equals("rockchip-proximity") || thread.getName().equals("rockchip-proximity-simulation") || thread.getName().equals("rockchip-proximity-reader"))
                : "leaked thread " + thread.getName();
        }
        System.out.println("PASS: hysteresis, clamping, simulated sweep, entity publishing, wake gating and lifecycle cleanup.");
    }
}
