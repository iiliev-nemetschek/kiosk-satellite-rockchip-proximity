#!/system/bin/sh
# Magisk module service script. Runs as root late in every boot.
# Makes the vi530x input node readable by apps for the Rockchip Proximity Sensor plugin.
# The plugin never changes permissions or SELinux policy itself. Review this before using it.

node=
i=0
while [ -z "$node" ] && [ $i -lt 60 ]; do
  for dir in /sys/class/input/input*; do
    [ "$(cat "$dir/name" 2>/dev/null)" = "vi530x" ] || continue
    for ev in "$dir"/event*; do
      [ -e "$ev" ] && node="/dev/input/$(basename "$ev")"
    done
  done
  [ -n "$node" ] && break
  sleep 1
  i=$((i + 1))
done

# The event number changes between firmware builds, so the node is found by name.
[ -n "$node" ] && chmod 666 "$node"

# Apps are normally denied input devices by SELinux even when the file mode allows it.
# This trades security for access. Remove it if the plugin works without it.
setenforce 0
