package com.androidharness.app.evolve

import java.io.File

/** The outcome of one real-test command: exit code, timeout flag, captured output. */
data class TestCommandResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
    val stderr: String,
)

/**
 * The seam between the real-project evaluator and the phone's shell: one
 * test command in one directory. The production implementation runs the
 * command through the app's ShellTierRouter; unit tests inject a fake
 * that returns canned exit codes. A fake result is never a real score —
 * the fake only stands in for the command, and the evaluator still
 * requires exit code 0 to pass a task.
 *
 * Leak contract (do not weaken): an implementation MUST destroy the child
 * process on timeout AND on coroutine cancellation — no orphaned Gradle
 * process may survive a stopped or timed-out run — and MUST cap the
 * captured output, never retaining unbounded process output in memory.
 */
interface TestCommandRunner {
    /** False when the Android build toolchain is not installed. */
    val toolchainReady: Boolean
    suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult
}

/** Until the app wires the real runner, every real task fails honestly. */
object UnavailableTestCommandRunner : TestCommandRunner {
    override val toolchainReady: Boolean = false
    override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult =
        TestCommandResult(
            exitCode = -1,
            timedOut = false,
            output = "",
            stderr = "Android build toolchain not installed — install it from Settings",
        )
}
