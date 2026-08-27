"""Measure ItemForge's real Java API floor instead of trusting a manifest claim.

Hytale only warns when ``ServerVersion`` disagrees and may still load the plugin.  That makes a
hand-written compatibility range a label, not a release gate.  This tool exists to inspect the
built shadow jar, recover every static link into ``com.hypixel.hytale`` from its JVMS chapter 4
constant pools, add the engine members ItemForge reaches by reflection, and resolve those links
against real server jars while following superclasses and interfaces.

The failure this prevents is a confident compatibility range unsupported by the artifact that
will actually ship.  Its boundary is equally important: bytecode linkage says nothing about asset
schema shape, codec structure, the client UI protocol, or runtime behaviour.  A pass proves only
that the measured Java symbols resolve on the tested jars.  ``--write-range`` makes carrying that
measurement into Gradle mechanical, but requires an explicit play-test assertion before it can do
so.
"""
from __future__ import annotations

import argparse
import functools
import json
import os
from pathlib import Path
import re
import struct
import sys
from dataclasses import dataclass
from typing import Iterable
import zipfile


ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(__file__).resolve().parent
PROPERTIES_FILE = ROOT / "gradle.properties"
SETTINGS_FILE = ROOT / "settings.gradle.kts"
PROJECT_NAME = re.compile(
    r"^\s*rootProject\.name\s*=\s*(['\"])([^'\"]+)\1\s*$", re.MULTILINE
)
ENGINE_VERSION = re.compile(
    r"^(\d+(?:\.\d+)*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
INSTALLED_JAR_PARTS = (
    "Hytale",
    "install",
    "release",
    "package",
    "game",
    "latest",
    "Server",
    "HytaleServer.jar",
)

# These are the default historical control points expected beside ItemForge.  Unlike the Armory's
# hand-compiled four-jar control, ItemForge has no independent compilation measurement to assert
# that every one must pass.  The synthetic positive/negative controls below test the resolver; this
# set tests that the intended default evidence was actually present.  Most importantly, both are
# scoped by is_our_class(): a miss owned solely by shaded code is artifact evidence, not evidence
# that ItemForge's own source failed its control.
EXPECTED_CONTROL_VERSIONS = {"0.5.0", "0.5.3", "0.5.7", "0.5.8", "0.5.9"}

DEFAULT_ENGINE_JARS = tuple(
    ROOT.parent / "APIReference" / "downloads" / f"Server-{version}.jar"
    for version in ("0.5.0", "0.5.3", "0.5.7", "0.5.8", "0.5.9")
)

# Found by running this grep from the repository root:
# rg -n 'getDeclaredField|getDeclaredMethod|getField|getMethod|Class\.forName|setAccessible' src/main/kotlin
# Re-derive the entire list whenever ItemForge's Kotlin reflection changes.  The reflected Vuetale
# internals v8Runtime, v8Executor, page, app, isMounted, and getPlayerRef$Vuetale are deliberately
# excluded: they are not com.hypixel.hytale engine symbols.
REFLECTED_MEMBERS = [
    (
        "com/hypixel/hytale/codec/validation/validator/RangeValidator",
        "min",
        "field",
    ),
    (
        "com/hypixel/hytale/codec/validation/validator/RangeValidator",
        "max",
        "field",
    ),
    (
        "com/hypixel/hytale/server/core/modules/entitystats/asset/EntityStatType",
        "hideFromTooltip",
        "field",
    ),
    (
        "com/hypixel/hytale/server/core/asset/type/item/config/Item",
        "cachedPacket",
        "field",
    ),
]

LIMITATIONS = (
    "asset schema shape and identifiers",
    "codec structural assumptions",
    "the client UI protocol",
    "runtime behaviour, including changed semantics behind an unchanged signature",
)


class ClassFormatError(ValueError):
    """A class file did not satisfy the portion of JVMS chapter 4 consumed here."""


@dataclass(frozen=True)
class Reference:
    kind: str
    owner: str
    name: str | None = None
    descriptor: str | None = None


@dataclass
class ClassInfo:
    name: str
    super_name: str | None
    interfaces: tuple[str, ...]
    fields: dict[str, set[str]]
    methods: dict[str, set[str]]


@dataclass(frozen=True)
class Resolution:
    status: str
    found: tuple[tuple[str, str], ...] = ()


class Reader:
    """Bounds-checked big-endian reads keep corrupt inputs from looking authoritative."""

    def __init__(self, data: bytes, source: str):
        self.data = data
        self.source = source
        self.offset = 0

    def take(self, size: int) -> bytes:
        end = self.offset + size
        if end > len(self.data):
            raise ClassFormatError(
                f"{self.source}: truncated at byte {self.offset}, needed {size} more bytes"
            )
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


def modified_utf8(raw: bytes, source: str) -> str:
    try:
        return raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")
    except UnicodeDecodeError as exc:
        raise ClassFormatError(f"{source}: invalid modified UTF-8: {exc}") from exc


def skip_attributes(reader: Reader) -> None:
    for _ in range(reader.u2()):
        reader.u2()
        reader.take(reader.u4())


def parse_class(data: bytes, source: str) -> tuple[ClassInfo, set[Reference]]:
    """Parse constants and declarations directly instead of scraping javap output."""
    reader = Reader(data, source)
    if reader.u4() != 0xCAFEBABE:
        raise ClassFormatError(f"{source}: not a class file (bad magic)")
    reader.u2()  # minor
    reader.u2()  # major

    count = reader.u2()
    pool: list[tuple | None] = [None] * count
    index = 1
    while index < count:
        tag = reader.u1()
        if tag == 1:
            pool[index] = (tag, modified_utf8(reader.take(reader.u2()), source))
        elif tag in (3, 4):
            pool[index] = (tag, reader.take(4))
        elif tag in (5, 6):
            pool[index] = (tag, reader.take(8))
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, reader.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, reader.u2(), reader.u2())
        elif tag == 15:
            pool[index] = (tag, reader.u1(), reader.u2())
        else:
            raise ClassFormatError(
                f"{source}: unsupported constant-pool tag {tag} at index {index}"
            )
        index += 1

    def entry(at: int, expected_tag: int) -> tuple:
        actual = pool[at] if 0 < at < len(pool) else None
        if actual is None or actual[0] != expected_tag:
            raise ClassFormatError(
                f"{source}: constant-pool entry {at} should have tag "
                f"{expected_tag}, found {actual}"
            )
        return actual

    def utf8(at: int) -> str:
        return entry(at, 1)[1]

    def class_name(at: int) -> str:
        return utf8(entry(at, 7)[1])

    def name_and_type(at: int) -> tuple[str, str]:
        item = entry(at, 12)
        return utf8(item[1]), utf8(item[2])

    references: set[Reference] = set()
    for pool_index, item in enumerate(pool[1:], start=1):
        if item is None:
            continue
        if item[0] == 7:
            target = class_name(pool_index)
            if target.startswith("com/hypixel/hytale/"):
                references.add(Reference("class", target))
        elif item[0] in (9, 10, 11):
            target = class_name(item[1])
            if not target.startswith("com/hypixel/hytale/"):
                continue
            name, descriptor = name_and_type(item[2])
            kind = {9: "field", 10: "method", 11: "interface-method"}[item[0]]
            references.add(Reference(kind, target, name, descriptor))

    reader.u2()  # access_flags
    this_name = class_name(reader.u2())
    super_index = reader.u2()
    super_name = class_name(super_index) if super_index else None
    interfaces = tuple(class_name(reader.u2()) for _ in range(reader.u2()))

    fields: dict[str, set[str]] = {}
    for _ in range(reader.u2()):
        reader.u2()
        name = utf8(reader.u2())
        descriptor = utf8(reader.u2())
        fields.setdefault(name, set()).add(descriptor)
        skip_attributes(reader)

    methods: dict[str, set[str]] = {}
    for _ in range(reader.u2()):
        reader.u2()
        name = utf8(reader.u2())
        descriptor = utf8(reader.u2())
        methods.setdefault(name, set()).add(descriptor)
        skip_attributes(reader)
    skip_attributes(reader)

    if reader.offset != len(data):
        raise ClassFormatError(
            f"{source}: {len(data) - reader.offset} unexplained bytes remain"
        )
    return ClassInfo(this_name, super_name, interfaces, fields, methods), references


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
            line = raw_line.strip()
            if not line or line.startswith(("#", "!")):
                continue
            key, separator, value = line.partition("=")
            if separator:
                result[key.strip()] = value.strip()
    except (OSError, UnicodeError) as exc:
        raise RuntimeError(f"{path.relative_to(ROOT)}: unreadable, {exc}") from exc
    return result


