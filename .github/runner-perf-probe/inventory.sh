#!/usr/bin/env bash
# CPU, memory, swap and storage of a hosted Linux runner.
set -u
out_dir=$1
mkdir -p "$out_dir"
report="$out_dir/inventory-linux.txt"

section() {
  local title=$1
  shift
  echo "::group::$title"
  { echo "== $title"; "$@" 2>&1; echo; } | tee -a "$report"
  echo "::endgroup::"
}

section "nproc" nproc
section "lscpu" lscpu
section "free -m" free -m
section "swapon" swapon --show --bytes
section "meminfo" head -25 /proc/meminfo
section "lsblk" lsblk -o NAME,SIZE,ROTA,TYPE,TRAN,MODEL,FSTYPE,MOUNTPOINT
section "df" df -hT
section "mount of RUNNER_TEMP" findmnt -T "$RUNNER_TEMP"
section "mount of /tmp" findmnt -T /tmp
section "block queues" bash -c 'for q in /sys/block/*/queue; do d=${q%/queue}; echo "${d##*/} rotational=$(cat "$q/rotational") scheduler=$(cat "$q/scheduler")"; done'
section "vm sysctls" sysctl vm.swappiness vm.overcommit_memory vm.dirty_ratio vm.dirty_background_ratio
section "pressure stall information" bash -c 'for f in /proc/pressure/*; do echo "$f"; cat "$f"; done'
section "paths" bash -c 'echo "RUNNER_TEMP=$RUNNER_TEMP GITHUB_WORKSPACE=$GITHUB_WORKSPACE HOME=$HOME TMPDIR=${TMPDIR:-} ImageOS=${ImageOS:-} ImageVersion=${ImageVersion:-}"'

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Linux runner inventory"
    echo
    echo "- Image: ${ImageOS:-?} ${ImageVersion:-?}"
    echo "- CPU: $(lscpu | sed -n 's/^Model name: *//p'); nproc $(nproc); $(lscpu | sed -n 's/^Thread(s) per core: */threads per core /p')"
    echo "- RAM: $(awk '/^MemTotal:/ {printf "%.2f GiB", $2 / 1048576}' /proc/meminfo); swap $(awk '/^SwapTotal:/ {printf "%.2f GiB", $2 / 1048576}' /proc/meminfo)"
    echo "- Disks: $(lsblk -dno NAME,SIZE,ROTA,MODEL | tr -s ' ' | paste -sd ';' -)"
    echo "- RUNNER_TEMP on: $(findmnt -no SOURCE,FSTYPE -T "$RUNNER_TEMP")"
    echo
  } >> "$GITHUB_STEP_SUMMARY"
fi
