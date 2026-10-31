#!/usr/bin/env python3
"""Build checked, reproducible delivery archives without changing source artwork.

Uses only Python's standard library. Requires a completed eight-card project,
the matching signed release APK, and Android SDK apksigner + Java. No build,
image generation, signing, or deletion of existing delivery contents occurs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

VERSION = "1.0.4"
APP_NAME = "映光卡匣"
EXPECTED_IDS = (
    "windowlight", "dusksea", "bluehour", "sunsettrain", "citywatch",
    "piratecat", "rainkitten", "silvermeadow",
)
LAYER_ROLES = ("subject", "background", "lineart", "text")
RENDERS = {"hero.png": "主视觉.png", "front.png": "正面.png",
           "tilt-left.png": "左倾.png", "tilt-right.png": "右倾.png"}
FORBIDDEN_PARTS = {
    "node_modules", "tools", "build", ".gradle", "generated-assets", ".git",
    "__pycache__", ".cache", "signing", "relief-fixture", "before-postprocess",
}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
SECRET_NAMES = {"local.properties", "signing.properties", "key.properties"}
SOURCE_PREFIX = "ruic-card-library/"


class ReleaseError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        raise ReleaseError(f"JSON 无法读取：{path}: {exc}") from exc


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def is_link(path: Path) -> bool:
    return path.is_symlink() or bool(getattr(path, "is_junction", lambda: False)())


def file_required(path: Path) -> Path:
    require(path.is_file() and not is_link(path), f"缺少普通文件或不允许链接：{path}")
    require(path.stat().st_size > 0, f"文件为空：{path}")
    return path


def source_files(folder: Path):
    """Prune dependency/cache trees before traversal, rather than after reading them."""
    for current, directories, files in os.walk(folder, followlinks=False):
        directories[:] = sorted(name for name in directories if name.casefold() not in FORBIDDEN_PARTS
                                and not name.casefold().startswith("native-qa-")
                                and name.casefold() != ".runtime")
        for name in directories:
            require(not is_link(Path(current) / name), f"源码目录不允许链接：{Path(current) / name}")
        for name in sorted(files):
            yield Path(current) / name


def safe_name(name: str) -> str:
    require(isinstance(name, str) and bool(name), "ZIP 条目名称为空")
    require("\\" not in name and "\x00" not in name and ":" not in name, f"非法 ZIP 路径：{name}")
    parts = PurePosixPath(name).parts
    require(bool(parts) and not name.startswith("/") and all(part not in (".", "..") for part in parts), f"越界 ZIP 路径：{name}")
    return name


def local_asset(base: Path, value: str) -> Path:
    require(isinstance(value, str) and value, f"缺少资源路径：{base}")
    parsed = urlsplit(value)
    require(not parsed.scheme and not parsed.netloc and not parsed.query and not parsed.fragment,
            f"资源必须为本地相对路径：{value}")
    relative = unquote(parsed.path)
    safe_name(relative[2:] if relative.startswith("./") else relative)
    candidate = (base / relative).resolve()
    require(candidate.is_relative_to(base.resolve()), f"资源越界：{value}")
    return file_required(candidate)


def png_info(path: Path) -> tuple[int, int, int]:
    with file_required(path).open("rb") as stream:
        header = stream.read(33)
    require(len(header) == 33 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[12:16] == b"IHDR",
            f"不是有效 PNG：{path}")
    width, height, _, color_type = struct.unpack(">IIBB", header[16:26])
    require(0 < width <= 8192 and 0 < height <= 8192 and width * height <= 32_000_000,
            f"PNG 尺寸不在导入范围：{path}")
    return width, height, color_type


def validate_glb(path: Path) -> dict:
    data = file_required(path).read_bytes()
    require(len(data) >= 20, f"GLB 不完整：{path}")
    magic, version, length = struct.unpack_from("<4sII", data)
    require(magic == b"glTF" and version == 2 and length == len(data), f"GLB 文件头错误：{path}")
    chunk_size, chunk_type = struct.unpack_from("<II", data, 12)
    require(chunk_type == 0x4E4F534A and 20 + chunk_size <= len(data), f"GLB 缺少 JSON：{path}")
    try:
        model = json.loads(data[20:20 + chunk_size].decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise ReleaseError(f"GLB JSON 错误：{path}") from exc
    for group in ("buffers", "images"):
        for item in model.get(group, []):
            uri = item.get("uri", "")
            require(not uri or uri.startswith("data:"), f"GLB 引用外部资源：{path}: {uri}")
    roles = {item["name"] for item in model.get("materials", []) if isinstance(item.get("name"), str)}
    require({"web_front", "web_edge", "web_back", "web_gold"} <= roles,
            f"GLB 不符合 RuiC 材质角色契约：{path}")
    require(bool(model.get("meshes")), f"GLB 没有真实网格：{path}")
    return {"bytes": len(data), "meshes": len(model["meshes"]), "materialRoles": sorted(roles)}


def clean_config(config: dict) -> dict:
    """Portable data only; retain RuiC's absent-sourceMode composite default."""
    require(isinstance(config, dict), "卡配置必须为 JSON 对象")
    keys = ("title", "subtitle", "description", "edition", "collection", "technique", "tagline",
            "sourceMode", "artworkFit", "safeArea", "appearance", "parameters")
    output = {key: config[key] for key in keys if key in config}
    if "sourceMode" in output:
        require(output["sourceMode"] in ("reference", "relief"), "卡配置 sourceMode 不受支持")
    # These nested containers may contain only data used by the trusted renderer.
    nested = {"safeArea": {"scale", "offset"}, "appearance": {"background", "finish"},
              "parameters": {"subjectScale", "subjectDepth", "backgroundDepth", "effectsScale", "effectsDepth", "foil"}}
    for key, allowed in nested.items():
        if key in output:
            require(isinstance(output[key], dict), f"配置 {key} 必须为对象")
            output[key] = {name: value for name, value in output[key].items() if name in allowed}
    json_bytes(output)  # rejects non-finite numbers before anything is written
    return output


