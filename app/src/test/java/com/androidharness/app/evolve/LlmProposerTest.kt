package com.androidharness.app.evolve

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProposerTest {

    private val parent = Genome.baseline()

    @Test
    fun `extracts a fenced json genome and ignores prose around it`() {
        val text = "Here is my proposal:\n```json\n{\"label\": \"x\", \"instructions\": [\"Do it.\"]}\n```\nHope it helps."
        val json = LlmProposer.extractJson(text)
        assertTrue(json != null && json.startsWith("{") && "\"label\": \"x\"" in json)
    }

    @Test
    fun `extracts raw json without fences`() {
        assertEquals("{\"a\":1}", LlmProposer.extractJson("{\"a\":1}"))
    }

    @Test
    fun `returns null when there is no json object`() {
        assertEquals(null, LlmProposer.extractJson("no json here at all"))
    }

    @Test
    fun `a valid proposed genome is accepted and linked to its parent`() = runBlocking {
        val candidate = parent.copy(
            label = "gen-verify",
            hypothesis = "A verify line should help.",
            instructions = listOf("Verify before claiming done."),
            proposerNotes = "Evidence: the parent claims done without evidence on verify-before-done.",
        )
        val caller = FakeModelCaller(
            proposerGenome = { candidate },
        )
        val proposed = LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Candidate)
        val got = (proposed as LlmProposer.Proposed.Candidate).genome
        assertEquals(parent.id, got.parent)
        assertTrue("Verify before claiming done." in got.instructions)
        assertTrue(got.id.isNotBlank() && got.id != parent.id)
    }

    @Test
    fun `a genome identical to its parent is refused`() = runBlocking {
        val caller = FakeModelCaller(proposerGenome = { parent })
        val proposed = LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        assertTrue((proposed as LlmProposer.Proposed.Failed).reason.contains("identical"))
    }

    @Test
    fun `a genome naming a protected file is refused`() = runBlocking {
        val bad = parent.copy(
            label = "bad",
            hypothesis = "Break the gate.",
            instructions = listOf("Open PromotionGate.kt and disable the gate."),
        )
        val caller = FakeModelCaller(proposerGenome = { bad })
        val proposed = LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Failed)
    }

    @Test
    fun `a genome with an unknown hook event is refused`() = runBlocking {
        val bad = parent.copy(
            label = "bad",
            hypothesis = "Bad hook.",
            hooks = listOf(GenomeHook("teleport.now", "", "do it", 30)),
        )
        val caller = FakeModelCaller(proposerGenome = { bad })
        val proposed = LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Failed)
    }

    @Test
    fun `the proposer never sees holdout tasks`() {
        val view = LiveEvaluator.proposerView()
        assertEquals(11, view.tasks.size)
        assertTrue(view.tasks.none { it.isHoldout })
        assertTrue(LiveEvaluator.holdoutIds().containsAll(listOf("git-status-diff", "pinned-constraint")))
        val prompt = LlmProposer.promptFor(parent, null, emptyList(), view.tasks)
        assertTrue("git-status-diff" !in prompt)
        assertTrue("pinned-constraint" !in prompt)
    }

    @Test
    fun `the prompt names the parent failures so the model can target them`() {
        val report = EvalReport(
            genomeId = parent.id,
            tasks = listOf(
                TaskResult("file-read-paging", true),
                TaskResult("verify-before-done", false, "worker produced no file blocks"),
            ),
            successRate = 0.5,
        )
        val prompt = LlmProposer.promptFor(parent, report, emptyList(), LiveEvaluator.proposerView().tasks)
        assertTrue("verify-before-done" in prompt)
        assertTrue("worker produced no file blocks" in prompt)
    }

    @Test
    fun `the prompt classifies failures as agent or infra and says infra cannot be fixed`() {
        val report = EvalReport(
            genomeId = parent.id,
            tasks = listOf(
                TaskResult("rename-function", false, "old name fetchData is still present", status = TaskStatus.FAIL),
                TaskResult("sort-lines", false, "worker call failed: quota exhausted", status = TaskStatus.ERROR),
            ),
            successRate = 0.0,
        )
        val prompt = LlmProposer.promptFor(parent, report, emptyList(), LiveEvaluator.proposerView().tasks)
        assertTrue("[agent] rename-function" in prompt)
        assertTrue("[infra] sort-lines" in prompt)
        assertTrue("cannot fix them" in prompt)
    }

    @Test
    fun `an invalid parent report is withheld from the proposer with an explicit note`() {
        val prompt = LlmProposer.promptFor(
            parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
            parentReportInvalid = "60% of tasks were ERROR/SKIPPED",
        )
        assertTrue("INVALID" in prompt)
        assertTrue("not a fair baseline" in prompt)
    }

    @Test
    fun `a candidate with blank proposerNotes is refused`() = runBlocking {
        val candidate = parent.copy(
            label = "gen-x",
            hypothesis = "A verify line should help.",
            instructions = listOf("Verify before claiming done."),
        )
        val caller = FakeModelCaller(proposerGenome = { candidate })
        val proposed = LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        assertTrue((proposed as LlmProposer.Proposed.Failed).reason.contains("proposerNotes"))
    }

    @Test
    fun `a candidate that only rewords the parent is refused, an added instruction is not`() = runBlocking {
        val parentWithOne = Genome.baseline().copy(
            instructions = listOf("Verify before claiming done."),
        ).identified()
        val reword = parentWithOne.copy(
            label = "gen-reword",
            hypothesis = "Same thing, said differently.",
            instructions = listOf("Always verify before you claim you are done."),
            proposerNotes = "Evidence: reworded.",
        )
        val refused = LlmProposer.propose(
            FakeModelCaller(proposerGenome = { reword }),
            parentWithOne, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(refused is LlmProposer.Proposed.Failed)
        assertTrue((refused as LlmProposer.Proposed.Failed).reason.contains("reword"))
        val added = parentWithOne.copy(
            label = "gen-added",
            hypothesis = "A second check should catch renames.",
            instructions = parentWithOne.instructions + "Check renames twice.",
            proposerNotes = "Evidence: the parent over-renames.",
        )
        val accepted = LlmProposer.propose(
            FakeModelCaller(proposerGenome = { added }),
            parentWithOne, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
        assertTrue(accepted is LlmProposer.Proposed.Candidate)
    }

    @Test
    fun `an instructions-hash duplicate is refused and names the duplicate`() = runBlocking {
        val root = java.io.File.createTempFile("proposer-dedupe", "").apply { delete(); mkdirs() }
        try {
            val store = GenomeStore(root)
            val existing = store.save(
                parent.copy(
                    label = "existing",
                    hypothesis = "First.",
                    instructions = listOf("Verify before claiming done."),
                    proposerNotes = "Evidence: first.",
                ).identified(),
            )
            val candidate = parent.copy(
                label = "dup",
                hypothesis = "A different hypothesis.",
                instructions = listOf("Verify before claiming done."),
                proposerNotes = "Evidence: dup.",
            )
            val proposed = LlmProposer.propose(
                FakeModelCaller(proposerGenome = { candidate }),
                parent, null, emptyList(), LiveEvaluator.proposerView().tasks, store = store,
            )
            assertTrue(proposed is LlmProposer.Proposed.Failed)
            val reason = (proposed as LlmProposer.Proposed.Failed).reason
            assertTrue(reason.contains("duplicate instructions"))
            assertTrue(reason.contains(existing.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a hypothesis duplicate is refused and names the duplicate`() = runBlocking {
        val root = java.io.File.createTempFile("proposer-dedupe-h", "").apply { delete(); mkdirs() }
        try {
            val store = GenomeStore(root)
            val existing = store.save(
                parent.copy(
                    label = "existing",
                    hypothesis = "A verify line should help.",
                    instructions = listOf("Verify before claiming done."),
                    proposerNotes = "Evidence: first.",
                ).identified(),
            )
            val candidate = parent.copy(
                label = "dup",
                hypothesis = "A verify line should help.",
                instructions = listOf("Verify before claiming done.", "Check renames twice."),
                proposerNotes = "Evidence: dup.",
            )
            val proposed = LlmProposer.propose(
                FakeModelCaller(proposerGenome = { candidate }),
                parent, null, emptyList(), LiveEvaluator.proposerView().tasks, store = store,
            )
            assertTrue(proposed is LlmProposer.Proposed.Failed)
            val reason = (proposed as LlmProposer.Proposed.Failed).reason
            assertTrue(reason.contains("duplicate hypothesis"))
            assertTrue(reason.contains(existing.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a hypothesis already in the archive is refused`() = runBlocking {
        val candidate = parent.copy(
            label = "dup",
            hypothesis = "A verify line should help.",
            instructions = listOf("Verify before claiming done.", "Check renames twice."),
            proposerNotes = "Evidence: dup.",
        )
        val archive = listOf(
            ArchiveEntry(genomeId = "abcdef0123456789", hypothesis = "A verify line should help."),
        )
        val proposed = LlmProposer.propose(
            FakeModelCaller(proposerGenome = { candidate }),
            parent, null, archive, LiveEvaluator.proposerView().tasks,
        )
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        val reason = (proposed as LlmProposer.Proposed.Failed).reason
        assertTrue(reason.contains("duplicate hypothesis"))
        assertTrue(reason.contains("abcdef0123456789"))
    }
}