def project_coordinates() -> tuple[str, str]:
    version = properties(PROPERTIES_FILE).get("projectVersion")
    if not version:
        raise RuntimeError("gradle.properties: projectVersion is missing or empty")
    try:
        settings = SETTINGS_FILE.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise RuntimeError(f"settings.gradle.kts: unreadable, {exc}") from exc
    match = PROJECT_NAME.search(settings)
    if not match:
        raise RuntimeError(
            "settings.gradle.kts: no rootProject.name = \"...\" assignment was found"
        )
    return match.group(2), version


def manifest_attributes(path: Path) -> tuple[dict[str, str] | None, str | None]:
    try:
        with zipfile.ZipFile(path) as jar:
            raw = jar.read("META-INF/MANIFEST.MF")
        text = raw.decode("utf-8-sig")
    except OSError as exc:
        return None, f"cannot open jar, {exc}"
    except zipfile.BadZipFile as exc:
        return None, f"not a readable jar, {exc}"
    except KeyError:
        return None, "META-INF/MANIFEST.MF is missing"
    except UnicodeError as exc:
        return None, f"META-INF/MANIFEST.MF is unreadable, {exc}"

    logical: list[str] = []
    for line in text.splitlines():
        if line.startswith(" ") and logical:
            logical[-1] += line[1:]
        else:
            logical.append(line)
    attributes: dict[str, str] = {}
    for line in logical:
        key, separator, value = line.partition(":")
        if separator:
            attributes[key.casefold()] = value.strip()
    return attributes, None


