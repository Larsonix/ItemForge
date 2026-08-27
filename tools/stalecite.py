"""Find visibly rotted, version-pinned engine claims in ItemForge source comments.

ItemForge records decompile findings beside the Kotlin that depends on them.  Those comments are
valuable until Hytale moves: a statement verified against 0.5.3 keeps sounding authoritative after
the compile SDK reaches 0.6.0, even though nobody re-checked it.  This gate makes that silent decay
visible by extracting version-like tokens near evidence words in Kotlin and Java comments and
comparing them with a target engine version.

What it checks, and what it deliberately does not
-------------------------------------------------
It proves that a version-pinned claim names the target version (or a newer one), or that an older
claim is explicitly recorded in the exceptions file for human review.  It cannot prove the claim
is true.  A pass means "no citation is visibly rotted", never "the comments are correct".

The zero-citation case is a failure.  An extractor that silently misses every comment has proved
nothing, which is the same false-green failure documented by the Armory's citecheck.py.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parent.parent
PROPERTIES_FILE = ROOT / "gradle.properties"
EXCEPTIONS_FILE = Path(__file__).resolve().parent / "stalecite-exceptions.txt"
SOURCE_ROOTS = (ROOT / "src/main/kotlin", ROOT / "src/main/java")
VERSION = re.compile(
    r"(?<![0-9A-Za-z])v?(\d+(?:\.\d+){2}(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?)"
)
EVIDENCE = re.compile(
    r"decompil(?:e|ed|ation)?|verified|as\s+of|in\s+0\.|SDK|source|extracted",
    re.IGNORECASE,
)
ORDERABLE_VERSION = re.compile(
    r"^(\d+(?:\.\d+)*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


@dataclass(frozen=True)
class Comment:
    start_line: int
    text: str


@dataclass(frozen=True)
class Citation:
    path: str
    line: int
    version: str
    text: str

    @property
    def key(self) -> tuple[str, int]:
        return self.path, self.line


def read_target() -> str:
    try:
        lines = PROPERTIES_FILE.read_text(encoding="utf-8-sig").splitlines()
    except (OSError, UnicodeError) as exc:
        raise RuntimeError(f"gradle.properties is unreadable: {exc}") from exc
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if separator and key.strip() == "hytaleServerVersion":
            target = value.strip()
            if target:
                return target
            break
    raise RuntimeError("gradle.properties: hytaleServerVersion is missing or empty")


def parsed_version(value: str):
    match = ORDERABLE_VERSION.fullmatch(value)
    if not match:
        return None
    core = tuple(int(component) for component in match.group(1).split("."))
    suffix = None if match.group(2) is None else tuple(match.group(2).split("."))
    return core, suffix


def compare_versions(left: str, right: str) -> int:
    left_parsed = parsed_version(left)
    right_parsed = parsed_version(right)
    if left_parsed is None or right_parsed is None:
        raise ValueError(f"cannot compare version {left!r} with {right!r}")
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


def extract_comments(source: str) -> list[Comment]:
    """Lex comments without mistaking quoted URLs or comment markers for comments."""
    comments: list[Comment] = []
    index = 0
    line = 1
    size = len(source)
    while index < size:
        char = source[index]
        following = source[index + 1] if index + 1 < size else ""
        if char == "\n":
            line += 1
            index += 1
            continue
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end == -1:
                return comments
            segment = source[index : end + 3]
            line += segment.count("\n")
            index = end + 3
            continue
        if char in ('"', "'"):
            quote = char
            index += 1
            while index < size:
                if source[index] == "\\":
                    index += 2
                    continue
                if source[index] == quote:
                    index += 1
                    break
                if source[index] == "\n":
                    line += 1
                index += 1
            continue
        if char == "/" and following == "/":
            start_line = line
            end = source.find("\n", index + 2)
            if end == -1:
                end = size
            comments.append(Comment(start_line, source[index:end]))
            index = end
            continue
        if char == "/" and following == "*":
            start = index
            start_line = line
            index += 2
            depth = 1
            while index < size and depth:
                if source.startswith("/*", index):
                    depth += 1
                    index += 2
                    continue
                if source.startswith("*/", index):
                    depth -= 1
                    index += 2
                    continue
                if source[index] == "\n":
                    line += 1
                index += 1
            comments.append(Comment(start_line, source[start:index]))
            continue
        index += 1

    # Consecutive // lines are one logical comment so "verified" on one line can qualify a version
    # on the next. Block comments are already kept intact.
    merged: list[Comment] = []
    for comment in comments:
        if (
            merged
            and merged[-1].text.startswith("//")
            and comment.text.startswith("//")
            and comment.start_line == merged[-1].start_line + merged[-1].text.count("\n") + 1
        ):
            previous = merged.pop()
            merged.append(Comment(previous.start_line, previous.text + "\n" + comment.text))
        else:
            merged.append(comment)
    return merged


def citation_text(comment: Comment, match: re.Match[str]) -> str:
    offset_line_start = comment.text.rfind("\n", 0, match.start()) + 1
    offset_line_end = comment.text.find("\n", match.end())
    if offset_line_end == -1:
        offset_line_end = len(comment.text)
    text = comment.text[offset_line_start:offset_line_end].strip()
    text = re.sub(r"^(?://+|/\*+|\*+)\s?", "", text)
    text = re.sub(r"\s*\*/$", "", text)
    return " ".join(text.split())


def citations_in(path: Path) -> list[Citation]:
    try:
        source = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise RuntimeError(f"{path.relative_to(ROOT).as_posix()}: unreadable, {exc}") from exc
    relative = path.relative_to(ROOT).as_posix()
    found: list[Citation] = []
    for comment in extract_comments(source):
        for match in VERSION.finditer(comment.text):
            window = comment.text[max(0, match.start() - 180) : match.end() + 180]
            if not EVIDENCE.search(window):
                continue
            line = comment.start_line + comment.text.count("\n", 0, match.start())
            found.append(
                Citation(relative, line, match.group(1), citation_text(comment, match))
            )
    return found


def source_files() -> list[Path]:
    found: list[Path] = []
    for root in SOURCE_ROOTS:
        if not root.is_dir():
            continue
        suffix = ".kt" if root.name == "kotlin" else ".java"
        found.extend(path for path in root.rglob(f"*{suffix}") if path.is_file())
    return sorted(found)


def exceptions() -> tuple[dict[tuple[str, int], str], list[str]]:
    loaded: dict[tuple[str, int], str] = {}
    errors: list[str] = []
    try:
        lines = EXCEPTIONS_FILE.read_text(encoding="utf-8-sig").splitlines()
    except OSError as exc:
        return {}, [f"{EXCEPTIONS_FILE.relative_to(ROOT).as_posix()}: unreadable, {exc}"]
    for number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        entry, marker, reason = line.partition("#")
        match = re.fullmatch(r"(.+):(\d+)\s*", entry)
        if not match:
            errors.append(
                f"{EXCEPTIONS_FILE.relative_to(ROOT).as_posix()}:{number}: expected path:line # reason"
            )
            continue
        path = match.group(1).strip().replace("\\", "/")
        explanation = reason.strip() if marker else ""
        if not explanation:
            errors.append(
                f"{EXCEPTIONS_FILE.relative_to(ROOT).as_posix()}:{number}: exception has no reason"
            )
            continue
        loaded[(path, int(match.group(2)))] = explanation
    return loaded, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Find version-pinned engine comments older than a target version."
    )
    parser.add_argument(
        "--target",
        help="engine version to compare against (default: gradle hytaleServerVersion)",
    )
    arguments = parser.parse_args(argv)
    try:
        target = arguments.target or read_target()
        if parsed_version(target) is None:
            raise RuntimeError(f"target version {target!r} is not orderable")
        citations: list[Citation] = []
        for path in source_files():
            citations.extend(citations_in(path))
        allowed, errors = exceptions()
    except RuntimeError as exc:
        print(f"stalecite: ERROR: {exc}")
        return 2

    print(f"target version    : {target}")
    print(f"citations found  : {len(citations)}")
    if not citations:
        print("FAIL: found zero citations; the extractor proved nothing and cannot be trusted")
        return 1
    if errors:
        for error in errors:
            print(f"stalecite: ERROR: {error}")
        return 2

    # A comment line often cites more than one version deliberately: "verified against the 0.5.4
    # and 0.6.0-pre.9 registries" is a STRONGER claim than either version alone, because it says
    # the fact was checked at two points and did not move. Judging each version token separately
    # marks the older one stale and fails the gate, which punishes exactly the citation style
    # this tool exists to encourage — and pushes every good citation into the exceptions file
    # until the exceptions file is the norm and the gate means nothing.
    #
    # So anchor each line to the NEWEST version it names. If that anchor is at or ahead of the
    # target, older tokens on the same line are supporting evidence, not rot.
    newest_on_line: dict[tuple[str, int], str] = {}
    for citation in citations:
        key = (citation.path, citation.line)
        current = newest_on_line.get(key)
        if current is None or compare_versions(citation.version, current) > 0:
            newest_on_line[key] = citation.version

    unexcepted: list[Citation] = []
    for citation in sorted(citations, key=lambda item: (item.path, item.line, item.version)):
        comparison = compare_versions(citation.version, target)
        anchor = newest_on_line[(citation.path, citation.line)]
        if comparison < 0 and compare_versions(anchor, target) >= 0:
            # Older token on a line whose newest citation is current or ahead.
            status = "ANCHORED"
            reason = None
        elif comparison < 0:
            reason = allowed.get(citation.key)
            status = "EXCEPTED stale" if reason else "STALE"
            if reason is None:
                unexcepted.append(citation)
        elif comparison == 0:
            status = "CURRENT"
            reason = None
        else:
            status = "NEWER"
            reason = None
        print(
            f"{citation.path}:{citation.line}: {status}: cites {citation.version}: "
            f"{citation.text}"
        )
        if reason:
            print(f"    exception: {reason}")

    if unexcepted:
        print(
            f"FAIL: {len(unexcepted)} stale citation(s) are not listed in "
            f"{EXCEPTIONS_FILE.relative_to(ROOT).as_posix()}"
        )
        return 1
    print("PASS: no stale citation is unaccounted for")
    print("note: this proves version labels are not visibly rotted, not that claims are true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
