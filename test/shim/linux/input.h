// SPDX-License-Identifier: Apache-2.0
// Host-side stand-in for the Android NDK's <linux/input.h>, used only by
// native_test.c so the native code can be compiled and exercised on macOS.
#ifndef SHIM_LINUX_INPUT_H
#define SHIM_LINUX_INPUT_H
#include <stdint.h>
#include <sys/time.h>

struct input_event {
    struct timeval time;
    uint16_t type;
    uint16_t code;
    int32_t value;
};

#define EV_SYN 0x00
#define EV_ABS 0x03
#define ABS_HAT1X 0x12
#define ABS_HAT1Y 0x13
#define EVIOCGNAME(len) (0x80004506UL | ((unsigned long)(len) << 16))
#endif
