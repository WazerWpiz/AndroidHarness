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

## P5 — genome export / import (shipped)

A reinstall used to wipe every genome: genomes live in app-private
storage, and uninstalling the app deletes it. `GenomeBackup` adds two
buttons to Settings → Recursive harness:

- **Export genomes** writes one JSON file with every genome, each
  genome's report, the archive entries, and the active pointer, through
  the system file picker — so it lands outside the app (Downloads).
- **Import genomes** picks that file back. Every genome re-enters only
  through the existing checks: the id must still match the content
  (`Genome.computeId()`), then `GenomeRules.requireValid()`, then the
  store's own hash check on save. A tampered genome is refused with a
  clear reason; a genome already stored with different bytes is never
  overwritten; reports and archive entries are restored only for genomes
  that are present. Import never activates anything: the active pointer
  in the file is recorded but not restored — re-promote through the
  normal approval if you want a genome active again.

Honest limits: the backup is manual — there is no cloud copy and no
auto-backup; you must tap Export and keep the file yourself. The file
is plain-text JSON: genomes are instructions, not secrets, but it holds
your agent's instructions, so keep it somewhere only you can read.
Import refuses files larger than 16 MB before reading them fully, and a
malformed file is refused with a clear message. The backup is a snapshot:
genomes evaluated after the export are not in it.

## P6 — live run progress (shipped)

A run used to show nothing but "Running…" until it finished. The
runner now reports each real transition to the screen, through the same
host state that already survives leaving Settings: the current
generation (out of the cap), the phase (proposing, evaluating, or
scoring), the candidate in progress (out of the cap, with its label
once proposed), the candidates that already finished with the score
each really got, tokens used against the token budget, the elapsed
time since the run started, and proposal failures so far with the last
reason. The elapsed clock ticks once a second from the recorded start
time; the Stop button works as before, and stopping still leaves no
partial promotion.

Honest limits: the preview shows counts and the current phase only —
there is no percent-complete and no ETA, because the total work is not
knowable in advance: a run can stop early on the token budget, the
wall-clock budget, stagnation, or a quota failure. The finished list
shows the most recent candidates only (capped at the per-generation
candidate cap), and the token count is what the ledger has recorded so
far, so it moves in steps as each model call is charged.

## P7 — full run report, sampler fix, toolchain indicator (shipped)

Three small additions, all from facts the app already records.

**Full run report.** After a run finishes, the screen shows a report
for one candidate: the promotable one when there is one, else the
newest archived candidate. It shows the per-task breakdown from that
genome's stored report — training and holdout tasks as separate groups
with their counts, each task with its id, passed/failed, and its
checker's detail — the parent's scores next to the candidate's, and
the gate's reason in one line ("Ready to promote" or the rejection
reason). Each archive row also shows its gate reason when one was
recorded. The reason is the one the gate gave at evaluation time, and
it is stored on the archive entry, capped at 200 characters.

**Sampler fix.** The parent sampler used to pick at random from the
top three entries, so it could re-pick the current parent — and the
run then stopped after one generation, which is why some runs ended
surprisingly fast. The runner now excludes the current parent from
the draw before ranking, so the top three are always other entries;
the old equality check stays as a safety net. If exclusion empties
the pool there is nothing else to evolve from, and the run stops.

**Toolchain indicator.** The section shows one line for what the test
runner reports about the build toolchain: installed, or not installed
with the warning that the 4 real tasks will fail and the score ceiling
is 8/12. The runner's answer is a live read of the Linux environment
marker, so installing the toolchain is picked up without an app
restart.

Honest limits: the gate reason is the reason at evaluation time —
re-evaluating a genome later can give a different one. The task
breakdown shows the latest stored report for that genome. The
toolchain line reports what the runner says, not a verified working
toolchain: it checks that the Linux environment reports gradle and
openjdk-21 installed, it does not run a build to prove it.

## P8 — fair evaluation, Doctor preflight, persisted run report (shipped)

Seven fixes that make the score mean something, a preflight that
checks the run can actually work, and a report block that survives
an app restart.

**Task statuses and INVALID reports.** Every task now reports a
status: PASS, FAIL, ERROR or SKIPPED. Only decided tasks (PASS/FAIL)
count in the success rate — an ERROR or SKIPPED task never counts as
a failure and never counts as a pass. Infra failures are ERROR: rate
limits, quota exhausted, timeouts, a model call that failed, an
unparseable critic verdict, a checker that threw. Agent failures are
FAIL: the checker failed, the critic gave a FAIL verdict, the worker
produced no usable output. An ERROR outcome is retried up to 3 times
with exponential backoff (1s, 2s, 4s); an answer that says "try again
in N seconds" is respected, capped at 60 seconds per wait. Tasks run
sequentially with a 1-second pause between them, to reduce rate-limit
hits on free models. A Gradle timeout is retried only once, because
each retry costs up to 10 minutes of wall time — and the repair rounds
stop at once on a timeout, because re-asking the worker cannot fix a
command that timed out. If more than 20% of
the chosen suite's tasks are ERROR/SKIPPED, the report is INVALID:
it is stored and shown, marked invalid, but it is NEVER used for a
promotion decision or for proposer feedback. With the toolchain
missing and no lite switch, every report would be invalid — which is
exactly why the lite switch exists.

