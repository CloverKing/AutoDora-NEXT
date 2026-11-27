import json
import tempfile
import unittest
from pathlib import Path

from ml.tools.build_unified_enemy_skill_master import (
    build_unified_master,
    clean_layout_and_ocr_artifacts,
    compute_canonical_hash,
    repair_title_and_body_split,
)


class EnemySkillCleanerTests(unittest.TestCase):

    def test_unwrap_mid_sentence_newlines_preserves_bullets_and_arrows(self):
        raw = (
            "·以【正方3x3】形首批消除\n"
            "≥1組9粒木符石\n"
            "⇒ 回合結束時，\n"
            "獲得1「毒蝕聖果」\n"
            "(上限2粒)\n"
            "AVE3"
        )
        cleaned, tails, joins = clean_layout_and_ocr_artifacts(raw)
        self.assertEqual([], tails)
        self.assertGreaterEqual(joins, 2)
        self.assertIn("• 以【正方3x3】形首批消除≥1組9粒木符石", cleaned)
        self.assertIn("⇒ 回合結束時，獲得1「毒蝕聖果」", cleaned)
        self.assertIn("(上限2粒)", cleaned)
        self.assertNotIn("AVE3", cleaned)

    def test_repairs_split_bracket_title_and_missing_bracket(self):
        t1, b1 = repair_title_and_body_split("【十之詛咒 10%·", "生命禁咒】\n每以【十】形首批消除")
        self.assertEqual("【十之詛咒 10%·生命禁咒】", t1)
        self.assertEqual("每以【十】形首批消除", b1)

        t2, _ = repair_title_and_body_split("【靈魂值 - 光成員 (每個 1,000)", "隊中每有1個光屬性成員")
        self.assertEqual("【靈魂值 - 光成員 (每個 1,000)】", t2)

        t3, b3 = repair_title_and_body_split("·以【正方3x3】形首批消除", "≥1組9粒木符石")
        self.assertEqual("", t3)
        self.assertTrue(b3.startswith("·以【正方3x3】"))

    def test_collapses_pseudo_dupes_keeps_distinct_turn_counts_and_applies_a1_authority(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a1 = root / "a1.json"
            enc = root / "enc.json"
            out = root / "out"

            a1.write_text(
                json.dumps(
                    {
                        "generated_at": "2026-11-27T12:00:00+08:00",
                        "skills": [
                            {
                                "skill_hash": "h1",
                                "skill_kind": "enemy_skill",
                                "title_zh_tw": "【終結倒數】",
                                "raw_body_zh_tw": "·敵人倒數3回合後\n⇒ 消耗召喚師 100% 生命力。",
                                "occurrence_count": 10,
                            },
                            {
                                "skill_hash": "h2",
                                "skill_kind": "enemy_skill",
                                "title_zh_tw": "【終結倒數】",
                                "raw_body_zh_tw": "•敵人倒數 3 回合後\n⇒ 消耗召喚師100%生命力",
                                "occurrence_count": 4,
                            },
                            {
                                "skill_hash": "h3",
                                "skill_kind": "enemy_skill",
                                "title_zh_tw": "【終結倒數】",
                                "raw_body_zh_tw": "敵人倒數2回合後\n⇒ 消耗召喚師 100% 生命力",
                                "occurrence_count": 5,
                            },
                            {
                                "skill_hash": "h4",
                                "skill_kind": "enemy_skill",
                                "title_zh_tw": "【攻前盾】",
                                "raw_body_zh_tw": "",
                                "occurrence_count": 2,
                            },
                            {
                                "skill_hash": "h5",
                                "skill_kind": "enemy_skill",
                                "title_zh_tw": "【攻前盾】",
                                "raw_body_zh_tw": "敵人無視任何傷害，\n直至敵人第1次攻撃。",
                                "occurrence_count": 3,
                            },
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            enc.write_text(
                json.dumps(
                    {
                        "skills": [
                            {
                                "index": 2,
                                "name_zh_tw": "攻前盾",
                                "name_zh_cn": "攻前盾",
                                "full_rule_text_zh_tw": "百科舊文",
                                "autodora_mapping": {"coverage_status": "NON_BOARD", "wave_shield_chips": []},
                            },
                            {
                                "index": 30,
                                "name_zh_tw": "起結有序",
                                "name_zh_cn": "起结有序",
                                "full_rule_text_zh_tw": "從綠色漩渦起手\n並於紅色漩渦結束",
                                "autodora_mapping": {"coverage_status": "FULL", "wave_shield_chips": ["traffic_light"]},
                            },
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            comp = build_unified_master(a1, enc, None, out)
            # 5 raw A1: h1+h2 collapsed to 14x, h3 distinct (5x), h4 dropped empty stub (+2x into h5 -> 5x), plus 1 enc fallback
            self.assertEqual(3, comp["after"]["a1_cleaned_unique_entries"])
            self.assertEqual(1, comp["after"]["encyclopedia_covered_by_a1"])
            self.assertEqual(1, comp["after"]["encyclopedia_fallback_added"])
            self.assertEqual(4, comp["after"]["unified_total_entries"])

            master = json.loads((out / "unified_enemy_skill_master.json").read_text(encoding="utf-8"))
            by_t_b = [(s["title_zh_tw"], s["occurrence_count"], s["source_priority"]) for s in master["skills"]]
            self.assertIn(("【終結倒數】", 14, "A1_LIVE_AUTHORITATIVE"), by_t_b)
            self.assertIn(("【終結倒數】", 5, "A1_LIVE_AUTHORITATIVE"), by_t_b)
            self.assertIn(("【攻前盾】", 5, "A1_LIVE_AUTHORITATIVE"), by_t_b)
            self.assertIn(("【起結有序】", 0, "ENCYCLOPEDIA_FALLBACK"), by_t_b)

    def test_preserves_standalone_scene_skill_and_vetoes_opposite_polarity_fuzzy_merge(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a1 = root / "a1.json"
            enc = root / "enc.json"
            out = root / "out"
            a1.write_text(
                json.dumps(
                    {
                        "generated_at": "2026-11-27T12:00:00+08:00",
                        "skills": [
                            {
                                "skill_hash": "s1",
                                "skill_kind": "scene_skill",
                                "title_zh_tw": "",
                                "raw_body_zh_tw": "每回合開始移動符石時，才會顯示場上的符石",
                                "occurrence_count": 1,
                            },
                            {
                                "skill_hash": "t1",
                                "skill_kind": "scene_skill",
                                "title_zh_tw": "【溫律臨界】",
                                "raw_body_zh_tw": "• 進場時溫度減少30度\n• 每首批消除1粒水符石⇒溫度減少2度",
                                "occurrence_count": 2,
                            },
                            {
                                "skill_hash": "t2",
                                "skill_kind": "scene_skill",
                                "title_zh_tw": "【溫律臨界】",
                                "raw_body_zh_tw": "• 進場時溫度提升30度\n• 每首批消除1粒水符石⇒溫度減少2度",
                                "occurrence_count": 1,
                            },
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            enc.write_text(json.dumps({"skills": []}), encoding="utf-8")
            comp = build_unified_master(a1, enc, None, out)
            self.assertEqual(3, comp["after"]["a1_cleaned_unique_entries"])
            self.assertEqual(0, comp["cleaning_actions"]["ocr_near_duplicate_merges"])


if __name__ == "__main__":
    unittest.main()
