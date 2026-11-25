#!/usr/bin/env python3
"""Losslessly optimize app asset PNGs with a bounded content-addressed cache."""

from __future__ import annotations

import hashlib
import shutil
import subprocess
from pathlib import Path

ASSET_ROOT = Path("app/src/main/assets")
CACHE_ROOT = Path(".oxipng-cache")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
OXIPNG_ARGS = ("-o", "4", "--strip", "safe")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    if not ASSET_ROOT.is_dir():
        raise SystemExit(f"Asset directory not found: {ASSET_ROOT}")

    version = subprocess.check_output(["oxipng", "--version"], text=True).strip()
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)

    files = sorted(
        path for path in ASSET_ROOT.rglob("*")
        if path.is_file() and path.suffix.lower() == ".png"
    )
    original_total = 0
    optimized_total = 0
    cache_hits = 0
    active_cache_files: set[Path] = set()

    for source in files:
        original = source.read_bytes()
        if not original.startswith(PNG_SIGNATURE):
            raise RuntimeError(f"File has a PNG extension but is not a PNG: {source}")

        original_total += len(original)
        source_hash = sha256(original)
        cache_key = sha256(
            (version + "\0" + " ".join(OXIPNG_ARGS) + "\0" + source_hash).encode()
        )
        cached = CACHE_ROOT / f"{cache_key}.png"
        active_cache_files.add(cached)

        if cached.is_file() and cached.read_bytes().startswith(PNG_SIGNATURE):
            cache_hits += 1
        else:
            cached.unlink(missing_ok=True)
            temporary = CACHE_ROOT / f"{cache_key}.tmp.png"
            temporary.unlink(missing_ok=True)
            subprocess.run(
                ["oxipng", *OXIPNG_ARGS, "--out", str(temporary), str(source)],
                check=True,
            )
            optimized = temporary.read_bytes()
            if not optimized.startswith(PNG_SIGNATURE):
                temporary.unlink(missing_ok=True)
                raise RuntimeError(f"oxipng produced an invalid PNG for {source}")
            temporary.replace(cached)

        shutil.copy2(cached, source)
        optimized_total += cached.stat().st_size

    # Keep only entries needed by the current asset set. This prevents old
    # texture revisions and interrupted temporary files accumulating forever.
    removed = 0
    for cached in CACHE_ROOT.iterdir():
        if cached.is_file() and cached not in active_cache_files:
            cached.unlink()
            removed += 1

    print(f"PNG files: {len(files)}")
    print(f"Cache hits: {cache_hits}")
    print(f"Stale cache files removed: {removed}")
    print(f"Original total: {original_total:,} bytes")
    print(f"Optimized total: {optimized_total:,} bytes")
    if original_total:
        saved = original_total - optimized_total
        print(f"Saved: {saved:,} bytes ({saved / original_total * 100:.2f}%)")


if __name__ == "__main__":
    main()
