// SPDX-License-Identifier: Apache-2.0
// Reads the vi530x time-of-flight sensor, which the panel kernel registers as
// an evdev input device. The distance in millimetres arrives as ABS_HAT1X.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/input.h>

#define DEVICE_NAME "vi530x"
#define DISTANCE_CODE ABS_HAT1X
#define MAX_EVENT_NODES 64
#define BATCH 32

static int open_node(const char *path) {
    int fd;
    do { fd = open(path, O_RDONLY | O_CLOEXEC | O_NOCTTY | O_NONBLOCK); }
    while (fd < 0 && errno == EINTR);
    return fd;
}

/* Returns a descriptor, -ENODEV when no node carries the sensor's name, or
 * -EACCES when some nodes could not be opened so the sensor may be among them. */
static int prox_open(void) {
    int denied = 0;
    for (int i = 0; i < MAX_EVENT_NODES; ++i) {
        char path[32];
        snprintf(path, sizeof path, "/dev/input/event%d", i);
        int fd = open_node(path);
        if (fd < 0) {
            if (errno == EACCES || errno == EPERM) denied = 1;
            continue;
        }
        char name[64];
        memset(name, 0, sizeof name);
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) >= 0 && strcmp(name, DEVICE_NAME) == 0) return fd;
        close(fd);
    }
    return denied ? -EACCES : -ENODEV;
}

/* Waits up to timeout_ms. Returns the newest distance (>= 0), -EAGAIN when no
 * distance arrived in the window, or another -errno if the device failed. */
static int prox_wait(int fd, int timeout_ms) {
    struct pollfd p;
    p.fd = fd;
    p.events = POLLIN;
    p.revents = 0;
    int ready;
    do { ready = poll(&p, 1, timeout_ms); } while (ready < 0 && errno == EINTR);
    if (ready < 0) return -errno;
    if (ready == 0) return -EAGAIN;
    if ((p.revents & (POLLERR | POLLHUP | POLLNVAL)) && !(p.revents & POLLIN)) return -ENODEV;
    struct input_event events[BATCH];
    ssize_t bytes;
    do { bytes = read(fd, events, sizeof events); } while (bytes < 0 && errno == EINTR);
    if (bytes < 0) return errno == EAGAIN ? -EAGAIN : -errno;
    if (bytes == 0) return -ENODEV;
    int result = -EAGAIN;
    size_t count = (size_t)bytes / sizeof events[0];
    for (size_t i = 0; i < count; ++i) {
        if (events[i].type == EV_ABS && events[i].code == DISTANCE_CODE) {
            result = events[i].value < 0 ? 0 : events[i].value;
        }
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_probe(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int fd = prox_open();
    if (fd < 0) return fd;
    close(fd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_openDevice(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return prox_open();
}

JNIEXPORT jint JNICALL
Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_waitDistance(JNIEnv *env, jclass cls, jint fd, jint timeoutMs) {
    (void)env; (void)cls;
    if (fd < 0 || timeoutMs < 0 || timeoutMs > 5000) return -EINVAL;
    return prox_wait(fd, timeoutMs);
}

JNIEXPORT void JNICALL
Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_closeDevice(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    if (fd >= 0) close(fd);
}
