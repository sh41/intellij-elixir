#!/usr/bin/env python3
"""Create, stat, walk, read and delete many small files, shaped like a compiler's class output."""
import argparse
import json
import os
import random
import shutil
import statistics
import sys
import time

SIZES = (512, 1024, 2048, 3072, 4096, 8192, 16384)
PHASES = ("create", "stat", "walk", "read", "delete")


def walk(root):
    count = 0
    stack = [root]
    while stack:
        with os.scandir(stack.pop()) as entries:
            for entry in entries:
                entry.stat(follow_symlinks=False)
                if entry.is_dir(follow_symlinks=False):
                    stack.append(entry.path)
                else:
                    count += 1
    return count


def run_once(root, files, dirs, seed):
    rng = random.Random(seed)
    sizes = [rng.choice(SIZES) for _ in range(files)]
    blob = os.urandom(max(sizes))
    per_dir = -(-files // dirs)
    dir_paths = [os.path.join(root, f"p{d:04d}") for d in range(dirs)]
    paths = [os.path.join(dir_paths[i // per_dir], f"C{i:06d}.class") for i in range(files)]
    t = {}

    start = time.perf_counter()
    for d in dir_paths:
        os.makedirs(d, exist_ok=True)
    for p, s in zip(paths, sizes):
        with open(p, "wb") as f:
            f.write(blob[:s])
    t["create"] = time.perf_counter() - start

    start = time.perf_counter()
    for p in paths:
        os.stat(p)
    t["stat"] = time.perf_counter() - start

    start = time.perf_counter()
    seen = walk(root)
    t["walk"] = time.perf_counter() - start
    if seen != files:
        raise SystemExit(f"walk saw {seen} files, expected {files}")

    start = time.perf_counter()
    total = 0
    for p in paths:
        with open(p, "rb") as f:
            total += len(f.read())
    t["read"] = time.perf_counter() - start

    start = time.perf_counter()
    for p in paths:
        os.remove(p)
    for d in dir_paths:
        os.rmdir(d)
    t["delete"] = time.perf_counter() - start
    return t, total


def bench(args):
    root = os.path.join(args.parent, "smallfiles-bench")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    with open(args.out, "a", encoding="utf-8") as out:
        for n in range(1, args.runs + 1):
            t, total = run_once(root, args.files, args.dirs, args.seed)
            row = {"label": args.label, "path": root, "run": n, "files": args.files, "bytes": total,
                   **{k: round(v, 3) for k, v in t.items()}}
            out.write(json.dumps(row) + "\n")
            print(f"{args.label} run {n}: " + "  ".join(f"{k} {t[k]:.2f}s" for k in PHASES), flush=True)
    shutil.rmtree(root, ignore_errors=True)


def summarize(args):
    with open(args.out, encoding="utf-8") as f:
        rows = [json.loads(line) for line in f if line.strip()]
    labels = list(dict.fromkeys(r["label"] for r in rows))
    lines = ["| location | runs | files | " + " | ".join(f"{p} s (files/s)" for p in PHASES) + " | total s |",
             "| --- | ---: | ---: | " + " | ".join("---:" for _ in PHASES) + " | ---: |"]
    for label in labels:
        rs = [r for r in rows if r["label"] == label]
        med = {p: statistics.median(r[p] for r in rs) for p in PHASES}
        cells = " | ".join(f"{med[p]:.2f} ({rs[0]['files'] / med[p]:,.0f})" if med[p] else "0" for p in PHASES)
        lines.append(f"| {label} | {len(rs)} | {rs[0]['files']} | {cells} | {sum(med.values()):.2f} |")
    text = "\n".join(lines) + "\n\nMedian of runs; each phase touches every file once.\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as s:
            s.write(f"### {args.title}\n\n{text}\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--label")
    ap.add_argument("--parent")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--files", type=int, default=20000)
    ap.add_argument("--dirs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=4080)
    ap.add_argument("--summarize", action="store_true")
    ap.add_argument("--title", default="Small-file workload")
    args = ap.parse_args()
    if args.summarize:
        summarize(args)
    elif args.label and args.parent:
        bench(args)
    else:
        sys.exit("--label and --parent are required unless --summarize")


if __name__ == "__main__":
    main()
