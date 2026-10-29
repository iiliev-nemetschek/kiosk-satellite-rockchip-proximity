// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.rockchipprox;

/** Pure decision logic, kept free of hardware and host state so it can be tested directly. */
final class ProximityMath {
    private ProximityMath() {}

    static int clamp(int mm, int max) { return Math.max(0, Math.min(mm, max)); }

    /** Near at or below {@code near}, far at or above {@code far}, otherwise unchanged. */
    static boolean near(boolean current, int mm, int near, int far) {
        if (mm <= near) return true;
        if (mm >= far) return false;
        return current;
    }

    static boolean significant(int last, int mm, int minChange) {
        return last < 0 || Math.abs(mm - last) >= minChange;
    }
}
