package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 — the Doctor preflight: toolchain missing selects the lite suite
 * without blocking, a failed model ping blocks the run, and a check
 * that cannot be performed reports UNKNOWN, never a guessed pass.
 */
class DoctorTest {

    private val root: File = Files.createTempDirectory("doctor").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A runner that answers the Doctor's probes with real version strings. */
    private class FakeRunner : TestCommandRunner {
        override val toolchainReady: Boolean = true
        override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult =
            when {
                command == "command -v bash" -> TestCommandResult(0, false, "/usr/bin/bash\n", "")
                command == "java -version" -> TestCommandResult(0, false, "", "openjdk version \"21.0.1\" 2023-10-17\n")
                command == "gradle --version" -> TestCommandResult(0, false, "Gradle 9.7.0\n", "")
                command.startsWith("for t in d8") -> TestCommandResult(
                    0, false, "/usr/bin/d8\n/usr/bin/apksigner\n/usr/bin/aapt2\n", "",
                )
                command == "aapt2 version" -> TestCommandResult(
                    0, false, "Android Asset Packaging Tool (aapt) 2.19.0\n", "",
                )
                command == "gradle test --console=plain" -> TestCommandResult(0, false, "BUILD SUCCESSFUL\n", "")
                else -> TestCommandResult(1, false, "", "unknown command: $command")
            }
    }

    private fun check(result: DoctorResult, id: String): DoctorCheck =
        result.checks.first { it.id == id }

    @Test
    fun `a missing toolchain selects the lite suite and does not block`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val result = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { true })
        assertFalse(result.toolchainReady)
        assertEquals("evolve-live-v1-lite", result.suiteId)
        assertFalse(result.blocksRun)
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.ID_LINUX).status)
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.ID_JDK).status)
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.ID_SMOKE).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.pingId(EvolveRole.WORKER)).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_FIXTURES).status)
    }

    @Test
    fun `a ready toolchain selects the full suite and the smoke test passes`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val result = Doctor.run(caller, FakeRunner(), File(root, "work"), networkProbe = { true })
        assertTrue(result.toolchainReady)
        assertEquals("evolve-live-v1", result.suiteId)
        assertFalse(result.blocksRun)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_LINUX).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_JDK).status)
        assertEquals("21", check(result, Doctor.ID_JDK).value)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_GRADLE).status)
        assertEquals("9.7.0", check(result, Doctor.ID_GRADLE).value)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_ANDROID_SDK).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_SMOKE).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.ID_NETWORK).status)
        // The storage status depends on the machine's free space (this
        // VM's /tmp is a 512 MB tmpfs, so it warns) — what matters is
        // that the reading is real and never blocks the run.
        assertTrue(check(result, Doctor.ID_STORAGE).status != DoctorStatus.FAIL)
        assertTrue((check(result, Doctor.ID_STORAGE).value?.toLongOrNull() ?: 0L) > 0)
    }

    @Test
    fun `a failed model ping blocks the run with the exact reason`() = runBlocking {
        val inner = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val caller = object : EvolveModelCaller {
            override suspend fun isConfigured(role: EvolveRole): Boolean = true
            override suspend fun complete(
                role: EvolveRole,
                system: String,
                user: String,
                maxOutputTokens: Int,
            ): ModelCallResult {
                if (role == EvolveRole.CRITIC) throw RuntimeException("401 Unauthorized: bad key")
                return inner.complete(role, system, user, maxOutputTokens)
            }
        }
        val result = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { true })
        assertTrue(result.blocksRun)
        assertTrue(result.blockReason!!.contains("critic"))
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.pingId(EvolveRole.CRITIC)).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.pingId(EvolveRole.WORKER)).status)
    }

    @Test
    fun `an unconfigured role fails its key check and its ping and blocks`() = runBlocking {
        val caller = FakeModelCaller(configuredRoles = setOf(EvolveRole.WORKER))
        val result = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { true })
        assertTrue(result.blocksRun)
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.keysId(EvolveRole.PROPOSER)).status)
        assertEquals(DoctorStatus.FAIL, check(result, Doctor.pingId(EvolveRole.PROPOSER)).status)
        assertEquals(DoctorStatus.PASS, check(result, Doctor.keysId(EvolveRole.WORKER)).status)
    }

    @Test
    fun `a work dir that does not exist yet still reports the real free space`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        // File(root, "work") is never created here: the old code read
        // usableSpace on it directly and got 0, so the Doctor blocked
        // every run with "0 MB free". The check must read the nearest
        // existing ancestor instead. The free-space value proves it: 0
        // would mean the bug is back.
        val result = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { true })
        assertTrue(check(result, Doctor.ID_STORAGE).status != DoctorStatus.FAIL)
        assertTrue((check(result, Doctor.ID_STORAGE).value?.toLongOrNull() ?: 0L) > 0)
        assertFalse(result.blocksRun)
    }

    @Test
    fun `a network probe failure is reported, not guessed`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val down = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { false })
        assertEquals(DoctorStatus.FAIL, check(down, Doctor.ID_NETWORK).status)
        // A network failure alone does not block: the run can still be
        // attempted (the models may be local), and the check says so.
        assertFalse(down.blocksRun)
        val up = Doctor.run(caller, UnavailableTestCommandRunner, File(root, "work"), networkProbe = { true })
        assertEquals(DoctorStatus.PASS, check(up, Doctor.ID_NETWORK).status)
    }
}
