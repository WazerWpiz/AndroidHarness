package com.androidharness.app.evolve

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import java.io.File

/**
 * Runs real test commands through the app's shell tiers (ShellTierRouter):
 * the APP_LINUX tier when the Linux userspace is installed, with the
 * Termux toolchain on PATH and JAVA_HOME set, so `gradle` resolves to
 * the installed Gradle + openjdk-21. Readiness is the installed package
 * set, checked the same way AndroidBuildToolsSetup checks it.
 *
 * No orphaned processes: the router's runApp kills the child's process
 * group and destroys the process both when the command times out and in
 * its finally block when the coroutine is cancelled, so a stopped or
 * timed-out Gradle run cannot keep eating RAM or battery. Captured
 * output is capped at MAX_OUTPUT_CHARS per stream.
 */
class ShellTestCommandRunner(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : TestCommandRunner {

    override val toolchainReady: Boolean
        get() = linuxEnv.isReady &&
            linuxEnv.installedContains("gradle") &&
            linuxEnv.installedContains("openjdk-21")

    override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult {
        val result = router.run(command, cwd, timeoutMs, MAX_OUTPUT_CHARS)
        return TestCommandResult(
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            output = result.rawOutput,
            stderr = result.rawStderr,
        )
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 32_000
    }
}
