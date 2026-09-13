#!/usr/bin/env python3
"""Count files, directories and bytes under each glob, and under its largest children by file count."""
import glob
import os
import sys


def measure(root):
    files = dirs = size = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirs += len(dirnames)
        files += len(filenames)
        for name in filenames:
            try:
                size += os.lstat(os.path.join(dirpath, name)).st_size
            except OSError:
                pass
    return files, dirs, size


for pattern in sys.argv[1:]:
    for root in sorted(glob.glob(pattern)):
        files, dirs, size = measure(root)
        print(f"{root}: {files:,} files, {dirs:,} dirs, {size / 2**30:.2f} GiB")
        children = [os.path.join(root, c) for c in os.listdir(root) if os.path.isdir(os.path.join(root, c))]
        for child, (cf, cd, cs) in sorted(((c, measure(c)) for c in children), key=lambda x: -x[1][0])[:8]:
            print(f"  {os.path.basename(child)}: {cf:,} files, {cd:,} dirs, {cs / 2**30:.2f} GiB")
