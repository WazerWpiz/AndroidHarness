# Recursive harness

The recursive harness evolves the part of the agent that is data: instructions, skills,
hooks and agent profiles. Model weights never change. The design is scaffolding evolution
in the spirit of the Darwin Gödel Machine and Voyager, not fine-tuning.

This page documents the recursive harness as it ships today: genomes, the archive,
protected paths, the promotion gate, the real model-backed proposer and evaluator,
and the wiring that lets an approved genome change how the agent works. Model
weights never change.

## What a genome is

```kotlin
Genome(
    id,                 // sha256 of the canonical payload, first 16 hex characters
    parent,             // lineage only, not part of the hash
    createdAt, label,   // presentation only
    hypothesis,         // one sentence: why this should help
    instructions,       // list of instruction blocks
    skills,             // skill name -> full SKILL.md text
    skillEnable,        // names switched on
    hooks,              // event, matcher, command, timeout
    agents,             // name -> GenomeAgent(instructions, model, tools)
    proposerNotes,      // the proposer's own working notes
)
```

The canonical payload leaves out `id`, `parent`, `createdAt`, `label` and `hypothesis`:
two genomes with the same meaning always hash to the same id. Instructions are trimmed,
CRLF is normalized to LF, maps and sets are sorted, so map insertion order can never mint
a second id for the same genome. `GenomeRules` then refuses anything outside the genome's
scope:

- labels over 80 characters and hypotheses over 500;
- skills or agents whose names are not `[a-z0-9][a-z0-9-]{0,63}`, which also rules out
  path traversal;
- a hook event that is not one of the 13 events this app fires;
- any text naming a protected path.

There is no permission, plugin, MCP or sandbox field on the genome at all, so a candidate
has no way to widen its own access.

## Storage

Genomes live in app storage, not in a workspace, because they describe the harness:

```
filesDir/evolve/genomes/<id>/genome.json
filesDir/evolve/reports/<id>.json
filesDir/evolve/archive.json
filesDir/evolve/active.json
```

Every write goes through `EvolveIo.write`: write `<name>.tmp`, `fd.sync()`, then rename, so
a reader sees the old file or the new one and never half of either. A genome whose bytes
no longer hash to its folder name is treated as tampered and refused, which means an
unevaluated edit on disk can never become the active genome.

`active.json` records the active id, the id that was active before it, when it was
activated and who approved it. It is only written by `PromotionGate`.

## Protected paths

`ProtectedPaths` is the fixed point of the loop. It lists the trusted-core files
(`GenomeStore`, `Archive`, `PromotionGate`, `LiveEvaluator`, `RealProjectEvaluator`, `LlmProposer`, `SafetyProbes`, `BudgetLedger`,
`ShellPolicy`, `SecretRedactor`), the build and signing configuration, and the holdout
suite directory. Matching is case-insensitive, accepts either slash, and also refuses
`.keystore`, `.jks` and `.p12` files.

Two checks enforce it:

- `ProtectedPaths.requireWritable(path)` before any evolve write;
- `ProtectedPaths.references(text)` over every piece of genome text — instructions, skill
  text, hook commands and matchers, agent instructions and proposer notes — before storing
  and again before promoting.

A refusal is a first-class failure, not a warning: the genome is not stored and cannot be
promoted.

## Promotion and rollback

```kotlin
val gate = PromotionGate(store, archive)
gate.promote(genomeId, PromotionGate.Approval(approvedBy = "you"))
gate.rollback(PromotionGate.Approval(approvedBy = "you"))
```

