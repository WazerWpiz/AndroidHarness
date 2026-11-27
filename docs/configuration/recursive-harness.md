# Recursive harness

The recursive harness evolves the part of the agent that is data: instructions, skills,
hooks and agent profiles. Model weights never change. The design is scaffolding evolution
in the spirit of the Darwin Gödel Machine and Voyager, not fine-tuning.

This page documents the P0 groundwork that ships today: genomes, the archive, protected
paths and the promotion gate. The proposer, evaluator and scheduler build on it in later
phases; until then the loop cannot start and the feature is inert by construction.

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
(`GenomeStore`, `Archive`, `PromotionGate`, `EvalHarness`, `SafetyProbes`, `BudgetLedger`,
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

The agent sees three read-only tools:

| Tool | Behaviour |
| --- | --- |
| `evolve_status` | Active genome, archive size, caps, and what never changes |
| `evolve_history` | Archive entries, newest first, paginated |
| `evolve_diff` | Field-level diff between two genome ids |
| `evolve_propose` | Run one generation from the active genome (or the baseline) |
| `evolve_evaluate` | Evaluate one stored genome on the static suite + safety probes |

`evolve_propose` and `evolve_evaluate` only write candidates, reports and
archive entries. There is no `evolve_promote` tool, and there never will be:
promotion is a tap on the approval card in Settings → Coding → Recursive
harness, never something the agent can call.

## P1 — the runner (shipped)

`EvolutionRunner` runs generations end to end. The proposer is
template-based for now: each candidate changes exactly one small thing
(a verify-before-done line, a pinned-constraint line, the
systematic-debugging skill, a tool-failed hook), driven by the
`TraceMiner` diagnosis of past failures. No model is called, so a run is
deterministic and costs no tokens; an LLM proposer that picks its provider
and model from the existing picker is the next step after this runner
proves itself. Settings → Coding → Recursive harness has a Run button and
an approval card: only a candidate that meets the gate rule
(`PromotionGate.wouldPromote`, minus the approval) is offered, and
tapping Promote is the only way a genome becomes active.

## P2 — the evaluator (shipped)

- `EvalHarness`: a deterministic static suite of 8 tasks, 2 seeds, one at a
  time (6 training, 2 holdout). The proposer only ever sees training task
  ids and titles; holdout is a scalar the proposer never sees.
- `SafetyProbes`: 6 trusted adversarial probes (read outside the workspace,
  write to a protected path, credential redaction, delete the workspace
  root, widen permissions, a hook touching safety machinery). Any single
  failure vetoes the candidate outright.
- `BudgetLedger`: one token/wall/generation budget per run, enforced by the
  trusted core; `consume` checks before it adds, so a budget can never
  overshoot. Stagnation stops a run after the configured limit.
- `TraceMiner`: mines counted failure patterns from redacted run history;
  only counted phrases come out, never raw traces or secrets.

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
