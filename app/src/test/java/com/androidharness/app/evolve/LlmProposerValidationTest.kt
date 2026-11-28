package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A proposer caller that answers from a script, in order, and records what it was sent. */
private class ScriptedProposerCaller(private val responses: List<String>) : EvolveModelCaller {
    var calls = 0
        private set
    var lastSystem: String? = null
        private set
    var lastUser: String? = null
        private set

    override suspend fun isConfigured(role: EvolveRole): Boolean = true

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult {
        calls++
        lastSystem = system
        lastUser = user
        val text = responses.getOrElse(calls - 1) { responses.last() }
        return ModelCallResult(text, 100, 50, "fake/proposer")
    }
}

/** A caller whose model call always throws, for the model-call-failed case. */
private class ThrowingCaller : EvolveModelCaller {
    var calls = 0
        private set

    override suspend fun isConfigured(role: EvolveRole): Boolean = true

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult {
        calls++
        throw IllegalStateException("boom")
    }
}

class LlmProposerValidationTest {

    private val root: File = Files.createTempDirectory("proposer-validation").toFile()
    private val parent = Genome.baseline()

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    /**
     * A genome JSON exactly like the real failures: the skill value is an
     * OBJECT where the schema needs a plain string, so decoding fails at
     * the skill's path.
     */
    private fun skillsAsObjectJson(skillName: String): String = """
        {
          "label": "gen-skills-object",
          "hypothesis": "A rename guard skill should stop over-renames.",
          "instructions": ["Re-read every file after a rename."],
          "skills": {"$skillName": {"steps": ["re-read", "fix"]}},
          "proposerNotes": "Evidence: the parent over-renames fetchDataInternal."
        }
    """.trimIndent()

    private val encodingJson = Json { encodeDefaults = true }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun validCandidateJson(): String {
        val candidate = parent.copy(
            label = "gen-verify",
            hypothesis = "A verify line should help.",
            instructions = listOf("Verify before claiming done."),
            proposerNotes = "Evidence: the parent claims done without evidence on verify-before-done.",
        )
        return encodingJson.encodeToString(Genome.serializer(), candidate)
    }

    private fun propose(caller: EvolveModelCaller): LlmProposer.Proposed = runBlocking {
        LlmProposer.propose(
            caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks,
        )
    }

    @Test
    fun `a skill value written as an object is refused and names the skill`() {
        val caller = ScriptedProposerCaller(listOf(skillsAsObjectJson("line-list-verify")))
        val proposed = propose(caller)
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        val failed = proposed as LlmProposer.Proposed.Failed
        assertTrue(failed.reason.contains("did not parse"))
        assertTrue(failed.reason.contains("line-list-verify"))
        assertTrue(failed.validationFailed)
        // The re-ask happened (the same bad JSON twice) and is named.
        assertEquals(2, caller.calls)
        assertTrue(failed.reason.contains("re-ask"))
    }

    @Test
    fun `a second skill written as an object is refused the same way`() {
        val caller = ScriptedProposerCaller(listOf(skillsAsObjectJson("identifier-rename-boilerplate")))
        val proposed = propose(caller)
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        val failed = proposed as LlmProposer.Proposed.Failed
        assertTrue(failed.reason.contains("did not parse"))
        assertTrue(failed.reason.contains("identifier-rename-boilerplate"))
        assertTrue(failed.validationFailed)
        assertEquals(2, caller.calls)
    }

    @Test
    fun `a failed schema gets one re-ask and a fixed answer becomes a candidate`() {
        val caller = ScriptedProposerCaller(
            listOf(skillsAsObjectJson("line-list-verify"), validCandidateJson()),
        )
        val proposed = propose(caller)
        assertTrue(proposed is LlmProposer.Proposed.Candidate)
        val candidate = proposed as LlmProposer.Proposed.Candidate
        assertEquals(2, caller.calls)
        // Both calls' tokens count toward the one proposal.
        assertEquals(200L, candidate.promptTokens)
        assertEquals(100L, candidate.completionTokens)
        assertTrue("Verify before claiming done." in candidate.genome.instructions)
        // The re-ask carried the specific error, with the original prompt.
        assertTrue(caller.lastUser!!.contains("Your previous output failed"))
        assertTrue(caller.lastUser!!.contains("did not parse"))
    }

    @Test
    fun `the skills example in the prompt parses with the same parser`() {
        val text = """
            {"label": "gen-example", "hypothesis": "A rename guard helps.",
             "instructions": ["Re-read after renaming."],
             ${LlmProposer.SKILLS_FORMAT_EXAMPLE},
             "proposerNotes": "Evidence: the example skill."}
        """.trimIndent()
        val decoded = lenientJson.decodeFromString(Genome.serializer(), text)
        assertEquals(
            "After renaming, re-read every file and list remaining occurrences of the old name. " +
                "If any remain, fix them before finishing.",
            decoded.skills["rename-guard"],
        )
        // And the model actually receives the example: the system prompt
        // carries both the example and the plain-text rule.
        val caller = ScriptedProposerCaller(listOf(validCandidateJson()))
        assertTrue(propose(caller) is LlmProposer.Proposed.Candidate)
        assertTrue(caller.lastSystem!!.contains("PLAIN TEXT"))
        assertTrue(caller.lastSystem!!.contains(LlmProposer.SKILLS_FORMAT_EXAMPLE))
    }

    @Test
    fun `a genome that breaks the rules is a validation failure and gets one re-ask`() {
        val bad = parent.copy(
            label = "bad",
            hypothesis = "Bad hook.",
            hooks = listOf(GenomeHook("teleport.now", "", "do it", 30)),
        )
        val caller = FakeModelCaller(proposerGenome = { bad })
        val proposed = propose(caller)
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        val failed = proposed as LlmProposer.Proposed.Failed
        assertTrue(failed.reason.contains("breaks the rules"))
        assertTrue(failed.validationFailed)
        assertEquals(2, caller.proposerCalls)
        assertTrue(failed.reason.contains("re-ask"))
    }

