#!/usr/bin/env python3
"""Imports the chess.com ground-truth games (one JSON per game, see the review team's data/INDEX.md)
into the compact test fixture src/test/resources/review/chesscom-games.jsonl.

Usernames, PGN headers, uuids and clocks are dropped: the fixture keeps only what the review harness
needs (moves, ratings, time class, accuracies, per-move labels when available).

Usage: scripts/review/import_chesscom_fixtures.py <games-dir> [output.jsonl] [--labels <labels_chesscom-dir>]

With --labels, the complete per-move chess.com labels of <labels-dir>/<id>.json ({labels: [{ply, san, label}]})
replace the partial ones of the game file.
"""
import glob
import json
import os
import re
import sys

KEEP = ["id", "url", "time_class", "time_control", "rated", "white_rating", "black_rating",
        "result", "result_white", "result_black", "eco_url", "final_fen", "accuracy", "labels", "labels_complete",
        "tags"]


def san_from_pgn(pgn):
    body = pgn.split("\n\n", 1)[1] if "\n\n" in pgn else pgn
    body = re.sub(r"\{[^}]*\}", " ", body)        # comments ([%clk ...])
    body = re.sub(r"\([^)]*\)", " ", body)        # variations
    body = re.sub(r"\$\d+", " ", body)            # NAGs
    out = []
    for tok in body.split():
        if re.fullmatch(r"\d+\.+", tok) or tok in ("1-0", "0-1", "1/2-1/2", "*"):
            continue
        tok = re.sub(r"^\d+\.+", "", tok)
        if tok:
            out.append(tok)
    return out


def result_of(g):
    if g.get("result"):
        return g["result"]
    if g.get("result_white") == "win":
        return "1-0"
    if g.get("result_black") == "win":
        return "0-1"
    return "1/2-1/2"


def main():
    args = sys.argv[1:]
    labels_dir = None
    if "--labels" in args:
        i = args.index("--labels")
        labels_dir = args[i + 1]
        del args[i:i + 2]
    src = args[0]
    dst = args[1] if len(args) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "..", "src", "test", "resources", "review", "chesscom-games.jsonl")
    rows = []
    for path in sorted(glob.glob(os.path.join(src, "*.json"))):
        with open(path, encoding="utf-8") as f:
            g = json.load(f)
        if labels_dir:
            lp = os.path.join(labels_dir, os.path.basename(path))
            if os.path.exists(lp):
                with open(lp, encoding="utf-8") as f:
                    full = json.load(f)
                g["labels"] = [{"ply": l["ply"], "san": l["san"], "label": l["label"]} for l in full["labels"]]
                g["labels_complete"] = True
        acc = g.get("accuracy") or {}
        if (acc.get("white") is None or acc.get("black") is None) and not g.get("labels"):
            continue  # neither accuracies nor labels: useless as ground truth
        row = {k: g.get(k) for k in KEEP if g.get(k) is not None}
        row["result"] = result_of(g)
        row["moves_san"] = g.get("moves_san") or san_from_pgn(g.get("pgn", ""))
        if g.get("moves_uci"):
            row["moves_uci"] = g["moves_uci"]
        rows.append(row)
    with open(dst, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"{len(rows)} games -> {os.path.normpath(dst)}")


if __name__ == "__main__":
    main()
