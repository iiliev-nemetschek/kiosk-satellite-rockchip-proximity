// SPDX-License-Identifier: Apache-2.0
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/input.h>

#define NODES 8
static struct { const char *name; int err; } nodes[NODES];
static int node_count, opens, closes, open_calls;
static int open_state[NODES];
static int poll_result, poll_revents, poll_timeout_seen;
static struct input_event queued[32];
static int queued_count, read_errno, read_empty;

static int fake_open(const char *path, int flags, ...) {
    int index = -1;
    assert(sscanf(path, "/dev/input/event%d", &index) == 1);
    assert((flags & O_ACCMODE) == O_RDONLY);
    assert(flags & O_NONBLOCK);
    assert(flags & O_CLOEXEC);
    open_calls++;
    if (index >= node_count) { errno = ENOENT; return -1; }
    if (nodes[index].err) { errno = nodes[index].err; return -1; }
    opens++;
    open_state[index] = 1;
    return 100 + index;
}
static int fake_close(int fd) {
    assert(fd >= 100 && fd < 100 + NODES);
    assert(open_state[fd - 100]);
    open_state[fd - 100] = 0;
    closes++;
    return 0;
}
static int fake_ioctl(int fd, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    char *out = va_arg(args, char *);
    va_end(args);
    assert(request == EVIOCGNAME(63));
    strncpy(out, nodes[fd - 100].name, 63);
    return (int)strlen(out) + 1;
}
static int fake_poll(struct pollfd *p, nfds_t count, int timeout) {
    assert(count == 1 && p->events == POLLIN);
    poll_timeout_seen = timeout;
    p->revents = (short)poll_revents;
    return poll_result;
}
static ssize_t fake_read(int fd, void *buffer, size_t size) {
    assert(fd >= 100);
    if (read_errno) { errno = read_errno; return -1; }
    if (read_empty) return 0;
    assert(size >= sizeof queued[0] * (size_t)queued_count);
    memcpy(buffer, queued, sizeof queued[0] * (size_t)queued_count);
    return (ssize_t)(sizeof queued[0] * (size_t)queued_count);
}
#define open fake_open
#define close fake_close
#define ioctl fake_ioctl
#define poll fake_poll
#define read fake_read
#include "../native/proximity.c"
#undef open
#undef close
#undef ioctl
#undef poll
#undef read

static void reset(void) {
    memset(nodes, 0, sizeof nodes);
    memset(open_state, 0, sizeof open_state);
    node_count = opens = closes = open_calls = 0;
    poll_result = 1; poll_revents = POLLIN; poll_timeout_seen = -1;
    memset(queued, 0, sizeof queued);
    queued_count = read_errno = read_empty = 0;
}
static void event(int type, int code, int value) {
    queued[queued_count].type = (uint16_t)type;
    queued[queued_count].code = (uint16_t)code;
    queued[queued_count].value = value;
    queued_count++;
}

int main(void) {
    /* Finds the sensor by name, closes every other node it opened. */
    reset();
    nodes[0].name = "goodix-ts"; nodes[1].err = ENOENT; nodes[2].name = "gsensor"; nodes[3].name = "vi530x";
    node_count = 4;
    assert(prox_open() == 103);
    assert(open_state[3] == 1 && open_state[0] == 0 && open_state[2] == 0);
    assert(opens - closes == 1);
    fake_close(103);

    /* Probe opens and closes without reading. */
    reset();
    nodes[0].name = "vi530x"; node_count = 1;
    assert(Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_probe(0, 0) == 0);
    assert(opens == 1 && closes == 1);

    /* No node carries the name: the device is absent. */
    reset();
    nodes[0].name = "goodix-ts"; nodes[1].name = "gsensor"; node_count = 2;
    assert(prox_open() == -ENODEV);
    assert(opens == closes);
    assert(open_calls == 64);

    /* Every node denied: the sensor may exist but this process cannot tell. */
    reset();
    nodes[0].err = EACCES; nodes[1].err = EACCES; node_count = 2;
    assert(Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_probe(0, 0) == -EACCES);

    /* A denied node does not hide a readable sensor. */
    reset();
    nodes[0].err = EACCES; nodes[1].name = "vi530x"; node_count = 2;
    assert(prox_open() == 101);
    fake_close(101);

    /* Newest distance in a batch wins and other axes are ignored. */
    reset();
    event(EV_ABS, ABS_HAT1X, 190); event(EV_ABS, ABS_HAT1Y, 900);
    event(EV_ABS, ABS_HAT1X, 54); event(EV_SYN, 0, 0);
    assert(prox_wait(100, 250) == 54);
    assert(poll_timeout_seen == 250);

    /* Zero is a valid distance. Negative values are clamped. */
    reset();
    event(EV_ABS, ABS_HAT1X, 0);
    assert(prox_wait(100, 10) == 0);
    reset();
    event(EV_ABS, ABS_HAT1X, -5);
    assert(prox_wait(100, 10) == 0);

    /* Batches without a distance, timeouts and spurious wakeups are not failures. */
    reset();
    event(EV_ABS, ABS_HAT1Y, 900); event(EV_SYN, 0, 0);
    assert(prox_wait(100, 10) == -EAGAIN);
    reset();
    poll_result = 0;
    assert(prox_wait(100, 10) == -EAGAIN);
    reset();
    read_errno = EAGAIN;
    assert(prox_wait(100, 10) == -EAGAIN);

    /* A vanished device is reported as such. */
    reset();
    read_empty = 1;
    assert(prox_wait(100, 10) == -ENODEV);
    reset();
    read_errno = ENODEV;
    assert(prox_wait(100, 10) == -ENODEV);
    reset();
    poll_revents = POLLHUP;
    assert(prox_wait(100, 10) == -ENODEV);

    /* The JNI wrappers reject bad arguments before touching the descriptor. */
    reset();
    assert(Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_waitDistance(0, 0, -1, 10) == -EINVAL);
    assert(Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_waitDistance(0, 0, 100, -1) == -EINVAL);
    assert(Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_waitDistance(0, 0, 100, 60000) == -EINVAL);
    assert(poll_timeout_seen == -1);

    /* closeDevice ignores invalid descriptors. */
    Java_me_jxl_kiosk_plugins_rockchipprox_NativeProximity_closeDevice(0, 0, -1);
    assert(closes == 0);
    return 0;
}
