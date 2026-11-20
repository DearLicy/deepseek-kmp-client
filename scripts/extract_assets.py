#!/usr/bin/env python3
"""Extract the small set of reusable UI assets from the analyzed DeepSeek APK.

The script reads only from .tmp and writes to assets/. It never writes to /tmp.
It also decodes compiled vector drawables with aapt2 into readable Android XML
and SVG so the same icon can be used by Android and iOS.
"""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / ".tmp" / "input" / "deepseek.apk"
APK_EXTRACTED = ROOT / ".tmp" / "extracted" / "apk"
RES_TABLE = ROOT / ".tmp" / "extracted" / "resources-parsed.tsv"
ASSETS = ROOT / "assets"
AAPT2 = Path(os.path.expandvars(os.environ.get("DEEPSEEK_AAPT2", "${ANDROID_HOME:-/nonexistent}/build-tools/35.0.0/aapt2")))


@dataclass(frozen=True)
class RawAsset:
    semantic: str
    filename: str
    folder: str
    extension: str


LOTTIE = [
    RawAsset("auto_play_off", "1I.json", "lottie", ".json"),
    RawAsset("auto_play_on", "oM.json", "lottie", ".json"),
    RawAsset("expert", "s7.json", "lottie", ".json"),
    RawAsset("flash", "En.json", "lottie", ".json"),
    RawAsset("globe", "U1.json", "lottie", ".json"),
    RawAsset("photo", "HR.json", "lottie", ".json"),
    RawAsset("playing", "u1.json", "lottie", ".json"),
    RawAsset("think", "b0.json", "lottie", ".json"),
]

AUDIO = [
    RawAsset("call_connected", "mT.m4a", "audio", ".m4a"),
    RawAsset("call_disconnected", "sN.m4a", "audio", ".m4a"),
]

SHADERS = [
    RawAsset("voice_mask", "02.agsl", "shaders", ".agsl"),
]

SHADER_ASSETS = [
    RawAsset("fluid_blob", "fluid_blob.agsl", "shaders", ".agsl"),
    RawAsset("fluid_blob", "fluid_blob.vert", "shaders", ".vert"),
    RawAsset("fluid_blob", "fluid_blob.frag", "shaders", ".frag"),
    RawAsset("fluid_blob_medium", "fluid_blob_medium.frag", "shaders", ".frag"),
    RawAsset("fluid_blob_low", "fluid_blob_low.frag", "shaders", ".frag"),
    RawAsset("fluid_blob_example", "fluid_blob.example.frag", "shaders", ".frag"),
    RawAsset("fluid_blob_medium_example", "fluid_blob_medium.example.frag", "shaders", ".frag"),
    RawAsset("fluid_blob_low_example", "fluid_blob_low.example.frag", "shaders", ".frag"),
]

# semantic, compiled drawable, png variants from resources-parsed.tsv
VECTORS = [
    ("chat_welcome_logo", "Oc.xml", []),
    ("deepseek_logo_title", "Hk.xml", []),
    ("ic_copy_outline", "B9.xml", ["N0.png", "2W.png", "Ta.png", "RE1.png", "_q.png", "7L.png"]),
    ("ic_like_outline", "6g.xml", []),
    ("ic_dislike_outline", "Vs.xml", []),
    ("ic_playing_fill_24", "ZL.xml", []),
    ("ic_search_web", "2Q.xml", ["f-.png", "Y6.png", "32.png", "sH.png", "a0.png", "VE.png"]),
    ("ic_send_fill_20", "Si.xml", []),
    ("ic_think_outline_16", "ZR.xml", ["ua.png", "xk.png", "Ni.png", "bl.png", "2n.png", "HW.png"]),
    ("ic_voice_outline_26", "fc.xml", []),
    ("ic_new_chat_outline_24", "2b.xml", []),
    ("ic_ds_new_chat_outline_20", "cF.xml", []),
    ("ic_ds_search_outline_16", "19.xml", []),
    ("ic_keyboard_voice_24", "Ch.xml", []),
    ("ic_photo_outline_16", "zA.xml", ["fk.png", "FM.png", "Iq.png", "HF.png", "Kc.png", "8_.png"]),
    ("ic_photo_outline_20", "7s.xml", ["w2.png", "88.png", "pW.png", "oV.png", "2z.png", "-E.png"]),
    ("ic_globe_language_outline_16", "Y2.xml", ["dS.png", "Dz.png", "881.png", "o-.png", "fr.png", "kC.png"]),
    ("ic_globe_language_outline_20", "Q0.xml", ["Lk.png", "TS.png", "dE.png", "tx.png", "aH.png", "ts.png"]),
    ("ic_font_size_small", "k3.xml", []),
    ("ic_font_size_large", "Vi.xml", []),
]