def parsed_engine_version(value: str):
    match = ENGINE_VERSION.fullmatch(value)
    if not match:
        return None
    core = tuple(int(component) for component in match.group(1).split("."))
    suffix = None if match.group(2) is None else tuple(match.group(2).split("."))
    return core, suffix


def compare_engine_versions(left: str, right: str) -> int:
    left_parsed = parsed_engine_version(left)
    right_parsed = parsed_engine_version(right)
    if left_parsed is None or right_parsed is None:
        raise ValueError(f"cannot compare engine versions {left!r} and {right!r}")
    left_core, left_suffix = left_parsed
    right_core, right_suffix = right_parsed
    width = max(len(left_core), len(right_core))
    left_core += (0,) * (width - len(left_core))
    right_core += (0,) * (width - len(right_core))
    if left_core != right_core:
        return -1 if left_core < right_core else 1
    if left_suffix is None or right_suffix is None:
        if left_suffix is right_suffix:
            return 0
        return 1 if left_suffix is None else -1
    for left_part, right_part in zip(left_suffix, right_suffix):
        if left_part == right_part:
            continue
        left_numeric = left_part.isdigit()
        right_numeric = right_part.isdigit()
        if left_numeric and right_numeric:
            return -1 if int(left_part) < int(right_part) else 1
        if left_numeric != right_numeric:
            return -1 if left_numeric else 1
        return -1 if left_part < right_part else 1
    if len(left_suffix) == len(right_suffix):
        return 0
    return -1 if len(left_suffix) < len(right_suffix) else 1


def is_our_class(class_name: str) -> bool:
    """Apply ItemForge's source ownership boundary to an artifact class name."""
    return (
        class_name.startswith("me/itemforge/")
        or class_name.startswith("li/kelp/vuetale/hytale/VuetaleUIPage")
        or class_name.startswith("li/kelp/vuetale/hytale/VuetaleEventData")
    )


def display_origin(class_name: str) -> str:
    return class_name.replace("/", ".")


def artifact_references(path: Path) -> tuple[dict[Reference, set[str]], int]:
    origins: dict[Reference, set[str]] = {}
    class_count = 0
    try:
        with zipfile.ZipFile(path) as jar:
            for item in jar.infolist():
                if not item.filename.endswith(".class"):
                    continue
                info, references = parse_class(
                    jar.read(item), f"{path}!/{item.filename}"
                )
                class_count += 1
                for reference in references:
                    origins.setdefault(reference, set()).add(info.name)
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise RuntimeError(f"cannot analyse artifact {path}: {exc}") from exc
    return origins, class_count


def jdk_release(path: Path) -> int | None:
    release_file = path.parent.parent / "release"
    try:
        text = release_file.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return None
    match = re.search(r'^JAVA_VERSION="(\d+)', text, re.MULTILINE)
    return int(match.group(1)) if match else None


def find_ct_sym(java_release: int) -> Path:
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "lib" / "ct.sym")
    candidates.extend((Path.home() / ".gradle" / "jdks").glob("*/lib/ct.sym"))
    for parent in (Path(r"C:\Program Files\Eclipse Adoptium"), Path(r"C:\Program Files\Java")):
        if parent.is_dir():
            candidates.extend(parent.glob("*/lib/ct.sym"))
    for candidate in candidates:
        if candidate.is_file() and jdk_release(candidate) == java_release:
            return candidate
    raise RuntimeError(
        f"no Java {java_release} lib/ct.sym was found in JAVA_HOME, Gradle toolchains, "
        "or standard Windows JDK locations"
    )


