#!/usr/bin/env python3
"""Accuracy formula experiments (SPEC section 10, E1/E2/E4) on the per-move output of the review harness.

Input: plies.csv written by ChessComAgreementTest (columns game, ply, ours, win_before, win_after, cc_acc_white,
cc_acc_black, ...). Win chances are the mover's, 0..1, from the review core's WinModel.

  E1  lichess AccuracyPercent per move + volatility-weighted/harmonic mean aggregation
  E2  acc_i = 100 * exp(-a * loss_points), power mean with exponent p (p = 0: geometric), grid search
  E4  each of the above with and without BOOK/FORCED moves

Prints the MAE against chess.com for each variant and the best E2 parameters.
Usage: scripts/review/fit_accuracy.py target/review/plies.csv
"""
import csv
import math
import statistics
import sys
from collections import defaultdict


def load(path):
    games = defaultdict(list)
    truth = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if not r.get("win_before"):
                continue
            ply = int(r["ply"]) - 1
            games[r["game"]].append((ply, r["ours"], float(r["win_before"]), float(r["win_after"])))
            if r["cc_acc_white"] not in ("", "NaN", "nan"):
                truth[r["game"]] = (float(r["cc_acc_white"]), float(r["cc_acc_black"]))
    for g in games.values():
        g.sort()
    return {k: v for k, v in games.items() if k in truth}, truth


def lichess_move(wb, wa):
    loss = max(0.0, (wb - wa) * 100)
    raw = 103.1668100711649 * math.exp(-0.04354415386753951 * loss) - 3.166924740191411
    return min(100.0, max(0.0, raw + 1))  # +1 uncertainty bonus, as lila


def lichess_game(moves, white, skip):
    """moves: [(ply, label, wb, wa)]. Volatility weights from the White win% sequence (lila gameAccuracy)."""
    seq = [50.0]
    for ply, _, wb, wa in moves:
        mover_white = ply % 2 == 0
        seq.append(100 * (wa if mover_white else 1 - wa))
    window = min(max(len(seq) // 10, 2), 8)
    windows = [seq[:window]] * max(0, min(window, len(seq)) - 2) + [seq[i:i + window] for i in range(0, len(seq) - window + 1)]
    weights = [min(12.0, max(0.5, statistics.pstdev(w))) for w in windows]
    accs, ws = [], []
    for k, (ply, label, wb, wa) in enumerate(moves):
        if (ply % 2 == 0) != white or label in skip:
            continue
        accs.append(lichess_move(wb, wa))
        ws.append(weights[k] if k < len(weights) else 1.0)
    if not accs:
        return None
    weighted = sum(a * w for a, w in zip(accs, ws)) / sum(ws)
    harmonic = len(accs) / sum(1 / max(a, 0.01) for a in accs)
    return (weighted + harmonic) / 2


def power_game(moves, white, skip, a, p):
    accs = [100 * math.exp(-a * max(0.0, (wb - wa) * 100))
            for ply, label, wb, wa in moves if (ply % 2 == 0) == white and label not in skip]
    if not accs:
        return None
    accs = [max(x, 0.01) for x in accs]
    if abs(p) < 1e-9:
        return math.exp(sum(math.log(x) for x in accs) / len(accs))
    return (sum(x ** p for x in accs) / len(accs)) ** (1 / p)


def mae(games, truth, fn):
    errs = []
    for gid, moves in games.items():
        for white, t in ((True, truth[gid][0]), (False, truth[gid][1])):
            v = fn(moves, white)
            if v is not None:
                errs.append(v - t)
    return statistics.mean(abs(e) for e in errs), statistics.mean(errs), len(errs)


def main():
    games, truth = load(sys.argv[1])
    print(f"{len(games)} games with chess.com accuracies")
    skips = {"all moves": set(), "no BOOK/FORCED": {"BOOK", "FORCED"}}
    for name, skip in skips.items():
        m, b, n = mae(games, truth, lambda mv, w: lichess_game(mv, w, skip))
        print(f"E1 lichess, {name}: MAE {m:.2f} bias {b:+.2f} (n={n})")
    grid_a = [round(0.03 + 0.01 * i, 2) for i in range(16)]
    grid_p = [round(-1 + 0.25 * i, 2) for i in range(13)]

    def fit(subset, skip):
        best = None
        for a in grid_a:
            for p in grid_p:
                m, b, n = mae(subset, truth, lambda mv, w: power_game(mv, w, skip, a, p))
                if best is None or m < best[0]:
                    best = (m, b, a, p)
        return best

    for name, skip in skips.items():
        m, b, a, p = fit(games, skip)
        print(f"E2 power mean, {name}: best MAE {m:.2f} bias {b:+.2f} at a={a} p={p}")
        # 2-fold cross-validation (split by game id): fit on one half, measure on the other
        ids = sorted(games)
        halves = [{k: games[k] for k in ids[0::2]}, {k: games[k] for k in ids[1::2]}]
        for i in (0, 1):
            _, _, a2, p2 = fit(halves[i], skip)
            m2, b2, n2 = mae(halves[1 - i], truth, lambda mv, w: power_game(mv, w, skip, a2, p2))
            print(f"   CV fold {i}: fit a={a2} p={p2} -> held-out MAE {m2:.2f} bias {b2:+.2f} (n={n2})")


if __name__ == "__main__":
    main()