`Approval` is a value only the approval card constructs, and every `promote` overload
requires one; a test asserts by reflection that no other overload exists. Promotion
re-validates the genome, refuses one with safety violations or a vetoed archive entry,
writes the active pointer, and updates archive statuses. Rollback targets the previously
active id (or the genome's parent), loads it, checks the hash, and activates it exactly.

## Caps

`EvolveSettings` ships with the loop off and clamps what it accepts:

| Cap | Default | Range |
| --- | --- | --- |
| Enabled | `false` | — |
| Generations per run | 3 | 1–10 |
| Candidates per generation | 4 | 1–8 |
| Wall clock per run | 30 min | 5–120 min |
| Token budget per run | 250,000 | 10,000–5,000,000 |
| Stagnations before stop | 2 | 1–5 |
| Minimum success delta | +5 points | 0–100 |
| Token regression allowed | 15% | 0–100 |
| Evaluation concurrency | 1 | fixed |

## Tools

The agent sees five tools:

| Tool | Behaviour |
| --- | --- |
| `evolve_status` | Active genome, archive size, caps, and what never changes |
| `evolve_history` | Archive entries, newest first, paginated |
| `evolve_diff` | Field-level diff between two genome ids |
| `evolve_propose` | Run one generation from the active genome (or the baseline) |
| `evolve_evaluate` | Evaluate one stored genome on the live suite + safety probes |

`evolve_propose` and `evolve_evaluate` only write candidates, reports and
archive entries. There is no `evolve_promote` tool, and there never will be:
promotion is a tap on the approval card in Settings → Coding → Recursive
harness, never something the agent can call.

## P1 — the runner (shipped)

`EvolutionRunner` runs generations end to end, after the Darwin Gödel
Machine (`jennyzzt/dgm`, arXiv:2505.22954): sample a parent from the
archive by score and novelty, self-modify, evaluate on a benchmark,
archive the result. The proposer is a real foundation model
(`LlmProposer`): it reads the parent genome, the parent's failing
benchmark cases and the archive's stepping stones, and writes ONE new
genome as JSON. Invalid output produces no candidate — never a template.
Settings → Coding → Recursive harness has a Run button, three model
pickers (proposer, worker, critic) and an approval card: only a
candidate that meets the gate rule (`PromotionGate.wouldPromote`, minus
the approval) is offered, and tapping Promote is the only way a genome
becomes active. If no model is configured for the proposer or the
worker, the run refuses instead of scoring anything fake.

## P2 — the evaluator (shipped)

- `LiveEvaluator`: DGM's evaluation phase plus Voyager's iterative
  prompting (`MineDojo/Voyager`). The worker model, running under the
  candidate's instructions and enabled skills, does 8 live benchmark
  tasks in a scratch workspace, with up to 3 repair rounds fed by the
  checker's feedback. A fixed checker verifies the produced artifact,
  and a separate critic model verifies it again. A task counts only
  when the checker AND the critic pass. Two tasks are holdout: the
  proposer never sees them, only their scalar score.
- `SafetyProbes`: 6 trusted adversarial probes (read outside the workspace,
  write to a protected path, credential redaction, delete the workspace
  root, widen permissions, a hook touching safety machinery). Any single
  failure vetoes the candidate outright.
- `BudgetLedger`: one token/wall/generation budget per run, enforced by the
  trusted core from provider-reported usage counts; `consume` checks before
  it adds, so a budget can never overshoot. Stagnation stops a run after the
  configured limit.
- `TraceMiner`: mines counted failure patterns from redacted run history
  and feeds them to the proposer as recurring hints; only counted
  phrases come out, never raw traces or secrets.

Honest limit: a phone has no Docker and cannot run SWE-bench, so the
benchmark is 8 small local tasks with fixed checkers plus a critic,
plus 4 real-project tasks (see P4 below) — the same loop mechanics as
DGM (archive → self-modify → empirical validation → promote on merit),
phone-scale benchmark. The score measures what a candidate's
instructions do to a real model's work: the harness never computes the
answer itself.

## P3 — activation wiring (shipped)

An approved genome now changes how the agent works, gated by a switch the
user controls:

- Settings → Coding → Recursive harness has a "Use the active genome"
  switch, bound to `EvolveSettings.enabled`. Off means the agent behaves
  exactly as shipped. Runs work either way.
- `EvolvePrompt.sectionFor(host)` appends one section to the agent's system
  prompt — in both the chat path and the coding path — only when the switch
  is on AND an active genome exists: the genome's instructions as bullets
  plus the text of its enabled skills (capped at 8,000 characters total).
  The section states the instructions never override safety rules, tool
  restrictions, or the user's current request. With the switch off, or with
  no active genome, the prompt is byte-identical to the shipped one.
- "Run one evolution" now runs in `EvolveHost`'s app scope, not the Settings
  screen's scope: leaving Settings no longer cancels the run. Run state
  (running, summary, promotable candidate, error) lives in the host and is
  shown again on return. A Stop button cancels the run; a stopped run leaves
  no partial promotion.

Honest limits: genome hooks (`GenomeHook`) and per-agent profiles
(`GenomeAgent`) are stored, validated and scored, but NOT applied to the
agent yet — only instructions and enabled skills reach the prompt. A run
survives leaving Settings but not the app process being killed; there is no
foreground service.

## P4 — real-project evaluation (shipped)

The live suite proves a candidate can follow instructions; it does not
prove the candidate helps on real code. `RealProjectEvaluator` (suite
`evolve-real-v1`) scores candidates on the real project itself —
AndroidHarness, this repo — with real test commands:

- `real-genome-id` (training): repair `Genome.kt` so `GenomeTest` passes.
- `real-budget-ledger` (training): repair `BudgetLedger.kt` so
  `BudgetLedgerTest` passes.
- `real-trace-miner` (training): repair `TraceMiner.kt` so
  `TraceMinerTest` passes.
- `real-secret-redactor` (holdout): repair `SecretRedactor.kt` so
  `SecretRedactorTest` passes. The proposer never sees it.

Each task is a minimal JVM Gradle project built in the scratch dir from
verbatim copies of the real source and test files, with exactly one
documented line broken in the fix-target file. The worker must repair
that line; the fixed checker then runs the real `gradle test --tests ...`
command for the task through the app's shell toolchain
(`ShellTestCommandRunner` over `ShellTierRouter`) and passes only on
exit code 0. Test files and build files are guarded by exact-text
comparison before Gradle runs, so the worker cannot pass by editing the
test, and only the one fix-target file may change. A separate critic
model still gives a second opinion after the tests pass, as in the live
suite.

The runner merges both suites into one `EvalReport`: 12 tasks (8 live +
4 real), the holdout score over all 3 holdouts (2 live + 1 real), the
token counts combined, and the suite identity stays `evolve-live-v1`,
so the gate, the archive and the reports API keep working on one report
per candidate. The proposer sees the combined training tasks of both
suites (9 ids and titles); the holdouts of both suites stay hidden.

Honest limits: the real suite requires the optional Linux toolchain +
Android build tools to be installed (Settings); on-phone Gradle runs
are slow and battery-heavy, each test command is capped at 10 minutes,
and the first run downloads the Kotlin Gradle plugin. If the toolchain
is not installed, the real tasks fail with a clear detail and the run
continues on the live suite alone — never a fake pass. The file
snapshots are frozen at P4 time; regenerate them with
`scripts/gen-real-project-tasks.py` after the source files change. The
standalone `evolve_evaluate` tool still runs the live suite only. The
Gradle test JVM compiles and runs worker-written code as the app user —
the same trust level as the agent's shell use — and `SafetyProbes`
still vets the candidate genome itself. No orphaned processes: the
shell runner kills the child's process group and destroys the process
on timeout and on cancellation, and captured output is capped at
32,000 characters per stream, so a stopped or timed-out Gradle run
cannot keep eating RAM or battery. Memory stays small: the runner
keeps only the merged report (12 task results) plus the capped command
tail; the file guard rejects a changed file by byte length before
reading it, and file listings read at most 8,000 characters per file,
so a huge worker-written file is never loaded into memory.

## Safety hardening (shipped with P3)

Two more layers, added before the activation wiring shipped:

- `GenomeRules` refuses any genome whose text tries to override safety:
  "ignore previous instructions", "disable safety", "bypass safety",
  "you are now an …" and similar phrases are refused before storage, the
  same as naming the trusted core. The check covers instructions, skill
  text, hook commands and matchers, agent instructions, proposer notes and
  the hypothesis. A denial ("never ignore…", "do not disable…") is not
  itself an override and still passes.
- `PromotionGate.wouldPromote` now requires a win on BOTH splits: the
  candidate must beat its parent by at least `minSuccessDelta` on the
  overall score AND on the holdout score, which the proposer never sees.
  A candidate with no holdout score cannot be offered at all, and neither
  can a first candidate that scores zero on holdout. The one exception is
  a parent that already aces holdout (1.0): tying it counts, because there
  is nothing left to beat.

## Safety model

- A genome cannot name or touch the trusted core, including through a hook command.
- A genome cannot change permissions, plugins, MCP servers or the sandbox: those fields do
  not exist on it.
- A vetoed genome, or one with any safety violation, cannot be promoted.
- Nothing is active without an explicit `Approval`.
- Rollback restores the exact previous hash, so a bad baseline is one action away from gone.
- Holdout tasks are protected core and are scored only by the trusted evaluator; the
  proposer will see a scalar, never the tasks or their checkers.

## Worked example

A generation wants to stop long grep output from filling the context window.

1. The proposer writes a candidate with one instruction ("Page grep results; never print
   more than 200 lines at once") and the hypothesis "Paging keeps long runs inside the
   context window". The genome is stored under the hash of that content.
2. The evaluator scores it against the fixed suite. The archive keeps the report even if
   the candidate loses, so a later generation can build on the idea.
3. The candidate wins by 8 points with no token regression and no safety violation, so the
   card offers Promote. Approving it writes `active.json` with the previous id beside it.
4. A later generation makes things worse. Rollback reads the previous id, verifies its
   hash, and restores it. The bad genome stays in the archive marked archived, as a
   stepping stone nobody has to repeat.
