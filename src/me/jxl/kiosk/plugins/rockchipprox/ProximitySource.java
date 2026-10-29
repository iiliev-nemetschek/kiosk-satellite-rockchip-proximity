// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

interface ProximitySource extends AutoCloseable {
    /** Callbacks arrive on source-owned threads and must return promptly. */
    interface Listener {
        void onDistance(int mm);
        void onFailure(String message);
    }

    int probe() throws Exception;
    void start(Listener listener) throws Exception;
    String label();
    void close();

    final class Direct implements ProximitySource {
        private volatile boolean closed;
        private Thread thread;
        public int probe() { return NativeProximity.probe(); }
        public void start(Listener listener) throws IOException {
            int fd = NativeProximity.openDevice();
            if (fd < 0) throw new IOException(NativeProximity.describe(fd));
            thread = new Thread(() -> {
                try {
                    while (!closed) {
                        int result = NativeProximity.waitDistance(fd, 250);
                        if (result >= 0) listener.onDistance(result);
                        else if (result != NativeProximity.EAGAIN) {
                            if (!closed) listener.onFailure(NativeProximity.describe(result));
                            break;
                        }
                    }
                } finally { NativeProximity.closeDevice(fd); }
            }, "rockchip-proximity-reader");
            thread.setDaemon(true);
            thread.start();
        }
        public String label() { return "Direct device access"; }
        public void close() {
            closed = true;
            Thread reader = thread;
            if (reader == null) return;
            try { reader.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    final class Simulation implements ProximitySource {
        static volatile int periodMs = 12000;
        private volatile boolean closed;
        private Thread thread;
        public int probe() { return 0; }
        public void start(Listener listener) {
            thread = new Thread(() -> {
                long begin = System.nanoTime();
                try {
                    while (!closed) {
                        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                        double phase = (elapsed % periodMs) / (double) periodMs;
                        double triangle = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
                        listener.onDistance((int) Math.round(1500 - triangle * 1450));
                        Thread.sleep(100);
                    }
                } catch (InterruptedException ignored) { /* close() interrupts the sleep. */ }
            }, "rockchip-proximity-simulation");
            thread.setDaemon(true);
            thread.start();
        }
        public String label() { return "Simulation mode. No hardware is being read."; }
        public void close() {
            closed = true;
            Thread worker = thread;
            if (worker == null) return;
            worker.interrupt();
            try { worker.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Runs {@link ProximityRootMain} through su. The helper prints "OPEN code" once, then
     * "DIST millimetres" for each reading, and "ERR code" if the device fails. It accepts only "quit".
     */
    final class Root implements ProximitySource {
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<Integer> opened = new LinkedBlockingQueue<>(1);
        private volatile Listener listener;
        private volatile int latest = -1;
        private volatile String pendingFailure;
        private volatile boolean closed;
        static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
        Root(String jar, String library) throws IOException {
            this(new ProcessBuilder("su", "-c", "CLASSPATH=" + quote(jar) + " /system/bin/app_process /system/bin me.jxl.kiosk.plugins.rockchipprox.ProximityRootMain " + quote(library))
                .redirectErrorStream(true).start());
        }
        Root(Process process) {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::read, "rockchip-proximity-root-output");
            reader.setDaemon(true);
            reader.start();
        }
        private void read() {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = output.readLine()) != null) {
                    if (line.length() >= 24) continue;
                    try {
                        if (line.startsWith("OPEN ")) opened.offer(Integer.parseInt(line.substring(5)));
                        else if (line.startsWith("DIST ")) {
                            int mm = Integer.parseInt(line.substring(5));
                            if (mm < 0) continue;
                            latest = mm;
                            Listener target = listener;
                            if (target != null) target.onDistance(mm);
                        } else if (line.startsWith("ERR ")) {
                            fail(NativeProximity.describe(Integer.parseInt(line.substring(4))));
                        }
                    } catch (NumberFormatException ignored) { /* Ignore unrelated root-manager output. */ }
                }
            } catch (IOException ignored) { /* Process shutdown closes the stream. */ }
            finally {
                opened.offer(-5);
                if (!closed) fail("The root helper stopped.");
            }
        }
        // Remembered when no listener exists yet, so start() can still report it.
        private void fail(String message) {
            if (pendingFailure == null) pendingFailure = message;
            Listener target = listener;
            if (target != null) target.onFailure(message);
        }
        public int probe() throws Exception {
            Integer reply = opened.poll(20_000, TimeUnit.MILLISECONDS);
            if (reply == null) { close(); throw new IOException("Root helper timed out. Check the root permission prompt and try again."); }
            return reply;
        }
        public void start(Listener target) {
            listener = target;
            int mm = latest;
            if (mm >= 0) target.onDistance(mm);
            String failure = pendingFailure;
            if (failure != null) target.onFailure(failure);
        }
        public String label() { return "Root helper access"; }
        public void close() {
            if (closed) return;
            closed = true;
            try { input.write("quit"); input.newLine(); input.flush(); input.close(); }
            catch (IOException ignored) { /* The helper may already have exited. */ }
            try { process.waitFor(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