**Doctor preflight.** Before every run, the Doctor checks the Linux
userspace, the JDK version (must be 21), the Gradle version, the
Android SDK tools (d8, apksigner, aapt2), free storage, free RAM, the
network (a TCP connect to repo1.maven.org:443), the provider keys for
the three configured role models, a minimal one-word ping for each role model (capped at 8 output tokens),
the suite fixtures, and a tiny hello-world Gradle build (120-second
timeout) proving the toolchain actually builds. The checklist is in
Settings, with a per-item Fix button where a fix exists: toolchain
items reuse the Linux environment install/update flow, key and ping
items open the model picker for that role. The blocking set is
explicit: a model ping that fails blocks the run with the exact reason
(no evaluation starts, no tokens spent); critically low storage
(under 200 MB) blocks it too. A missing toolchain does NOT block: the
run switches to the lite suite. Everything else warns. The Doctor
result is stored on every report the run produces. A check that cannot
be performed reports "unknown" with the reason, never a guessed pass.
Cost: the whole preflight is cheap except the smoke test (one tiny
Gradle build, up to 120 seconds on a cold plugin cache) and the three
minimal model pings.

**Preflight selects the suite; SKIPPED is for inside the suite.**
The preflight picks the suite: full (13 live + 4 real tasks, 17
total) when the toolchain is ready, lite (13 live tasks, no real
tasks) when it is not. SKIPPED applies to tasks that cannot run
WITHIN the chosen suite — for example a real task whose command
cannot run for an infra reason other than the toolchain. Promotion
only ever compares reports from the same suite: a lite 100% (13/13)
and a full 100% (17/17) are different denominators and are never
compared.

**Fair comparison.** Baseline is evaluated first, once per
(suite, suite version, worker pin, critic pin, seeds), and the stored
report is reused while the key matches; it is re-evaluated only when
the model or the suite changes. The cache key comes from the
CONFIGURED role models, so when the roles fall back to the app's
active model (no stable identifier) there is no reuse and the parent
is evaluated fresh. Every candidate is screened at 1 seed first:
only a candidate whose 1-seed success rate is STRICTLY greater than
its parent's on the same seed goes to a 3-seed confirmation, where
candidate and parent are both evaluated on the same 3 seeds (seed 1
is reused from the screening; seeds 2 and 3 are fresh, unless the
parent already has a cached 3-seed aggregate). The promotion rule is
now: the candidate beats the parent by at least +2 tasks on average
across the 3 confirmation seeds, AND its mean holdout score is not
lower than the parent's. A 1-task win is never enough. The gate also
refuses to compare reports with different model pins, different
critic pins, or different suites, and refuses INVALID reports on
either side. All safety vetoes are unchanged, including the token
regression check. Cost: confirmation roughly triples the evaluation
work for a candidate that passes screening, and the parent is
evaluated on the same 3 seeds — but only for candidates that already
beat the parent at 1 seed, and the budget ledger still caps the run:
a run that would exceed the budget mid-confirmation stops honestly
with the ledger reason and never overspends. `minSuccessDelta` is no
longer used by the gate since P8 (the +2-task rule replaced the
percentage bar); the setting stays in storage so old settings still
read.

**Critic strict JSON.** The critic must return
`{"verdict":"PASS|FAIL","reason":"..."}` and the parser accepts ONLY
that — no free-text fallback, no startsWith("PASS"). Unparseable
output, a missing or invalid verdict, or a PASS whose reason says the
task failed, is ERROR, not FAIL. Temperature 0 could NOT be forced:
the model-call path (`EvolveModelCaller` → provider) has no
temperature parameter anywhere in the app, so there is nothing to
set. Determinism is enforced by the JSON-only prompt plus this strict
parsing, and this doc says so instead of pretending a temperature was
forced. The critic is SKIPPED for tasks whose checker is fully
deterministic — the checker decides alone. Per task: rename-function
and its three variants, file-read-paging, file-list-grep, multi-edit,
apply-patch-hunk, git-status-diff, sort-lines, dedupe-lines and the
four real tasks are deterministic (Gradle exit 0 plus the guards for
the real ones); verify-before-done and pinned-constraint still need
the critic, because their checkers are only a partial test of the
task (the evidence line's truth, the first and last lines around the
pinned line).

