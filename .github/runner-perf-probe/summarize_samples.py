#!/usr/bin/env python3
"""Reduce sampler.ps1 / sampler.sh output to one column per build phase.

Phases come from a file of "label epoch" lines written as each step starts. Cumulative counters are
reported as the change across the phase; gauges as min / max within it.
"""
import argparse
import collections
import csv
import os
import re

CUMULATIVE = ("disk_", "pswpin", "pswpout", "pgmajfault", "pages_in", "pages_out", "cpu_s:", "psi_")
METRIC = re.compile(r"^[a-z_]+(:[A-Za-z0-9_.:-]+)?$")


def value_at(series, when):
    # Interpolated, because a phase shorter than the sample interval would otherwise show no change at all.
    if when <= series[0][0]:
        return series[0][1]
    for (t0, v0), (t1, v1) in zip(series, series[1:]):
        if t0 <= when <= t1:
            return v0 if t1 == t0 else v0 + (v1 - v0) * (when - t0) / (t1 - t0)
    return series[-1][1]


def fmt_metric(metric, value):
    if "_bytes:" in metric:
        return f"{value / 2**20:,.0f}"
    if metric.startswith("psi_"):
        return f"{value / 1e6:,.1f}"
    return f"{value:,.0f}" if abs(value) >= 10 or value == int(value) else f"{value:,.1f}"


def label_metric(metric):
    if "_bytes:" in metric:
        return metric.replace("_bytes:", "_MiB:")
    if metric.startswith("psi_"):
        return metric.replace("_us", "_s")
    return metric


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", required=True)
    ap.add_argument("--phases", required=True)
    ap.add_argument("--title", default="Resource use by phase")
    args = ap.parse_args()

    series = collections.defaultdict(list)
    with open(args.samples, newline="", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) != 3 or not METRIC.match(row[1]):
                continue
            try:
                series[row[1]].append((int(row[0]), float(row[2])))
            except ValueError:
                continue
    for s in series.values():
        s.sort()
    if not series:
        raise SystemExit("no samples")
    last_t = max(s[-1][0] for s in series.values())

    with open(args.phases, encoding="utf-8") as f:
        marks = [(parts[0], int(parts[1])) for parts in (line.split() for line in f) if len(parts) == 2]
    phases = [(label, start, marks[i + 1][1] if i + 1 < len(marks) else last_t)
              for i, (label, start) in enumerate(marks)]

    header = "| metric | " + " | ".join(f"{label} ({end - start}s)" for label, start, end in phases) + " |"
    lines = [header, "| --- |" + " ---: |" * len(phases)]
    for metric in sorted(series):
        s = series[metric]
        cells = []
        for _, start, end in phases:
            if metric.startswith(CUMULATIVE):
                cells.append(fmt_metric(metric, value_at(s, end) - value_at(s, start)))
            else:
                inside = [v for t, v in s if start <= t <= end]
                cells.append(f"{fmt_metric(metric, min(inside))} / {fmt_metric(metric, max(inside))}" if inside else "")
        lines.append(f"| {label_metric(metric)} | " + " | ".join(cells) + " |")

    text = "\n".join(lines) + (
        "\n\nCumulative counters (disk_*, pages_*, pswp*, pgmajfault, cpu_s:*, psi_*) show the change across the "
        "phase, interpolated at its boundaries; everything else shows min / max of the samples in it.\n\n"
        "pages_in (Windows) counts pages read to resolve hard faults, memory-mapped JARs and DLLs included, so it "
        "is not swap-in: compare paging through pagefile_used_mb against swap_used_mb and pswpin/pswpout.\n")
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(f"### {args.title}\n\n{text}\n")


if __name__ == "__main__":
    main()
