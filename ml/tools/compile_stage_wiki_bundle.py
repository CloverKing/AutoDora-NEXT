"""
Compile A1 live-crawled `tos_current_version_stage_wiki.json` (read-only) into an App-importable
`StageWikiImportBundle` (`RawWikiBundleJson` schema consumed by `StageWaveScriptParser.parseStageWikiJson`).

Features:
  - Read-only ingestion of `backups/phase4_v2_inbox/stage_wiki_db/tos_current_version_stage_wiki.json`
  - Cleans every skill & scene_skill using `ml.tools.build_unified_enemy_skill_master` unwrapper
  - Propagates wave `scene_skill` into waves AND into each Boss phase when a wave has multi-phase Bosses
  - Expands multi-phase Bosses (`enemy.phases`) into sequential waves so Phase 1 and Phase 2 shields never conflict
  - Deduplicates identical cleaned skills per wave by canonical hash and filters empty/noise stubs
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ml.tools.build_unified_enemy_skill_master import (
    classify_drop_reason,
    clean_layout_and_ocr_artifacts,
    compute_canonical_hash,
    repair_title_and_body_split,
    to_canonical_zh_cn,
)


def _clean_single_skill(skill_obj: dict[str, Any]) -> dict[str, str] | None:
    raw_title = (skill_obj.get("title_zh_tw") or "").strip()
    raw_body = (skill_obj.get("raw_body_zh_tw") or "").strip()
    clean_title, rep_body = repair_title_and_body_split(raw_title, raw_body)
    clean_body, _, _ = clean_layout_and_ocr_artifacts(rep_body)
    if classify_drop_reason(clean_title, clean_body, occ=2) is not None:
        return None
    c_hash = compute_canonical_hash(clean_title, clean_body)
    full_text = f"{clean_title}\n{clean_body}".strip() if clean_title else clean_body
    return {
        "canonical_hash": c_hash,
        "title": clean_title,
        "clean_body": clean_body,
        "full_text": full_text,
    }


def _collect_skills(
    raw_skill_objs: list[dict[str, Any]],
    scene_skill_obj: dict[str, Any] | None,
) -> tuple[list[str], list[str]]:
    seen_hashes: set[str] = set()
    titles: list[str] = []
    skill_texts: list[str] = []

    ordered = list(raw_skill_objs)
    if scene_skill_obj:
        ordered.append(scene_skill_obj)

    for obj in ordered:
        cleaned = _clean_single_skill(obj)
        if not cleaned:
            continue
        h = cleaned["canonical_hash"]
        if h in seen_hashes:
            continue
        seen_hashes.add(h)
        if cleaned["title"] and cleaned["title"] not in titles:
            titles.append(cleaned["title"])
        skill_texts.append(cleaned["full_text"])
    return titles, skill_texts


def _compute_stage_key(region: str, dungeon: str, stage_name: str, waves: list[dict[str, Any]]) -> str:
    sig_parts = [to_canonical_zh_cn(f"{region}|{dungeon}|{stage_name}")]
    for w in waves:
        sig_parts.append(f"w{w['waveIndex']}:{'B' if w['isBoss'] else 'N'}:{'/'.join(w['skills'])}")
    return hashlib.sha256("|".join(sig_parts).encode("utf-8")).hexdigest()[:16]


def _clean_stage_label(s: str) -> str:
    s = s.strip()
    s = re.sub(r"^\d{3}_", "", s)
    s = s.replace("ⅡI", "III").replace("Ⅱ", "II")
    s = re.sub(r"(?<=[\u4e00-\u9fffA-Za-z])\|$", "I", s)
    s = s.replace("|", "")
    s = re.sub(r"_\d+$", "", s)
    if s in ("(新手適用", "《新手適用"):
        s = "雙週活動"
    return s.strip()


def _infer_hierarchy(reg_name: str, dg_name: str, stage_name: str, raw_diff: str) -> tuple[str, str, str, str]:
    combined = f"{reg_name} {dg_name} {stage_name}"
    if "古神遺跡" in reg_name:
        entry = "古神遺跡"
    elif "大型活動" in reg_name:
        entry = "大型活動"
    elif "故事" in reg_name:
        entry = "故事模式"
    elif "育成訓練" in reg_name:
        entry = "育成訓練"
    elif "遺跡特許" in reg_name:
        entry = "遺跡特許"
    else:
        entry = "大型活動"

    if entry == "古神遺跡":
        if "災厄" in combined:
            sub = "災厄級"
        elif "夢魘" in combined:
            sub = "夢魘級"
        elif "地獄" in combined:
            sub = "地獄級"
        elif "是日" in combined or "資源" in combined:
            sub = "日常遺跡"
        else:
            sub = "高難試煉"
    elif entry == "大型活動":
        if "初心" in reg_name or "精選" in combined or "契機" in combined:
            sub = "初心試煉塔"
        elif "每月挑戰" in combined or "挑戰任務" in combined:
            sub = "每月挑戰"
        elif "靈罩塔" in combined:
            sub = "靈罩塔"
        else:
            sub = "雙週與活動"
    elif entry == "故事模式":
        if "以諾" in reg_name:
            sub = "以諾時代"
        elif "嶄新" in reg_name or "間章" in combined:
            sub = "嶄新封印"
        elif "副線" in reg_name:
            sub = "故事副線"
        else:
            sub = "主線故事"
    elif entry == "育成訓練":
        if "敵技" in combined or "模擬應戰" in combined:
            sub = "敵技訓練"
        elif "之日" in combined or "靈魂石" in combined:
            sub = "每日資源關"
        else:
            sub = "成長與挑戰"
    else:
        if "夢魘" in combined:
            sub = "夢魘級"
        elif "地獄" in combined:
            sub = "地獄級"
        else:
            sub = "戰魂憶魂"

    diff = raw_diff.strip()
    if not diff:
        for tag in ("災厄級", "夢魘級", "地獄級", "困難", "進階", "高級", "初級", "初階"):
            if tag in combined:
                diff = tag
                break
        if not diff and ("Extra" in combined or "Lv." in combined):
            diff = "挑戰/Lv"

    return entry, sub, dg_name, diff


def compile_stage_wiki_bundle(wiki_data: dict[str, Any]) -> dict[str, Any]:
    """Compiles parsed `tos_current_version_stage_wiki.json` payload into App-compatible bundle."""
    compiled_stages: list[dict[str, Any]] = []
    total_waves_out = 0
    total_boss_phases_expanded = 0
    total_skills_emitted = 0

    for reg in wiki_data.get("regions", []):
        reg_name = (reg.get("region_name") or "").strip()
        for dg in reg.get("dungeons", []):
            dg_name = _clean_stage_label(dg.get("dungeon_name") or "")
            for st in dg.get("stages", []):
                raw_stage_name = _clean_stage_label(st.get("stage_name") or "")
                if not raw_stage_name:
                    continue
                if "BATTLE" in raw_stage_name.upper() and dg_name:
                    raw_stage_name = dg_name
                display_name = f"{dg_name} · {raw_stage_name}" if dg_name and dg_name != raw_stage_name else raw_stage_name

                out_waves: list[dict[str, Any]] = []
                wave_idx = 1
                for rw in st.get("waves", []):
                    w_label = (rw.get("wave_label") or f"WAVE {wave_idx}").strip()
                    is_boss_wave = "boss" in w_label.lower()
                    scene_obj = rw.get("scene_skill")
                    enemies = rw.get("enemies", [])

                    # Check if any enemy has multi-phase breakdown (`phases` non-empty)
                    multi_phase_enemies = [e for e in enemies if e.get("phases")]
                    static_enemies = [e for e in enemies if not e.get("phases")]

                    if not multi_phase_enemies:
                        raw_objs: list[dict[str, Any]] = []
                        for e in enemies:
                            raw_objs.extend(e.get("skills", []))
                        titles, skills = _collect_skills(raw_objs, scene_obj)
                        if skills or is_boss_wave:
                            out_waves.append({
                                "waveIndex": wave_idx,
                                "waveLabel": w_label,
                                "isBoss": is_boss_wave,
                                "skillTitles": titles,
                                "skills": skills,
                            })
                            total_skills_emitted += len(skills)
                        wave_idx += 1
                    else:
                        # Group phase indices across simultaneous enemies, or emit sequential phases
                        base_static_objs: list[dict[str, Any]] = []
                        for e in static_enemies:
                            base_static_objs.extend(e.get("skills", []))
                        max_phases = max(len(e.get("phases", [])) for e in multi_phase_enemies)
                        for p_idx in range(max_phases):
                            phase_objs = list(base_static_objs)
                            phase_labels: list[str] = []
                            for e in multi_phase_enemies:
                                phases = e.get("phases", [])
                                if p_idx < len(phases):
                                    ph = phases[p_idx]
                                    if ph.get("phase_label"):
                                        phase_labels.append(str(ph["phase_label"]).strip())
                                    phase_objs.extend(ph.get("skills", []))
                            titles, skills = _collect_skills(phase_objs, scene_obj)
                            p_suffix = f" ({phase_labels[0]})" if phase_labels else f" (Phase {p_idx + 1})"
                            out_waves.append({
                                "waveIndex": wave_idx,
                                "waveLabel": f"{w_label}{p_suffix}",
                                "isBoss": True,
                                "skillTitles": titles,
                                "skills": skills,
                            })
                            total_skills_emitted += len(skills)
                            total_boss_phases_expanded += 1
                            wave_idx += 1

                if not out_waves:
                    continue
                total_waves_out += len(out_waves)
                entry_cat, sub_cat, series_name, diff_tag = _infer_hierarchy(
                    reg_name, dg_name, raw_stage_name, st.get("difficulty") or ""
                )
                compiled_stages.append({
                    "stageKey": _compute_stage_key(reg_name, dg_name, raw_stage_name, out_waves),
                    "stageName": display_name,
                    "region": reg_name,
                    "dungeon": dg_name,
                    "difficulty": diff_tag,
                    "entryCategory": entry_cat,
                    "subCategory": sub_cat,
                    "dungeonSeries": series_name,
                    "difficultyTag": diff_tag,
                    "waves": out_waves,
                })

    now_iso = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    return {
        "schema_version": "1.0",
        "generated_at": now_iso,
        "source_generated_at": wiki_data.get("generated_at", ""),
        "source": "A1_LIVE_STAGE_WIKI_COMPILED",
        "total_stages": len(compiled_stages),
        "total_waves": total_waves_out,
        "boss_phases_expanded": total_boss_phases_expanded,
        "total_skills_emitted": total_skills_emitted,
        "stages": compiled_stages,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Compile A1 stage_wiki_db JSON into App StageWikiImportBundle")
    parser.add_argument(
        "--input",
        default="backups/phase4_v2_inbox/stage_wiki_db/tos_current_version_stage_wiki.json",
        help="Read-only path to A1 stage wiki DB JSON",
    )
    parser.add_argument(
        "--out",
        default="ml/datasets/enemy_skill_master_clean/tos_stage_wiki_import_bundle.json",
        help="Output bundle path (inside gitignored ml/datasets/)",
    )
    args = parser.parse_args()

    in_path = Path(args.input)
    out_path = Path(args.out)
    wiki_data = json.loads(in_path.read_text(encoding="utf-8"))
    bundle = compile_stage_wiki_bundle(wiki_data)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        f"Compiled stages={bundle['total_stages']}, waves={bundle['total_waves']}, "
        f"boss_phases_expanded={bundle['boss_phases_expanded']}, "
        f"skills_emitted={bundle['total_skills_emitted']} -> {out_path}"
    )


if __name__ == "__main__":
    main()