**Proposer.** The proposer prompt now classifies each failing
training task as agent-failure or infra-failure and says explicitly
that infra failures cannot be fixed and must not be designed around.
Each candidate must change a different dimension — a skill, a hook,
an agent profile, or the instruction count (an instruction added or
removed). A pure reword (the same instruction count, nothing else
changed) is rejected. A clean mechanical "reword" test is impossible
(an edited line is indistinguishable from a new line), so the
instruction count is the line drawn. Candidates are deduped by
instructions hash and by hypothesis against the store and the
archive, and the rejection names the duplicate. Already-tested
hypotheses with their scores are in the prompt (newest 10), so the
proposer does not repeat them. `proposerNotes` is required, never
blank: it must state the evidence that motivated the change, and a
candidate with a blank note is rejected.

**Telemetry.** Each task report now carries real turns (repair rounds
used), real tokens (worker + critic for that task), and a short worker
trace (capped at 2000 characters: the rounds and what the worker last
produced, so the proposer can see WHY a task failed). The trace is in
the report and in the proposer prompt (capped at 500 characters
there). Caps: traces 2000 chars, gate reasons 200 chars, Doctor
results one entry per check.

**Tasks.** rename-function is kept, with its checker fixed: the old
checker failed on the substring "fetchData" inside
"fetchDataInternal", so a CORRECT rename failed it — that is why
rename-function failed in every genome. The checker now uses
word-boundary matching (the target renamed, similar names unchanged,
imports and call sites consistent). Three variants were added
(rename across two files, rename with a similar decoy name, rename
defined in one file and used in another) plus sort-lines and
dedupe-lines. The live suite is now 13 tasks (11 training, 2
holdouts: git-status-diff and pinned-constraint), the full suite 17
tasks (3 holdouts: the 2 live ones plus real-secret-redactor). The
live suite version bumped 1 → 2: scores before and after P8 are NOT
comparable (different denominators), and the gate refuses to compare
across versions through the cache key.

**Persisted run report.** The report block used to depend on the
session's run state, so it vanished after an app restart. The host
now writes a small last-run pointer (`last-run.json` under the evolve
directory: the promotable/best candidate id, the run summary, when it
finished) whenever a run ends — success, stopped, blocked, or error.
The pointer is written BEFORE the run state flips to not-running, so
a finished run always has its pointer on disk already. The block loads
from that pointer, falls back to the newest archived
candidate when the last run produced none, and shows nothing (no
crash) when the archive is empty. The gate reason still comes from
the stored archive entry, not duplicated into the report. A candidate's
3-seed aggregate is its best-known report: when it later becomes a
parent, the runner reuses that aggregate instead of re-evaluating it
at 1 seed, and never overwrites it with a 1-seed report.

Honest limits: the Doctor's smoke test can time out on a cold plugin
cache (the first Gradle run downloads the plugin); that is a failed
check, not proof the toolchain is broken, and the real tasks retry
with 10 minutes. The 3-seed confirmation reuses the seed-1
evaluation, so a confirmation costs 2 fresh evaluations per side, not
3. The cache only reuses a report when the role models are explicitly
configured; with fallback models the parent is evaluated fresh every
run. A candidate can only be promoted when it beats its parent by 2
tasks on average — a parent that already scores 13/13 cannot be
beaten, and its candidates are screened out, which is correct: there
is nothing left to improve.

