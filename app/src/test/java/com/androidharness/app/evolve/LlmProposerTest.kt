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
        assertEquals(6, view.tasks.size)
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
}