def load_cards(project: Path) -> list[dict]:
    catalog = read_json(project / "cards/catalog.json")
    require(isinstance(catalog, list) and len(catalog) == 8, "目录必须完整包含 8 张卡；等待制卡完成后再打包")
    require(all(isinstance(card, dict) for card in catalog), "目录条目必须为对象")
    ids = [card.get("id") for card in catalog]
    require(len(set(ids)) == 8 and set(ids) == set(EXPECTED_IDS), "卡 ID 不完整或重复")
    titles = set()
    result = []
    for card in catalog:
        card_id, title = card["id"], card.get("title", "")
        require(isinstance(title, str) and re.search(r"[\u4e00-\u9fff]", title) is not None
                and not re.search(r'[<>:"/\\|?*\x00-\x1f]', title) and title.rstrip(". ") == title,
                f"卡名不能用作中文文件名：{title!r}")
        require(title.casefold() not in titles, f"卡名重复：{title}")
        titles.add(title.casefold())
        root = project / "cards" / card_id
        cover = file_required(root / "beautified.png")
        png_info(cover)
        require(local_asset(project / "cards", card.get("cover", "")) == cover.resolve(), f"目录封面不对应卡片：{card_id}")
        config_path = local_asset(project / "cards", card.get("configUrl", ""))
        require(config_path == (root / "web/card-config.json").resolve(), f"目录配置位置错误：{card_id}")
        config = read_json(config_path)
        portable = clean_config(config)
        require(portable.get("title") == title, f"目录与配置卡名不同：{card_id}")
        assets = config.get("assets", {})
        require(isinstance(assets, dict), f"配置缺少 assets：{card_id}")
        paths, portable_assets, size = {}, {}, None
        for role in (*LAYER_ROLES, "effects", "model"):
            if role == "effects" and role not in assets:
                continue
            path = local_asset(config_path.parent, assets.get(role, ""))
            require(path == (root / "web/assets" / ("card.glb" if role == "model" else f"{role}.png")).resolve(),
                    f"资源需使用导出的标准文件名：{card_id}/{role}")
            paths[role] = path
            portable_assets[role] = "./assets/" + path.name
            if role != "model":
                width, height, color_type = png_info(path)
                require(size is None or size == (width, height), f"图层画布不一致：{card_id}/{role}")
                size = width, height
                if role in ("subject", "text", "effects"):
                    require(color_type in (4, 6), f"透明图层必须带 alpha：{card_id}/{role}")
                source_layer = file_required(root / "assets" / path.name)
                require(sha256(source_layer) == sha256(path), f"源图层与 web 导出不同，请重新构建：{card_id}/{role}")
        portable["assets"] = portable_assets
        glb = validate_glb(paths["model"])
        blend = file_required(root / "card.blend")
        with blend.open("rb") as stream:
            require(stream.read(7) == b"BLENDER", f"不是未压缩的 Blender 工程：{blend}")
        renders = {name: file_required(root / "renders" / name) for name in RENDERS}
        for render in renders.values():
            png_info(render)
        result.append({"metadata": card, "root": root, "cover": cover, "config": portable,
                       "assets": paths, "blend": blend, "renders": renders, "glb": glb})
    return result