FILL_TYPE = {"0": "nonZero", "1": "evenOdd"}
LINE_CAP = {"0": "butt", "1": "round", "2": "square"}
LINE_JOIN = {"0": "miter", "1": "round", "2": "bevel"}
XML_ATTR = {
    "height": "android:height",
    "width": "android:width",
    "viewportWidth": "android:viewportWidth",
    "viewportHeight": "android:viewportHeight",
    "autoMirrored": "android:autoMirrored",
    "alpha": "android:alpha",
    "fillColor": "android:fillColor",
    "fillAlpha": "android:fillAlpha",
    "strokeColor": "android:strokeColor",
    "strokeAlpha": "android:strokeAlpha",
    "strokeWidth": "android:strokeWidth",
    "strokeLineCap": "android:strokeLineCap",
    "strokeLineJoin": "android:strokeLineJoin",
    "strokeMiterLimit": "android:strokeMiterLimit",
    "pathData": "android:pathData",
    "fillType": "android:fillType",
    "trimPathStart": "android:trimPathStart",
    "trimPathEnd": "android:trimPathEnd",
    "trimPathOffset": "android:trimPathOffset",
    "name": "android:name",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def copy_raw(asset: RawAsset, source_root: Path, category: str) -> dict[str, str]:
    source = source_root / asset.filename
    if not source.exists():
        raise FileNotFoundError(source)
    destination = ASSETS / "original" / asset.folder / f"{asset.semantic}{asset.extension}"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return {
        "category": category,
        "semantic": asset.semantic,
        "source": str(source.relative_to(ROOT)),
        "destination": str(destination.relative_to(ROOT)),
        "sha256": sha256(destination),
    }


def parse_xmltree(filename: str) -> list[dict]:
    if not AAPT2.exists():
        raise FileNotFoundError(f"aapt2 not found: {AAPT2}")
    command = [str(AAPT2), "dump", "xmltree", str(APK), "--file", f"res/{filename}"]
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    # Keep the raw aapt2 projection for reproducibility.
    raw_dir = ASSETS / "original" / "icons" / "aapt2-xmltree"
    raw_dir.mkdir(parents=True, exist_ok=True)
    raw_text = result.stdout
    (raw_dir / f"{filename}.txt").write_text(raw_text, encoding="utf-8")

    stack: list[dict] = []
    roots: list[dict] = []
    for line in raw_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("E: "):
            indent = len(line) - len(line.lstrip())
            while stack and stack[-1]["indent"] >= indent:
                stack.pop()
            node = {"tag": stripped[3:].strip().split(" ", 1)[0], "attrs": [], "children": [], "indent": indent}
            if stack:
                stack[-1]["children"].append(node)
            else:
                roots.append(node)
            stack.append(node)
            continue
        if stripped.startswith("A: ") and stack:
            match = re.search(r"android:([A-Za-z0-9_]+)\([^)]*\)=(.*?)(?: \(Raw: (.*?)\))?$", stripped)
            if not match:
                continue
            name = match.group(1)
            value = match.group(3) if match.group(3) is not None else match.group(2)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] == '"':
                value = value[1:-1]
            stack[-1]["attrs"].append((name, value))
    return roots


def attr_value(attrs: list[tuple[str, str]], name: str, default: str | None = None) -> str | None:
    for key, value in attrs:
        if key == name:
            return value
    return default


