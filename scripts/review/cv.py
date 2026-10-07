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
  scripts/review/cv.py --ref sf22                       # compare with chess.com Stockfish 16 depth 22 (default torch18)
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
# reference labels (--ref): chess.com Game Review with Torch Human depth 18 (first export) or Stockfish 16 depth 22
REFS = {"torch18": "labels_chesscom", "sf22": "labels_chesscom_sf22"}
LABELS_DIR = DATA / REFS["torch18"]
FOLDS = None           # --folds: another fold file than <dump>/folds.json


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


def run_java(cp, knobs, out, budget, mode, dataset, dump, labels=None, games=None, folds=None, explain=False,
             recheck=None):
    cmd = ["java", "-Xss8m"] + [f"-Djavachess.review.{k}={v}" for k, v in sorted(knobs.items())]
    cmd += ["-cp", cp, "io.github.hardin22.javachess.review.ReviewCv", "--out", str(out), "--budget", budget,
            "--mode", mode, "--set", dataset, "--dump", str(dump), "--labels", str(labels or LABELS_DIR)]
    if folds or FOLDS:
        cmd += ["--folds", str(folds or FOLDS)]
    if games:
        cmd += ["--games", str(games)]
    if explain:
        cmd += ["--explain", "true"]
    if recheck:
        cmd += ["--recheck", recheck]
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


SPECIAL = ["brilliant", "great", "miss"]
# win chance loss thresholds of ReviewClassifier.Tuning (defaults; -D overrides apply) for the "near a threshold" test
# our top move called Good/Excellent by chess.com while our second line is this close: the engines' best moves differ
TOP_TIE_PAWNS = 0.30
# PHASE3 §20 targets: (precision, recall)
TARGET = {"brilliant": (0.90, 0.70), "great": (0.85, 0.55)}
THRESHOLDS = {"excellent": 0.02, "good": 0.05, "inaccuracy": 0.10, "mistake": 0.20, "blunderAnyway": 0.30}


def row_ref(p):
    mv = (int(p["ply"]) + 1) // 2
    san = f"{mv}.{p['san']}" if p["color"] == "w" else f"{mv}...{p['san']}"
    return f"{p['game']} ply {p['ply']} {san}"


def special_stats(plies):
    """Precision / recall / F1 of Brilliant, Great, Miss, with the false positives and negatives."""
    out = {}
    for c in SPECIAL:
        fp = [p for p in plies if p["ours"] == c and p["cc"] != c]
        fn = [p for p in plies if p["cc"] == c and p["ours"] != c]
        tp = sum(p["ours"] == c and p["cc"] == c for p in plies)
        pr = tp / (tp + len(fp)) if tp + len(fp) else float("nan")
        rc = tp / (tp + len(fn)) if tp + len(fn) else float("nan")
        f1 = 2 * pr * rc / (pr + rc) if tp else 0.0
        out[c] = {"tp": tp, "fp": fp, "fn": fn, "precision": pr, "recall": rc, "f1": f1}
    return out