    @Test
    fun `validationFailed is false for a duplicate, and a duplicate is never re-asked`() {
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
        val caller = FakeModelCaller(proposerGenome = { candidate })
        val proposed = runBlocking {
            LlmProposer.propose(
                caller, parent, null, emptyList(), LiveEvaluator.proposerView().tasks, store = store,
            )
        }
        assertTrue(proposed is LlmProposer.Proposed.Failed)
        val failed = proposed as LlmProposer.Proposed.Failed
        assertTrue(failed.reason.contains("duplicate instructions"))
        assertTrue(failed.reason.contains(existing.id))
        assertFalse(failed.validationFailed)
        assertEquals(1, caller.proposerCalls)
    }

    @Test
    fun `validationFailed is false for identical, reword, blank notes and a failed model call`() {
        // Identical to its parent.
        val identical = propose(FakeModelCaller(proposerGenome = { parent }))
        assertTrue(identical is LlmProposer.Proposed.Failed)
        assertFalse((identical as LlmProposer.Proposed.Failed).validationFailed)
        assertTrue(identical.reason.contains("identical"))

        // A pure reword: same instruction count, nothing else changed.
        val parentWithOne = Genome.baseline().copy(
            instructions = listOf("Verify before claiming done."),
        ).identified()
        val reword = parentWithOne.copy(
            label = "gen-reword",
            hypothesis = "Same thing, said differently.",
            instructions = listOf("Always verify before you claim you are done."),
            proposerNotes = "Evidence: reworded.",
        )
        val reworded = runBlocking {
            LlmProposer.propose(
                FakeModelCaller(proposerGenome = { reword }),
                parentWithOne, null, emptyList(), LiveEvaluator.proposerView().tasks,
            )
        }
        assertTrue(reworded is LlmProposer.Proposed.Failed)
        val rewordFailed = reworded as LlmProposer.Proposed.Failed
        assertFalse(rewordFailed.validationFailed)
        assertTrue(rewordFailed.reason.contains("reword"))

        // Blank proposerNotes.
        val blankNotes = parent.copy(
            label = "gen-x",
            hypothesis = "A verify line should help.",
            instructions = listOf("Verify before claiming done."),
        )
        val blank = propose(FakeModelCaller(proposerGenome = { blankNotes }))
        assertTrue(blank is LlmProposer.Proposed.Failed)
        val blankFailed = blank as LlmProposer.Proposed.Failed
        assertFalse(blankFailed.validationFailed)
        assertTrue(blankFailed.reason.contains("proposerNotes"))

        // The model call itself failed: not a validation failure, no re-ask.
        val throwing = ThrowingCaller()
        val failedCall = propose(throwing)
        assertTrue(failedCall is LlmProposer.Proposed.Failed)
        assertFalse((failedCall as LlmProposer.Proposed.Failed).validationFailed)
        assertTrue(failedCall.reason.contains("proposer model call failed"))
        assertEquals(1, throwing.calls)
    }

    private fun runner(caller: EvolveModelCaller): EvolutionRunner {
        val host = EvolveHost.at(root)
        val ledger = BudgetLedger(
            maxTokens = host.settings.tokenBudget,
            maxWallMs = host.settings.maxWallMinutes * 60_000L,
            maxGenerations = host.settings.maxGenerations,
            maxCandidates = host.settings.maxCandidates,
            stagnationLimit = host.settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller, delayFn = {})
    }

    @Test
    fun `the runner counts validation failures across a generation`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = ScriptedProposerCaller(listOf(skillsAsObjectJson("line-list-verify")))
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertEquals(host.settings.maxCandidates, result.proposalFailures.size)
        assertEquals(host.settings.maxCandidates, result.validationFailures)
        assertTrue(result.candidateIds.isEmpty())
        assertNull(result.promotableId)
    }

    @Test
    fun `a run sums validation failures across generations`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        // A second archived genome, so the sampler has somewhere to go
        // after the first generation: the run then spans two generations
        // before the stagnation cap stops it.
        val other = host.store.save(
            Genome(label = "other", instructions = listOf("Another instruction."), createdAt = 2L).identified(),
        )
        host.archive.add(ArchiveEntry(genomeId = other.id, label = "other", successRate = 0.0, createdAt = 2L))
        host.store.saveReport(
            EvalReport(
                genomeId = other.id, suiteId = "evolve-live-v1-lite",
                suiteVersion = LiveEvaluator.SUITE_VERSION, seeds = listOf(1),
                valid = true, successRate = 0.0, meanPassedTasks = 0.0,
            ),
        )
        val caller = ScriptedProposerCaller(listOf(skillsAsObjectJson("line-list-verify")))
        val result = runner(caller).run(parent, File(root, "work"))
        assertEquals(2, result.generations.size)
        assertEquals(result.generations.sumOf { it.validationFailures }, result.validationFailures)
        assertEquals(2 * host.settings.maxCandidates, result.validationFailures)
    }

    private suspend fun awaitSettled(host: EvolveHost) {
        withTimeout(120_000) {
            host.runState.first { it != null && !it.running }
        }
    }

    @Test
    fun `the host summary names the validation failures`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = EvolveHost.at(root)
        host.store.save(Genome.baseline())
        val caller = ScriptedProposerCaller(listOf(skillsAsObjectJson("line-list-verify")))
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertTrue(state.summary!!.contains("validation failed:"))
    }
}