def normalize_enum(name: str, value: str) -> str:
    if name == "fillType":
        return FILL_TYPE.get(value, value)
    if name == "strokeLineCap":
        return LINE_CAP.get(value, value)
    if name == "strokeLineJoin":
        return LINE_JOIN.get(value, value)
    return value


def make_android_xml(root: dict) -> str:
    attrs = root["attrs"]
    width = attr_value(attrs, "width", "24dp")
    height = attr_value(attrs, "height", "24dp")
    viewport_width = attr_value(attrs, "viewportWidth", "24")
    viewport_height = attr_value(attrs, "viewportHeight", "24")
    lines = ['<?xml version="1.0" encoding="utf-8"?>']
    lines.append(
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        f'android:width="{width}" android:height="{height}" '
        f'android:viewportWidth="{viewport_width}" android:viewportHeight="{viewport_height}"'
    )
    if attr_value(attrs, "autoMirrored"):
        lines[-1] += f' android:autoMirrored="{attr_value(attrs, "autoMirrored")}"'
    lines[-1] += ">"
    def render_child(child: dict, depth: int = 1) -> None:
        if child["tag"] not in {"group", "path", "clip-path"}:
            raise ValueError(f"unsupported vector element: {child['tag']}")
        path_attrs = []
        for name, value in child["attrs"]:
            xml_name = XML_ATTR.get(name)
            if not xml_name:
                raise ValueError(f"unsupported vector attribute: {name}")
            path_attrs.append((xml_name, normalize_enum(name, value)))
        rendered = " ".join(
            f'{name}="{html.escape(value, quote=True)}"' for name, value in path_attrs
        )
        prefix = "    " * depth
        if child["children"]:
            lines.append(f"{prefix}<{child['tag']} {rendered}>")
            for nested in child["children"]:
                render_child(nested, depth + 1)
            lines.append(f"{prefix}</{child['tag']}>")
        else:
            lines.append(f"{prefix}<{child['tag']} {rendered} />")

    for child in root["children"]:
        render_child(child)
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def make_svg(root: dict) -> str:
    attrs = root["attrs"]
    width = attr_value(attrs, "width", "24dp").removesuffix("dp")
    height = attr_value(attrs, "height", "24dp").removesuffix("dp")
    viewport_width = attr_value(attrs, "viewportWidth", "24")
    viewport_height = attr_value(attrs, "viewportHeight", "24")
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {viewport_width} {viewport_height}" fill="none">',
    ]
    clip_count = 0

    def svg_color(value: str) -> tuple[str, float]:
        if not re.fullmatch(r"#[0-9a-fA-F]{8}", value):
            raise ValueError(f"unsupported Android color: {value}")
        return f"#{value[3:]}", int(value[1:3], 16) / 255

    def render_children(children: list[dict], depth: int = 1) -> None:
        nonlocal clip_count
        clipped_groups = 0
        for child in children:
            prefix = "  " * depth
            tag = child["tag"]
            if tag == "clip-path":
                clip_count += 1
                clip_id = f"clip-{clip_count}"
                data = html.escape(attr_value(child["attrs"], "pathData", ""), quote=True)
                lines.append(f'{prefix}<defs><clipPath id="{clip_id}"><path d="{data}"/></clipPath></defs>')
                lines.append(f'{prefix}<g clip-path="url(#{clip_id})">')
                depth += 1
                clipped_groups += 1
                continue
            if tag == "group":
                if child["attrs"]:
                    raise ValueError("transformed groups need explicit SVG conversion")
                lines.append(f"{prefix}<g>")
                render_children(child["children"], depth + 1)
                lines.append(f"{prefix}</g>")
                continue
            if tag != "path":
                raise ValueError(f"unsupported SVG source element: {tag}")
            path_attrs = {"fill": "#000000"}
            for name, value in child["attrs"]:
                if name == "pathData":
                    path_attrs["d"] = value
                elif name in {"fillColor", "strokeColor"}:
                    target = "fill" if name == "fillColor" else "stroke"
                    color, alpha = svg_color(value)
                    path_attrs[target] = color
                    if alpha != 1:
                        path_attrs[f"{target}-opacity"] = str(alpha)
                elif name in {"fillAlpha", "strokeAlpha"}:
                    target = "fill-opacity" if name == "fillAlpha" else "stroke-opacity"
                    path_attrs[target] = str(float(path_attrs.get(target, "1")) * float(value))
                elif name == "strokeWidth":
                    path_attrs["stroke-width"] = value.removesuffix("dp")
                elif name == "strokeLineCap":
                    path_attrs["stroke-linecap"] = normalize_enum(name, value)
                elif name == "strokeLineJoin":
                    path_attrs["stroke-linejoin"] = normalize_enum(name, value)
                elif name == "fillType":
                    path_attrs["fill-rule"] = "evenodd" if normalize_enum(name, value) == "evenOdd" else "nonzero"
                else:
                    raise ValueError(f"unsupported SVG attribute: {name}")
            rendered = " ".join(f'{key}="{html.escape(value, quote=True)}"' for key, value in path_attrs.items())
            lines.append(f"{prefix}<path {rendered}/>")
        for _ in range(clipped_groups):
            depth -= 1
            lines.append(f"{'  ' * depth}</g>")

    render_children(root["children"])
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def write_imageset(semantic: str, svg_path: Path) -> Path:
    imageset = ASSETS / "derived" / "ios-assets" / f"{semantic}.imageset"
    imageset.mkdir(parents=True, exist_ok=True)
    shutil.copy2(svg_path, imageset / f"{semantic}.svg")
    contents = {
        "images": [
            {
                "filename": f"{semantic}.svg",
                "idiom": "universal",
            }
        ],
        "info": {"author": "xcode", "version": 1},
        "properties": {"preserves-vector-representation": True},
    }
    (imageset / "Contents.json").write_text(
        json.dumps(contents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return imageset


def main() -> int:
    if not APK.exists():
        print(f"missing APK: {APK}", file=sys.stderr)
        return 2
    apk_digest = sha256(APK)
    manifest: list[dict[str, str]] = []
    # Remove only previous script-owned names, after their current source exists.
    for old_name, source_name in [("fluid_blob_vert.vert", "fluid_blob.vert"), ("fluid_blob_frag.frag", "fluid_blob.frag")]:
        if (APK_EXTRACTED / "assets" / "shaders" / source_name).is_file():
            (ASSETS / "original" / "shaders" / old_name).unlink(missing_ok=True)

    for asset in LOTTIE:
        manifest.append(copy_raw(asset, APK_EXTRACTED / "res", "lottie"))
    for asset in AUDIO:
        manifest.append(copy_raw(asset, APK_EXTRACTED / "res", "audio"))
    for asset in SHADERS:
        manifest.append(copy_raw(asset, APK_EXTRACTED / "res", "shader"))
    for asset in SHADER_ASSETS:
        manifest.append(copy_raw(asset, APK_EXTRACTED / "assets" / "shaders", "shader"))

    playing_bars_source = APK_EXTRACTED / "assets" / "ic_playing_bars_24.json"
    playing_bars_destination = ASSETS / "original" / "lottie" / "playing_bars.json"
    playing_bars_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(playing_bars_source, playing_bars_destination)
    manifest.append(
        {
            "category": "lottie",
            "semantic": "playing_bars",
            "source": str(playing_bars_source.relative_to(ROOT)),
            "destination": str(playing_bars_destination.relative_to(ROOT)),
            "sha256": sha256(playing_bars_destination),
        }
    )

    android_dir = ASSETS / "derived" / "icons" / "android-vector"
    svg_dir = ASSETS / "derived" / "icons" / "svg"
    png_root = ASSETS / "original" / "icons" / "png"
    android_dir.mkdir(parents=True, exist_ok=True)
    svg_dir.mkdir(parents=True, exist_ok=True)
    for semantic, filename, png_names in VECTORS:
        roots = parse_xmltree(filename)
        if not roots or roots[0]["tag"] != "vector":
            raise ValueError(f"not a vector drawable: res/{filename}")
        compiled = ASSETS / "original" / "icons" / "compiled" / f"{semantic}.xml.bin"
        compiled.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(APK_EXTRACTED / "res" / filename, compiled)
        manifest.append({"category": "compiled-vector", "semantic": semantic, "source": f"res/{filename}", "destination": str(compiled.relative_to(ROOT)), "sha256": sha256(compiled)})
        projection = ASSETS / "original" / "icons" / "aapt2-xmltree" / f"{filename}.txt"
        manifest.append({"category": "aapt2-projection", "semantic": semantic, "source": f"res/{filename}", "destination": str(projection.relative_to(ROOT)), "sha256": sha256(projection)})
        android_xml = android_dir / f"{semantic}.xml"
        svg_xml = svg_dir / f"{semantic}.svg"
        android_xml.write_text(make_android_xml(roots[0]), encoding="utf-8")
        svg_xml.write_text(make_svg(roots[0]), encoding="utf-8")
        imageset = write_imageset(semantic, svg_xml)
        for output in sorted(imageset.iterdir()):
            manifest.append({"category": "ios-imageset", "semantic": semantic, "source": f"res/{filename}", "destination": str(output.relative_to(ROOT)), "sha256": sha256(output)})
        manifest.extend(
            [
                {
                    "category": "android-vector",
                    "semantic": semantic,
                    "source": f"res/{filename}",
                    "destination": str(android_xml.relative_to(ROOT)),
                    "sha256": sha256(android_xml),
                },
                {
                    "category": "svg",
                    "semantic": semantic,
                    "source": f"res/{filename}",
                    "destination": str(svg_xml.relative_to(ROOT)),
                    "sha256": sha256(svg_xml),
                },
            ]
        )
        for png_name in png_names:
            source = APK_EXTRACTED / "res" / png_name
            if not source.exists():
                continue
            destination = png_root / semantic / png_name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            manifest.append(
                {
                    "category": "png",
                    "semantic": semantic,
                    "source": str(source.relative_to(ROOT)),
                    "destination": str(destination.relative_to(ROOT)),
                    "sha256": sha256(destination),
                }
            )

    for row in manifest:
        source_path = row["source"]
        if source_path.startswith(".tmp/extracted/apk/"):
            source_path = source_path.removeprefix(".tmp/extracted/apk/")
        row["source"] = source_path
        row["source_sha256"] = sha256(APK_EXTRACTED / source_path)
        row["apk_sha256"] = apk_digest

    manifest_path = ASSETS / "MANIFEST.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    source_lines = [
        "# 素材来源与哈希",
        "",
        "本目录的素材由 `scripts/extract_assets.py` 从已分析的官方 APK 中提取。",
        "",
        "APK:",
        "",
        "```text",
        "package: com.deepseek.chat",
        "version: 2.8.0 (297)",
        f"sha256: {sha256(APK)}",
        "```",
        "",
        "## 资源与派生文件",
        "",
        "| 类别 | 语义 | 来源 | 目标 | SHA-256 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in manifest:
        source_lines.append(
            f"| {row['category']} | `{row['semantic']}` | `{row['source']}` | "
            f"`{row['destination']}` | `{row['sha256']}` |"
        )
    source_lines.extend(
        [
            "",
            "## 说明",
            "",
            "- `assets/original/lottie/`：直接复制的 Lottie JSON。",
            "- `assets/original/shaders/`：直接复制的 AGSL/GLSL 文件。",
            "- `assets/original/audio/`：直接复制的 m4a 资源。",
            "- `assets/original/icons/compiled/`：compiled XML 原始字节；仅归档，不能直接作为 Android 源 XML 使用。",
            "- `assets/original/icons/aapt2-xmltree/`：aapt2 对 compiled XML 的可读投影，仅作为还原证据。",
            "- `assets/derived/icons/android-vector/`：从 compiled XML 还原的 Android Vector。",
            "- `assets/derived/icons/svg/`：同源 SVG，用于 iOS Asset 或视觉比对。",
            "- `assets/derived/ios-assets/`：iOS `.imageset` 目录，Contents.json 已标记 preserve vector representation。",
            "- 官方素材的商标与版权归属原权利人；公开发布前必须单独确认使用许可。",
            "",
        ]
    )
    (ASSETS / "SOURCE.md").write_text("\n".join(source_lines), encoding="utf-8")
    print(f"wrote {len(manifest)} manifest entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
