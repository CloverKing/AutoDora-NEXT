"""
End-to-end synthetic unit tests for Stage 2A/2B PC ML ingestion, video dual-frame pairing,
BoardMasterGridNet & EnemyIconNet forward/training/ONNX export without requiring external media.
"""

from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from ml.board_master_arch import BoardMasterGridNet
from ml.enemy_icon_arch import ENEMY_ICON_CLASSES, STRIP_H, STRIP_W, EnemyIconNet
from ml.export_onnx import export_board_master_to_onnx, export_enemy_icon_to_onnx
from ml.schema import OUTPUT_DIM
from ml.tools.extract_youtube_frames import deduplicate_frame_sequence
from ml.tools.ingest_device_export import ingest_zip_bundle
from ml.train_board_master import train_one_run


class MlPipelineTests(unittest.TestCase):

    def test_board_master_and_enemy_icon_onnx_export_and_parity(self) -> None:
        bm = BoardMasterGridNet()
        x5 = torch.rand(2, 6, 5 * 64, 6 * 64)
        out5 = bm(x5, rows=5, cols=6)
        self.assertEqual(tuple(out5.shape), (2, 30, OUTPUT_DIM))

        x8 = torch.rand(1, 6, 8 * 64, 6 * 64)
        out8 = bm(x8, rows=8, cols=6)
        self.assertEqual(tuple(out8.shape), (1, 48, OUTPUT_DIM))

        ei = EnemyIconNet()
        icon_out = ei(torch.rand(2, 3, STRIP_H, STRIP_W))
        self.assertEqual(tuple(icon_out.shape), (2, len(ENEMY_ICON_CLASSES)))

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            bm_path = tmp_dir / "bm.onnx"
            diff_bm = export_board_master_to_onnx(bm, bm_path, rows=5, cols=6)
            diff_ei = export_enemy_icon_to_onnx(ei, tmp_dir / "ei.onnx")
            self.assertLess(diff_bm, 1e-4)
            self.assertLess(diff_ei, 1e-4)

            import onnxruntime as ort
            sess = ort.InferenceSession(str(bm_path), providers=["CPUExecutionProvider"])
            out_dyn_8 = sess.run(["cell_probs"], {"dual_board": x8.numpy()})[0]
            self.assertEqual(tuple(out_dyn_8.shape), (1, 48, OUTPUT_DIM))

            ei_sess = ort.InferenceSession(str(tmp_dir / "ei.onnx"), providers=["CPUExecutionProvider"])
            ei_probs, ei_peaks = ei_sess.run(
                ["icon_probs", "icon_peaks"],
                {"upper_strip": np.zeros((2, 3, STRIP_H, STRIP_W), dtype=np.float32)},
            )
            self.assertEqual(tuple(ei_probs.shape), (2, len(ENEMY_ICON_CLASSES)))
            self.assertEqual(tuple(ei_peaks.shape), (2, len(ENEMY_ICON_CLASSES), 2))

    def test_train_one_run_with_p0_weights(self) -> None:
        rng = np.random.default_rng(7)
        n = 4
        dual_boards = rng.random((n, 6, 5 * 64, 6 * 64), dtype=np.float32)
        targets = np.zeros((n, 30, OUTPUT_DIM), dtype=np.float32)
        targets[:, :, 0] = 1.0
        targets[:, :, 1] = 1.0  # Water
        targets[:, :, 10] = 1.0 # Normal
        targets[:, :, 14] = 1.0 # None race
        masks = np.ones((n, 6), dtype=np.float32)
        weights = np.array([4.0, 1.0, 2.0, 1.0], dtype=np.float32)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            npz_path = tmp_dir / "toy_train.npz"
            np.savez(
                npz_path,
                dual_boards=dual_boards,
                targets=targets,
                label_masks=masks,
                sample_weights=weights,
                rows=np.array(5),
                cols=np.array(6),
            )
            ckpt = train_one_run(npz_path, tmp_dir / "run", epochs=2, batch_size=2, device_str="cpu")
            self.assertTrue(ckpt.is_file())
            self.assertTrue((tmp_dir / "run" / "train_history.json").is_file())

    def test_ingest_device_export_weights_and_unparsed_audit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            zip_path = tmp_dir / "AutoDora_TrainingData_test.zip"
            manifest = {
                "schemaVersion": 1,
                "totalSamples": 2,
                "items": [
                    {
                        "id": "ed_1",
                        "domain": "BOARD_EDITOR_CORRECTION",
                        "priority": 0,
                        "primaryImage": "boards/ed_1_a.webp",
                        "metadataJson": "meta/ed_1.json",
                    },
                    {
                        "id": "em_1",
                        "domain": "ENEMY_POPUP_LONG_PRESS",
                        "priority": 1,
                        "primaryImage": "enemy_popups/em_1_full.webp",
                        "metadataJson": "meta/em_1.json",
                    },
                ],
            }
            skills = [
                {
                    "skillHash": "h1",
                    "titleZhTw": "未知測試盾",
                    "rawBodyZhTw": "這是一條尚未納入規則的新敵技描述",
                    "sourceType": "ENEMY_POPUP_LONG_PRESS",
                    "parseStatus": "UNPARSED",
                    "hitCount": 2,
                }
            ]
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("manifest.json", json.dumps(manifest))
                zf.writestr("enemy_skills_master.json", json.dumps(skills))
                zf.writestr("boards/ed_1_a.webp", b"fake")
                zf.writestr("enemy_popups/em_1_full.webp", b"fake")
                zf.writestr("meta/ed_1.json", "{}")
                zf.writestr("meta/em_1.json", "{}")

            summary = ingest_zip_bundle(
                zip_path,
                tmp_dir / "staged",
                Path("android/app/src/main/assets/tos_enemy_skill_rules_v2.json"),
            )
            m = summary["staging_manifest"]
            self.assertEqual(m["totalSamples"], 2)
            self.assertEqual(m["p0CorrectionCount"], 1)
            self.assertEqual(m["boards"][0]["sampleWeight"], 4.0)
            self.assertEqual(summary["unparsed_count"], 1)

    def test_video_dual_frame_dedup_rejects_moving_frames_and_duplicates(self) -> None:
        img_a1 = Image.fromarray(np.tile(np.arange(0, 64, dtype=np.uint8), (64, 1)), mode="L").convert("RGB")
        img_a2 = img_a1.copy()
        img_b1 = Image.fromarray(np.tile(np.arange(63, -1, -1, dtype=np.uint8), (64, 1)), mode="L").convert("RGB")
        img_b2 = img_b1.copy()
        # sequence: [A1, A2 (static pair 1), A1 (moving transition to B1 -> skipped), A1, A2 (dup pair -> skipped), B1, B2 (static pair 2)]
        pairs = deduplicate_frame_sequence([img_a1, img_a2, img_a1, img_b1, img_a1, img_a2, img_b1, img_b2], pair_offset_frames=1)
        self.assertEqual(len(pairs), 2)


if __name__ == "__main__":
    unittest.main()
