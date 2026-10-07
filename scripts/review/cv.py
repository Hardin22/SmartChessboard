#!/usr/bin/env python3
"""Cross-validated comparison of our move labels with chess.com Game Review, offline on the evaluation dump.

The classification runs in Java (ReviewCv: ReviewClassifier.classifyGame on the stored evaluations, no engine), once per
parameter set; this script computes the metrics of docs ACCEPTANCE.md (level scale below) and, with a grid, a real
nested cross-validation: for each fold, the parameter set that scores best on the OTHER folds labels the games of that
fold. Numbers are always on games the chosen parameters never saw.

  scripts/review/cv.py                                  # current defaults, per fold
  scripts/review/cv.py --grid grid.json                 # nested 5-fold CV over a grid of -Djavachess.review.* knobs
  scripts/review/cv.py -D good=0.06 -D mistake=0.18     # fixed knobs (same as a grid of one)
  scripts/review/cv.py --budget deep                    # labels from the deep dump (diagnostic)
  scripts/review/cv.py --mode second|mpv3               # second line everywhere / MultiPV 3 lines (diagnostic)
  scripts/review/cv.py stability lite lite-b            # our own noise: labels on two dumps of the same games
  scripts/review/cv.py ceiling [--budget deep]          # best exact any thresholds could reach with these evals
  scripts/review/cv.py noise                            # chess.com against itself (labels_chesscom_rerun)
  scripts/review/cv.py --holdout --final                # hold-out games (only for the final, frozen measure)

grid.json: {"good": [0.04, 0.05, 0.06], "mistake": [0.18, 0.20]} (cartesian product) or a list of {knob: value}.
Knob names are those of ReviewClassifier.tuning() (-Djavachess.review.<name>).
Output: target/cv/<name>/report.md (+ plies.tsv of the CV predictions); a one-screen summary on stdout.
"""
import argparse
import csv
import hashlib
import itertools
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = Path.home() / ".javachess-orchestrator" / "review-team" / "data"
LEVEL = {"brilliant": 0, "great": 1, "best": 2, "book": 2, "forced": 2, "excellent": 3, "good": 4,
         "inaccuracy": 5, "mistake": 6, "miss": 6, "blunder": 7}
ORDER = ["brilliant", "great", "best", "book", "forced", "excellent", "good", "inaccuracy", "mistake", "miss",
         "blunder"]
PI5_NPS = 350_000      # per core, assumed until measured on a real Pi 5 (stockfish bench)
PI5_PROCESSES = 3      # EngineManager.Budget.review() on a Pi 5 8 GB


def dist(a, b):
    return abs(LEVEL[a] - LEVEL[b])


# ------------------------------------------------------------------------------------------------ running Java

def classpath():
    cp_file = REPO / "target" / "cv-classpath.txt"
    pom = REPO / "pom.xml"
    if not cp_file.exists() or cp_file.stat().st_mtime < pom.stat().st_mtime:
        subprocess.run([str(REPO / "mvnw"), "-q", "dependency:build-classpath",
                        f"-Dmdep.outputFile={cp_file}"], cwd=REPO, check=True)
    return f"{REPO / 'target' / 'classes'}:{REPO / 'target' / 'test-classes'}:{cp_file.read_text().strip()}"


def run_java(cp, knobs, out, budget, mode, dataset, dump):
    cmd = ["java", "-Xss8m"] + [f"-Djavachess.review.{k}={v}" for k, v in sorted(knobs.items())]
    cmd += ["-cp", cp, "io.github.hardin22.javachess.review.ReviewCv", "--out", str(out), "--budget", budget,
            "--mode", mode, "--set", dataset, "--dump", str(dump)]
    r = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"ReviewCv failed for {knobs}:\n{r.stdout[-2000:]}\n{r.stderr[-4000:]}")
    return read_tsv(out / "plies.tsv"), read_tsv(out / "games.tsv")


