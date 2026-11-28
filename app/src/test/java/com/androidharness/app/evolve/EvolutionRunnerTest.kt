package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted stand-in for a real model: the proposer returns a genome the
 * test builds, the worker returns the correct file blocks for each task
 * (computed from the task prompt, like a competent model would), and the
 * critic passes. This verifies the harness mechanics — propose, evaluate,
 * archive, gate — not any real model's skill.
 */
class FakeModelCaller(
    private val proposerGenome: (Genome) -> Genome? = { null },
    private val worker: (String) -> String = { "" },
    /** Strict JSON by default (P8): the critic answers {"verdict":"PASS|FAIL","reason":"..."}. */
    private val criticVerdict: String = "{\"verdict\":\"PASS\",\"reason\":\"ok\"}",
    private val configuredRoles: Set<EvolveRole> = EvolveRole.entries.toSet(),
    /**
     * A worker that also sees the system prompt (the genome's
     * instructions), for tests where the worker must behave differently
     * under the parent than under a candidate. Takes precedence over
     * [worker] when set.
     */
    private val workerSystem: ((String, String) -> String)? = null,
) : EvolveModelCaller {
    var proposerCalls = 0
        private set
    var workerCalls = 0
        private set
    var criticCalls = 0
        private set

    override suspend fun isConfigured(role: EvolveRole): Boolean = role in configuredRoles

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult = when (role) {
        EvolveRole.PROPOSER -> {
            proposerCalls++
            // The test's proposer genome is supplied via the parent in the prompt;
            // the fake extracts nothing — it returns the canned genome JSON.
            val genome = proposerGenome(Genome.baseline())
            if (genome == null) ModelCallResult("not json at all", 10, 5)
            else ModelCallResult(
                Json { encodeDefaults = true }.encodeToString(Genome.serializer(), genome),
                100, 50, "fake/proposer",
            )
        }
        EvolveRole.WORKER -> {
            workerCalls++
            ModelCallResult(workerSystem?.invoke(system, user) ?: worker(user), 200, 100, "fake/worker")
        }
        EvolveRole.CRITIC -> {
            criticCalls++
            ModelCallResult(criticVerdict, 50, 10, "fake/critic")
        }
    }

    companion object {
        /** A worker that does each live task correctly, by reading the prompt. */
        fun correctWorker(): (String) -> String = { user ->
            when {
                // The Doctor's 1-token ping: a real model answers "ok".
                "Reply with exactly one word" in user -> "ok"
                "lines 101-150" in user && "claim.txt" in user -> {
                    val page = (101..150).joinToString("\n") { "line $it" }
                    "```page.txt\n$page\n```\n```claim.txt\nstatus: done\nevidence: page.txt has 50 lines\n```"
                }
                "lines 101-150" in user -> {
                    val page = (101..150).joinToString("\n") { "line $it" }
                    "```page.txt\n$page\n```"
                }
                "needle" in user -> "```hits.txt\na.txt: needle here\nc.md: another needle\n```"
                "ALPHA" in user -> "```edit.txt\nALPHA\nbeta\nGAMMA\n```"
                // Across two files first: its prompt also mentions loadData.
                "util.kt" in user -> "```main.kt\nfun loadData(): String {\n    return fetchDataInternal()\n}\n\nfun fetchDataInternal(): String = \"data\"\n```\n```util.kt\nfun useIt(): String = loadData()\n```"
                "getData" in user -> "```main.kt\nfun fetchData(): String {\n    return getDataInternal() + getDataFromCache()\n}\n\nfun getDataInternal(): String = \"data\"\n\nfun getDataFromCache(): String = \"cached\"\n```"
                "computeTotal" in user -> "```service.kt\nfun sumTotal(a: Int, b: Int): Int {\n    return computeTotalInternal(a, b)\n}\n\nfun computeTotalInternal(a: Int, b: Int): Int = a + b\n```\n```app.kt\nfun main() {\n    println(sumTotal(2, 3))\n}\n```"
                "sorted alphabetically" in user -> "```sorted.txt\napple\napple\nbanana\ncherry\n```"
                "first-seen order" in user -> "```unique.txt\nalpha\nbeta\ngamma\n```"
                // The correct rename keeps fetchDataInternal untouched:
                // the old checker failed a correct rename, so this used
                // to over-rename and pass by accident.
                "loadData" in user -> "```main.kt\nfun loadData(): String {\n    return fetchDataInternal()\n}\n\nfun fetchDataInternal(): String = \"data\"\n```"
                "second line to TWO" in user -> "```patch.txt\none\nTWO\nthree\n```"
                "modified:" in user -> "```summary.txt\nmodified: 1\nuntracked: 1\n```"
                "pinned constraint" in user -> {
                    "```summary.txt\nfiller line 1\nfiller line 2\nfiller line 3\nfiller line 4\nfiller line 5\nfiller line 46\nfiller line 47\nfiller line 48\nfiller line 49\nfiller line 50\n[PINNED] keep the 30 minute cap\n```"
                }
                else -> ""
            }
        }

        /**
         * A worker that does most tasks correctly but over-renames the
         * rename tasks (loadDataInternal) and mis-sorts: the parent's
         * score in tests where a candidate must beat it.
         */
        fun imperfectWorker(): (String) -> String = { user ->
            when {
                "util.kt" in user -> "```main.kt\nfun loadData(): String {\n    return loadDataInternal()\n}\n\nfun loadDataInternal(): String = \"data\"\n```\n```util.kt\nfun useIt(): String = loadData()\n```"
                "loadData" in user -> "```main.kt\nfun loadData(): String {\n    return loadDataInternal()\n}\n\nfun loadDataInternal(): String = \"data\"\n```"
                "sorted alphabetically" in user -> "```sorted.txt\nbanana\napple\ncherry\napple\n```"
                else -> correctWorker()(user)
            }
        }
    }
}

