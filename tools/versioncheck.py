"""Check every ItemForge version source before another artifact lies about itself.

This gate follows the Armory check written after a real 1.18.4 jar shipped with a 1.18.3 plugin
manifest.  The build was green: Gradle named the artifact from one value while the engine and users
saw another, and nothing compared them.  ItemForge has six mod-version surfaces plus a separately
versioned Vuetale UI-plugin manifest, so visual review is an even weaker control here.

Gradle's ``projectVersion`` is authoritative because it names the jar.  ItemForge's source plugin
manifest intentionally contains ``${version}``; that placeholder is correct by construction, and
this tool verifies its expanded value inside the built jar instead of accusing the source template.
The same rule applies to its ``${serverVersionRange}`` placeholder, whose authority is the measured
value in ``gradle.properties``.  It also reports compile-SDK, installed-game, published-SDK, and
declared-range drift.  Engine drift is informational in a normal run and fatal under ``--release``;
network failure is never disguised as a mismatch.

Before reading the repository, the gate creates a known-bad temporary fixture and proves that it
detects a Gradle/manifest disagreement.  If that control stops failing, the checker exits non-zero
and says it cannot be trusted.  A version gate unable to reproduce the shipped Armory failure would
be only another confident green light.
"""
from __future__ import annotations

import argparse
import functools
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(__file__).resolve().parent
PROPERTIES_FILE = Path("gradle.properties")
SETTINGS_FILE = Path("settings.gradle.kts")
MANIFEST_FILE = Path("src/main/resources/manifest.json")
UI_PACKAGE_FILE = Path("src/ui/package.json")
VUETALE_MANIFEST_FILE = Path("src/main/resources/vuetale/itemforge/manifest.json")
CHANGELOG_FILE = Path("CHANGELOG.md")
SETTINGS_NAME = re.compile(
    r"^\s*rootProject\.name\s*=\s*(['\"])([^'\"]+)\1\s*$", re.MULTILINE
)
CHANGELOG_VERSION = re.compile(
    r"^##\s+v?(\d+(?:\.\d+)+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?)\b",
    re.MULTILINE,
)
ENGINE_VERSION = re.compile(
    r"^(\d+(?:\.\d+)*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
VERSION_PLACEHOLDER = "${version}"
SERVER_RANGE_PLACEHOLDER = "${serverVersionRange}"
MAVEN_METADATA = (
    "https://maven.hytale.com/release/com/hypixel/hytale/Server/maven-metadata.xml"
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


def shown(value: object) -> str:
    return json.dumps(value, ensure_ascii=True)


def read_properties(root: Path) -> tuple[dict[str, str] | None, str | None]:
    path = root / PROPERTIES_FILE
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
        return None, f"{PROPERTIES_FILE.as_posix()}: unreadable, {exc}"
    return result, None


def json_document(root: Path, relative: Path):
    path = root / relative
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except OSError as exc:
        return None, f"{relative.as_posix()}: unreadable, {exc}"
    except (UnicodeError, ValueError) as exc:
        return None, f"{relative.as_posix()}: does not parse as JSON, {exc}"
    if not isinstance(document, dict):
        return None, f"{relative.as_posix()}: top level is not a JSON object"
    return document, None


def project_name(root: Path) -> tuple[str | None, str | None]:
    try:
        text = (root / SETTINGS_FILE).read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        return None, f"{SETTINGS_FILE.as_posix()}: unreadable, {exc}"
    match = SETTINGS_NAME.search(text)
    if not match:
        return None, (
            f"{SETTINGS_FILE.as_posix()}: no rootProject.name = \"...\" assignment found"
        )
    return match.group(2), None


def jar_manifest_attributes(path: Path) -> tuple[dict[str, str] | None, str | None]:
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


def jar_implementation_version(path: Path) -> tuple[str | None, str | None]:
    attributes, error = jar_manifest_attributes(path)
    if attributes is None:
        return None, error
    version = attributes.get("implementation-version")
    if version:
        return version, None
    return None, "Implementation-Version is missing or empty in META-INF/MANIFEST.MF"


def jar_json(path: Path, member: str):
    try:
        with zipfile.ZipFile(path) as jar:
            raw = jar.read(member)
        document = json.loads(raw.decode("utf-8-sig"))
    except OSError as exc:
        return None, f"cannot open jar, {exc}"
    except zipfile.BadZipFile as exc:
        return None, f"not a readable jar, {exc}"
    except KeyError:
        return None, f"{member} is missing from the jar"
    except (UnicodeError, ValueError) as exc:
        return None, f"{member} does not parse as JSON, {exc}"
    if not isinstance(document, dict):
        return None, f"{member} does not contain a top-level JSON object"
    return document, None


def parsed_version(value: str):
    if not isinstance(value, str):
        return None
    match = ENGINE_VERSION.fullmatch(value)
    if not match:
        return None
    core = tuple(int(component) for component in match.group(1).split("."))
    suffix = None if match.group(2) is None else tuple(match.group(2).split("."))
    return core, suffix


def compare_versions(left: str, right: str) -> int:
    left_parsed = parsed_version(left)
    right_parsed = parsed_version(right)
    if left_parsed is None or right_parsed is None:
        raise ValueError(f"cannot confidently compare {left!r} and {right!r}")
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


def version_equal(left: str, right: str) -> bool:
    try:
        return compare_versions(left, right) == 0
    except ValueError:
        return left == right


def newest_git_tag(root: Path) -> tuple[str | None, str | None]:
    try:
        completed = subprocess.run(
            ["git", "tag", "-l"],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
    except OSError as exc:
        return None, f"git tag -l could not run, {exc}"
    if completed.returncode != 0:
        detail = completed.stderr.strip() or f"exit code {completed.returncode}"
        return None, f"git tag -l failed, {detail}"
    versions = [
        tag[1:]
        for tag in completed.stdout.splitlines()
        if tag.startswith("v") and parsed_version(tag[1:]) is not None
    ]
    if not versions:
        return None, "git tag -l found no vX.Y.Z-style tags"
    versions.sort(key=functools.cmp_to_key(compare_versions))
    return versions[-1], None


def first_changelog_version(root: Path) -> tuple[str | None, str | None]:
    try:
        text = (root / CHANGELOG_FILE).read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        return None, f"{CHANGELOG_FILE.as_posix()}: unreadable, {exc}"
    match = CHANGELOG_VERSION.search(text)
    if not match:
        return None, f"{CHANGELOG_FILE.as_posix()}: no ## <version> heading found"
    return match.group(1), None


def expect_version(
    problems: list[str], source: str, actual: object, authority: str
) -> None:
    if actual is None:
        problems.append(f"{source}: version is missing, should say {shown(authority)}")
    elif not isinstance(actual, str) or not version_equal(actual, authority):
        problems.append(
            f"{source}: version says {shown(actual)}, should say {shown(authority)}"
        )


def mod_version_findings(
    root: Path, *, check_artifact: bool = True, check_git: bool = True
) -> tuple[list[str], list[str], dict[str, object]]:
    problems: list[str] = []
    reports: list[str] = []
    details: dict[str, object] = {}

    gradle, error = read_properties(root)
    if error:
        return [error], reports, details
    authority = gradle.get("projectVersion")
    if not authority:
        return ["gradle.properties: projectVersion is missing or empty"], reports, details
    details["gradle"] = authority
    declared_range = gradle.get("serverVersionRange")
    details["declared_server_range"] = declared_range
    if not declared_range:
        problems.append("gradle.properties: serverVersionRange is missing or empty")
    else:
        _, range_error = parse_range(declared_range)
        if range_error:
            problems.append(f"gradle.properties: serverVersionRange {range_error}")
        else:
            details["range_warnings"] = upper_bound_prerelease_warnings(declared_range)

    name, error = project_name(root)
    if error:
        problems.append(error)
        name = None
    details["project_name"] = name

    manifest, error = json_document(root, MANIFEST_FILE)
    if error:
        problems.append(error)
    elif "Version" not in manifest:
        problems.append(
            f"{MANIFEST_FILE.as_posix()}: Version is missing, expected {VERSION_PLACEHOLDER!r}"
        )
    elif manifest["Version"] == VERSION_PLACEHOLDER:
        reports.append(
            f"{MANIFEST_FILE.as_posix()}: Version is the build-expanded {VERSION_PLACEHOLDER!r} placeholder"
        )
    else:
        expect_version(
            problems, f"{MANIFEST_FILE.as_posix()} Version", manifest["Version"], authority
        )
    if manifest is not None:
        source_range = manifest.get("ServerVersion")
        if source_range == SERVER_RANGE_PLACEHOLDER:
            reports.append(
                f"{MANIFEST_FILE.as_posix()}: ServerVersion is the build-expanded "
                f"{SERVER_RANGE_PLACEHOLDER!r} placeholder"
            )
        elif source_range is None:
            problems.append(
                f"{MANIFEST_FILE.as_posix()}: ServerVersion is missing, expected "
                f"{SERVER_RANGE_PLACEHOLDER!r}"
            )
        else:
            problems.append(
                f"{MANIFEST_FILE.as_posix()}: ServerVersion says {shown(source_range)}, "
                f"expected the build-expanded {SERVER_RANGE_PLACEHOLDER!r} placeholder"
            )

    ui_package, error = json_document(root, UI_PACKAGE_FILE)
    if error:
        problems.append(error)
    else:
        expect_version(
            problems, f"{UI_PACKAGE_FILE.as_posix()} version", ui_package.get("version"), authority
        )
        details["ui_package"] = ui_package.get("version")

    changelog, error = first_changelog_version(root)
    if error:
        problems.append(error)
    else:
        expect_version(problems, "CHANGELOG.md first heading", changelog, authority)
        details["changelog"] = changelog

    if check_git:
        tag, error = newest_git_tag(root)
        if error:
            problems.append(error)
        else:
            expect_version(problems, "newest git tag", tag, authority)
            details["git_tag"] = tag

    if check_artifact and name is not None:
        expected_artifact = root / "build" / "libs" / f"{name}-{authority}.jar"
        details["artifact"] = str(expected_artifact)
        if not expected_artifact.is_file():
            candidates = sorted((root / "build" / "libs").glob(f"{name}-*.jar"))
            found = ", ".join(path.name for path in candidates) or "none"
            problems.append(
                f"build/libs: expected {expected_artifact.name}, found matching jars: {found}"
            )
        else:
            embedded, embedded_error = jar_json(expected_artifact, "manifest.json")
            if embedded_error:
                problems.append(f"{expected_artifact}: {embedded_error}")
            else:
                expect_version(
                    problems,
                    f"{expected_artifact.name}!/manifest.json Version",
                    embedded.get("Version"),
                    authority,
                )
                details["expanded_manifest"] = embedded.get("Version")
                embedded_range = embedded.get("ServerVersion")
                details["expanded_server_range"] = embedded_range
                if embedded_range != declared_range:
                    problems.append(
                        f"{expected_artifact.name}!/manifest.json ServerVersion says "
                        f"{shown(embedded_range)}, but gradle.properties serverVersionRange "
                        f"says {shown(declared_range)}"
                    )

    vuetale, error = json_document(root, VUETALE_MANIFEST_FILE)
    if error:
        reports.append(error)
    else:
        value = vuetale.get("version")
        details["vuetale_ui_plugin"] = value
        # This manifest is a different namespace.  A human must decide whether it should track the
        # mod version; mechanically forcing equality would turn an explicit product decision into
        # a false release failure.
        reports.append(
            f"{VUETALE_MANIFEST_FILE.as_posix()}: separate Vuetale UI-plugin version is "
            f"{shown(value)} (reported only; a human must decide whether it tracks the mod)"
        )

    details["authority"] = authority
    details["compile_sdk"] = gradle.get("hytaleServerVersion")
    return problems, reports, details


def control_check() -> None:
    with tempfile.TemporaryDirectory(prefix="versioncheck-control-", dir=TOOLS) as temporary:
        root = Path(temporary)
        (root / "src/main/resources").mkdir(parents=True)
        (root / "src/ui").mkdir(parents=True)
        (root / "src/main/resources/vuetale/itemforge").mkdir(parents=True)
        (root / "gradle.properties").write_text(
            "projectVersion=1.18.4\nserverVersionRange=>=0.5.0 <0.6.0\n"
            "hytaleServerVersion=0.5.9\n",
            encoding="utf-8",
        )
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "Control"\n', encoding="utf-8"
        )
        (root / "src/main/resources/manifest.json").write_text(
            json.dumps(
                {"Version": "1.18.3", "ServerVersion": SERVER_RANGE_PLACEHOLDER}
            ),
            encoding="utf-8",
        )
        (root / "src/ui/package.json").write_text(
            json.dumps({"version": "1.18.4"}), encoding="utf-8"
        )
        (root / "src/main/resources/vuetale/itemforge/manifest.json").write_text(
            json.dumps({"version": "0.1.0"}), encoding="utf-8"
        )
        (root / "CHANGELOG.md").write_text("## 1.18.4\n", encoding="utf-8")
        problems, _, _ = mod_version_findings(
            root, check_artifact=False, check_git=False
        )
        if not any("1.18.3" in problem and "1.18.4" in problem for problem in problems):
            raise RuntimeError(
                "the known-bad 1.18.4 Gradle / 1.18.3 manifest fixture was not rejected"
            )


def published_sdk_version(offline: bool) -> tuple[str | None, str]:
    if offline:
        return None, "network metadata skipped by --offline"
    request = urllib.request.Request(
        MAVEN_METADATA, headers={"User-Agent": "ItemForge-versioncheck/1"}
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            raw = response.read()
        root = ET.fromstring(raw)
    except (OSError, urllib.error.URLError, ET.ParseError) as exc:
        return None, f"network metadata unavailable ({exc})"
    release = root.findtext("./versioning/release")
    if release and release.strip():
        return release.strip(), "Maven <release>"
    versions = [
        node.text.strip()
        for node in root.findall("./versioning/versions/version")
        if node.text and parsed_version(node.text.strip()) is not None
    ]
    if not versions:
        return None, "metadata contained no orderable release versions"
    versions.sort(key=functools.cmp_to_key(compare_versions))
    return versions[-1], "newest orderable Maven <version>"


RANGE_VERSION_TEXT = (
    r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)"
    r"(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)
RANGE_VERSION = re.compile(rf"^{RANGE_VERSION_TEXT}$")
RANGE_COMPARATOR = re.compile(
    rf"^(?P<operator>>=|<=|>|<|=)(?P<version>{RANGE_VERSION_TEXT})$"
)
RANGE_HYPHEN = re.compile(
    rf"^(?P<lower>{RANGE_VERSION_TEXT})\s+-\s+(?P<upper>{RANGE_VERSION_TEXT})$"
)


def expanded_shorthand(operator: str, version: str) -> list[tuple[str, str]]:
    parsed = parsed_version(version)
    if parsed is None:
        raise ValueError(f"invalid shorthand version {version!r}")
    core, _ = parsed
    major, minor, patch = core
    if operator == "^":
        if major:
            upper = f"{major + 1}.0.0"
        elif minor:
            upper = f"0.{minor + 1}.0"
        else:
            upper = f"0.0.{patch + 1}"
    elif operator == "~":
        upper = f"{major}.{minor + 1}.0" if minor else f"{major + 1}.0.0"
    elif patch:
        raise ValueError(
            f"bare version {version!r} has a non-zero patch; use '=' or '^' explicitly"
        )
    elif major == minor == patch == 0:
        return [(">=", version)]
    elif minor:
        upper = f"{major}.{minor + 1}.0"
    else:
        upper = f"{major + 1}.0.0"
    return [(">=", version), ("<", upper)]


def parse_range(expression: object):
    """Expand Hytale's comparator, shorthand, hyphen, wildcard, AND, and OR forms."""
    if not isinstance(expression, str):
        return None, f"is not a recognised ServerVersion range: {shown(expression)}"
    expression = expression.strip()
    if not expression or expression == "*":
        return [[]], None
    alternatives: list[list[tuple[str, str]]] = []
    for raw_alternative in expression.split("||"):
        alternative = raw_alternative.strip()
        if not alternative:
            return None, f"is not a recognised ServerVersion range: {shown(expression)}"
        hyphen = RANGE_HYPHEN.fullmatch(alternative)
        if hyphen:
            alternatives.append(
                [(">=", hyphen.group("lower")), ("<=", hyphen.group("upper"))]
            )
            continue
        if alternative.startswith(("~", "^")):
            operator = alternative[0]
            version = alternative[1:].strip()
            if RANGE_VERSION.fullmatch(version):
                alternatives.append(expanded_shorthand(operator, version))
                continue
            return None, f"is not a recognised ServerVersion range: {shown(expression)}"
        if re.search(r"\s", alternative):
            terms: list[tuple[str, str]] = []
            for token in re.split(r"\s+", alternative):
                comparator = RANGE_COMPARATOR.fullmatch(token)
                if comparator is None:
                    return None, f"is not a recognised ServerVersion range: {shown(expression)}"
                terms.append((comparator.group("operator"), comparator.group("version")))
            alternatives.append(terms)
            continue
        comparator = RANGE_COMPARATOR.fullmatch(alternative)
        if comparator:
            alternatives.append(
                [(comparator.group("operator"), comparator.group("version"))]
            )
            continue
        if RANGE_VERSION.fullmatch(alternative):
            try:
                alternatives.append(expanded_shorthand("", alternative))
            except ValueError:
                return None, (
                    f"rejects bare version {shown(alternative)} because Hytale only accepts a "
                    "bare range when patch is 0; use '=' or '^' explicitly"
                )
            continue
        wildcard = re.fullmatch(
            r"(?:(?:0|[1-9]\d*)|x)\.(?:(?:0|[1-9]\d*)|x)\."
            r"(?:(?:0|[1-9]\d*)|x)",
            alternative,
        )
        if wildcard:
            normalized = alternative.replace("x", "0")
            try:
                alternatives.append(expanded_shorthand("", normalized))
            except ValueError:
                return None, f"is not a recognised ServerVersion range: {shown(expression)}"
            continue
        return None, f"is not a recognised ServerVersion range: {shown(expression)}"
    return alternatives, None


def comparator_satisfied(operator: str, actual: str, expected: str) -> bool:
    comparison = compare_versions(actual, expected)
    if operator == ">=":
        return comparison >= 0
    if operator == ">":
        return comparison > 0
    if operator == "<=":
        return comparison <= 0
    if operator == "<":
        return comparison < 0
    if operator == "=":
        return comparison == 0
    raise ValueError(f"unsupported range operator {operator!r}")


def range_contains(expression: object, version: str) -> tuple[bool | None, str | None]:
    alternatives, error = parse_range(expression)
    if error:
        return None, error
    actual = parsed_version(version)
    if actual is None or len(actual[0]) != 3:
        return None, f"cannot confidently evaluate version {shown(version)}"
    try:
        actual_core, actual_prerelease = actual
        for terms in alternatives:
            if not terms:
                return True, None
            if not all(
                comparator_satisfied(operator, version, expected)
                for operator, expected in terms
            ):
                continue
            if actual_prerelease is not None:
                names_same_prerelease_core = any(
                    parsed_version(expected)[0] == actual_core
                    and parsed_version(expected)[1] is not None
                    for _, expected in terms
                )
                if not names_same_prerelease_core:
                    continue
            return True, None
    except ValueError as exc:
        return None, str(exc)
    return False, None


def upper_bound_prerelease_warnings(expression: str) -> list[str]:
    alternatives, error = parse_range(expression)
    if error:
        return []
    warnings: list[str] = []
    for terms in alternatives:
        for operator, version in terms:
            parsed = parsed_version(version)
            if operator not in {"<", "<="} or parsed[1] is not None:
                continue
            candidate = f"{version}-pre.1"
            contains, _ = range_contains(expression, candidate)
            if contains is False:
                warnings.append(
                    f"declared serverVersionRange {expression!r} does not match {candidate}: "
                    "Hytale applies npm-strict pre-release matching, so an AND-set must name "
                    f"that core with a pre-release comparator (for example >={candidate})"
                )
    return list(dict.fromkeys(warnings))


def installed_engine() -> tuple[Path, str | None, str | None]:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        path = Path("%APPDATA%").joinpath(*INSTALLED_JAR_PARTS)
        return path, None, "APPDATA is not set"
    path = Path(appdata).joinpath(*INSTALLED_JAR_PARTS)
    if not path.is_file():
        return path, None, "file does not exist"
    version, error = jar_implementation_version(path)
    return path, version, error


def engine_findings(
    compile_sdk: object,
    installed_path: Path,
    installed: str | None,
    installed_error: str | None,
    published: str | None,
    published_note: str,
    declared_range: object,
) -> tuple[list[str], list[str]]:
    drift: list[str] = []
    reports: list[str] = []
    compile_value = compile_sdk if isinstance(compile_sdk, str) else None
    reports.append(f"compile SDK pin: {shown(compile_sdk)}")
    if installed is None:
        drift.append(f"installed game at {installed_path} cannot be measured: {installed_error}")
    else:
        reports.append(f"installed game: {installed} [{installed_path}]")
    if published is None:
        reports.append(f"published release SDK: unknown; {published_note}")
    else:
        reports.append(f"published release SDK: {published} ({published_note})")
    reports.append(f"declared compatibility range: {shown(declared_range)}")

    measured = [
        ("compile SDK", compile_value),
        ("installed game", installed),
        ("published release SDK", published),
    ]
    if compile_value is None:
        drift.append("gradle.properties: hytaleServerVersion is missing or empty")
    for left_index, (left_name, left_value) in enumerate(measured):
        if left_value is None:
            continue
        if parsed_version(left_value) is None:
            drift.append(f"{left_name} version {shown(left_value)} cannot be ordered")
            continue
        for right_name, right_value in measured[left_index + 1 :]:
            if right_value is None or parsed_version(right_value) is None:
                continue
            if compare_versions(left_value, right_value) != 0:
                drift.append(
                    f"{left_name} is {left_value}, but {right_name} is {right_value}"
                )

    if isinstance(declared_range, str):
        for source, value in measured:
            if value is None or parsed_version(value) is None:
                continue
            contains, error = range_contains(declared_range, value)
            if error:
                drift.append(f"gradle.properties serverVersionRange cannot be evaluated: {error}")
                break
            if contains is False:
                drift.append(
                    f"declared serverVersionRange {declared_range!r} excludes the {source} {value}"
                )
    else:
        drift.append(
            f"gradle.properties serverVersionRange is missing or invalid: "
            f"{shown(declared_range)}"
        )
    return drift, reports


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare ItemForge mod versions and report engine-version drift."
    )
    parser.add_argument(
        "repository_root", nargs="?", type=Path, default=ROOT, help=argparse.SUPPRESS
    )
    parser.add_argument("--offline", action="store_true", help="skip Maven metadata")
    parser.add_argument(
        "--release", action="store_true", help="make engine-version drift fatal"
    )
    arguments = parser.parse_args(argv)

    try:
        control_check()
    except Exception as exc:  # The control must turn any failure into a distrust verdict.
        print(f"versioncheck: SELF-TEST FAILED; gate cannot be trusted: {exc}")
        return 2

    root = arguments.repository_root.expanduser().resolve()
    if not root.is_dir():
        print(f"versioncheck: no such repository directory: {root}")
        return 2

    problems, reports, details = mod_version_findings(root)
    installed_path, installed, installed_error = installed_engine()
    published, published_note = published_sdk_version(arguments.offline)
    drift, engine_reports = engine_findings(
        details.get("compile_sdk"),
        installed_path,
        installed,
        installed_error,
        published,
        published_note,
        details.get("declared_server_range"),
    )

    for report in reports:
        print(f"versioncheck: report: {report}")
    for report in engine_reports:
        print(f"versioncheck: report: {report}")
    for warning in details.get("range_warnings", []):
        print(f"versioncheck: WARNING: {warning}")
    for item in drift:
        level = "ERROR" if arguments.release else "drift"
        print(f"versioncheck: {level}: {item}")
    for problem in problems:
        print(f"versioncheck: ERROR: {problem}")

    if problems or (arguments.release and drift):
        return 1
    print(
        f"versioncheck: mod versions agree at {details['authority']}"
        if not problems
        else "versioncheck: mod versions disagree"
    )
    if drift and not arguments.release:
        print("versioncheck: engine drift is informational; use --release to make it fatal")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