class PlatformClasses:
    """Read the matching JDK signature archive when an engine hierarchy enters Java."""

    def __init__(self, java_release: int):
        self.java_release = java_release
        self.path = find_ct_sym(java_release)
        self.jar = zipfile.ZipFile(self.path)
        self.cache: dict[str, ClassInfo | None] = {}
        release_code = (
            str(java_release)
            if java_release < 10
            else chr(ord("A") + java_release - 10)
        )
        self.entries: dict[str, str] = {}
        for entry in self.jar.infolist():
            parts = entry.filename.split("/", 2)
            if (
                len(parts) == 3
                and release_code in parts[0]
                and parts[2].endswith(".sig")
            ):
                self.entries[parts[2][:-4]] = entry.filename

    def close(self) -> None:
        self.jar.close()

    def class_info(self, name: str) -> ClassInfo | None:
        if name in self.cache:
            return self.cache[name]
        entry = self.entries.get(name)
        if entry is None:
            self.cache[name] = None
            return None
        info, _ = parse_class(self.jar.read(entry), f"{self.path}!/{entry}")
        self.cache[name] = info
        return info


class EngineJar:
    """Read hierarchy nodes lazily instead of parsing an entire server jar up front."""

    def __init__(self, path: Path, version: str, platform: PlatformClasses):
        self.path = path
        self.version = version
        self.platform = platform
        self.jar = zipfile.ZipFile(path)
        self.cache: dict[str, ClassInfo | None] = {}

    def close(self) -> None:
        self.jar.close()

    def class_info(self, name: str) -> ClassInfo | None:
        if name in self.cache:
            return self.cache[name]
        try:
            raw = self.jar.read(name + ".class")
        except KeyError:
            info = self.platform.class_info(name)
            self.cache[name] = info
            return info
        info, _ = parse_class(raw, f"{self.path}!/{name}.class")
        self.cache[name] = info
        return info

    def resolve(self, reference: Reference) -> Resolution:
        if reference.kind == "class":
            return Resolution("resolved" if self.class_info(reference.owner) else "absent")
        if reference.kind.startswith("reflected-"):
            return self.resolve_reflected(reference)

        member_kind = "field" if reference.kind == "field" else "method"
        lineage: list[ClassInfo] = []
        seen_classes: set[str] = set()
        current: str | None = reference.owner
        while current and current not in seen_classes:
            seen_classes.add(current)
            info = self.class_info(current)
            if info is None:
                break
            lineage.append(info)
            current = info.super_name

        matches: list[tuple[str, str]] = []

        def inspect(info: ClassInfo) -> bool:
            table = info.fields if member_kind == "field" else info.methods
            descriptors = table.get(reference.name or "", set())
            for descriptor in sorted(descriptors):
                if descriptor == reference.descriptor:
                    return True
                matches.append((info.name, descriptor))
            return False

        for info in lineage:
            if inspect(info):
                return Resolution("resolved")

        seen_interfaces: set[str] = set()

        def inspect_interface(name: str) -> bool:
            if name in seen_interfaces:
                return False
            seen_interfaces.add(name)
            info = self.class_info(name)
            if info is None:
                return False
            if inspect(info):
                return True
            return any(inspect_interface(parent) for parent in info.interfaces)

        for info in lineage:
            if any(inspect_interface(interface) for interface in info.interfaces):
                return Resolution("resolved")
        if matches:
            return Resolution("signature-changed", tuple(sorted(set(matches))))
        return Resolution("absent")

    def resolve_reflected(self, reference: Reference) -> Resolution:
        # Every maintained entry currently uses getDeclaredField, so intentionally do not walk
        # ancestry here: a move to a superclass would break the source's reflective lookup.
        info = self.class_info(reference.owner)
        if info is None:
            return Resolution("absent")
        table = info.fields if reference.kind == "reflected-field" else info.methods
        descriptors = table.get(reference.name or "", set())
        if not descriptors:
            return Resolution("absent")
        return Resolution(
            "resolved",
            tuple((info.name, descriptor) for descriptor in sorted(descriptors)),
        )


