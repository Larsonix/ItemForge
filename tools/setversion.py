"""Move ItemForge's release version without creating another split-brain artifact.

This tool exists because the version gate was motivated by a real release whose jar and plugin
manifest disagreed even though its build was green.  ItemForge declares its mod version in Gradle
and in the UI package, so changing them by hand under release pressure recreates the same measured
failure mode.  The Vuetale UI-plugin manifest has an intentionally independent version namespace;
it is always reported and never edited here.

Edits are made against the original bytes rather than serialising either document.  Only the two
version values move, preserving comments, indentation, key order, encoding, and newline style.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parent.parent
PROPERTIES_FILE = ROOT / "gradle.properties"
UI_PACKAGE_FILE = ROOT / "src/ui/package.json"
VUETALE_MANIFEST_FILE = ROOT / "src/main/resources/vuetale/itemforge/manifest.json"
SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
PROJECT_VERSION_LINE = re.compile(
    rb"(?m)^([ \t]*projectVersion[ \t]*=[ \t]*)([^\r\n]*)(?=\r?$)"
)
PACKAGE_VERSION_LINE = re.compile(
    rb'(?m)^([ \t]*"version"[ \t]*:[ \t]*")([^"\r\n]*)("[ \t]*,?[ \t]*)(?=\r?$)'
)


def parsed_semver(value: str):
    match = SEMVER.fullmatch(value)
    if not match:
        return None
    core = tuple(int(match.group(index)) for index in range(1, 4))
    prerelease = None if match.group(4) is None else tuple(match.group(4).split("."))
    return core, prerelease


def compare_semver(left: str, right: str) -> int:
    left_parsed = parsed_semver(left)
    right_parsed = parsed_semver(right)
    if left_parsed is None or right_parsed is None:
        raise ValueError(f"cannot compare non-SemVer values {left!r} and {right!r}")
    left_core, left_prerelease = left_parsed
    right_core, right_prerelease = right_parsed
    if left_core != right_core:
        return -1 if left_core < right_core else 1
    if left_prerelease is None or right_prerelease is None:
        if left_prerelease is right_prerelease:
            return 0
        return 1 if left_prerelease is None else -1
    for left_part, right_part in zip(left_prerelease, right_prerelease):
        if left_part == right_part:
            continue
        left_numeric = left_part.isdigit()
        right_numeric = right_part.isdigit()
        if left_numeric and right_numeric:
            return -1 if int(left_part) < int(right_part) else 1
        if left_numeric != right_numeric:
            return -1 if left_numeric else 1
        return -1 if left_part < right_part else 1
    if len(left_prerelease) == len(right_prerelease):
        return 0
    return -1 if len(left_prerelease) < len(right_prerelease) else 1


def one_line_value(path: Path, pattern: re.Pattern[bytes], label: str) -> tuple[bytes, str]:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise RuntimeError(f"{path.relative_to(ROOT)}: unreadable, {exc}") from exc
    matches = list(pattern.finditer(raw))
    if len(matches) != 1:
        raise RuntimeError(
            f"{path.relative_to(ROOT)}: expected exactly one {label} line, found {len(matches)}"
        )
    try:
        value = matches[0].group(2).decode("utf-8")
    except UnicodeError as exc:
        raise RuntimeError(
            f"{path.relative_to(ROOT)}: {label} value is not UTF-8, {exc}"
        ) from exc
    return raw, value


def replace_line_value(raw: bytes, pattern: re.Pattern[bytes], value: str) -> bytes:
    match = pattern.search(raw)
    if match is None:
        raise RuntimeError("validated version line disappeared before replacement")
    replacement = match.group(1) + value.encode("ascii") + match.group(3)
    return raw[: match.start()] + replacement + raw[match.end() :]


def replace_property_value(raw: bytes, value: str) -> bytes:
    match = PROJECT_VERSION_LINE.search(raw)
    if match is None:
        raise RuntimeError("validated projectVersion line disappeared before replacement")
    replacement = match.group(1) + value.encode("ascii")
    return raw[: match.start()] + replacement + raw[match.end() :]


def json_object(path: Path) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except OSError as exc:
        raise RuntimeError(f"{path.relative_to(ROOT)}: unreadable, {exc}") from exc
    except (UnicodeError, ValueError) as exc:
        raise RuntimeError(f"{path.relative_to(ROOT)}: invalid JSON, {exc}") from exc
    if not isinstance(document, dict):
        raise RuntimeError(f"{path.relative_to(ROOT)}: top level is not a JSON object")
    return document


def current_versions() -> tuple[bytes, bytes, str, str, object]:
    properties_raw, project_version = one_line_value(
        PROPERTIES_FILE, PROJECT_VERSION_LINE, "projectVersion="
    )
    package_raw, package_version = one_line_value(
        UI_PACKAGE_FILE, PACKAGE_VERSION_LINE, '"version":'
    )
    package = json_object(UI_PACKAGE_FILE)
    if package.get("version") != package_version:
        raise RuntimeError(
            "src/ui/package.json: parsed version does not agree with its single-line value"
        )
    vuetale_version = json_object(VUETALE_MANIFEST_FILE).get("version")
    return properties_raw, package_raw, project_version, package_version, vuetale_version


def report_versions(project: str, package: str, vuetale: object) -> None:
    print(f"setversion: gradle.properties projectVersion: {json.dumps(project)}")
    print(f"setversion: src/ui/package.json version: {json.dumps(package)}")
    print(
        "setversion: src/main/resources/vuetale/itemforge/manifest.json version: "
        f"{json.dumps(vuetale)} (independent Vuetale UI-plugin namespace; unchanged)"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Update ItemForge's Gradle and UI-package versions together."
    )
    parser.add_argument("version", nargs="?", help="new plain SemVer, for example 1.1.0")
    parser.add_argument(
        "--force",
        action="store_true",
        help="allow an equal or lower version (never needed for a normal release)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="report declarations and fail if the ItemForge mod versions disagree",
    )
    arguments = parser.parse_args(argv)
    if arguments.check and arguments.version is not None:
        parser.error("VERSION cannot be supplied with --check")
    if arguments.check and arguments.force:
        parser.error("--force has no effect with --check")
    if not arguments.check and arguments.version is None:
        parser.error("VERSION is required unless --check is used")
    if arguments.version is not None and parsed_semver(arguments.version) is None:
        parser.error(f"VERSION must be a plain SemVer; got {arguments.version!r}")

    try:
        properties_raw, package_raw, project, package, vuetale = current_versions()
    except RuntimeError as exc:
        print(f"setversion: ERROR: {exc}", file=sys.stderr)
        return 2
    report_versions(project, package, vuetale)

    invalid = [
        (site, value)
        for site, value in (
            ("gradle.properties projectVersion", project),
            ("src/ui/package.json version", package),
        )
        if parsed_semver(value) is None
    ]
    if invalid:
        for site, value in invalid:
            print(f"setversion: ERROR: {site} is not plain SemVer: {value!r}", file=sys.stderr)
        return 1

    if arguments.check:
        if project != package:
            print("setversion: ERROR: ItemForge mod version declarations disagree", file=sys.stderr)
            return 1
        print(f"setversion: ItemForge mod versions agree at {project}")
        return 0

    target = arguments.version
    if not arguments.force:
        not_older = [
            (site, value)
            for site, value in (
                ("gradle.properties", project),
                ("src/ui/package.json", package),
            )
            if compare_semver(target, value) <= 0
        ]
        if not_older:
            detail = ", ".join(f"{site} is {value}" for site, value in not_older)
            print(
                f"setversion: ERROR: {target} is not above every current mod version ({detail}); "
                "use --force only when moving backwards is intentional",
                file=sys.stderr,
            )
            return 1

    updated_properties = replace_property_value(properties_raw, target)
    updated_package = replace_line_value(package_raw, PACKAGE_VERSION_LINE, target)
    try:
        json.loads(updated_package.decode("utf-8-sig"))
        PROPERTIES_FILE.write_bytes(updated_properties)
        try:
            UI_PACKAGE_FILE.write_bytes(updated_package)
        except OSError:
            PROPERTIES_FILE.write_bytes(properties_raw)
            raise
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"setversion: ERROR: could not write validated version files: {exc}", file=sys.stderr)
        return 2

    print(f"setversion: updated ItemForge mod version to {target}")
    print("setversion: remaining release steps:")
    print("  [ ] add a CHANGELOG.md entry")
    print("  [ ] run python -B tools/versioncheck.py --release")
    print(f"  [ ] git tag v{target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
