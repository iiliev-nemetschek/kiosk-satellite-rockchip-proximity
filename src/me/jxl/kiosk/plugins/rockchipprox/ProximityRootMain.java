// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed protocol for the optional root process. No shell commands from settings. */
public final class ProximityRootMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) return;
        NativeProximity.load(args[0]);
        int fd = NativeProximity.openDevice();
        System.out.println("OPEN " + (fd < 0 ? fd : 0));
        System.out.flush();
        if (fd < 0) return;
        AtomicBoolean stop = new AtomicBoolean();
        // Ends on "quit" or when the plugin process closes stdin.
        Thread control = new Thread(() -> {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = input.readLine()) != null) if ("quit".equals(line)) break;
            } catch (Exception ignored) { /* Treat a broken pipe like quit. */ }
            stop.set(true);
        }, "control");
        control.setDaemon(true);
        control.start();
        try {
            while (!stop.get()) {
                int result = NativeProximity.waitDistance(fd, 250);
                if (result >= 0) {
                    System.out.println("DIST " + result);
                    System.out.flush();
                } else if (result != NativeProximity.EAGAIN) {
                    System.out.println("ERR " + result);
                    System.out.flush();
                    break;
                }
            }
        } finally { NativeProximity.closeDevice(fd); }
    }
}
