"""
Extracts dual-frame static board pairs (frame_a + frame_b spaced ~0.35s apart to preserve
flashing hazards such as Electrified, Burning, and Vortex animations) with 64-bit dHash
deduplication from local MP4 recordings or YouTube URLs.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image


def compute_dhash64(img: Image.Image) -> int:
    gray = img.convert("L").resize((9, 8), Image.BILINEAR)
    arr = np.asarray(gray, dtype=np.int32)
    h = 0
    bit = 0
    for y in range(8):
        for x in range(8):
            if arr[y, x] > arr[y, x + 1]:
                h |= 1 << bit
            bit += 1
    return h


def hamming_distance(a: int, b: int) -> int:
    return (a ^ b).bit_count()


def deduplicate_frame_sequence(
    frames: list[Image.Image],
    pair_offset_frames: int = 1,
    min_hamming: int = 5,
    max_pair_hamming: int = 4,
) -> list[tuple[Image.Image, Image.Image, int]]:
    """
    Selects unique static board pairs `(frame_a, frame_b, dhash)` from an ordered frame stream:
     - Skips animated cascades/drags where `hamming(frame_t, frame_{t+offset}) > max_pair_hamming`
     - Deduplicates repeated idle boards within `<= min_hamming` of recently seen hashes.
    """
    accepted: list[tuple[Image.Image, Image.Image, int]] = []
    seen_hashes: list[int] = []
    n = len(frames)
    i = 0
    while i + pair_offset_frames < n:
        fa = frames[i]
        fb = frames[i + pair_offset_frames]
        ha = compute_dhash64(fa)
        hb = compute_dhash64(fb)
        if hamming_distance(ha, hb) <= max_pair_hamming:
            if all(hamming_distance(ha, prev) > min_hamming for prev in seen_hashes):
                accepted.append((fa, fb, ha))
                seen_hashes.append(ha)
        i += max(1, pair_offset_frames + 1)
    return accepted


def extract_pairs_from_video(
    video_path: Path,
    out_dir: Path,
    sample_fps: float = 3.0,
    tag: str = "clip",
) -> int:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("ffmpeg not found in PATH")
    tmp_dir = out_dir / "_raw_frames"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    pattern = str(tmp_dir / f"{tag}_%06d.jpg")
    subprocess.run(
        [ffmpeg, "-y", "-i", str(video_path), "-vf", f"fps={sample_fps}", pattern],
        check=True,
    )
    raw_paths = sorted(tmp_dir.glob(f"{tag}_*.jpg"))
    imgs = [Image.open(p).convert("RGB") for p in raw_paths]
    pairs = deduplicate_frame_sequence(imgs, pair_offset_frames=1)
    pairs_dir = out_dir / "pairs"
    pairs_dir.mkdir(parents=True, exist_ok=True)
    for idx, (fa, fb, hVal) in enumerate(pairs):
        stem = f"{tag}_{idx:04d}_{hVal:016x}"
        fa.save(pairs_dir / f"{stem}_a.webp", format="WEBP", quality=92)
        fb.save(pairs_dir / f"{stem}_b.webp", format="WEBP", quality=92)
    for im in imgs:
        im.close()
    shutil.rmtree(tmp_dir, ignore_errors=True)
    return len(pairs)


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract dual-frame board pairs from gameplay video")
    parser.add_argument("--input", help="Local MP4/MKV video file")
    parser.add_argument("--url", help="YouTube URL (requires yt-dlp)")
    parser.add_argument("--tag", default="gameplay", help="Clip tag prefix")
    parser.add_argument("--fps", type=float, default=3.0, help="Frame sampling rate before dual-frame pairing")
    parser.add_argument("--out", default="ml/datasets/video_pairs", help="Output folder")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.input:
        vid = Path(args.input)
    elif args.url:
        ytdlp = shutil.which("yt-dlp")
        if not ytdlp:
            raise RuntimeError("yt-dlp not found in PATH")
        slug = hashlib.sha1(args.url.encode("utf-8")).hexdigest()[:8]
        vid = out_dir / f"{args.tag}_{slug}.mp4"
        subprocess.run([ytdlp, "-f", "mp4", "-o", str(vid), args.url], check=True)
    else:
        raise ValueError("Provide either --input or --url")

    count = extract_pairs_from_video(vid, out_dir, sample_fps=args.fps, tag=args.tag)
    print(f"Saved {count} deduplicated dual-frame pairs to {out_dir / 'pairs'}")


if __name__ == "__main__":
    main()
