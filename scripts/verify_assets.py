#!/usr/bin/env python3
"""Verify the extraction manifest against APK bytes and preserved vector paths."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
SVG = '{http://www.w3.org/2000/svg}'


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', default='.tmp/qa/assets-verification.json')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'assets/MANIFEST.json').read_text())
    apk_path = ROOT / '.tmp/input/deepseek.apk'
    apk_sha = digest(apk_path.read_bytes())
    counts: Counter[str] = Counter()
    paths: set[str] = set()
    vectors = 0
    with zipfile.ZipFile(apk_path) as apk:
        for row in manifest:
            name = row['destination']
            assert name not in paths, f'duplicate manifest destination: {name}'
            paths.add(name)
            dest = ROOT / name
            assert dest.is_relative_to(ROOT / 'assets'), f'unsafe destination: {name}'
            assert digest(dest.read_bytes()) == row['sha256'], f'changed artifact: {name}'
            assert apk_sha == row['apk_sha256'], 'APK identity mismatch'
            assert digest(apk.read(row['source'])) == row['source_sha256'], f'changed source: {name}'
            counts[row['category']] += 1
            if row['category'] in {'lottie', 'audio', 'shader', 'png', 'compiled-vector'}:
                assert row['source_sha256'] == row['sha256'], f'original bytes changed: {name}'
            if row['category'] == 'lottie':
                animation = json.loads(dest.read_text())
                assert animation['fr'] > 0 and animation['op'] > animation.get('ip', 0)
                assert animation['w'] > 0 and animation['h'] > 0
            if row['category'] == 'android-vector':
                semantic = row['semantic']
                source_name = Path(row['source']).name
                projection = (ROOT / f'assets/original/icons/aapt2-xmltree/{source_name}.txt').read_text()
                paths_from_dump = re.findall(r'android:pathData\([^)]*\)="(.*?)"', projection)
                android_root = ET.parse(dest).getroot()
                actual = [p.attrib[ANDROID + 'pathData'] for p in android_root.iter() if p.tag in {'path', 'clip-path'}]
                assert Counter(actual) == Counter(paths_from_dump), f'path mismatch: {semantic}'
                svg_root = ET.parse(ROOT / f'assets/derived/icons/svg/{semantic}.svg').getroot()
                svg_paths = [p.attrib['d'] for p in svg_root.iter(SVG + 'path')]
                assert Counter(svg_paths) == Counter(paths_from_dump), f'SVG path mismatch: {semantic}'
                for node in svg_root.iter():
                    for attr in ['fill', 'stroke']:
                        value = node.get(attr)
                        if value and value != 'none':
                            assert re.fullmatch(r'#[0-9a-fA-F]{6}', value), f'wrong SVG color encoding: {value}'
                vectors += 1
        actual_files = {str(p.relative_to(ROOT)) for folder in ['assets/original', 'assets/derived'] for p in (ROOT/folder).rglob('*') if p.is_file()}
        assert actual_files == paths, f'untracked/absent assets: {actual_files.symmetric_difference(paths)}'
    report = {'apk_sha256': apk_sha, 'manifest_entries': len(manifest), 'categories': dict(sorted(counts.items())), 'verified_vector_pairs': vectors, 'source_and_output_hashes_verified': True, 'vector_path_multisets_verified': True, 'runtime_animation_or_metal_verified': False}
    output = (ROOT / args.report).resolve()
    assert output.is_relative_to(ROOT / '.tmp'), 'verification report must be under .tmp'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