+**Archive pruning (P9).** After each run ends — success or stopped,
+never during the run, never when the Doctor blocks it — the archive is
+pruned automatically, before the run is shown as finished, so the list
+never shows the unpruned rows: it keeps the best 10 entries by fitness,
+plus the active genome's full lineage, plus the last run's candidate and
+its lineage. The cap is fixed at 10; there is no setting for it. Each row
+can also be deleted by hand from the list, with four protections: never
+the active genome, never an ancestor of the active genome, never the
+last run's candidate (the report block loads from it), and never a
+genome that still has children.
+
+Rows are the view, files are the record: pruning and deleting remove
+ARCHIVE ROWS ONLY. The stored genomes and their reports stay on disk —
+still included in the backup export, and the proposer's dedupe still
+checks them — so a deleted row never loses data. Pruning is
+best-effort: a failure is silent and never fails the run or loses its
+result.
+
+Honest limit: the list after a prune is the best 10 PLUS the active
+lineage PLUS the last run's candidate and their parent chains, so it
+can be more than 10 rows — 10 is the fitness cap, not a hard row
+count. With a long active lineage the list can still grow, just never
+by piling up losers.
+
+**JVM redirect + ping retry (P10).** Root cause, found on-device:
+Gradle is not corrupt (9.9.3, all jars present including the aarch64
+native-platform jar). The Termux-built JDK 21 ships `java.io.tmpdir`
+and `user.home` baked to `/data/data/com.termux/…`, which does not
+exist for this app UID, so Gradle's native loader cannot create its
+extraction file and `gradle --version` fails with "Could not
+initialize native services". The app already exports HOME and TMPDIR
+inside its own prefix, but this JVM ignores those env vars — only -D
+properties override the baked-in paths. The fix is one entry in
+`processEnv()`: `JDK_JAVA_OPTIONS=-Djava.io.tmpdir=<prefix>/tmp
+-Duser.home=<prefix>/home`, picked up silently by every `java`
+invocation (JDK 21), including the `gradle` launcher. Both values
+stay inside the app's own prefix — nothing ever points at
+Android/data, the sdcard, or any shared storage. The Doctor's model
+pings are also retried up to 3 attempts (1s, then 2s backoff) before
+a ping FAILs: on 2026-11-28 the failures rotated between roles with
+the same model, i.e. transient empty answers, not a broken model.
+A ping that fails all three attempts still FAILs and still blocks
+the run, and the ping itself stays minimal (one word, 8 output
+tokens).
+
+Honest limits: the redirect only fixes JVM tools launched through
+the app's shell — a user running gradle from another terminal still
+needs the flags by hand. The retry does not fix a model that is
+genuinely down; three failures still block. The on-phone proof (the
+Doctor's Gradle check passing, the smoke test running, the suite
+becoming FULL) is verified after install, not in the VM.
+
+## P11 — proposer validation, gate-feasibility check, gate characterization (shipped)
+
+**Proposer validation.** The proposer twice wrote a skill value as a
+JSON object where the genome schema needs a plain string, and both
+proposals died in `decodeFromString` before any evaluation. The SYSTEM
+prompt now shows the format explicitly — "Skill values are PLAIN TEXT
+(the skill's instructions), never an object" — with one example kept
+as `SKILLS_FORMAT_EXAMPLE` so a test can prove the example itself
+parses. The example does not prove the error is gone; what is proven
+is the validator: when decoding fails or the genome breaks the rules,
+the proposer is asked ONCE more with its own error message, and the
+second call's tokens count toward the same proposal. Any other refusal
+(no JSON, identical, reword, blank notes, duplicate, model call
+failed) is final — a re-ask cannot change those verdicts. A defective
+genome never reaches the store, the archive or evaluation. Failures
+that died on validation are counted separately (`validationFailed`),
+and the run summary names them ("— validation failed: N").
+
+**Gate-feasibility check.** Promotion needs a candidate to beat the
+parent by at least 2 tasks on average, so when the parent's latest
+VALID report for the run's suite shows it failed fewer than 2 decided
+tasks, no candidate can gain 2 and a run would only burn quota. The
+host refuses to start such a run — "Benchmark saturated: parent
+passed all but <n> of <total> tasks; promotion needs +2. Run a single
+evaluation or add harder tasks." — with the last-run pointer written
+first, exactly like the Doctor-blocked path. No valid report, an
+invalid report, or a report from another suite or version says nothing
+about this run, so the run starts normally and the runner evaluates
+the parent. This is a 1-seed heuristic, not a proof that promotion is
+impossible, so the screen offers "Run anyway" — only after a
+saturated refusal, and only as an explicit tap. The check decides
+whether a run starts; it never touches scoring, screening,
+confirmation, or the gate itself.
+
+**Gate characterization.** `GateCharacterizationTest` freezes the
+gate's current behaviour as golden tests: +2 with no holdout drop is
+approved; +1 and ties are rejected; a holdout drop is rejected; an
+invalid report on either side is rejected; different suites, worker
+pins or critic pins are never compared; safety violations and a
+missing holdout score are rejected; screening passes a candidate that
+exceeds its parent at 1 seed and rejects one that merely ties. If a
+later round changes the gate, these tests must be updated
+deliberately.
+
+Honest limits: the skills example can anchor the proposer (it may
+copy the example's wording), and with only two real failures nothing
+proves the example kills the error — the validator and the re-ask are
+the parts that are proven, by fixtures built from the two real
+failures. The feasibility check uses the parent's 1-seed report: a
+3-seed mean could in principle differ, which is why the override
+exists. Pre-screen, the Doctor smoke-test cache, the quick-run
+preset and harder tasks are deliberately NOT in this round: with the
+parent at 17/17 the pre-screen's subset is empty, so it would only
+make a useless run cheaper.
+
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
