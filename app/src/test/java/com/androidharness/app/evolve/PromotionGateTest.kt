package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionGateTest {

    private val root: File = Files.createTempDirectory("promote").toFile()
    private val store = GenomeStore(root)
    private val archive = Archive(File(root, "archive.json"))
    private val gate = PromotionGate(store, archive)
    private val approval = PromotionGate.Approval("Sanu")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun saved(label: String, parent: String? = null): Genome =
        store.save(Genome(label = label, parent = parent, createdAt = System.currentTimeMillis(),
            hypothesis = "Why not.", instructions = listOf("Instruction for $label")).identified())

    @Test
    fun `nothing is active until a genome is promoted`() {
        saved("Baseline")
        assertNull(store.active())
    }

    @Test
    fun `promotion needs an approval value, and the gate exposes no overload without one`() {
        // promote() returns kotlin.Result, so the Kotlin compiler mangles its JVM name
        // to "promote-<hash>"; match the mangled name too, or reflection finds nothing.
        val promoteMethods = PromotionGate::class.java.methods.filter { it.name == "promote" || it.name.startsWith("promote-") }
        assertTrue(promoteMethods.isNotEmpty())
        assertTrue(promoteMethods.all { method -> method.parameterTypes.contains(PromotionGate.Approval::class.java) })
        val result = runCatching { PromotionGate.Approval(" ") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `an approved genome becomes active and the previous one is archived`() {
        val first = saved("Baseline")
        val second = saved("Paging", parent = first.id)
        archive.add(ArchiveEntry(genomeId = first.id, label = "Baseline", status = ArchiveStatus.ACTIVE))
        archive.add(ArchiveEntry(genomeId = second.id, parent = first.id, label = "Paging", status = ArchiveStatus.EVALUATED))

        assertTrue(gate.promote(first.id, approval).isSuccess)
        assertTrue(gate.promote(second.id, approval).isSuccess)

        assertEquals(second.id, store.active()?.genomeId)
        assertEquals(first.id, store.active()?.previous)
        assertEquals(ArchiveStatus.ARCHIVED, archive.entries().first { it.genomeId == first.id }.status)
        assertEquals(ArchiveStatus.ACTIVE, archive.entries().first { it.genomeId == second.id }.status)
    }

    @Test
    fun `rollback restores the exact parent hash`() {
        val first = saved("Baseline")
        val second = saved("Paging", parent = first.id)
        gate.promote(first.id, approval)
        gate.promote(second.id, approval)

        val rolledBack = gate.rollback(approval).getOrThrow()
        assertEquals(first.id, rolledBack.id)
        assertEquals(first.computeId(), store.load(rolledBack.id)?.computeId())
        assertEquals(first.id, store.active()?.genomeId)
        assertNull(store.active()?.previous)
    }

    @Test
    fun `rollback without an active genome is refused`() {
        assertTrue(gate.rollback(approval).isFailure)
    }

    @Test
    fun `rollback without a parent is refused`() {
        val only = saved("Baseline")
        gate.promote(only.id, approval)
        assertTrue(gate.rollback(approval).isFailure)
    }

    @Test
    fun `a vetoed genome cannot be promoted`() {
        val genome = saved("Vetoed")
        archive.add(ArchiveEntry(genomeId = genome.id, status = ArchiveStatus.VETOED, safetyViolations = 1))
        val result = gate.promote(genome.id, approval)
        assertTrue(result.isFailure)
        assertNull(store.active())
    }

    @Test
    fun `a genome with safety violations cannot be promoted even when archived as evaluated`() {
        val genome = saved("Risky")
        archive.add(ArchiveEntry(genomeId = genome.id, status = ArchiveStatus.EVALUATED, safetyViolations = 2))
        assertTrue(gate.promote(genome.id, approval).isFailure)
        assertNull(store.active())
    }

    @Test
    fun `an unknown genome cannot be promoted`() {
        assertTrue(gate.promote("ffffffffffffffff", approval).isFailure)
    }

    @Test
    fun `there is no promote tool for the agent`() = runBlocking {
        saved("Baseline")
        val host = EvolveHost.at(root)
        val tools = host.tools()
        assertTrue(tools.none { "promote" in it.name })
        // Propose and evaluate are the only tools that write, and all they
        // write is candidates, reports and archive entries. Everything else
        // is read-only. (P0 had only read-only tools, so this test used to
        // assert that for all of them; the loop cannot run without writers.)
        assertEquals(
            listOf("evolve_evaluate", "evolve_propose"),
            tools.filter { !it.isReadOnly }.map { it.name }.sorted(),
        )
        // Running every tool must never make a genome active: promotion is a
        // settings action behind an approval card, never an agent call.
        val ctx = ToolContext(FileFs(root))
        tools.forEach { it.execute(JsonObject(emptyMap()), ctx) }
        assertNull(store.active())
        assertNull(host.store.active())
    }
}
