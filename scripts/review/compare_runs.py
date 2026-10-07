#!/usr/bin/env python3
"""Label stability between harness runs (e.g. node budgets, SPEC E8): agreement of each run with a reference run.

Usage: scripts/review/compare_runs.py reference/plies.csv run1/plies.csv [run2/plies.csv ...]
Only plies present in both files are compared.
"""
import csv
import sys

BUCKET = {"BRILLIANT": 0, "GREAT": 0, "BEST": 0, "EXCELLENT": 0, "FORCED": 0, "GOOD": 1, "BOOK": 2,
          "INACCURACY": 3, "MISTAKE": 4, "MISS": 4, "BLUNDER": 5}
ERRORS = {"INACCURACY", "MISTAKE", "MISS", "BLUNDER"}


def load(path):
    with open(path, newline="", encoding="utf-8") as f:
        return {(r["game"], r["ply"]): r["ours"] for r in csv.DictReader(f)}


def main():
    ref = load(sys.argv[1])
    print(f"reference {sys.argv[1]}: {len(ref)} plies")
    for path in sys.argv[2:]:
        run = load(path)
        keys = [k for k in run if k in ref]
        same = sum(run[k] == ref[k] for k in keys)
        bucket = sum(BUCKET[run[k]] == BUCKET[ref[k]] for k in keys)
        flips = sum((run[k] in ERRORS) != (ref[k] in ERRORS) for k in keys)
        severe = sum(abs(BUCKET[run[k]] - BUCKET[ref[k]]) >= 2 for k in keys)
        n = max(1, len(keys))
        print(f"{path}: {len(keys)} plies, same label {100 * same / n:.1f}%, same bucket {100 * bucket / n:.1f}%, "
              f"error flips {100 * flips / n:.1f}%, off by 2+ buckets {100 * severe / n:.1f}%")


if __name__ == "__main__":
    main()