def classfile_java_release(path: Path) -> int | None:
    try:
        with zipfile.ZipFile(path) as jar:
            for entry in jar.infolist():
                if not entry.filename.endswith(".class"):
                    continue
                header = jar.read(entry)[:8]
                if len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe":
                    major = struct.unpack(">H", header[6:8])[0]
                    return major - 44 if major >= 45 else None
    except (OSError, zipfile.BadZipFile):
        return None
    return None


def engine_candidates(extra: Iterable[Path]) -> list[Path]:
    candidates = list(DEFAULT_ENGINE_JARS)
    appdata = os.environ.get("APPDATA")
    if appdata:
        candidates.append(Path(appdata).joinpath(*INSTALLED_JAR_PARTS))
    candidates.extend(path.expanduser().resolve() for path in extra)
    return candidates


def open_engines(candidates: Iterable[Path]) -> tuple[list[EngineJar], list[str]]:
    engines: list[EngineJar] = []
    notices: list[str] = []
    seen_paths: set[str] = set()
    platforms: dict[int, PlatformClasses] = {}
    for path in candidates:
        resolved_path = path.resolve()
        identity = os.path.normcase(str(resolved_path))
        if identity in seen_paths:
            continue
        seen_paths.add(identity)
        if not resolved_path.is_file():
            notices.append(f"SKIP {resolved_path}: file does not exist")
            continue
        attributes, error = manifest_attributes(resolved_path)
        if attributes is None:
            notices.append(f"SKIP {resolved_path}: {error}")
            continue
        version = attributes.get("implementation-version")
        if not version:
            notices.append(
                f"SKIP {resolved_path}: Implementation-Version is missing or empty"
            )
            continue
        if parsed_engine_version(version) is None:
            notices.append(
                f"SKIP {resolved_path}: Implementation-Version {version!r} cannot be ordered"
            )
            continue
        java_value = attributes.get("java-version") or attributes.get("build-jdk-spec")
        java_match = re.match(r"^(\d+)", java_value or "")
        java_release = int(java_match.group(1)) if java_match else classfile_java_release(resolved_path)
        if java_release is None:
            notices.append(
                f"SKIP {resolved_path}: Java release is absent and cannot be derived"
            )
            continue
        try:
            if java_release not in platforms:
                platforms[java_release] = PlatformClasses(java_release)
            platform = platforms[java_release]
            engines.append(EngineJar(resolved_path, version, platform))
        except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
            notices.append(f"SKIP {resolved_path}: cannot open jar, {exc}")
    engines.sort(
        key=functools.cmp_to_key(
            lambda left, right: compare_engine_versions(left.version, right.version)
        )
    )
    return engines, notices


def shown_reference(reference: Reference) -> str:
    owner = reference.owner.replace("/", ".")
    if reference.kind == "class":
        return f"class {owner}"
    descriptor = "" if reference.descriptor is None else f" {reference.descriptor}"
    return f"{reference.kind} {owner}.{reference.name}{descriptor}"


def control_class(owner: str, missing_name: str) -> bytes:
    """Build a valid class whose unused pool contains one impossible engine call."""
    owner_bytes = owner.encode()
    missing_bytes = missing_name.encode()
    constants = [
        b"\x01" + struct.pack(">H", 16) + b"apifloor/Control",
        b"\x07\x00\x01",
        b"\x01" + struct.pack(">H", 16) + b"java/lang/Object",
        b"\x07\x00\x03",
        b"\x01" + struct.pack(">H", len(owner_bytes)) + owner_bytes,
        b"\x07\x00\x05",
        b"\x01" + struct.pack(">H", len(missing_bytes)) + missing_bytes,
        b"\x01\x00\x03()V",
        b"\x0c\x00\x07\x00\x08",
        b"\x0a\x00\x06\x00\x09",
    ]
    header = struct.pack(">IHHH", 0xCAFEBABE, 0, 52, len(constants) + 1)
    body = b"".join(constants) + struct.pack(">HHHHHHH", 0x0021, 2, 4, 0, 0, 0, 0)
    return header + body