class RuntimeHTML(HTMLParser):
    def __init__(self):
        super().__init__()
        self.references = []
        self.inline_script = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and not attrs.get("src"):
            self.inline_script = True
        attribute = "href" if tag == "link" else "src"
        if tag in ("script", "link", "img", "iframe", "source", "video", "audio") and attrs.get(attribute):
            self.references.append(attrs[attribute])


def validate_runtime(project: Path, cards: list[dict]) -> dict:
    """Check executable entry graphs, rather than flagging URL strings in licenses."""
    pending = [project / "web/index.html", project / "web/viewer/index.html"]
    pending += [card["root"] / "web/index.html" for card in cards]
    visited = set()
    while pending:
        path = file_required(pending.pop()).resolve()
        if path in visited:
            continue
        visited.add(path)
        require(path.is_relative_to(project.resolve()), f"运行资源越界：{path}")
        text = path.read_text(encoding="utf-8-sig")
        refs = []
        if path.suffix == ".html":
            parser = RuntimeHTML()
            parser.feed(text)
            require(not parser.inline_script, f"运行页面存在内联脚本：{path}")
            refs = parser.references
        elif path.suffix in (".js", ".mjs"):
            refs = re.findall(r'\b(?:import|export)\s+(?:[^;\n]*?\s+from\s+)?[\'\"]([^\'\"]+)[\'\"]', text)
            refs += re.findall(r'\bimport\s*\(\s*[\'\"]([^\'\"]+)[\'\"]\s*\)', text)
            require(not re.search(r'\b(?:fetch|importScripts)\s*\(\s*[\'\"](?:https?:)?//', text),
                    f"运行脚本硬编码外网请求：{path}")
        elif path.suffix == ".css":
            refs = re.findall(r'url\(\s*[\'\"]?([^\'\")\s]+)', text)
            refs += re.findall(r'@import\s+[\'\"]([^\'\"]+)[\'\"]', text)
        for ref in refs:
            if ref.startswith("data:"):
                continue
            parsed = urlsplit(ref)
            require(not parsed.scheme and not parsed.netloc and not ref.startswith("//"), f"运行时外网依赖：{path}: {ref}")
            require(ref.startswith(("./", "../", "/")), f"运行时裸模块依赖，需重建 bundle：{path}: {ref}")
            target = (project / "web" / parsed.path.lstrip("/")) if ref.startswith("/") else path.parent / unquote(parsed.path)
            target = file_required(target.resolve())
            if target.suffix in (".html", ".js", ".mjs", ".css"):
                pending.append(target)
    manifest = (project / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    require("android.permission.INTERNET" not in manifest, "源码 AndroidManifest 不应申请网络权限")
    return {"offlineEntryGraphs": len(visited), "runtimeNetworkDependency": False}


def find_apksigner(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if path.suffix.lower() != ".jar":
            path = path.parent / "lib/apksigner.jar"
        return file_required(path)
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    roots += [str(Path.home() / "AppData/Local/Android/Sdk")]
    for root in filter(None, roots):
        jars = sorted((Path(root) / "build-tools").glob("*/lib/apksigner.jar"), reverse=True)
        if jars:
            return file_required(jars[0])
    raise ReleaseError("找不到 apksigner.jar；设置 ANDROID_HOME 或传 --apksigner")


def check_zip(archive: Path) -> list[str]:
    with ZipFile(archive) as zf:
        names = zf.namelist()
        require(len(names) == len(set(name.casefold() for name in names)), f"ZIP 重复条目：{archive}")
        for info in zf.infolist():
            safe_name(info.filename)
            require((info.external_attr >> 16) & 0o170000 != 0o120000, f"ZIP 不允许链接：{archive}")
        bad = zf.testzip()
        require(bad is None, f"ZIP CRC 校验失败：{archive}: {bad}")
    return names


def validate_apk(apk: Path, project: Path, cards: list[dict], apksigner: Path) -> dict:
    file_required(apk)
    names = check_zip(apk)
    require("AndroidManifest.xml" in names and "classes.dex" in names, "不是 Android APK")
    require(not any("node_modules" in PurePosixPath(name).parts for name in names), "APK 错误包含 node_modules")
    with ZipFile(apk) as zf:
        expected_catalog = read_json(project / "cards/catalog.json")
        require(json.loads(zf.read("assets/library/cards/catalog.json")) == expected_catalog,
                "APK 的目录索引不是当前八卡版本，请重新构建正式版")
        matched = 0
        for card in cards:
            card_id = card["metadata"]["id"]
            entries = {f"cards/{card_id}/beautified.png": card["cover"],
                       f"cards/{card_id}/web/card-config.json": card["root"] / "web/card-config.json"}
            entries.update({f"cards/{card_id}/web/assets/{path.name}": path for path in card["assets"].values()})
            for relative, source in entries.items():
                entry = "assets/library/" + relative
                require(entry in names and hashlib.sha256(zf.read(entry)).hexdigest() == sha256(source),
                        f"APK 资源缺失或未更新：{entry}")
                matched += 1
        # Verify every shipped main-app runtime file, including the relief visibility fix.
        for source in source_files(project / "web"):
            if not source.is_file() or source.suffix not in (".js", ".css", ".html"):
                continue
            relative = source.relative_to(project / "web")
            if "node_modules" in relative.parts or relative.parts[0] == "cards":
                continue
            entry = "assets/library/" + relative.as_posix()
            require(entry in names and hashlib.sha256(zf.read(entry)).hexdigest() == sha256(source),
                    f"APK 前端未同步：{entry}")
            matched += 1
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else None
    executable = str(java) if java and java.is_file() else shutil.which("java")
    require(bool(executable), "签名验证需要 Java；设置 JAVA_HOME 或将 java 加入 PATH")
    result = subprocess.run([executable, "-jar", str(apksigner), "verify", "--verbose", str(apk)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    require(result.returncode == 0, f"正式 APK 签名校验失败：{result.stdout}\n{result.stderr}")
    return {"sha256": sha256(apk), "signatureVerified": True, "matchedRuntimeFiles": matched,
            "apksigner": result.stdout.strip().splitlines()}


def zip_write(zf: ZipFile, name: str, value: Path | bytes) -> None:
    info = ZipInfo(safe_name(name), date_time=(1980, 1, 1, 0, 0, 0))
    info.create_system = 3
    info.external_attr = (0o100755 if name.endswith("/gradlew") else 0o100644) << 16
    info.compress_type = ZIP_DEFLATED
    info._compresslevel = 9
    if isinstance(value, bytes):
        zf.writestr(info, value)
    else:
        file_required(value)
        with value.open("rb") as source, zf.open(info, "w", force_zip64=True) as target:
            shutil.copyfileobj(source, target, length=1024 * 1024)


def build_ruic(card: dict, target: Path) -> dict:
    metadata = {key: card["metadata"].get(key, "") for key in ("id", "title", "subtitle", "description", "edition")}
    metadata.update({"kind": "layered", "source": "imported", "cover": "./cover.png", "configUrl": "./card-config.json"})
    entries = {"card.json": json_bytes(metadata), "card-config.json": json_bytes(card["config"]), "cover.png": card["cover"]}
    entries.update({"assets/" + path.name: path for path in card["assets"].values()})
    with ZipFile(target, "w", compression=ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            zip_write(zf, name, entries[name])
    names = check_zip(target)
    require(set(names) == set(entries), f"导入包结构不符：{target}")
    require(all(Path(name).suffix.lower() in (".json", ".png", ".glb") for name in names), f"导入包包含代码：{target}")
    with ZipFile(target) as zf:
        config = json.loads(zf.read("card-config.json"))
        require("font" not in config, f"导入包残留机器字体：{target}")
        for role, value in config["assets"].items():
            require(value.removeprefix("./") in names, f"导入资源不存在：{target}/{role}")
        require(json.loads(zf.read("card.json"))["cover"].removeprefix("./") in names, f"导入封面缺失：{target}")
    return {"id": metadata["id"], "title": metadata["title"], "entries": len(names),
            "crcVerified": True, "noExecutableImport": True, "glb": card["glb"]}


def source_allowed(relative: Path) -> bool:
    parts = {part.casefold() for part in relative.parts}
    return (not (parts & FORBIDDEN_PARTS) and not any(part.startswith("native-qa-") or part == ".runtime" for part in parts)
            and relative.name.casefold() not in SECRET_NAMES
            and not relative.name.casefold().startswith(".env")
            and relative.suffix.lower() not in SECRET_SUFFIXES | {".pyc", ".blend1", ".apk", ".aab", ".log", ".class"})


def source_entries(project: Path, cards: list[dict]) -> dict[str, Path | bytes]:
    entries: dict[str, Path | bytes] = {}
    for name in ("README.md", "PLAN.md", ".gitignore", "artwork-provenance.json"):
        entries[name] = file_required(project / name)
    for folder in ("web", "android", "scripts"):
        for source in source_files(project / folder):
            relative = source.relative_to(project)
            if source.is_file() and source_allowed(relative):
                if folder == "web" and relative.parts[1:2] == ("cards",):
                    continue
                if folder == "android" and "verification" in relative.parts:
                    continue
                require(not is_link(source), f"源码不允许链接：{source}")
                require(source.suffix != ".jar" or relative.as_posix() == "android/gradle/wrapper/gradle-wrapper.jar",
                        f"源码不应包含其他二进制 JAR：{source}")
                if source.name == "card-config.json":
                    portable = read_json(source)
                    require(isinstance(portable, dict), f"配置必须为对象：{source}")
                    portable.pop("font", None)
                    entries[relative.as_posix()] = json_bytes(portable)
                else:
                    entries[relative.as_posix()] = source
    entries["cards/catalog.json"] = project / "cards/catalog.json"
    for card in cards:
        prefix = f"cards/{card['metadata']['id']}/"
        entries[prefix + "beautified.png"] = card["cover"]
        entries[prefix + "card.blend"] = card["blend"]
        entries[prefix + "card-config.json"] = json_bytes(card["config"])
        for role, path in card["assets"].items():
            # Include the portable GLB beside source layers as well as the runtime copy.
            entries[prefix + "assets/" + path.name] = path
        for name, path in card["renders"].items():
            entries[prefix + "renders/" + name] = path
        for source in source_files(card["root"] / "web"):
            relative = source.relative_to(project)
            if source.is_file() and source_allowed(relative):
                entries[relative.as_posix()] = json_bytes(card["config"]) if source.name == "card-config.json" else source
        for name in ("asset-validation.json", "geometry-validation.json", "blender-postprocess.json", "verification.json"):
            path = card["root"] / name
            if path.is_file():
                entries[prefix + name] = path
    # Keep compact evidence; generated screenshots, diagnostic scenes and fixtures stay out.
    evidence = ("verification/card-builds.json", "verification/card-visual-review.json",
                "verification/card-library-audit.json", "android/verification/release-report.json",
                "android/verification/native-api-report.json", "android/verification/packaged-import-report.json")
    for relative in evidence:
        path = project / relative
        if path.is_file():
            entries[relative] = path
    for path in sorted((project / "verification/frontend").glob("*-report.json")):
        entries[path.relative_to(project).as_posix()] = path
    for path in sorted((project / "verification/frontend").glob("verify-*.mjs")):
        entries[path.relative_to(project).as_posix()] = path
    for version in ("1.0.2", "1.0.3", VERSION):
        for pattern in ("*-report.json", "*-check.json", "*.mjs", "README.md"):
            for path in sorted((project / f"verification/frontend/{version}").glob(pattern)):
                entries[path.relative_to(project).as_posix()] = path
    # The visual comparison uses a fixed historical renderer, not Git commands
    # or files from a user's previous installation. Keep its allowlist explicit.
    for name in ("app.bundle.js", "app.js", "image-card.js", "README.md"):
        path = project / "verification/frontend/1.0.2/baseline" / name
        if path.is_file():
            entries[path.relative_to(project).as_posix()] = path
    for version in ("1.0.1", "1.0.2", "1.0.3", VERSION):
        for pattern in ("*-report.json", "*.mjs", "*.ps1", "packaged-web-hashes.json", "apk-metadata.txt", "apk-signature.txt", "README.md"):
            for path in sorted((project / f"android/verification/{version}").glob(pattern)):
                entries[path.relative_to(project).as_posix()] = path
    return entries


def build_source_zip(project: Path, cards: list[dict], target: Path) -> dict:
    entries = source_entries(project, cards)
    for required in ("web/viewer/app.bundle.js", "web/viewer/LICENSE-RuiC.txt", "web/viewer/LICENSE-Three.txt",
                     "web/viewer/LICENSE-Lucide.txt", "android/gradlew", "android/gradlew.bat",
                     "android/gradle/wrapper/gradle-wrapper.jar", "scripts/package-release.py"):
        require(required in entries, f"源码包缺少必要文件：{required}")
    with ZipFile(target, "w", compression=ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            require(source_allowed(Path(name)), f"源码包包含不允许文件：{name}")
            zip_write(zf, SOURCE_PREFIX + name, entries[name])
    names = check_zip(target)
    with ZipFile(target) as zf:
        require(len(json.loads(zf.read(SOURCE_PREFIX + "cards/catalog.json"))) == 8, "源码包目录数量错误")
        for card in cards:
            prefix = SOURCE_PREFIX + f"cards/{card['metadata']['id']}/"
            for subdir in ("", "web/"):
                config = json.loads(zf.read(prefix + subdir + "card-config.json"))
                require("font" not in config, "源码制卡配置残留机器字体")
                for value in config["assets"].values():
                    require(prefix + subdir + value.removeprefix("./") in names, f"源码卡配置资源缺失：{value}")
    return {"entries": len(names), "crcVerified": True, "noPrivateSigningFiles": True,
            "deterministicZipMetadata": True, "cardCount": 8}


def instructions(cards: list[dict]) -> str:
    titles = "、".join(card["metadata"]["title"] for card in cards)
    return f"""映光卡匣 {VERSION} 使用说明

一、安装
把“{APP_NAME}-{VERSION}.apk”复制到安卓平板，打开文件并按系统提示允许当前文件来源安装，然后启动“映光卡匣”。支持 Android 8.0 及以上；内置八张卡，无需联网或账号。
已安装 1.0.0、1.0.1 或 1.0.2 的设备直接覆盖安装，保留原有卡库、收藏和参数；不要先卸载。
本版为荣耀 MagicPad Pro 3 13.3 寸的大屏横竖布局准备，已进行浏览器与安卓模拟器验证；真实荣耀设备的 GPU、触控和系统文件选择器仍需真机验收。

二、浏览和微调
主页采用云白相册风格，平铺全部卡片，可搜索、排序和收藏。网格按钮切换标准与紧凑布局。轻点卡片直接全屏预览，点“返回卡库”回到刚才的浏览位置。
“光影参数”默认折叠，每次打开或切换卡片都会收起。点击展开后调整光泽、比例、景深和材质；平板在侧边显示，小屏在底部显示。安卓返回键先关闭设置、对话框或参数面板，再返回卡库。
拖动卡片旋转，人物与背景产生相对视差，镭射彩虹与高光跟随观看角度移动；停止拖动且关闭自动转动时光效静止。支持自动转动与翻面。沿用 1.0.2 的层间错位、彩虹色带和反射高光；“原画”材质保留无镭射的观赏方式。
右上角齿轮打开“观赏设置”，拖动旋转灵敏度可调 0.25–2.00 倍，适用于全部卡片，自动保存并随备份恢复。恢复单张卡的默认参数不会重置全局灵敏度。
光泽、比例、景深和材质逐卡自动保存，重启后恢复。“恢复默认”恢复当前卡配置。
“分层展示”展开背景、人物、线稿、文字（有特效时包含特效）的真实三维图层；拖动旋转，调整“层间距”，再点“合回卡片”。切卡或恢复默认会合回。

三、导入
“导入闪卡”可多选普通 PNG/JPEG/WebP 图片或本目录 02-可导入闪卡 中的 .ruic 文件。八张内置卡无需再次导入；单卡包重复导入会产生独立副本。
普通图片是“单图光泽”，不自动产生独立图层。四张真实图层与 GLB 齐全的卡包才支持分层展示。导入包只接收数据、图片和模型，不执行包内代码。
导入卡可重命名或删除，删除会先确认。原始图片文件不受影响。

四、备份和恢复
打开右上角齿轮设置，再点“备份卡匣与参数”，安卓端会把导入卡资源及收藏、名称、微调参数、全局观赏设置保存为 ZIP；通过“导入卡片”选择该备份 ZIP 恢复。同 ID 已有资源会跳过，状态会恢复；内置资源随 APK 提供。
卸载会清空应用私有卡库。换设备或卸载前先备份，并把 ZIP 另存到安全位置。
电脑浏览器的 JSON 备份只含状态和参数，不含图片资源；两种备份不要混淆。

五、交付文件
01-美化图片：{titles}，共八张，原图未改动。
02-可导入闪卡：八个 .ruic，每包含配置、封面、四层及 GLB。
03-Blender工程：八个可编辑 .blend 与主视觉、正面、两侧倾斜预览。
完整源码与制卡工程.zip：网页本地 bundle、许可证、Android 源码与 Gradle wrapper、制卡脚本、图层、模型、工程及预览；不含开发依赖、构建缓存或签名私钥。
SHA256SUMS.txt：所有本次交付文件的 SHA-256；交付核验.json 记录静态结构、资源与签名检查。未知的已有目录内容不会被打包脚本删除。

六、电脑查看与修改
解压源码包，安装 Node.js 后，在 ruic-card-library 根目录运行 node scripts/dev-server.mjs，然后打开 http://127.0.0.1:4173/。
重新制卡需要 RuiC Card Skill、Blender、Python + Pillow；重新构建安卓需要 JDK 17、Android SDK 35。完整命令见源码 README.md。
Blender 节点材质在浏览器中由 GLSL 重建，两者视觉接近而非逐像素相同；无 WebGL 时 CSS3D 降级的质感会不同。许可证见源码 web/viewer/LICENSE-*.txt。
"""


def publish_files(staging: Path, output: Path) -> None:
    """Replace only our named files; never recursively remove existing output."""
    ancestors = [output, *output.parents]
    require(not any(is_link(path) for path in ancestors if path.exists()), "交付路径不允许符号链接或 junction")
    output.mkdir(parents=True, exist_ok=True)
    staged = sorted(path for path in staging.rglob("*") if path.is_file())
    # Preflight every destination before publishing the first file.
    for source in staged:
        target = output / source.relative_to(staging)
        require(not target.exists() or target.is_file(), f"交付文件名与已有目录冲突：{target}")
        require(not any(is_link(path) for path in [target, *target.parents] if path.exists()), f"交付目标不允许链接：{target}")
    for source in staged:
        target = output / source.relative_to(staging)
        target.parent.mkdir(parents=True, exist_ok=True)
        os.replace(source, target)


def main() -> int:
    parser = argparse.ArgumentParser(description="检查完整八卡与最终签名 APK，生成映光卡匣交付包")
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path, help="默认 android/app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output", type=Path, default=Path.home() / "Desktop/cc产品" / f"{APP_NAME}-{VERSION}")
    parser.add_argument("--apksigner", help="SDK apksigner.jar 或 apksigner.bat 路径；缺省自动发现")
    args = parser.parse_args()
    project, output = args.project.expanduser().resolve(), args.output.expanduser().absolute()
    require(not output.resolve().is_relative_to(project), "交付目录必须位于源码目录之外")
    apk = (args.apk or project / "android/app/build/outputs/apk/release/app-release.apk").expanduser().resolve()
    print("检查八卡资源、离线入口、正式 APK 签名与资源一致性…", flush=True)
    cards = load_cards(project)
    runtime = validate_runtime(project, cards)
    apk_report = validate_apk(apk, project, cards, find_apksigner(args.apksigner))
    require(not any(is_link(path) for path in [output, *output.parents] if path.exists()), "交付路径不允许符号链接或 junction")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ruic-release-", dir=output.parent) as temporary:
        staging = Path(temporary)
        require(staging.resolve().parent == output.parent.resolve() and staging.name.startswith(".ruic-release-"),
                "临时目录未位于明确的交付父目录内")
        shutil.copyfile(apk, staging / f"{APP_NAME}-{VERSION}.apk")
        card_reports = []
        for card in cards:
            title = card["metadata"]["title"]
            image_dir, ruic_dir, blend_dir = staging / "01-美化图片", staging / "02-可导入闪卡", staging / "03-Blender工程" / title
            for folder in (image_dir, ruic_dir, blend_dir):
                folder.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(card["cover"], image_dir / f"{title}.png")
            card_reports.append(build_ruic(card, ruic_dir / f"{title}.ruic"))
            shutil.copyfile(card["blend"], blend_dir / f"{title}.blend")
            for source_name, target_name in RENDERS.items():
                shutil.copyfile(card["renders"][source_name], blend_dir / target_name)
        source_report = build_source_zip(project, cards, staging / "完整源码与制卡工程.zip")
        (staging / "使用说明.txt").write_text(instructions(cards), encoding="utf-8-sig", newline="\n")
        report = {"application": APP_NAME, "version": VERSION, "cardCount": len(cards),
                  "beautifiedCount": len(list((staging / "01-美化图片").glob("*.png"))),
                  "importPackageCount": len(card_reports), "editableBlendCount": len(list((staging / "03-Blender工程").rglob("*.blend"))),
                  "apk": apk_report, "cards": card_reports, "sourceArchive": source_report, "runtime": runtime,
                  "scope": "本报告检查交付结构、资源路径、ZIP CRC、签名和本地运行入口；不代替图像审美与真机操作验收。",
                  "targetDeviceVerified": False}
        require(all(report[key] == 8 for key in ("cardCount", "beautifiedCount", "importPackageCount", "editableBlendCount")), "交付数量必须全部为 8")
        (staging / "交付核验.json").write_bytes(json_bytes(report))
        manifest = {path.relative_to(staging).as_posix(): sha256(path) for path in sorted(staging.rglob("*")) if path.is_file()}
        (staging / "SHA256SUMS.txt").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(manifest.items())), encoding="utf-8", newline="\n")
        publish_files(staging, output)
        for name, digest in manifest.items():
            require(sha256(output / name) == digest, f"发布后 SHA-256 不匹配：{name}")
    print(f"交付完成：{output}\n8 张美化图 / 8 个 .ruic / 8 套 Blender 工程；SHA256SUMS.txt 已核验。")
    return 0


if __name__ == "__main__":
    # Windows launcher consoles may default to an encoding without Chinese glyphs.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        print(f"打包失败：{error}", file=sys.stderr)
        raise SystemExit(1)
