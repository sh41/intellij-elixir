#!/usr/bin/env bash
# Samples memory, swap, per-disk I/O, CPU, load and pressure stalls every few seconds, plus the resident
# memory of each JVM and BEAM kind. Writes long-format rows: epoch,metric,value.
# Cumulative counters are written raw; summarize_samples.py takes per-phase deltas.
# Creating <dir>/stop ends the loop after one final sample, so no row is cut off by a kill.
set -u
dir=$1
interval=${2:-5}
mkdir -p "$dir"
csv="$dir/samples.csv"
proc_log="$dir/processes.txt"
declare -A seen
prev_total=
prev_idle=

kind_of() {
  case "$1" in
    *GradleDaemon*) echo gradle-daemon ;;
    *KotlinCompileDaemon*) echo kotlin-daemon ;;
    *GradleWorkerMain*) echo gradle-worker ;;
    *GradleWrapperMain*) echo gradle-client ;;
    *beam.smp*|*epmd*) echo beam ;;
    *) case "${1%% *}" in *java) echo java-other ;; *) echo "" ;; esac ;;
  esac
}

xmx_of() {
  local m
  m=$(grep -o -- '-Xmx[^ ]*' <<<"$1" | head -1)
  if [ -z "$m" ]; then
    for f in $(grep -o '@[^ ]*' <<<"$1" | cut -c2-); do
      [ -r "$f" ] && m=$(grep -o -- '-Xmx[^ ]*' "$f" | head -1) && [ -n "$m" ] && m="$m (from argfile)" && break
    done
  fi
  echo "${m:-none}"
}

while true; do
  t=$(date +%s)
  {
    awk -v t="$t" '
      /^MemAvailable:/ { printf "%s,avail_mb,%d\n", t, $2 / 1024 }
      /^Committed_AS:/ { printf "%s,commit_mb,%d\n", t, $2 / 1024 }
      /^CommitLimit:/ { printf "%s,commit_limit_mb,%d\n", t, $2 / 1024 }
      /^SwapTotal:/ { st = $2 }
      /^SwapFree:/ { sf = $2 }
      END { printf "%s,swap_used_mb,%d\n", t, (st - sf) / 1024 }' /proc/meminfo
    awk -v t="$t" '$1 == "pswpin" || $1 == "pswpout" || $1 == "pgmajfault" { printf "%s,%s,%s\n", t, $1, $2 }' /proc/vmstat
    awk -v t="$t" '$3 ~ /^(sd[a-z]+|nvme[0-9]+n[0-9]+|vd[a-z]+)$/ {
      printf "%s,disk_read_bytes:%s,%.0f\n", t, $3, $6 * 512
      printf "%s,disk_write_bytes:%s,%.0f\n", t, $3, $10 * 512
      printf "%s,disk_reads:%s,%s\n", t, $3, $4
      printf "%s,disk_writes:%s,%s\n", t, $3, $8
    }' /proc/diskstats
    # USER_HZ is 100 on the runner kernels; steal is time the hypervisor gave this VM's vCPUs to others.
    awk -v t="$t" '$1 == "cpu" { printf "%s,cpu_s:iowait,%.2f\n%s,cpu_s:steal,%.2f\n", t, $6 / 100, t, $9 / 100 }' /proc/stat
    awk -v t="$t" '{ printf "%s,cpu_s:sampler,%.2f\n", t, ($14 + $15 + $16 + $17) / 100 }' "/proc/$$/stat"
    awk -v t="$t" '{ printf "%s,load1,%s\n", t, $1 }' /proc/loadavg
    awk -v t="$t" '$1 == "procs_running" { printf "%s,procs_running,%s\n", t, $2 }' /proc/stat
    for res in memory io cpu; do
      [ -r "/proc/pressure/$res" ] && awk -v t="$t" -v r="$res" '{ split($5, a, "="); printf "%s,psi_%s_%s_us,%s\n", t, r, $1, a[2] }' "/proc/pressure/$res"
    done
  } >> "$csv" 2>> "$dir/sampler-errors.txt"

  read -r _ user nice system idle iowait irq softirq steal _ < /proc/stat
  total=$((user + nice + system + idle + iowait + irq + softirq + steal))
  idle_all=$((idle + iowait))
  if [ -n "$prev_total" ] && [ "$total" -gt "$prev_total" ]; then
    echo "$t,cpu_pct,$(( 100 * ((total - prev_total) - (idle_all - prev_idle)) / (total - prev_total) ))" >> "$csv"
  fi
  prev_total=$total
  prev_idle=$idle_all

  declare -A rss=() cnt=()
  while read -r pid rss_kb args; do
    kind=$(kind_of "$args")
    [ -z "$kind" ] && continue
    rss[$kind]=$(( ${rss[$kind]:-0} + rss_kb ))
    cnt[$kind]=$(( ${cnt[$kind]:-0} + 1 ))
    if [ -z "${seen[$pid]:-}" ]; then
      seen[$pid]=1
      echo "$t pid=$pid kind=$kind xmx=$(xmx_of "$args") cmd=${args:0:600}" >> "$proc_log"
    fi
  done < <(ps -eww -o pid=,rss=,args=)
  for k in "${!rss[@]}"; do
    echo "$t,rss_mb:$k,$(( ${rss[$k]} / 1024 ))" >> "$csv"
    echo "$t,count:$k,${cnt[$k]}" >> "$csv"
  done
  unset rss cnt
  [ -e "$dir/stop" ] && break
  sleep "$interval"
done