def run_controls(
    engines: list[EngineJar],
    our_references: set[Reference],
    all_results: dict[EngineJar, dict[Reference, Resolution]],
) -> tuple[str, str]:
    real: tuple[EngineJar, Reference] | None = None
    for engine in engines:
        for reference in sorted(our_references, key=shown_reference):
            if reference.kind == "method" and all_results[engine][reference].status == "resolved":
                real = engine, reference
                break
        if real:
            break
    if real is None:
        raise RuntimeError("control failed: no real owned engine method reference resolved")

    engine, real_reference = real
    missing_name = "__apifloor_control_method_that_does_not_exist__"
    _, synthetic = parse_class(
        control_class(real_reference.owner, missing_name), "synthetic apifloor control"
    )
    missing = next(
        reference
        for reference in synthetic
        if reference.kind == "method" and reference.name == missing_name
    )
    if engine.resolve(missing).status != "absent":
        raise RuntimeError("control failed: an impossible method did not resolve as absent")
    if engine.resolve(real_reference).status != "resolved":
        raise RuntimeError("control failed: a known real reference stopped resolving")
    return (
        f"{shown_reference(missing)} -> absent on {engine.version}",
        f"{shown_reference(real_reference)} -> resolved on {engine.version}",
    )


def resolution_record(
    reference: Reference,
    resolution: Resolution,
    origins: set[str],
) -> dict[str, object]:
    return {
        "status": resolution.status,
        "reference": shown_reference(reference),
        "origins": [display_origin(origin) for origin in sorted(origins)],
        "found": [
            {"owner": owner.replace("/", "."), "descriptor": descriptor}
            for owner, descriptor in resolution.found
        ],
    }


def semver_range(passing_versions: list[str], tested_versions: list[str]) -> str | None:
    unique_tested = list(dict.fromkeys(tested_versions))
    unique_passing = list(dict.fromkeys(passing_versions))
    if not unique_passing:
        return None
    indices = [unique_tested.index(version) for version in unique_passing]
    contiguous = indices == list(range(min(indices), max(indices) + 1))
    if contiguous:
        first = unique_passing[0]
        last = unique_passing[-1]
        return first if first == last else f">={first} <={last}"
    return " || ".join(unique_passing)


def release_range(report: dict[str, object]) -> tuple[str, list[str]]:
    rows = report["engines"]
    failures = [row for row in rows if row["owned_unresolved"] > 0]
    if failures:
        summary = ", ".join(
            f"{row['version']} ({row['owned_unresolved']} owned misses)"
            for row in failures
        )
        raise RuntimeError(
            "refusing to write serverVersionRange because every tested engine must have "
            f"zero owned misses; failing evidence: {summary}"
        )
    if not rows:
        raise RuntimeError("refusing to write serverVersionRange without tested engines")

    def release_core(version: str) -> tuple[int, int, int]:
        parsed = parsed_engine_version(version)
        if parsed is None:
            raise RuntimeError(f"tested engine version {version!r} cannot be ordered")
        core, _ = parsed
        if len(core) > 3:
            raise RuntimeError(f"tested engine version {version!r} is not a three-part SemVer")
        return (core + (0, 0, 0))[:3]

    oldest = release_core(rows[0]["version"])
    newest = release_core(rows[-1]["version"])
    lower = ".".join(str(component) for component in oldest)
    upper = f"{newest[0]}.{newest[1] + 1}.0"
    prereleases = [
        row["version"]
        for row in rows
        if parsed_engine_version(row["version"])[1] is not None
    ]
    return f">={lower} <{upper}", prereleases


def update_server_range(value: str, *, dry_run: bool) -> tuple[str, str]:
    try:
        original = PROPERTIES_FILE.read_bytes()
    except OSError as exc:
        raise RuntimeError(f"gradle.properties: unreadable, {exc}") from exc
    pattern = re.compile(
        rb"(?m)^([ \t]*serverVersionRange[ \t]*=[ \t]*)([^\r\n]*)(?=\r?$)"
    )
    matches = list(pattern.finditer(original))
    if len(matches) != 1:
        raise RuntimeError(
            "gradle.properties: expected exactly one serverVersionRange= line, "
            f"found {len(matches)}"
        )
    match = matches[0]
    replacement = match.group(1) + value.encode("ascii")
    updated = original[: match.start()] + replacement + original[match.end() :]
    try:
        before = match.group(0).decode("utf-8")
        after = replacement.decode("utf-8")
    except UnicodeError as exc:
        raise RuntimeError(f"gradle.properties: serverVersionRange line is not UTF-8, {exc}") from exc
    if not dry_run:
        try:
            PROPERTIES_FILE.write_bytes(updated)
        except OSError as exc:
            raise RuntimeError(f"gradle.properties: cannot write, {exc}") from exc
    return before, after


