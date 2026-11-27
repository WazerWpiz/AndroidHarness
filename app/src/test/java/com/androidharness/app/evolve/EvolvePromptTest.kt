package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolvePromptTest {

    private val root: File = Files.createTempDirectory("prompt").toFile()
    private val host = EvolveHost.at(root)
    private val approval = PromotionGate.Approval("tester")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun promoted(
        instructions: List<String> = emptyList(),
        skills: Map<String, String> = emptyMap(),
        skillEnable: Set<String> = emptySet(),
    ): Genome {
        val genome = host.store.save(
            Genome(
                label = "Test",
                hypothesis = "Why not.",
                instructions = instructions,
                skills = skills,
                skillEnable = skillEnable,
            ).identified(),
        )
        assertTrue(host.gate.promote(genome.id, approval).isSuccess)
        return genome
    }

    @Test
    fun `no active genome means no prompt section`() {
        host.updateSettings(host.settings.copy(enabled = true))
        assertNull(EvolvePrompt.sectionFor(host))
    }

    @Test
    fun `a switched-off harness means no prompt section even with an active genome`() {
        promoted(instructions = listOf("Re-read every changed site."))
        assertFalse(host.settings.enabled)
        assertNull(EvolvePrompt.sectionFor(host))
    }

    @Test
    fun `an enabled harness injects the active genome instructions`() {
        val genome = promoted(
            instructions = listOf("Re-read every changed site.", "Prove completion with evidence."),
        )
        host.updateSettings(host.settings.copy(enabled = true))
        val section = EvolvePrompt.sectionFor(host)
        assertNotNull(section)
        assertTrue(section!!.contains("Re-read every changed site."))
        assertTrue(section.contains("Prove completion with evidence."))
        assertTrue(section.contains(genome.id))
        assertTrue(section.contains("never override safety rules"))
    }

    @Test
    fun `only enabled skills are included`() {
        promoted(
            skills = mapOf("alpha" to "Skill A text", "beta" to "Skill B text"),
            skillEnable = setOf("alpha"),
        )
        host.updateSettings(host.settings.copy(enabled = true))
        val section = EvolvePrompt.sectionFor(host)
        assertNotNull(section)
        assertTrue(section!!.contains("Skill A text"))
        assertFalse(section.contains("Skill B text"))
    }

    @Test
    fun `skill text is capped so one genome cannot flood the context`() {
        promoted(
            skills = mapOf("big" to "x".repeat(9_000) + "TAILMARKER"),
            skillEnable = setOf("big"),
        )
        host.updateSettings(host.settings.copy(enabled = true))
        val section = EvolvePrompt.sectionFor(host)
        assertNotNull(section)
        assertFalse(section!!.contains("TAILMARKER"))
    }

    @Test
    fun `a genome with no instructions and no enabled skills gives no section`() {
        promoted()
        host.updateSettings(host.settings.copy(enabled = true))
        assertNull(EvolvePrompt.sectionFor(host))
    }
}