def read_tsv(p):
    with open(p, newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def tag(knobs):
    if not knobs:
        return "defaults"
    s = ",".join(f"{k}={v}" for k, v in sorted(knobs.items()))
    return s if len(s) < 60 else hashlib.sha1(s.encode()).hexdigest()[:12]


# ------------------------------------------------------------------------------------------------ metrics

def metrics(plies, games):
    n = len(plies)
    exact = sum(p["ours"] == p["cc"] for p in plies)
    d = [dist(p["ours"], p["cc"]) for p in plies]
    within1 = sum(x <= 1 for x in d)
    far = [p for p, x in zip(plies, d) if x >= 2]
    errs = []
    for g in games:
        for c in ("white", "black"):
            if g[f"cc_{c}"] and g[f"ours_{c}"]:
                errs.append(abs(float(g[f"ours_{c}"]) - float(g[f"cc_{c}"])))
    mates = [p for p in plies if p["mate_check"].startswith("VIOLATION")]
    pi = [int(g["product_nodes"]) / (PI5_NPS * PI5_PROCESSES) * 80 / max(1, int(g["plies"])) for g in games]
    return {
        "plies": n, "games": len(games),
        "exact": exact / n if n else 0, "within1": within1 / n if n else 0,
        "far": far, "far_n": len(far),
        "mae": sum(errs) / len(errs) if errs else float("nan"),
        "within5": sum(e <= 5 for e in errs) / len(errs) if errs else float("nan"),
        "mates": mates,
        "pi5_s_per_40": sum(pi) / len(pi) if pi else float("nan"),
        "pi5_s_per_40_max": max(pi) if pi else float("nan"),
    }


def objective(m, penalty):
    return m["exact"] * m["plies"] - penalty * m["far_n"]


def confusion(plies):
    c = Counter((p["cc"], p["ours"]) for p in plies)
    present = [l for l in ORDER if any(k[0] == l or k[1] == l for k in c)]
    lines = ["| chess.com \\ ours | " + " | ".join(present) + " | recall |", "|---" * (len(present) + 2) + "|"]
    for t in present:
        row = [c[(t, o)] for o in present]
        tot = sum(row)
        cells = [f"**{v}**" if o == t and v else (f"_{v}_" if v and dist(t, o) >= 2 else str(v or ""))
                 for v, o in zip(row, present)]
        lines.append(f"| {t} | " + " | ".join(cells) + f" | {c[(t, t)] / tot:.0%} |" if tot else "")
    prec = []
    for o in present:
        col = sum(c[(t, o)] for t in present)
        prec.append(f"{c[(o, o)] / col:.0%}" if col else "")
    lines.append("| precision | " + " | ".join(prec) + " | |")
    return "\n".join(l for l in lines if l)


def level_confusion(plies):
    c = Counter((LEVEL[p["cc"]], LEVEL[p["ours"]]) for p in plies)
    lines = ["| cc level \\ ours | " + " | ".join(str(i) for i in range(8)) + " |", "|---" * 9 + "|"]
    for t in range(8):
        lines.append(f"| {t} | " + " | ".join(str(c[(t, o)] or "") for o in range(8)) + " |")
    return "\n".join(lines)


def far_table(far):
    rows = ["| game | ply | SAN | ours | chess.com | d | eval before | after (played) | EP before→after | loss | best | "
            "2nd line (EP) |", "|---|---:|---|---|---|---:|---|---|---|---:|---|---|"]
    for p in sorted(far, key=lambda p: (-dist(p["ours"], p["cc"]), p["game"], int(p["ply"]))):
        mv = (int(p["ply"]) + 1) // 2
        san = f"{mv}.{p['san']}" if p["color"] == "w" else f"{mv}...{p['san']}"
        second = f"{p['second']} {p['second_eval']} ({p['second_ep']})" if p["second"] else ""
        rows.append(f"| {p['game']} | {p['ply']} | {san} | {p['ours']} | {p['cc']} | {dist(p['ours'], p['cc'])} | "
                    f"{p['eval_before']} | {p['eval_played']} | {p['ep_before']}→{p['ep_after']} | {p['ep_loss']} | "
                    f"{p['best']}{' (top)' if p['is_top'] == 'true' else ''} | {second} |")
    return "\n".join(rows)


def summary_line(name, m):
    return (f"{name}: exact {m['exact']:.1%}, within 1 level {m['within1']:.1%}, >=2 levels {m['far_n']} "
            f"({m['far_n'] / max(1, m['plies']):.1%}), accuracy MAE {m['mae']:.2f} (within 5: {m['within5']:.0%}), "
            f"mate violations {len(m['mates'])}, Pi 5 {m['pi5_s_per_40']:.1f} s/40 moves (max {m['pi5_s_per_40_max']:.1f}); "
            f"{m['games']} games, {m['plies']} plies")


def report(path, title, m, plies, extra=""):
    by_fold = defaultdict(list)
    for p in plies:
        by_fold[p["fold"]].append(p)
    fold_rows = ["| fold | plies | exact | within 1 | >=2 |", "|---|---:|---:|---:|---:|"]
    for f in sorted(by_fold):
        ps = by_fold[f]
        ex = sum(p["ours"] == p["cc"] for p in ps) / len(ps)
        w1 = sum(dist(p["ours"], p["cc"]) <= 1 for p in ps) / len(ps)
        fold_rows.append(f"| {f} | {len(ps)} | {ex:.1%} | {w1:.1%} | "
                         f"{sum(dist(p['ours'], p['cc']) >= 2 for p in ps)} |")
    md = [f"# {title}", "", summary_line("result", m), "", extra, "## Per fold", "\n".join(fold_rows), "",
          "## Confusion (rows chess.com, columns ours; bold = exact, italic = >=2 levels)", confusion(plies), "",
          "## Confusion by level (0 brilliant .. 7 blunder)", level_confusion(plies), "",
          f"## Cases at >=2 levels ({m['far_n']})", far_table(m["far"]), "",
          f"## Mate violations ({len(m['mates'])})"]
    md += [f"- {p['game']} ply {p['ply']} {p['san']}: {p['mate_check']}" for p in m["mates"]]
    path.write_text("\n".join(md) + "\n")


# ------------------------------------------------------------------------------------------------ commands

def expand_grid(spec):
    if isinstance(spec, list):
        return [dict(x) for x in spec]
    keys = sorted(spec)
    return [dict(zip(keys, vals)) for vals in itertools.product(*(spec[k] for k in keys))]


def cmd_cv(a):
    if a.holdout and not a.final:
        sys.exit("--holdout is the final measure: add --final once the parameters are frozen (PHASE3.md §6)")
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    cp = classpath()
    fixed = dict(kv.split("=", 1) for kv in a.D)
    points = [dict(fixed, **g) for g in expand_grid(json.loads(Path(a.grid).read_text()))] if a.grid else [fixed]
    dataset = "holdout" if a.holdout else "cv"
    root = REPO / "target" / "cv"
    with ThreadPoolExecutor(max_workers=a.jobs) as ex:
        runs = list(ex.map(lambda k: (k, *run_java(cp, k, root / "runs" / f"{a.budget}-{a.mode}-{dataset}-{tag(k)}",
                                                     a.budget, a.mode, dataset, a.dump)), points))
    name = a.name or (f"{dataset}-{a.budget}-{a.mode}-" + ("grid" if a.grid else tag(fixed)))
    out = root / name
    out.mkdir(parents=True, exist_ok=True)
    extra = []
    if len(runs) == 1:
        knobs, plies, games = runs[0]
        kind = "hold-out (final)" if a.holdout else "fixed parameters, per fold (CV only if they were not tuned on these games)"
        extra.append(f"Parameters: `{knobs or 'defaults'}` — {kind}.\n")
    else:
        folds = sorted({p["fold"] for p in runs[0][1]})
        plies, games, chosen = [], [], []
        for f in folds:
            def train_score(r):
                tr_p = [p for p in r[1] if p["fold"] != f]
                tr_g = [g for g in r[2] if g["fold"] != f]
                return objective(metrics(tr_p, tr_g), a.penalty)
            best = max(runs, key=train_score)
            chosen.append((f, best[0], train_score(best)))
            plies += [p for p in best[1] if p["fold"] == f]
            games += [g for g in best[2] if g["fold"] == f]
        extra.append(f"Nested CV over {len(runs)} parameter sets (objective on the training folds: exact − "
                     f"{a.penalty} × cases ≥2 levels).\n")
        extra.append("| test fold | parameters chosen on the other folds |\n|---|---|")
        extra += [f"| {f} | `{k}` |" for f, k, _ in chosen]
        extra.append("\n### In-sample score of every parameter set (all folds; for orientation, NOT a CV number)\n")
        extra.append("| parameters | exact | within 1 | >=2 | MAE |\n|---|---:|---:|---:|---:|")
        for k, p, g in sorted(runs, key=lambda r: -objective(metrics(r[1], r[2]), a.penalty))[:25]:
            m = metrics(p, g)
            extra.append(f"| `{k}` | {m['exact']:.1%} | {m['within1']:.1%} | {m['far_n']} | {m['mae']:.2f} |")
        extra.append("")
    m = metrics(plies, games)
    title = f"{'Hold-out' if a.holdout else 'Cross-validation'}: budget {a.budget}, mode {a.mode}"
    report(out / "report.md", title, m, plies, "\n".join(extra))
    with open(out / "plies.tsv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(plies[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(plies)
    print(summary_line(name, m))
    print(f"report: {out / 'report.md'}")


def cmd_stability(a):
    """Label agreement between two dumps of the same games (same classifier): our own noise floor."""
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    cp = classpath()
    root = REPO / "target" / "cv" / "runs"
    pa, ga = run_java(cp, {}, root / f"stab-{a.a}", a.a, a.mode, "all", a.dump)
    pb, gb = run_java(cp, {}, root / f"stab-{a.b}", a.b, a.mode, "all", a.dump)
    kb = {(p["game"], p["ply"]): p for p in pb}
    pairs = [(p, kb[(p["game"], p["ply"])]) for p in pa if (p["game"], p["ply"]) in kb]
    n = len(pairs)
    same = sum(x["ours"] == y["ours"] for x, y in pairs)
    w1 = sum(dist(x["ours"], y["ours"]) <= 1 for x, y in pairs)
    far = [(x, y) for x, y in pairs if dist(x["ours"], y["ours"]) >= 2]
    ea = sum(x["ours"] == x["cc"] for x, _ in pairs) / n
    eb = sum(y["ours"] == y["cc"] for _, y in pairs) / n
    acc = {g["game"]: g for g in gb}
    dacc = [abs(float(g[f"ours_{c}"]) - float(acc[g["game"]][f"ours_{c}"])) for g in ga if g["game"] in acc
            for c in ("white", "black")]
    print(f"stability {a.a} vs {a.b}: {n} plies, same label {same / n:.1%}, within 1 level {w1 / n:.1%}, "
          f">=2 levels {len(far)}; exact vs chess.com {ea:.1%} / {eb:.1%}; accuracy |diff| mean "
          f"{sum(dacc) / len(dacc):.2f}, max {max(dacc):.2f}")
    flips = Counter((x["ours"], y["ours"]) for x, y in pairs if x["ours"] != y["ours"])
    print("most frequent changes:", ", ".join(f"{k[0]}→{k[1]} {v}" for k, v in flips.most_common(10)))
    for x, y in far[:40]:
        print(f"  {x['game']} ply {x['ply']} {x['san']}: {a.a} {x['ours']} ({x['eval_before']}→{x['eval_played']}) "
              f"vs {a.b} {y['ours']} ({y['eval_before']}→{y['eval_played']}), chess.com {x['cc']}")


def pawns(e, white):
    """Mover POV pawns of a formatted Eval ("+0.35", "M3", "-M2", "1-0"); None for mates."""
    if "M" in e or e in ("1-0", "0-1"):
        return None
    v = float(e)
    return v if white else -v


def oracle(xs, classes):
    """Best exact count of a monotone assignment of classes (in order) to the sorted values xs [(value, label)]."""
    n = len(xs)
    pref = {c: [0] * (n + 1) for c in classes}
    for i, (_, c) in enumerate(xs):
        for k in classes:
            pref[k][i + 1] = pref[k][i] + (c == k)
    prev = pref[classes[0]][:]
    for c in classes[1:]:
        pc = pref[c]
        cur, run = [0] * (n + 1), -10 ** 9
        for i in range(n + 1):
            run = max(run, prev[i] - pc[i])   # best split point k <= i
            cur[i] = run + pc[i]
        prev = cur
    return prev[n]


def cmd_ceiling(a):
    """In-sample oracle: best exact any monotone thresholds could reach on our loss, for non-top non-mate moves."""
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    plies, _ = run_java(classpath(), {}, REPO / "target" / "cv" / "runs" / f"ceiling-{a.budget}-{a.mode}", a.budget,
                        a.mode, "cv", a.dump)
    std = ["best", "excellent", "good", "inaccuracy", "mistake", "blunder"]
    sel = []
    for p in plies:
        if p["cc"] not in std or p["is_top"] == "true" or p["ours"] in ("book", "forced"):
            continue
        w = p["color"] == "w"
        b, f = pawns(p["eval_before"], w), pawns(p["eval_played"], w)
        if b is None or f is None:
            continue
        sel.append((p, float(p["ep_loss"]), max(0.0, b - f)))
    n = len(sel)
    cur = sum(p["ours"] == p["cc"] for p, _, _ in sel)
    print(f"ceiling {a.budget}/{a.mode}: {n} non-top non-mate moves labelled best..blunder by chess.com; "
          f"exact now {cur / n:.1%}")
    for name, k in (("win% loss", 1), ("cp loss", 2)):
        xs = sorted((x[k], x[0]["cc"]) for x in sel)
        print(f"  oracle monotone thresholds on {name}: {oracle(xs, std) / n:.1%}")
    for c in std:
        v = sorted(x[1] for x in sel if x[0]["cc"] == c)
        if v:
            print(f"  {c:<11} n={len(v):<4} win% loss p10/p50/p90 {v[len(v) // 10]:.3f} {v[len(v) // 2]:.3f} "
                  f"{v[9 * len(v) // 10]:.3f}")
    top = Counter(p["cc"] for p in plies if p["is_top"] == "true")
    print("  chess.com labels of our top moves:", dict(top.most_common()))


def cmd_noise(a):
    """chess.com against itself: labels_chesscom vs labels_chesscom_rerun (only reviews really recomputed)."""
    rerun = DATA / "labels_chesscom_rerun"
    n = same = w1 = 0
    far, flips, acc, games = [], Counter(), [], 0
    for f in sorted(rerun.glob("*.json")):
        b = json.loads(f.read_text())
        if not b.get("recomputed"):
            continue  # identical to the original: a cached review tells nothing about the noise
        o = json.loads((DATA / "labels_chesscom" / f.name).read_text())
        games += 1
        for x, y in zip(o["labels"], b["labels"]):
            n += 1
            same += x["label"] == y["label"]
            w1 += dist(x["label"], y["label"]) <= 1
            if x["label"] != y["label"]:
                flips[(x["label"], y["label"])] += 1
            if dist(x["label"], y["label"]) >= 2:
                far.append(f"{o['id']} ply {x['ply']} {x['san']}: {x['label']} -> {y['label']}")
        acc += [abs(o["accuracy_white"] - b["accuracy_white"]), abs(o["accuracy_black"] - b["accuracy_black"])]
    print(f"chess.com vs chess.com ({games} recomputed games, {n} plies): same {same / n:.1%}, within 1 level "
          f"{w1 / n:.1%}, >=2 levels {len(far)} ({len(far) / n:.1%}); accuracy |diff| mean {sum(acc) / len(acc):.2f}, "
          f"max {max(acc):.2f}")
    print("changes:", ", ".join(f"{k[0]}→{k[1]} {v}" for k, v in flips.most_common(12)))
    print("\n".join("  " + x for x in far))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="cv", choices=["cv", "stability", "ceiling", "noise"])
    ap.add_argument("a", nargs="?", help="stability: first budget")
    ap.add_argument("b", nargs="?", help="stability: second budget")
    ap.add_argument("--dump", default=str(DATA / "evals_labeled"))
    ap.add_argument("--budget", default="lite")
    ap.add_argument("--mode", default="product", choices=["product", "second", "mpv3"])
    ap.add_argument("--grid", help="JSON file: {knob: [values]} or [{knob: value}, ...]")
    ap.add_argument("-D", action="append", default=[], help="fixed knob, e.g. -D good=0.06")
    ap.add_argument("--penalty", type=float, default=5.0, help="objective: exact plies - penalty * cases >=2 levels")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) // 2))
    ap.add_argument("--name", help="output folder name under target/cv")
    ap.add_argument("--holdout", action="store_true")
    ap.add_argument("--final", action="store_true")
    ap.add_argument("--no-build", action="store_true", help="skip ./mvnw test-compile")
    a = ap.parse_args()
    {"stability": cmd_stability, "ceiling": cmd_ceiling, "noise": cmd_noise}.get(a.command, cmd_cv)(a)


if __name__ == "__main__":
    main()