def print_range_update(report: dict[str, object], *, dry_run: bool) -> None:
    value, prereleases = release_range(report)
    before, after = update_server_range(value, dry_run=dry_run)
    print("\nserverVersionRange update:" + (" (dry run)" if dry_run else ""))
    print(f"-{before}")
    print(f"+{after}")
    if prereleases:
        newest_core = prereleases[-1].split("-", 1)[0]
        print(
            "apifloor: WARNING: pre-release engine evidence was tested ("
            + ", ".join(prereleases)
            + "), but the written range is deliberately release-only. Hytale applies the "
            "npm-strict pre-release rule, so it will not match those builds unless an AND-set "
            f"names the same core with a pre-release comparator; >={newest_core}-pre.1 is the "
            "form a human may want instead."
        )


def print_text(report: dict[str, object]) -> None:
    for notice in report["notices"]:
        print(notice)
    print(f"Artifact: {report['artifact']}")
    print(f"Classes analysed: {report['classes_analysed']}")
    print(
        "References analysed: "
        f"{report['references_analysed']} distinct targets "
        f"({report['static_references']} static, {report['reflected_references']} reflected)"
    )
    print("\nResolution table:")
    print("  version          refs checked  owned misses  shaded-only misses  verdict")
    for row in report["engines"]:
        print(
            f"  {row['version']:<16} {row['refs_checked']:>12} "
            f"{row['owned_unresolved']:>13} {row['shaded_unresolved']:>19}  "
            f"{row['verdict']}"
        )
        print(f"    {row['path']}")

    owned_sections = [row for row in report["engines"] if row["owned_findings"]]
    if owned_sections:
        print("\nUNRESOLVED REFERENCES OWNED BY ITEMFORGE:")
        for row in owned_sections:
            print(f"  {row['version']} [{row['path']}]")
            for finding in row["owned_findings"]:
                print(f"    {finding['status']}: {finding['reference']}")
                print(f"      from: {', '.join(finding['origins'])}")
                if finding["found"]:
                    found = ", ".join(
                        f"{item['owner']} {item['descriptor']}" for item in finding["found"]
                    )
                    print(f"      found instead: {found}")

    shaded_sections = [row for row in report["engines"] if row["shaded_findings"]]
    if shaded_sections:
        print("\nUNRESOLVED REFERENCES OWNED SOLELY BY SHADED THIRD-PARTY CODE:")
        print(
            "  These ship in the artifact, but they are not evidence that ItemForge-owned "
            "source failed. Reachability is not measured."
        )
        for row in shaded_sections:
            print(f"  {row['version']} [{row['path']}]")
            for finding in row["shaded_findings"]:
                print(f"    {finding['status']}: {finding['reference']}")
                print(f"      from: {', '.join(finding['origins'])}")

    print("\nControl checks:")
    print(f"  PASS missing reference: {report['controls']['negative']}")
    print(f"  PASS real reference: {report['controls']['positive']}")
    missing_controls = report["controls"]["missing_expected_versions"]
    if missing_controls:
        print("  NOTE missing default evidence versions: " + ", ".join(missing_controls))
    else:
        print("  PASS all expected default evidence versions were present")

    measured = report["measured_range"]
    if measured:
        print(f"\nMeasured Java-linkage range (SemverRange): {measured}")
        print("  This range summarizes tested version points; intervening untested builds are unproven.")
    else:
        print("\nMeasured Java-linkage range (SemverRange): none")
    print("\nCompatibility layers this tool cannot prove:")
    for limitation in report["limitations"]:
        print(f"  - {limitation}")