class EvolutionRunnerTest {

    private val root: File = Files.createTempDirectory("runner").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
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
        // No-op delays: retries and the 1-second inter-task pause are
        // real in production but must never slow a test.
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller, delayFn = {})
    }

    /**
     * A caller whose worker is imperfect under the parent (it
     * over-renames and mis-sorts) and correct under a candidate that
     * carries the verify instruction: the only way a candidate can
     * exceed a parent at 1 seed in a scripted test.
     */
    private fun improvingCaller(parent: Genome): FakeModelCaller = FakeModelCaller(
        proposerGenome = { _ ->
            parent.copy(
                label = "gen-verify",
                hypothesis = "A verify-before-done line should stop claims without evidence.",
                instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                proposerNotes = "Evidence: the parent over-renames fetchDataInternal and mis-sorts; a verify step should stop both.",
            )
        },
        workerSystem = { system, user ->
            // workerSystem returns the worker's OUTPUT for this call,
            // not the worker function itself.
            val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
            else FakeModelCaller.imperfectWorker()
            w(user)
        },
    )

    @Test
    fun `one generation with a real proposer and worker produces a promotable candidate`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = improvingCaller(parent)
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        // P8: the toolchain is not installed in tests, so the Doctor
        // selects the lite suite — the 13 live tasks only, no real ones.
        assertTrue(result.reports.all { it.tasks.size == 13 })
        assertTrue(result.reports.all { it.suiteId == "evolve-live-v1-lite" })
        assertNotNull(result.promotableId)
        // Attempts 2-4 re-propose the same genome: the dedupe rejects
        // them, and the rejections are recorded, not silent.
        assertTrue(result.proposalFailures.isNotEmpty())
        assertTrue(result.proposalFailures.all { "duplicate" in it })
        result.candidateIds.forEach { assertNotNull(host.store.load(it)) }
        val archived = host.archive.entries().map { it.genomeId }.toSet()
        assertTrue(archived.containsAll(result.candidateIds))
        assertTrue(archived.contains(parent.id))
    }

    @Test
    fun `a full run stops within the generation cap`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val result = runner(improvingCaller(parent)).run(parent, File(root, "work"))
        assertTrue(result.generations.size in 1..host.settings.maxGenerations)
        assertNotNull(result.promotableId)
        assertNotNull(host.store.load(result.promotableId!!))
    }

    @Test
    fun `without a configured model the run refuses instead of faking a score`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(configuredRoles = emptySet())
        try {
            runner(caller).runGeneration(parent, File(root, "work"))
            throw AssertionError("expected NoModelConfigured")
        } catch (e: NoModelConfigured) {
            assertTrue(e.message!!.contains("PROPOSER"))
        }
    }

    @Test
    fun `a proposer that returns no JSON produces no candidate, only a recorded failure`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(proposerGenome = { null }, worker = FakeModelCaller.correctWorker())
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isEmpty())
        assertEquals(host.settings.maxCandidates, result.proposalFailures.size)
        assertEquals(null, result.promotableId)
    }

    /** A report with the given mean task-pass count, for gate tests. The suite has 13 tasks. */
    private fun report(
        id: String,
        passedTasks: Double,
        holdout: Double?,
        suite: String = "evolve-live-v1",
        pin: String = "live/fake/worker",
        critic: String = "fake/critic",
        valid: Boolean = true,
        tokensPerSuccess: Double = 100.0,
        safety: Int = 0,
    ): EvalReport = EvalReport(
        genomeId = id,
        suiteId = suite,
        successRate = passedTasks / 13.0,
        holdoutScore = holdout,
        tokensPerSuccess = tokensPerSuccess,
        meanPassedTasks = passedTasks,
        modelPin = pin,
        criticPin = critic,
        valid = valid,
        invalidReason = if (valid) null else "too many tasks were ERROR/SKIPPED",
        safetyViolations = safety,
    )

    @Test
    fun `the gate rule without approval - two tasks, safety and token regression`() {
        val settings = EvolveSettings.DEFAULT
        val baseline = report("base", 11.0, 1.0)
        val same = report("same", 11.0, 1.0)
        assertTrue(PromotionGate.wouldPromote(baseline, same, true, settings) is GateDecision.Rejected)
        // One task more is noise, never a promotion.
        val oneMore = report("one-more", 12.0, 1.0)
        assertTrue(PromotionGate.wouldPromote(baseline, oneMore, true, settings) is GateDecision.Rejected)
        // Two tasks more, holdout not lower: promotable.
        val better = report("better", 13.0, 1.0)
        assertTrue(PromotionGate.wouldPromote(baseline, better, true, settings) is GateDecision.Approved)
        val unsafe = report("unsafe", 13.0, 1.0, safety = 1)
        assertTrue(PromotionGate.wouldPromote(baseline, unsafe, false, settings) is GateDecision.Rejected)
        val wasteful = report("wasteful", 13.0, 1.0, tokensPerSuccess = 200.0)
        assertTrue(PromotionGate.wouldPromote(baseline, wasteful, true, settings) is GateDecision.Rejected)
        val first = report("first", 5.0, 0.5)
        assertTrue(PromotionGate.wouldPromote(null, first, true, settings) is GateDecision.Approved)
        val zero = report("zero", 0.0, 0.0)
        assertTrue(PromotionGate.wouldPromote(null, zero, true, settings) is GateDecision.Rejected)
    }

    @Test
    fun `the gate also needs a holdout win and refuses cross-suite and cross-model comparisons`() {
        val settings = EvolveSettings.DEFAULT
        val baseline = report("base", 11.0, 1.0)
        // Wins by two tasks, loses holdout.
        val holdoutLoss = report("holdout-loss", 13.0, 0.5)
        assertTrue(PromotionGate.wouldPromote(baseline, holdoutLoss, true, settings) is GateDecision.Rejected)
        // No holdout evidence at all cannot be offered.
        val noHoldout = report("no-holdout", 13.0, null)
        assertTrue(PromotionGate.wouldPromote(baseline, noHoldout, true, settings) is GateDecision.Rejected)
        // A parent that already aces holdout cannot be beaten there, so tying it counts.
        val aceTie = report("ace-tie", 13.0, 1.0)
        assertTrue(PromotionGate.wouldPromote(baseline, aceTie, true, settings) is GateDecision.Approved)
        // The first candidate must score on holdout too.
        val firstFlat = report("first-flat", 5.0, 0.0)
        assertTrue(PromotionGate.wouldPromote(null, firstFlat, true, settings) is GateDecision.Rejected)
        // An invalid report is never promotable, however good it looks.
        val invalid = report("invalid", 13.0, 1.0, valid = false)
        assertTrue(PromotionGate.wouldPromote(baseline, invalid, true, settings) is GateDecision.Rejected)
        // An invalid baseline cannot be compared against either.
        val invalidBaseline = report("invalid-base", 11.0, 1.0, valid = false)
        assertTrue(PromotionGate.wouldPromote(invalidBaseline, report("better", 13.0, 1.0), true, settings) is GateDecision.Rejected)
        // A lite report and a full report have different denominators: never compared.
        val lite = report("lite", 13.0, 1.0, suite = "evolve-live-v1-lite")
        assertTrue(PromotionGate.wouldPromote(baseline, lite, true, settings) is GateDecision.Rejected)
        // Reports judged by different models are never compared.
        val otherModel = report("other-model", 13.0, 1.0, pin = "live/other/model")
        assertTrue(PromotionGate.wouldPromote(baseline, otherModel, true, settings) is GateDecision.Rejected)
        val otherCritic = report("other-critic", 13.0, 1.0, critic = "other/critic")
        assertTrue(PromotionGate.wouldPromote(baseline, otherCritic, true, settings) is GateDecision.Rejected)
    }

    @Test
    fun `a vetoed candidate is archived as vetoed and never promotable`() = runBlocking {
        val host = EvolveHost.at(root)
        val bad = Genome.baseline().copy(
            instructions = listOf("Open PromotionGate.kt first."),
        ).identified()
        assertTrue(SafetyProbes.vetoed(bad, root))
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-verify",
                    hypothesis = "A verify-before-done line should stop claims without evidence.",
                    instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                    proposerNotes = "Evidence: the parent over-renames; a verify step should stop it.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        val vetoed = host.archive.entries().filter { it.status == ArchiveStatus.VETOED }
        assertTrue(vetoed.none { it.genomeId == result.promotableId })
        assertEquals(null, result.reports.firstOrNull { it.safetyViolations > 0 })
    }

    /** A caller whose worker always fails with an infra error. */
    private class ThrowingWorkerCaller(
        private val inner: FakeModelCaller,
        private val message: String,
    ) : EvolveModelCaller {
        override suspend fun isConfigured(role: EvolveRole): Boolean = inner.isConfigured(role)
        override suspend fun complete(
            role: EvolveRole,
            system: String,
            user: String,
            maxOutputTokens: Int,
        ): ModelCallResult {
            if (role == EvolveRole.WORKER) throw RuntimeException(message)
            return inner.complete(role, system, user, maxOutputTokens)
        }
    }

    @Test
    fun `an all-error run produces an invalid report that is never promotable`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = ThrowingWorkerCaller(improvingCaller(parent), "quota exhausted")
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertNull(result.promotableId)
        val report = host.store.report(result.candidateIds.first())!!
        assertFalse(report.valid)
        assertNotNull(report.invalidReason)
        assertTrue(report.tasks.all { it.resolvedStatus == TaskStatus.ERROR })
        val entry = host.archive.entries().first { it.genomeId == result.candidateIds.first() }
        assertNotNull(entry.gateReason)
        assertTrue(entry.gateReason!!.contains("invalid"))
    }

    @Test
    fun `the lite suite is selected when the toolchain is missing, the full suite when it is ready`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val lite = runner(improvingCaller(parent)).runGeneration(parent, File(root, "work-lite"))
        assertTrue(lite.reports.all { it.tasks.size == 13 })
        assertTrue(lite.reports.all { it.suiteId == "evolve-live-v1-lite" })
        val ready = object : TestCommandRunner {
            override val toolchainReady: Boolean = true
            override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult =
                TestCommandResult(0, false, "", "")
        }
        try {
            RealProjectEvaluator.commandRunner = ready
            // A different candidate than the lite run's, or the proposer's
            // dedupe refuses it as a duplicate: different instructions
            // AND a different hypothesis.
            val fullCaller = FakeModelCaller(
                proposerGenome = { _ ->
                    parent.copy(
                        label = "gen-verify-full",
                        hypothesis = "A verify line plus a rename double-check should stop claims without evidence.",
                        instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence. Check renames twice.",
                        proposerNotes = "Evidence: the parent over-renames fetchDataInternal and mis-sorts; a verify step should stop both.",
                    )
                },
                workerSystem = { system, user ->
                    // workerSystem returns the worker's OUTPUT for this
                    // call, not the worker function itself.
                    val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
                    else FakeModelCaller.imperfectWorker()
                    w(user)
                },
            )
            val full = runner(fullCaller).runGeneration(parent, File(root, "work-full"))
            assertTrue(full.reports.all { it.tasks.size == 17 })
            assertTrue(full.reports.all { it.suiteId == "evolve-live-v1" })
            assertNotNull(full.promotableId)
        } finally {
            RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        }
    }

    @Test
    fun `the baseline report is cached and reused on a second run`() = runBlocking {
        val host = EvolveHost.at(root)
        host.updateSettings(host.settings.copy(
            workerModel = com.androidharness.app.data.ChatModelChoice("fake", "worker"),
            criticModel = com.androidharness.app.data.ChatModelChoice("fake", "critic"),
        ))
        val parent = host.store.save(Genome.baseline())
        var baselineWorkerCalls = 0
        var n = 0
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                n++
                parent.copy(
                    label = "gen-$n",
                    hypothesis = "Candidate $n.",
                    instructions = parent.instructions + "Step $n: verify before claiming done.",
                    proposerNotes = "Evidence: candidate $n.",
                )
            },
            workerSystem = { system, user ->
                if ("(no instructions)" in system) baselineWorkerCalls++
                FakeModelCaller.correctWorker()(user)
            },
        )
        val first = runner(caller).runGeneration(parent, File(root, "work1"))
        assertTrue(first.candidateIds.isNotEmpty())
        assertTrue(baselineWorkerCalls > 0)
        val afterFirst = baselineWorkerCalls
        val second = runner(caller).runGeneration(parent, File(root, "work2"))
        // The second run still produced a new candidate; it just did not
        // re-evaluate Baseline — the cached report was reused.
        assertTrue(second.candidateIds.isNotEmpty())
        assertEquals(afterFirst, baselineWorkerCalls)
    }

    @Test
    fun `a candidate that does not exceed its parent at 1 seed is never confirmed`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-same",
                    hypothesis = "Same score.",
                    instructions = parent.instructions + "Verify before claiming done.",
                    proposerNotes = "Evidence: the candidate scores the same as the parent.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertNull(result.promotableId)
        val report = host.store.report(result.candidateIds.first())!!
        assertEquals(listOf(1), report.seeds)
        val entry = host.archive.entries().first { it.genomeId == result.candidateIds.first() }
        assertNotNull(entry.gateReason)
        assertTrue(entry.gateReason!!.contains("screening"))
    }

    @Test
    fun `a candidate that exceeds its parent gets exactly 3 confirmation seeds`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val result = runner(improvingCaller(parent)).runGeneration(parent, File(root, "work"))
        assertNotNull(result.promotableId)
        val report = host.store.report(result.promotableId!!)!!
        assertEquals(listOf(1, 2, 3), report.seeds)
        assertEquals(13.0, report.meanPassedTasks, 0.001)
    }
}
