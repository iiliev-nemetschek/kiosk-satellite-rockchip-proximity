// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

public final class NativeProximity {
    public static final int EAGAIN = -11;
    public static final int ENODEV = -19;
    private NativeProximity() {}
    public static void load(String path) { System.load(path); }
    /** 0 when the sensor's input device can be opened, otherwise -errno. */
    public static native int probe();
    /** A descriptor for the sensor's input device, or -errno. */
    public static native int openDevice();
    /** Newest distance in millimetres, {@link #EAGAIN} when none arrived, or another -errno. */
    public static native int waitDistance(int fd, int timeoutMs);
    public static native void closeDevice(int fd);

    static String describe(int code) {
        if (code == ENODEV) return "No vi530x proximity input device was found.";
        return "Proximity sensor access failed (errno " + (-code) + "). "
            + (code == -13 || code == -1
                ? "The device permissions or SELinux policy denied access."
                : "Check the panel driver and its /dev/input node.");
    }
}
