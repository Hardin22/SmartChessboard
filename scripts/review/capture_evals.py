#!/usr/bin/env python3
"""Builds src/test/resources/review/capture-evals.tsv, the after-capture searches of the Phase 4 "threat ignored"
Brilliant candidates, from the engine oracle's answers notes/special/oracle/TI3_<id>_<ply>.json (sf19_lite.after:
the product's Lite search, 200k nodes, of the position after the opponent takes the piece our move left en prise).
Columns: id, ply (our move, 1-based), capture (the opponent's capture, UCI), eval (White POV, dump format), best
(our engine's best reply after the capture), depth, nodes, pv. Run again when the oracle answers new TI requests."""
import json
from pathlib import Path

ORACLE = Path.home() / ".javachess-orchestrator/review-team/notes/special/oracle"
OUT = Path(__file__).resolve().parents[2] / "src/test/resources/review/capture-evals.tsv"

rows = []
for f in sorted(ORACLE.glob("TI3_*.json")):
    d = json.loads(f.read_text())
    a = (d.get("sf19_lite") or {}).get("after")
    if not a or "e" not in a:
        continue
    gid = d["id"][len("TI3_"):] if d["id"].startswith("TI3_") else d["id"]
    rows.append([gid, str(d["ply"]), d["move"], a["e"], a.get("move", ""), str(a.get("depth", "")),
                 str(a.get("nodes", "")), " ".join(a.get("pv", [])[:12])])
rows.sort(key=lambda r: (r[0], int(r[1])))
OUT.write_text("# After-capture searches (Phase 4 TI), built by scripts/review/capture_evals.py from the oracle's TI3 answers\n"
               "id\tply\tcapture\teval\tbest\tdepth\tnodes\tpv\n" + "".join("\t".join(r) + "\n" for r in rows))
print(f"{len(rows)} after-capture searches -> {OUT}")
