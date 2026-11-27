package com.androidharness.app.evolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetLedgerTest {

    @Test
    fun `consume refuses an overshoot and changes nothing`() {
        val ledger = BudgetLedger(maxTokens = 100, maxWallMs = 1_000)
        assertTrue(ledger.consume(BudgetRole.WORKER, 60, 500))
        assertFalse(ledger.consume(BudgetRole.WORKER, 41, 0))
        assertEquals(60L, ledger.tokensUsed)
        assertFalse(ledger.consume(BudgetRole.WORKER, 0, 501))
        assertEquals(500L, ledger.maxWallMs - ledger.wallRemainingMs)
    }

    @Test
    fun `stagnation stops a run after the limit`() {
        val ledger = BudgetLedger(maxGenerations = 3, stagnationLimit = 2)
        assertFalse(ledger.shouldStop())
        ledger.recordGeneration(false)
        assertFalse(ledger.shouldStop())
        ledger.recordGeneration(false)
        assertTrue(ledger.shouldStop())
        assertTrue(ledger.stopReason()!!.contains("stagnation"))
    }

    @Test
    fun `improvement resets the stagnation counter`() {
        val ledger = BudgetLedger(maxGenerations = 3, stagnationLimit = 2)
        ledger.recordGeneration(false)
        ledger.recordGeneration(true)
        assertEquals(0, ledger.consecutiveStagnant)
        assertFalse(ledger.shouldStop())
    }

    @Test
    fun `generation cap stops a run`() {
        val ledger = BudgetLedger(maxGenerations = 2, stagnationLimit = 5)
        ledger.recordGeneration(true)
        ledger.recordGeneration(true)
        assertTrue(ledger.shouldStop())
        assertEquals("generation cap reached (2)", ledger.stopReason())
    }

    @Test
    fun `negative consume is refused`() {
        val ledger = BudgetLedger()
        assertFalse(ledger.consume(BudgetRole.PROPOSER, -1, 0))
        assertFalse(ledger.consume(BudgetRole.PROPOSER, 0, -1))
        assertEquals(0L, ledger.tokensUsed)
        assertNull(ledger.stopReason())
    }
}
