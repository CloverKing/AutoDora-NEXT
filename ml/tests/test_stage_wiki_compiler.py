import unittest

from ml.tools.compile_stage_wiki_bundle import compile_stage_wiki_bundle


class StageWikiCompilerTest(unittest.TestCase):
    def test_expands_boss_phases_propagates_scene_skill_and_drops_noise(self) -> None:
        sample = {
            "generated_at": "2026-11-27T12:00:00+08:00",
            "regions": [
                {
                    "region_name": "古神遺跡",
                    "dungeons": [
                        {
                            "dungeon_name": "雙影試煉",
                            "stages": [
                                {
                                    "stage_name": "試煉之間 災厄級",
                                    "difficulty": "災厄級",
                                    "waves": [
                                        {
                                            "wave_label": "WAVE 1",
                                            "scene_skill": {
                                                "title_zh_tw": "【毒果凝實 - 木(3x3)】",
                                                "raw_body_zh_tw": "• 以【正方3x3】形首批消除\n≥1組9粒木符石",
                                            },
                                            "enemies": [
                                                {
                                                    "name_zh_tw": "試煉兵",
                                                    "skills": [
                                                        {
                                                            "title_zh_tw": "【≥5連擊盾】",
                                                            "raw_body_zh_tw": "• 達成≥5連擊或以上\n才會造成傷害",
                                                        },
                                                        {
                                                            "title_zh_tw": "【空條目】",
                                                            "raw_body_zh_tw": "",
                                                        },
                                                    ],
                                                }
                                            ],
                                        },
                                        {
                                            "wave_label": "WAVE 2",
                                            "scene_skill": None,
                                            "enemies": [
                                                {
                                                    "name_zh_tw": "雙階段魔王",
                                                    "phases": [
                                                        {
                                                            "phase_label": "第 1 階段",
                                                            "skills": [
                                                                {
                                                                    "title_zh_tw": "【全消水盾】",
                                                                    "raw_body_zh_tw": "首批消除場上所有水符石",
                                                                }
                                                            ],
                                                        },
                                                        {
                                                            "phase_label": "第 2 階段",
                                                            "skills": [
                                                                {
                                                                    "title_zh_tw": "【全版消除盾】",
                                                                    "raw_body_zh_tw": "首批消除場上所有符石",
                                                                }
                                                            ],
                                                        },
                                                    ],
                                                }
                                            ],
                                        },
                                    ],
                                }
                            ],
                        }
                    ],
                }
            ],
        }

        out = compile_stage_wiki_bundle(sample)
        self.assertEqual(out["total_stages"], 1)
        self.assertEqual(out["total_waves"], 3)
        self.assertEqual(out["boss_phases_expanded"], 2)

        st = out["stages"][0]
        self.assertEqual(st["stageName"], "雙影試煉 · 試煉之間 災厄級")
        self.assertEqual(len(st["waves"]), 3)

        # Wave 1 combines enemy skill + scene_skill, drops empty stub, and unwraps mid-sentence breaks
        w1 = st["waves"][0]
        self.assertFalse(w1["isBoss"])
        self.assertEqual(w1["waveIndex"], 1)
        self.assertEqual(len(w1["skills"]), 2)
        self.assertIn("達成≥5連擊或以上才會造成傷害", w1["skills"][0])
        self.assertIn("以【正方3x3】形首批消除≥1組9粒木符石", w1["skills"][1])

        # Wave 2 & Wave 3 are separated Phase 1 and Phase 2 BOSS waves
        w2, w3 = st["waves"][1], st["waves"][2]
        self.assertTrue(w2["isBoss"])
        self.assertTrue(w3["isBoss"])
        self.assertEqual(w2["waveIndex"], 2)
        self.assertEqual(w3["waveIndex"], 3)
        self.assertIn("全消水盾", w2["skills"][0])
        self.assertIn("全版消除盾", w3["skills"][0])


if __name__ == "__main__":
    unittest.main()
