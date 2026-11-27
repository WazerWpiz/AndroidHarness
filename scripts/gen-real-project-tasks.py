#!/usr/bin/env python3
"""Regenerate RealProjectTaskData.kt: verbatim snapshots of the real repo
files the real-project evaluator (P4) builds its minimal Gradle projects
from.

Each *_TEST_KT constant is a repo test file copied exactly. Each *_BROKEN
source is the matching repo source file with exactly ONE line deliberately
broken (marked with a BUG comment) that the worker must fix; the real
test file then decides whether the fix is right.

Usage: python3 scripts/gen-real-project-tasks.py
Run from the repo root. Re-run whenever any snapshotted file changes,
or the tasks silently test a stale snapshot.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/java/com/androidharness/app/evolve/RealProjectTaskData.kt"

EVOLVE_MAIN = "app/src/main/java/com/androidharness/app/evolve"
EVOLVE_TEST = "app/src/test/java/com/androidharness/app/evolve"

# (constant name, repo-relative source path, break or None)
# A break is (exact line to find, replacement lines) and must occur once.
FILES = [
    ("GENOME_KT_BROKEN", f"{EVOLVE_MAIN}/Genome.kt", (
        "        private const val ID_LENGTH = 16",
        "        // BUG INTRODUCED FOR THIS TASK: the id must be 16 hex characters, not 15.\n"
        "        private const val ID_LENGTH = 15",
    )),
    ("GENOME_TEST_KT", f"{EVOLVE_TEST}/GenomeTest.kt", None),
    ("BUDGET_LEDGER_KT_BROKEN", f"{EVOLVE_MAIN}/BudgetLedger.kt", (
        "        consecutiveStagnant = if (improved) 0 else consecutiveStagnant + 1",
        "        // BUG INTRODUCED FOR THIS TASK: improvement must reset the stagnation counter, not grow it.\n"
        "        consecutiveStagnant = if (improved) consecutiveStagnant + 1 else 0",
    )),
    ("BUDGET_LEDGER_TEST_KT", f"{EVOLVE_TEST}/BudgetLedgerTest.kt", None),
    ("TRACE_MINER_KT_BROKEN", f"{EVOLVE_MAIN}/TraceMiner.kt", (
        '                val pattern = clean.replace(Regex("[0-9]+"), "#").replace(Regex("\\\\s+"), " ").take(120)',
        "                // BUG INTRODUCED FOR THIS TASK: numbers must be folded to '#', not deleted.\n"
        '                val pattern = clean.replace(Regex("[0-9]+"), "").replace(Regex("\\\\s+"), " ").take(120)',
    )),
    ("SECRET_REDACTOR_KT", "app/src/main/java/com/androidharness/app/tools/SecretRedactor.kt", None),
    ("TRACE_MINER_TEST_KT", f"{EVOLVE_TEST}/TraceMinerTest.kt", None),
    ("SECRET_REDACTOR_KT_BROKEN", "app/src/main/java/com/androidharness/app/tools/SecretRedactor.kt", (
        "    private fun segments(name: String) = name.lowercase().split('_', '-', '.')",
        "    // BUG INTRODUCED FOR THIS TASK: names must be matched case-insensitively, so segments must lowercase.\n"
        "    private fun segments(name: String) = name.split('_', '-', '.')",
    )),
    ("SECRET_REDACTOR_TEST_KT", "app/src/test/java/com/androidharness/app/tools/SecretRedactorTest.kt", None),
]


def kotlin_string(text: str) -> str:
    """A Kotlin double-quoted string literal for verbatim text."""
    if "\x08" in text or "\x0c" in text:
        raise ValueError("content holds a control character Kotlin cannot escape")
    # json.dumps gives valid Kotlin escapes for \n \" \\ and non-ASCII;
    # Kotlin additionally needs $ escaped.
    return json.dumps(text, ensure_ascii=True).replace("$", "\\$")


def main() -> int:
    entries = []
    for name, rel, brk in FILES:
        text = (ROOT / rel).read_text(encoding="utf-8")
        note = f"verbatim copy of {rel}"
        if brk is not None:
            old, new = brk
            if text.count(old) != 1:
                print(f"ERROR: break line occurs {text.count(old)}x in {rel} (want 1)", file=sys.stderr)
                return 1
            text = text.replace(old, new)
            note = f"{rel} with ONE deliberately broken line (marked BUG)"
        entries.append((name, note, kotlin_string(text)))

    lines = [
        "package com.androidharness.app.evolve",
        "",
        "/**",
        " * Verbatim snapshots of real repo files, frozen at P4 time, for the",
        " * real-project evaluator's minimal Gradle projects (see",
        " * RealProjectEvaluator). Each *_TEST_KT constant is a repo test file",
        " * copied exactly. Each *_BROKEN source is the matching repo source",
        " * with exactly ONE deliberately broken line (marked with a BUG",
        " * comment) that the worker must fix; the real test file then decides",
        " * whether the fix is right, by running on-device Gradle.",
        " *",
        " * GENERATED FILE — do not edit by hand. Regenerate with:",
        " *   python3 scripts/gen-real-project-tasks.py",
        " * Re-run it whenever any snapshotted file changes, or the tasks",
        " * silently test a stale snapshot.",
        " */",
        "internal object RealProjectTaskData {",
        "",
    ]
    for name, note, literal in entries:
        lines.append(f"    /** {note}. */")
        lines.append(f"    val {name}: String = {literal}")
        lines.append("")
    lines.append("}")
    lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