def special_md(st):
    md = ["| class | chess.com | ours | TP | FP | FN | precision | recall | F1 |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for c, v in st.items():
        md.append(f"| {c} | {v['tp'] + len(v['fn'])} | {v['tp'] + len(v['fp'])} | {v['tp']} | {len(v['fp'])} | "
                  f"{len(v['fn'])} | {v['precision']:.0%} | {v['recall']:.0%} | {v['f1']:.2f} |")
    for c, v in st.items():
        for kind in ("fp", "fn"):
            if v[kind]:
                md.append(f"\n**{c} {'false positives (ours ' + c + ', chess.com other)' if kind == 'fp' else 'false negatives (chess.com ' + c + ', ours other)'}**\n")
                md += [f"- {row_ref(p)}: ours {p['ours']}, chess.com {p['cc']}; {p['eval_before']}→{p['eval_played']}, "
                       f"EP {p['ep_before']}→{p['ep_after']}, best {p['best']}{' (top)' if p['is_top'] == 'true' else ''}"
                       f"{', 2nd ' + p['second'] + ' ' + p['second_eval'] if p['second'] else ''}" for p in v[kind]]
    return "\n".join(md)


def rerun_labels():
    """chess.com labels of the reviews really recomputed (labels_chesscom_rerun, flag recomputed), by (game, ply)."""
    out = {}
    d = DATA / "labels_chesscom_rerun"
    for f in sorted(d.glob("*.json")) if d.exists() else []:
        b = json.loads(f.read_text())
        if b.get("recomputed"):
            for x in b["labels"]:
                out[(b["id"], str(x["ply"]))] = x["label"]
    return out


def classify_far(far, deep, knobs_of, margin, ref):
    """Splits the cases >=2 levels into unstable (chess.com changes its label in a recomputed rerun, our label changes
    from lite to deep, or the move is within `margin` win chance of a threshold) and rule cases (everything else).
    Returns [(ply, [reasons])]."""
    rr = rerun_labels() if ref == "torch18" else {}
    # the other chess.com engine (Torch18 vs SF22): a ply where they already differ by >= 2 levels is unstable
    other = {}
    other_dir = DATA / REFS["torch18" if ref == "sf22" else "sf22"]
    for gid in {p["game"] for p in far}:
        f = other_dir / f"{gid}.json"
        if f.exists():
            for x in json.loads(f.read_text())["labels"]:
                other[(gid, str(x["ply"]))] = x["label"]
    out = []
    for p in far:
        why = []
        k = (p["game"], p["ply"])
        if k in rr and rr[k] != p["cc"]:
            why.append(f"chess.com rerun {rr[k]}")
        o = other.get(k)
        if o is not None and dist(o, p["cc"]) >= 2:
            why.append(f"chess.com engines disagree ({'torch18' if ref == 'sf22' else 'sf22'} {o})")
        w = p["color"] == "w"
        b, s2 = pawns(p["eval_before"], w), pawns(p["second_eval"], w) if p["second_eval"] else None
        if p["is_top"] == "true" and p["cc"] in ("good", "excellent") and b is not None and s2 is not None \
                and b - s2 < TOP_TIE_PAWNS:
            why.append(f"top-move tie (2nd line {100 * (b - s2):.0f} cp behind)")
        d = deep.get(k)
        if d is not None and d["ours"] != p["ours"]:
            why.append(f"deep {d['ours']}")
        th = dict(THRESHOLDS)
        th.update({n: float(v) for n, v in knobs_of(p).items() if n in th})
        loss = float(p["ep_loss"] or 0)
        near = [n for n, t in th.items() if abs(loss - t) < margin]
        if near and p["is_top"] != "true":
            why.append(f"loss {loss:.3f} near {near[0]} {th[near[0]]}")
        out.append((p, why))
    return out


def far_split_md(split, margin, has_deep, ref):
    rule = [p for p, w in split if not w]
    unstable = [(p, w) for p, w in split if w]
    md = [f"Unstable = chess.com changes its label in a recomputed rerun{'' if ref == 'torch18' else ' (no rerun for this reference)'}, "
          f"or the two chess.com engines (Torch18, SF22) differ by >= 2 levels on the ply, or our top move is called "
          f"Good/Excellent with our second line < {100 * TOP_TIE_PAWNS:.0f} cp behind (top-move tie), "
          f"or our label changes lite→deep{'' if has_deep else ' (no deep dump)'}, or the win chance loss is within {margin} of a "
          f"threshold. **Rule cases: {len(rule)}**, unstable: {len(unstable)}.", "",
          f"### Rule cases ({len(rule)})", far_table(rule), "", f"### Unstable cases ({len(unstable)})"]
    md += [f"- {row_ref(p)}: ours {p['ours']}, chess.com {p['cc']} — {'; '.join(w)}" for p, w in unstable]
    return "\n".join(md)


def report(path, title, m, plies, extra="", far_split=""):
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
          "## Brilliant / Great / Miss", special_md(special_stats(plies)), "",
          f"## Cases at >=2 levels ({m['far_n']})", far_split or far_table(m["far"]), "",
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
        runs = list(ex.map(lambda k: (k, *run_java(cp, k, root / "runs" / f"{a.budget}-{a.mode}-{dataset}-{a.ref}-"
                                                     f"{a.recheck or 'norecheck'}-{tag(k)}", a.budget, a.mode, dataset,
                                                     a.dump, recheck=a.recheck)), points))
    name = a.name or (f"{dataset}-{a.budget}-{a.mode}-{a.ref}-" + ("grid" if a.grid else tag(fixed)))
    out = root / name
    out.mkdir(parents=True, exist_ok=True)
    extra = []
    if len(runs) == 1:
        knobs, plies, games = runs[0]
        knobs_by_fold = defaultdict(lambda: knobs)
        kind = "hold-out (final)" if a.holdout else "fixed parameters, per fold (CV only if they were not tuned on these games)"
        extra.append(f"Parameters: `{knobs or 'defaults'}` — {kind}.\n")
    else:
        folds = sorted({p["fold"] for p in runs[0][1]})
        plies, games, chosen = [], [], []
        knobs_by_fold = {}
        for f in folds:
            def train_score(r):
                tr_p = [p for p in r[1] if p["fold"] != f]
                tr_g = [g for g in r[2] if g["fold"] != f]
                return objective(metrics(tr_p, tr_g), a.penalty)
            best = max(runs, key=train_score)
            chosen.append((f, best[0], train_score(best)))
            knobs_by_fold[f] = best[0]
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
    # lite vs deep for the unstable/rule split: the deep dump labelled with the knobs each fold used
    deep = {}
    deep_dir = Path(a.dump) / ("holdout" if a.holdout else "") / a.deep
    if a.deep and a.deep != a.budget and deep_dir.is_dir():
        for kn in {json.dumps(k, sort_keys=True) for k in (knobs_by_fold[p["fold"]] for p in plies)}:
            k = json.loads(kn)
            dp, _ = run_java(cp, k, root / "runs" / f"{a.deep}-{a.mode}-{dataset}-{a.ref}-{tag(k)}", a.deep, a.mode,
                             dataset, a.dump)
            folds_k = {p["fold"] for p in plies if knobs_by_fold[p["fold"]] == k}
            deep.update({(p["game"], p["ply"]): p for p in dp if p["fold"] in folds_k})
    split = classify_far(m["far"], deep, lambda p: knobs_by_fold[p["fold"]], a.margin, a.ref)
    m["rule_n"] = sum(1 for _, w in split if not w)
    st = special_stats(plies)
    title = f"{'Hold-out' if a.holdout else 'Cross-validation'}: budget {a.budget}, mode {a.mode}, reference {a.ref}"
    report(out / "report.md", title, m, plies, "\n".join(extra), far_split_md(split, a.margin, bool(deep), a.ref))
    with open(out / "plies.tsv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(plies[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(plies)
    print(summary_line(name, m))
    print(f"  cases >=2: {m['rule_n']} rule, {m['far_n'] - m['rule_n']} unstable (margin {a.margin}"
          f"{', deep ' + a.deep if deep else ', no deep'}); "
          + ", ".join(f"{c} P {v['precision']:.0%} R {v['recall']:.0%} F1 {v['f1']:.2f}" for c, v in st.items()))
    print(f"report: {out / 'report.md'}")


def cmd_stability(a):
    """Label agreement between two dumps of the same games (same classifier): our own noise floor."""
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    cp = classpath()
    root = REPO / "target" / "cv" / "runs"
    pa, ga = run_java(cp, {}, root / f"stab-{a.a}", a.a, a.mode, "cv", a.dump)
    pb, gb = run_java(cp, {}, root / f"stab-{a.b}", a.b, a.mode, "cv", a.dump)
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


def cmd_refs(a):
    """chess.com Torch Human depth 18 against chess.com Stockfish 16 depth 22 on the same moves (CV games only)."""
    folds = json.loads(Path(FOLDS or Path(a.dump) / "folds.json").read_text())["folds"]
    n = same = w1 = 0
    flips, far, games = Counter(), [], 0
    special_t, special_s, special_both = Counter(), Counter(), Counter()
    for f in sorted((DATA / REFS["sf22"]).glob("*.json")):
        gid = f.stem
        t = DATA / REFS["torch18"] / f.name
        if gid not in folds or not t.exists():
            continue
        x, y = json.loads(t.read_text())["labels"], json.loads(f.read_text())["labels"]
        if len(x) != len(y):
            continue
        games += 1
        for u, v in zip(x, y):
            n += 1
            same += u["label"] == v["label"]
            w1 += dist(u["label"], v["label"]) <= 1
            special_t[u["label"]] += 1
            special_s[v["label"]] += 1
            special_both[u["label"]] += u["label"] == v["label"]
            if u["label"] != v["label"]:
                flips[(u["label"], v["label"])] += 1
            if dist(u["label"], v["label"]) >= 2:
                far.append(f"{gid} ply {u['ply']} {u['san']}: torch18 {u['label']} / sf22 {v['label']}")
    print(f"torch18 vs sf22 ({games} CV games, {n} plies): same {same / n:.1%}, within 1 level {w1 / n:.1%}, "
          f">=2 levels {len(far)} ({len(far) / n:.1%})")
    print("changes torch18→sf22:", ", ".join(f"{k[0]}→{k[1]} {v}" for k, v in flips.most_common(14)))
    for lab in SPECIAL:
        both = special_both[lab]
        print(f"  {lab}: torch18 {special_t[lab]}, sf22 {special_s[lab]}, both {both} (sf22 as truth: torch18 precision "
              f"{both / max(1, special_t[lab]):.0%}, recall {both / max(1, special_s[lab]):.0%})")
    print("\n".join("  " + x for x in far[:60]))


FP_COLUMNS = ["set", "kind", "id", "ply", "san", "class", "ours", "chesscom", "fen_before", "fen_after", "eval_before",
              "eval_played", "best", "best_pv", "second", "second_eval", "second_pv", "material_before",
              "material_after", "rule", "epB", "epA", "loss", "alt_eval", "alt_ep", "gap", "opp_loss", "sac_value",
              "sac_regain", "is_top", "in_check", "capture"]


def chessigma_labels(out):
    """Label files for the Chessigma benchmark: the chess.com-certified Brilliant ply; every other ply unknown
    (written as 'best', only the certified ply is scored)."""
    out.mkdir(parents=True, exist_ok=True)
    certified = {}
    for line in (REPO / "src/test/resources/review/chesscom-games.jsonl").read_text().splitlines():
        g = json.loads(line) if line.strip() else None
        if not g or "brilliant_benchmark" not in g.get("tags", []):
            continue
        plies = {x["ply"] for x in g.get("labels") or [] if str(x.get("label", "")).lower() == "brilliant"}
        if len(plies) != 1:
            continue
        ply = plies.pop()
        certified[g["id"]] = ply
        labels = [{"ply": i + 1, "san": s, "label": "brilliant" if i + 1 == ply else "best"}
                  for i, s in enumerate(g["moves_san"])]
        (out / f"{g['id']}.json").write_text(json.dumps({"id": g["id"], "labels": labels}))
    return certified


def cmd_fplist(a):
    """Every false positive / negative of Brilliant and Great (FP_LIST.csv, FN_LIST.csv) on the CV games (SF22 and
    Torch18), the famous games (SF16 depth 22) and the Chessigma benchmark (recall only), never the hold-out."""
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    cp = classpath()
    root = REPO / "target" / "cv" / "fp"
    nofolds = root / "nofolds.json"
    root.mkdir(parents=True, exist_ok=True)
    nofolds.write_text('{"folds": {}}')
    knobs = dict(kv.split("=", 1) for kv in a.D)
    famous_kind = {f.stem: json.loads(f.read_text()).get("kind", "") for f in (DATA / "famous_chesscom").glob("*.json")}
    certified = chessigma_labels(root / "chessigma-labels")
    sets = [
        ("cv-sf22", dict(budget=a.budget, dataset="cv", dump=a.dump, labels=DATA / REFS["sf22"])),
        ("cv-torch18", dict(budget=a.budget, dataset="cv", dump=a.dump, labels=DATA / REFS["torch18"])),
        ("famous", dict(budget=a.side_evals, dataset="all", dump=DATA / "evals_famous", labels=DATA / "famous_chesscom",
                        folds=nofolds, games=DATA / "famous")),
        ("chessigma", dict(budget=a.side_evals, dataset="all", dump=DATA / "evals_chessigma", labels=root / "chessigma-labels",
                           folds=nofolds)),
    ]
    fp, fn, summary = [], [], []
    for name, kw in sets:
        if not (Path(kw["dump"]) / kw["budget"]).is_dir():
            print(f"{name}: no dump, skipped")
            continue
        run_java(cp, knobs, root / name, kw["budget"], a.mode, kw["dataset"], kw["dump"], labels=kw["labels"],
                 games=kw.get("games"), folds=kw.get("folds"), explain=True)
        rows = read_tsv(root / name / "specials.tsv") if (root / name / "specials.tsv").stat().st_size else []
        plies = read_tsv(root / name / "plies.tsv")
        for r in rows:
            if name == "chessigma" and r["cc"] != "brilliant" and r["ours"] != "brilliant":
                continue
            kind = famous_kind.get(r["game"], "") if name == "famous" else ""
            for c in ("brilliant", "great"):
                if name == "chessigma" and c == "great":
                    continue
                base = {"set": name, "kind": kind, "id": r["game"], "ply": r["ply"], "san": r["san"], "class": c,
                        "ours": r["ours"], "chesscom": r["cc"] if name != "chessigma" or r["cc"] == "brilliant" else "unlabelled"}
                base.update({k: r.get(k, "") for k in FP_COLUMNS if k not in base})
                if r["ours"] == c and r["cc"] != c and name != "chessigma":
                    fp.append(base)
                if r["cc"] == c and r["ours"] != c:
                    fn.append(base)
        groups = {"chessigma": [("chessigma", plies)]} if name == "chessigma" else (
            {"famous": [(f"famous-{k}", [p for p in plies if famous_kind.get(p["game"]) == k]) for k in ("brilliant", "control")]}
            if name == "famous" else {name: [(name, plies)]})
        for label, ps in next(iter(groups.values())):
            for c in ("brilliant", "great"):
                if label == "chessigma" and c == "great":
                    continue
                if label == "chessigma":
                    tp = sum(1 for p in ps if p["cc"] == "brilliant" and p["ours"] == "brilliant")
                    pos = sum(1 for p in ps if p["cc"] == "brilliant")
                    other = sum(1 for p in ps if p["ours"] == "brilliant" and p["cc"] != "brilliant")
                    summary.append((label, c, pos, tp + other, tp, None, pos - tp, float("nan"), tp / pos if pos else float("nan"), other))
                    continue
                tp = sum(1 for p in ps if p["cc"] == c and p["ours"] == c)
                nfp = sum(1 for p in ps if p["ours"] == c and p["cc"] != c)
                nfn = sum(1 for p in ps if p["cc"] == c and p["ours"] != c)
                summary.append((label, c, tp + nfn, tp + nfp, tp, nfp, nfn, tp / (tp + nfp) if tp + nfp else float("nan"),
                                tp / (tp + nfn) if tp + nfn else float("nan"), None))
    outdir = Path(a.fp_out)
    outdir.mkdir(parents=True, exist_ok=True)
    for fname, rows in (("FP_LIST.csv", fp), ("FN_LIST.csv", fn)):
        with open(outdir / fname, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FP_COLUMNS)
            w.writeheader()
            w.writerows(rows)
    print("| set | class | chess.com | ours | TP | FP | FN | precision | recall |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    for label, c, pos, ours, tp, nfp, nfn, pr, rc, other in summary:
        fp_txt = f"({other} other plies, unlabelled)" if other is not None else str(nfp)
        print(f"| {label} | {c} | {pos} | {ours} | {tp} | {fp_txt} | {nfn} | {pr:.0%} | {rc:.0%} |")
    print(f"{len(fp)} false positives -> {outdir / 'FP_LIST.csv'}; {len(fn)} false negatives -> {outdir / 'FN_LIST.csv'}")


def pr(ps, c):
    tp = sum(1 for p in ps if p["cc"] == c and p["ours"] == c)
    fp = sum(1 for p in ps if p["ours"] == c and p["cc"] != c)
    fn = sum(1 for p in ps if p["cc"] == c and p["ours"] != c)
    return tp, fp, fn


def fmt_pr(t):
    tp, fp, fn = t
    p = f"{tp / (tp + fp):.2f}" if tp + fp else "-"
    r = f"{tp / (tp + fn):.2f}" if tp + fn else "-"
    return f"{tp}/{fp} P {p} R {r}"


def cmd_prcurve(a):
    """Precision/recall of Brilliant, Great (and Miss) for a list of classifier variants on the CV games (SF22), the
    famous games at the product's default rating 1500 and at 2500 (sensitivity), and Chessigma (recall)."""
    if not a.no_build:
        subprocess.run([str(REPO / "mvnw"), "-q", "test-compile"], cwd=REPO, check=True)
    cp = classpath()
    spec = json.loads(Path(a.variants).read_text())
    variants = spec if isinstance(spec, list) else [{"name": tag(k), "knobs": k} for k in expand_grid(spec)]
    root = REPO / "target" / "cv" / "prcurve"
    nofolds = root / "nofolds.json"
    root.mkdir(parents=True, exist_ok=True)
    nofolds.write_text('{"folds": {}}')
    chessigma_labels(root / "chessigma-labels")
    kind = {f.stem: json.loads(f.read_text()).get("kind", "") for f in (DATA / "famous_chesscom").glob("*.json")}
    famous_deep = (DATA / "evals_famous" / "deep").is_dir() and len(list((DATA / "evals_famous" / "deep").glob("*.jsonl"))) >= 35

    def one(v):
        k = {kk: str(vv) for kk, vv in v.get("knobs", {}).items()}
        rc = v.get("recheck")
        nm = v.get("name") or tag(k)
        out = {"name": nm}
        cvp, cvg = run_java(cp, k, root / nm / "cv", a.budget, a.mode, "cv", a.dump, labels=DATA / REFS["sf22"],
                            recheck=rc)
        m = metrics(cvp, cvg)
        out["cv"] = {c: pr(cvp, c) for c in SPECIAL}
        out["exact"], out["far"], out["pi5"] = m["exact"], m["far_n"], m["pi5_s_per_40"]
        out["pi5max"] = m["pi5_s_per_40_max"]
        # worst real game: its whole review time on the Pi 5, not normalised to 40 moves
        game_s = [(int(g["product_nodes"]) / (PI5_NPS * PI5_PROCESSES), g["game"], int(g["plies"])) for g in cvg]
        out["pi5game"] = max(game_s)
        for rating in (1500, 2500):
            kr = dict(k, defaultRating=str(rating))
            fr = rc if famous_deep else None
            fp_, _ = run_java(cp, kr, root / nm / f"famous{rating}", a.side_evals, a.mode, "all", DATA / "evals_famous",
                              labels=DATA / "famous_chesscom", games=DATA / "famous", folds=nofolds, recheck=fr)
            for kd in ("brilliant", "control"):
                ps = [p for p in fp_ if kind.get(p["game"]) == kd]
                out[f"f{rating}{kd}"] = {c: pr(ps, c) for c in ("brilliant", "great")}
            out[f"f{rating}recheck"] = bool(fr) or not rc
        if not (DATA / "evals_chessigma" / a.side_evals).is_dir():
            out["chessigma"] = f"no {a.side_evals} dump"
            return out
        cs, _ = run_java(cp, k, root / nm / "chessigma", a.side_evals, a.mode, "all", DATA / "evals_chessigma",
                         labels=root / "chessigma-labels", folds=nofolds)
        tp = sum(1 for p in cs if p["cc"] == "brilliant" and p["ours"] == "brilliant")
        pos = sum(1 for p in cs if p["cc"] == "brilliant")
        out["chessigma"] = f"{tp}/{pos} R {tp / pos:.2f}" if pos else "-"
        return out

    def ok(r, c):
        """✓ when the class meets its target on CV and on the famous games at 2500 (brilliant + control)."""
        def meets(t):
            tp, fp, fn = t
            return tp + fp > 0 and tp / (tp + fp) >= TARGET[c][0] and tp / max(1, tp + fn) >= TARGET[c][1]
        fam = tuple(x + y for x, y in zip(r["f2500brilliant"][c], r["f2500control"][c]))
        return "✓" if meets(r["cv"][c]) and meets(fam) else "✗"

    with ThreadPoolExecutor(max_workers=a.jobs) as ex:
        res = list(ex.map(one, variants))
    md = [f"Evals: {a.budget} (CV), {a.side_evals} (famous, Chessigma). Targets (PHASE3 §20): Brilliant P >= "
          f"{TARGET['brilliant'][0]} & R >= {TARGET['brilliant'][1]}, Great P >= {TARGET['great'][0]} & R >= "
          f"{TARGET['great'][1]}; famous main column = rating 2500. ✓/✗ = target met on CV and famous 2500.", "",
          "| variant | CV exact / ≥2 | CV Brilliant | CV Great | CV Miss | **famous 2500 brilliant-kind B / G** | "
          "famous 2500 control B / G | famous 1500 brilliant-kind B / G | famous 1500 control B / G | Chessigma B | "
          "Pi 5 s/40 mean / worst | worst game on Pi 5 |", "|---|---|---|---|---|---|---|---|---|---|---:|---|"]
    for r in res:
        star = "" if r["f1500recheck"] else " (no famous deep: lite)"
        md.append(f"| {r['name']}{star} | {r['exact']:.1%} / {r['far']} | {fmt_pr(r['cv']['brilliant'])} | "
                  f"{fmt_pr(r['cv']['great'])} | {fmt_pr(r['cv']['miss'])} | "
                  f"{fmt_pr(r['f2500brilliant']['brilliant'])} {ok(r, 'brilliant')} / "
                  f"{fmt_pr(r['f2500brilliant']['great'])} {ok(r, 'great')} | "
                  f"{fmt_pr(r['f2500control']['brilliant'])} / {fmt_pr(r['f2500control']['great'])} | "
                  f"{fmt_pr(r['f1500brilliant']['brilliant'])} / {fmt_pr(r['f1500brilliant']['great'])} | "
                  f"{fmt_pr(r['f1500control']['brilliant'])} / {fmt_pr(r['f1500control']['great'])} | "
                  f"{r['chessigma']} | {r['pi5']:.1f} / {r['pi5max']:.1f} | {r['pi5game'][0]:.1f} s "
                  f"({r['pi5game'][1]}, {(r['pi5game'][2] + 1) // 2} moves) |")
    text = "\n".join(md)
    (root / f"{a.name or Path(a.variants).stem}.md").write_text(text + "\n")
    print(text)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="cv", choices=["cv", "stability", "ceiling", "noise", "refs", "fplist", "prcurve"])
    ap.add_argument("a", nargs="?", help="stability: first budget")
    ap.add_argument("b", nargs="?", help="stability: second budget")
    ap.add_argument("--dump", default=str(DATA / "evals_labeled"))
    ap.add_argument("--budget", default="lite")
    ap.add_argument("--folds", help="fold file (default <dump>/folds.json)")
    ap.add_argument("--ref", default="torch18", choices=sorted(REFS), help="chess.com labels to compare with")
    ap.add_argument("--mode", default="product", choices=["product", "second", "mpv3"])
    ap.add_argument("--grid", help="JSON file: {knob: [values]} or [{knob: value}, ...]")
    ap.add_argument("-D", action="append", default=[], help="fixed knob, e.g. -D good=0.06")
    ap.add_argument("--penalty", type=float, default=5.0, help="objective: exact plies - penalty * cases >=2 levels")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) // 2))
    ap.add_argument("--name", help="output folder name under target/cv")
    ap.add_argument("--deep", default="deep", help="dump compared with --budget for the unstable/rule split ('' = off)")
    ap.add_argument("--margin", type=float, default=0.01, help="unstable when the win chance loss is this close to a threshold")
    ap.add_argument("--fp-out", default=str(Path.home() / ".javachess-orchestrator/review-team/notes/fp"),
                    help="fplist: folder of FP_LIST.csv / FN_LIST.csv")
    ap.add_argument("--recheck", choices=["second", "full"], help="candidates re-searched deeper, simulated with "
                    "the --deep dump: 'second' = deep second line when the deep best move is ours, 'full' = deep eval")
    ap.add_argument("--evals", help="engine dump for every set: CV/hold-out <dump>/<evals>, famous and Chessigma "
                    "evals_famous/<evals>, evals_chessigma/<evals> (e.g. sf16-lite); default --budget and 'lite'")
    ap.add_argument("--variants", help="prcurve: JSON list of {name, knobs: {...}, recheck} or a knob grid")
    ap.add_argument("--holdout", action="store_true")
    ap.add_argument("--final", action="store_true")
    ap.add_argument("--no-build", action="store_true", help="skip ./mvnw test-compile")
    a = ap.parse_args()
    a.side_evals = a.evals or "lite"
    if a.evals:
        a.budget = a.evals
    global LABELS_DIR, FOLDS
    LABELS_DIR = DATA / REFS[a.ref]
    FOLDS = a.folds
    {"stability": cmd_stability, "ceiling": cmd_ceiling, "noise": cmd_noise, "refs": cmd_refs, "fplist": cmd_fplist, "prcurve": cmd_prcurve}.get(a.command, cmd_cv)(a)


if __name__ == "__main__":
    main()