def analyse(extra_jars: list[Path]) -> tuple[dict[str, object], int]:
    project_name, version = project_coordinates()
    artifact = ROOT / "build" / "libs" / f"{project_name}-{version}.jar"
    if not artifact.is_file():
        raise RuntimeError(
            f"built artifact is absent: {artifact}. Source files are never used as a fallback"
        )

    engines, notices = open_engines(engine_candidates(extra_jars))
    if not engines:
        raise RuntimeError("no engine jar with a readable Implementation-Version was found")

    try:
        origins, class_count = artifact_references(artifact)
        static_references = set(origins)
        reflected_references = {
            Reference(f"reflected-{kind}", owner, member)
            for owner, member, kind in REFLECTED_MEMBERS
        }
        reflection_origin = "me/itemforge/(reflection audit)"
        for reference in reflected_references:
            origins.setdefault(reference, set()).add(reflection_origin)
        references = static_references | reflected_references
        our_references = {
            reference
            for reference, reference_origins in origins.items()
            if any(is_our_class(origin) for origin in reference_origins)
        }

        all_results: dict[EngineJar, dict[Reference, Resolution]] = {}
        rows: list[dict[str, object]] = []
        passing_versions: list[str] = []
        for engine in engines:
            results = {reference: engine.resolve(reference) for reference in references}
            all_results[engine] = results
            owned_findings: list[dict[str, object]] = []
            shaded_findings: list[dict[str, object]] = []
            for reference in sorted(references, key=shown_reference):
                resolution = results[reference]
                if resolution.status == "resolved":
                    continue
                record = resolution_record(reference, resolution, origins[reference])
                if reference in our_references:
                    owned_findings.append(record)
                else:
                    shaded_findings.append(record)
            if not owned_findings:
                passing_versions.append(engine.version)
            verdict = "FAIL" if owned_findings else ("WARN" if shaded_findings else "PASS")
            rows.append(
                {
                    "version": engine.version,
                    "path": str(engine.path),
                    "refs_checked": len(references),
                    "owned_unresolved": len(owned_findings),
                    "shaded_unresolved": len(shaded_findings),
                    "verdict": verdict,
                    "owned_findings": owned_findings,
                    "shaded_findings": shaded_findings,
                }
            )

        negative, positive = run_controls(engines, our_references, all_results)
        versions_present = {engine.version for engine in engines}
        tested_versions = [engine.version for engine in engines]
        newest_version = tested_versions[-1]
        newest_rows = [row for row in rows if row["version"] == newest_version]
        newest_failed = any(row["owned_unresolved"] for row in newest_rows)
        report: dict[str, object] = {
            "artifact": str(artifact.relative_to(ROOT)),
            "classes_analysed": class_count,
            "references_analysed": len(references),
            "static_references": len(static_references),
            "reflected_references": len(reflected_references),
            "owned_references": len(our_references),
            "engines": rows,
            "controls": {
                "negative": negative,
                "positive": positive,
                "missing_expected_versions": sorted(
                    EXPECTED_CONTROL_VERSIONS - versions_present,
                    key=functools.cmp_to_key(compare_engine_versions),
                ),
            },
            "measured_range": semver_range(passing_versions, tested_versions),
            "range_evidence": list(dict.fromkeys(passing_versions)),
            "newest_version_tested": newest_version,
            "newest_owned_verdict": "FAIL" if newest_failed else "PASS",
            "limitations": list(LIMITATIONS),
            "notices": notices,
        }
        return report, 1 if newest_failed else 0
    finally:
        for engine in engines:
            engine.close()
        for platform in {engine.platform for engine in engines}:
            platform.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Measure ItemForge's built Java API floor against real engine jars."
    )
    parser.add_argument(
        "--jar",
        action="append",
        default=[],
        type=Path,
        metavar="PATH",
        help="additional engine jar; repeatable (manifest version is authoritative)",
    )
    parser.add_argument(
        "--json", action="store_true", help="emit one machine-readable JSON document"
    )
    parser.add_argument(
        "--write-range",
        action="store_true",
        help=(
            "write the measured release-only range to gradle.properties; requires --playtested"
        ),
    )
    parser.add_argument(
        "--playtested",
        action="store_true",
        help="the human asserting they opened the mod in game on the newest tested engine",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="with --write-range, print the one-line change without writing it",
    )
    arguments = parser.parse_args(argv)
    if arguments.dry_run and not arguments.write_range:
        parser.error("--dry-run requires --write-range")
    if arguments.playtested and not arguments.write_range:
        parser.error("--playtested requires --write-range")
    if arguments.json and arguments.write_range:
        parser.error("--json cannot be combined with --write-range")
    if arguments.write_range and not arguments.playtested:
        print(
            "apifloor: ERROR: refusing --write-range without --playtested; bytecode linkage "
            "cannot prove asset schemas, codec assumptions, the client UI protocol, or runtime "
            "behaviour. --playtested is the human asserting they opened the mod in game on the "
            "newest tested engine.",
            file=sys.stderr,
        )
        return 2
    try:
        report, exit_code = analyse(arguments.jar)
    except (ClassFormatError, RuntimeError, OSError, ValueError, zipfile.BadZipFile) as exc:
        if arguments.json:
            print(json.dumps({"ok": False, "error": str(exc)}, indent=2))
        else:
            print(f"apifloor: ERROR: {exc}", file=sys.stderr)
        return 2
    if arguments.json:
        print(json.dumps({"ok": exit_code == 0, **report}, indent=2))
    else:
        print_text(report)
    if arguments.write_range:
        try:
            print_range_update(report, dry_run=arguments.dry_run)
        except RuntimeError as exc:
            print(f"apifloor: ERROR: {exc}", file=sys.stderr)
            return 2
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
