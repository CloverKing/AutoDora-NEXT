"""
Ingests exported AutoDora-NEXT on-device ZIP bundles (AutoDora_TrainingData_*.zip) into a clean
local staging directory, assigns sample weights (P0 editor-correction=4.0x, P1=2.0x, P2=1.0x),
and audits captured enemy skills against android/app/src/main/assets/tos_enemy_skill_rules_v2.json.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import zipfile
from pathlib import Path
from typing import Any


DOMAIN_WEIGHT = {
    "BOARD_EDITOR_CORRECTION": 4.0,
    "BOARD_SOLVE": 1.5,
    "BOARD_AUTO_IDLE": 1.0,
    "ENEMY_POPUP_LONG_PRESS": 2.0,
    "STAGE_INFO_MODAL": 2.0,
}


def compute_sample_weight(item: dict[str, Any]) -> float:
    domain = item.get("domain", "")
    priority = int(item.get("priority", 2))
    base = DOMAIN_WEIGHT.get(domain, 1.0)
    if priority == 0:
        return max(base, 4.0)
    if priority == 1:
        return max(base, 2.0)
    return base


def compile_rules_v2(rules_json_path: Path) -> list[tuple[str, str, re.Pattern[str]]]:
    data = json.loads(rules_json_path.read_text(encoding="utf-8"))
    compiled: list[tuple[str, str, re.Pattern[str]]] = []
    for r in data.get("rules", []):
        pat = r.get("pattern", "")
        if not pat:
            continue
        try:
            compiled.append((r["id"], r["chipId"], re.compile(pat, re.IGNORECASE)))
        except re.error:
            continue
    return compiled


def ingest_zip_bundle(
    zip_path: Path,
    out_dir: Path,
    rules_json_path: Path | None = None,
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    extract_dir = out_dir / zip_path.stem
    extract_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(extract_dir)

    manifest_path = extract_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    items = manifest.get("items", [])

    board_records: list[dict[str, Any]] = []
    modal_records: list[dict[str, Any]] = []
    for item in items:
        rec = dict(item)
        rec["sampleWeight"] = compute_sample_weight(item)
        rec["rootDir"] = str(extract_dir)
        domain = item.get("domain", "")
        if domain.startswith("BOARD_"):
            board_records.append(rec)
        else:
            modal_records.append(rec)

    master_path = extract_dir / "enemy_skills_master.json"
    skills = json.loads(master_path.read_text(encoding="utf-8")) if master_path.exists() else []
    rules = compile_rules_v2(rules_json_path) if (rules_json_path and rules_json_path.is_file()) else []

    unparsed_skills: list[dict[str, Any]] = []
    for sk in skills:
        status = sk.get("parseStatus", "UNPARSED")
        body = sk.get("rawBodyZhTw", "")
        title = sk.get("titleZhTw", "")
        if status == "UNPARSED":
            matched_v2 = [rid for (rid, _, pat) in rules if pat.search(body) or pat.search(title)]
            entry = dict(sk)
            entry["matchedLocallyByRulesV2"] = matched_v2
            unparsed_skills.append(entry)

    staging_manifest = {
        "sourceZip": str(zip_path),
        "totalSamples": len(items),
        "boardCount": len(board_records),
        "modalCount": len(modal_records),
        "p0CorrectionCount": sum(1 for r in board_records if int(r.get("priority", 2)) == 0),
        "boards": board_records,
        "modals": modal_records,
    }
    (out_dir / "staging_manifest.json").write_text(
        json.dumps(staging_manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (out_dir / "unparsed_skills_report.json").write_text(
        json.dumps(unparsed_skills, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return {
        "staging_manifest": staging_manifest,
        "unparsed_count": len(unparsed_skills),
        "skills_total": len(skills),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest AutoDora device training ZIP export")
    parser.add_argument("--zip", required=True, help="Path to AutoDora_TrainingData_*.zip")
    parser.add_argument("--out", default="ml/datasets/device_staged", help="Output staging directory")
    parser.add_argument(
        "--rules",
        default="android/app/src/main/assets/tos_enemy_skill_rules_v2.json",
        help="Optional tos_enemy_skill_rules_v2.json path for local re-audit",
    )
    args = parser.parse_args()
    summary = ingest_zip_bundle(Path(args.zip), Path(args.out), Path(args.rules))
    m = summary["staging_manifest"]
    print(
        f"Ingested {m['totalSamples']} samples "
        f"(boards={m['boardCount']}, P0={m['p0CorrectionCount']}, modals={m['modalCount']}), "
        f"skills={summary['skills_total']}, unparsed={summary['unparsed_count']}"
    )


if __name__ == "__main__":
    main()
