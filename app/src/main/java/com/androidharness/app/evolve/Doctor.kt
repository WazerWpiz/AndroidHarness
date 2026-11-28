package com.androidharness.app.evolve

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** One preflight check: pass, fail, warn, or unknown (could not be performed — never a guessed pass). */
@Serializable
enum class DoctorStatus { PASS, FAIL, WARN, UNKNOWN }

/** One Doctor check: what was checked, how it came out, and the version or value found where relevant. */
@Serializable
data class DoctorCheck(
    val id: String,
    val title: String,
    val status: DoctorStatus,
    val detail: String = "",
    val value: String? = null,
)

/**
 * The Doctor preflight result (P8): every check, the suite it selects,
 * and whether it blocks the run. The blocking set is explicit and
 * small: a model ping that fails blocks the run (the evaluation would
 * fail every task anyway), and critically low storage (< 200 MB) blocks
 * it (the run cannot write its work). A missing toolchain does NOT
 * block: the run switches to the lite suite instead. Everything else
 * warns — the run goes ahead and the check is recorded here and on
 * every report the run produces.
 *
 * Cost: the whole preflight is cheap except the smoke test (one tiny
 * Gradle build, up to 120 seconds) and the three minimal one-word model
 * pings (each capped at 8 output tokens, not literally one token).
 * A ping is retried up to [PING_MAX_ATTEMPTS] times in total (P10):
 * the free-tier models sometimes answer a ping with empty text, and
 * the failures rotate between roles, so one empty answer is not proof
 * the model is down. A ping that fails every attempt still FAILs and
 * still blocks the run.
 */
@Serializable
data class DoctorResult(
    val checkedAt: Long = 0L,
    val suiteId: String = EvalSuite.FULL.suiteId,
    val checks: List<DoctorCheck> = emptyList(),
    /** The toolchain checks all passed: the run uses the full suite. False: lite. */
    val toolchainReady: Boolean = false,
    /** True when the run must not start (a model ping failed, or storage critically low). */
    val blocksRun: Boolean = false,
    /** The exact reason the run is blocked, or null. */
    val blockReason: String? = null,
)

/**
 * The Doctor preflight (P8): run automatically at the start of every
 * evolution, and on demand from Settings. It works only through seams
 * that already exist — the test command runner for shell probes, the
 * model caller for key checks and pings, the evolve directory for
 * storage, /proc/meminfo for RAM, and a plain TCP connect for the
 * network — so it needs no new wiring and no new permissions. A check
 * that cannot be performed reports UNKNOWN with the reason, never a
 * guessed pass.
 */
object Doctor {
    /** One tiny Gradle build may take this long; slower is a failed check, never a hang. */
    const val SMOKE_TIMEOUT_MS = 120_000

    /**
     * Model-ping attempts in total per role (P10). The first attempt
     * fails fast on an empty answer or a thrown error; the backoff
     * between attempts is [PING_RETRY_DELAYS_MS]. A model that is
     * genuinely down still fails all three and still blocks the run.
     */
    const val PING_MAX_ATTEMPTS = 3
    private val PING_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

    /** Below this free storage the check fails; below [BLOCK_FREE_BYTES] it blocks the run. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024
    const val BLOCK_FREE_BYTES = 200L * 1024 * 1024

    /** Free storage above this passes; between it and [MIN_FREE_BYTES] it warns. */
    const val WARN_FREE_BYTES = 2L * 1024 * 1024 * 1024

    /** Below this free RAM the check fails; above [WARN_FREE_RAM_BYTES] it passes. */
    const val MIN_FREE_RAM_BYTES = 512L * 1024 * 1024
    const val WARN_FREE_RAM_BYTES = 1L * 1024 * 1024 * 1024

    const val NETWORK_TIMEOUT_MS = 3_000
    private const val COMMAND_TIMEOUT_MS = 10_000
    private const val MB = 1024L * 1024

    /** Check ids the Settings screen's Fix buttons key on. */
    const val ID_LINUX = "linux-userspace"
    const val ID_JDK = "jdk"
    const val ID_GRADLE = "gradle"
    const val ID_ANDROID_SDK = "android-sdk"
    const val ID_SMOKE = "smoke-build"
    const val ID_STORAGE = "free-storage"
    const val ID_RAM = "free-ram"
    const val ID_NETWORK = "network"
    const val ID_FIXTURES = "suite-fixtures"
    fun keysId(role: EvolveRole): String = "keys-${role.name.lowercase()}"
    fun pingId(role: EvolveRole): String = "ping-${role.name.lowercase()}"

    /** The toolchain checks: all of them must pass for the full suite. */
    private val TOOLCHAIN_IDS = setOf(ID_LINUX, ID_JDK, ID_GRADLE, ID_ANDROID_SDK)

    /** True when the check is one of the toolchain checks (a Fix button installs/updates the toolchain). */
    fun isToolchainCheck(id: String): Boolean = id in TOOLCHAIN_IDS || id == ID_SMOKE

    /** True when the check is a model key or ping check (a Fix button opens the model picker for that role). */
    fun roleForCheck(id: String): EvolveRole? = EvolveRole.values().firstOrNull {
        id == keysId(it) || id == pingId(it)
    }

    private fun defaultNetworkProbe(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("repo1.maven.org", 443), NETWORK_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Runs every check. [networkProbe] is injectable for tests; the
     * default is a plain TCP connect to repo1.maven.org:443, where
     * Gradle downloads from — inside the toolchain path, no new network
     * destination.
     */
    suspend fun run(
        caller: EvolveModelCaller,
        commandRunner: TestCommandRunner,
        workDir: File,
        networkProbe: suspend () -> Boolean = { withContext(Dispatchers.IO) { defaultNetworkProbe() } },
    ): DoctorResult {
        val checks = mutableListOf<DoctorCheck>()
        val userspace = probeUserspace(commandRunner, workDir)
        checks += userspace
        val userspaceOk = userspace.status == DoctorStatus.PASS
        val notChecked = "not checked: the Linux userspace is not installed"
        val jdk = if (userspaceOk) probeJdk(commandRunner, workDir) else
            DoctorCheck(ID_JDK, "JDK", DoctorStatus.FAIL, notChecked)
        val gradle = if (userspaceOk) probeGradle(commandRunner, workDir) else
            DoctorCheck(ID_GRADLE, "Gradle", DoctorStatus.FAIL, notChecked)
        val androidSdk = if (userspaceOk) probeAndroidSdk(commandRunner, workDir) else
            DoctorCheck(ID_ANDROID_SDK, "Android SDK tools (d8, apksigner, aapt2)", DoctorStatus.FAIL, notChecked)
        checks += jdk
        checks += gradle
        checks += androidSdk
        val toolchainReady = checks.filter { it.id in TOOLCHAIN_IDS }.all { it.status == DoctorStatus.PASS }
        checks += probeStorage(workDir)
        checks += probeRam()
        checks += probeNetwork(networkProbe)
        var pingFailed: String? = null
        for (role in EvolveRole.values()) {
            val configured = try {
                caller.isConfigured(role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            checks += DoctorCheck(
                keysId(role),
                "${role.name.lowercase()} model key",
                if (configured) DoctorStatus.PASS else DoctorStatus.FAIL,
                if (configured) "a model is configured for ${role.name.lowercase()}"
                else "no model configured — set one in Settings → Coding → Recursive harness",
            )
            val ping = pingRole(caller, role, configured)
            checks += ping
            if (ping.status == DoctorStatus.FAIL && pingFailed == null) {
                pingFailed = ping.detail.ifBlank { "the ${role.name.lowercase()} model did not answer its ping" }
            }
        }
        checks += probeFixtures()
        checks += if (toolchainReady) probeSmoke(commandRunner, workDir)
        else DoctorCheck(ID_SMOKE, "Hello-world build smoke test", DoctorStatus.FAIL, "not run: the toolchain is not installed")
        val storage = checks.first { it.id == ID_STORAGE }
        val criticallyLow = storage.status == DoctorStatus.FAIL &&
            (storage.value?.toLongOrNull() ?: Long.MAX_VALUE) < BLOCK_FREE_BYTES
        val blocksRun = pingFailed != null || criticallyLow
        val blockReason = when {
            pingFailed != null -> "Doctor blocked the run: $pingFailed"
            criticallyLow -> "Doctor blocked the run: only ${storage.value?.toLongOrNull()?.let { it / MB } ?: "?"} MB free — under 200 MB the run cannot write its work"
            else -> null
        }
        return DoctorResult(
            checkedAt = System.currentTimeMillis(),
            suiteId = (if (toolchainReady) EvalSuite.FULL else EvalSuite.LITE).suiteId,
            checks = checks,
            toolchainReady = toolchainReady,
            blocksRun = blocksRun,
            blockReason = blockReason,
        )
    }

    private suspend fun runCommand(
        commandRunner: TestCommandRunner,
        command: String,
        workDir: File,
        timeoutMs: Int = COMMAND_TIMEOUT_MS,
    ): TestCommandResult? = try {
        commandRunner.run(command, workDir, timeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun probeUserspace(commandRunner: TestCommandRunner, workDir: File): DoctorCheck {
        val result = runCommand(commandRunner, "command -v bash", workDir)
            ?: return DoctorCheck(ID_LINUX, "Linux userspace", DoctorStatus.UNKNOWN, "the probe could not run")
        return if (result.exitCode == 0) {
            DoctorCheck(ID_LINUX, "Linux userspace", DoctorStatus.PASS, "bash is on PATH", "bash")
        } else {
            DoctorCheck(ID_LINUX, "Linux userspace", DoctorStatus.FAIL, "bash is not on PATH — install the Linux environment from Settings")
        }
    }

    private suspend fun probeJdk(commandRunner: TestCommandRunner, workDir: File): DoctorCheck {
        val result = runCommand(commandRunner, "java -version", workDir)
            ?: return DoctorCheck(ID_JDK, "JDK", DoctorStatus.UNKNOWN, "the probe could not run")
        if (result.exitCode != 0) {
            return DoctorCheck(ID_JDK, "JDK", DoctorStatus.FAIL, "java did not run: ${(result.stderr + result.output).take(200).trim()}")
        }
        val text = result.stderr + "\n" + result.output
        val major = Regex("version \"(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            major == null -> DoctorCheck(ID_JDK, "JDK", DoctorStatus.WARN, "java runs but its version could not be read", text.lineSequence().firstOrNull()?.trim())
            major == 21 -> DoctorCheck(ID_JDK, "JDK", DoctorStatus.PASS, "openjdk-21 is installed", "21")
            else -> DoctorCheck(ID_JDK, "JDK", DoctorStatus.FAIL, "java $major found — the toolchain needs openjdk-21", "$major")
        }
    }

    private suspend fun probeGradle(commandRunner: TestCommandRunner, workDir: File): DoctorCheck {
        val result = runCommand(commandRunner, "gradle --version", workDir)
            ?: return DoctorCheck(ID_GRADLE, "Gradle", DoctorStatus.UNKNOWN, "the probe could not run")
        if (result.exitCode != 0) {
            return DoctorCheck(ID_GRADLE, "Gradle", DoctorStatus.FAIL, "gradle did not run: ${(result.stderr + result.output).take(200).trim()}")
        }
        val text = result.output + "\n" + result.stderr
        val version = Regex("Gradle ([0-9.]+)").find(text)?.groupValues?.get(1)
        return if (version != null) {
            DoctorCheck(ID_GRADLE, "Gradle", DoctorStatus.PASS, "gradle is installed", version)
        } else {
            DoctorCheck(ID_GRADLE, "Gradle", DoctorStatus.WARN, "gradle runs but its version could not be read", text.lineSequence().firstOrNull()?.trim())
        }
    }

    private suspend fun probeAndroidSdk(commandRunner: TestCommandRunner, workDir: File): DoctorCheck {
        val result = runCommand(
            commandRunner,
            "for t in d8 apksigner aapt2; do command -v \$t || echo \"missing: \$t\"; done",
            workDir,
        ) ?: return DoctorCheck(ID_ANDROID_SDK, "Android SDK tools (d8, apksigner, aapt2)", DoctorStatus.UNKNOWN, "the probe could not run")
        val missing = Regex("missing: (\\S+)").findAll(result.output + "\n" + result.stderr).map { it.groupValues[1] }.toList()
        if (missing.isNotEmpty()) {
            return DoctorCheck(
                ID_ANDROID_SDK, "Android SDK tools (d8, apksigner, aapt2)", DoctorStatus.FAIL,
                "missing: ${missing.joinToString(", ")} — install them from Settings",
            )
        }
        val versionResult = runCommand(commandRunner, "aapt2 version", workDir)
        val version = versionResult?.let { (it.output + "\n" + it.stderr).lineSequence().firstOrNull()?.trim()?.take(120) }
        return DoctorCheck(
            ID_ANDROID_SDK, "Android SDK tools (d8, apksigner, aapt2)", DoctorStatus.PASS,
            "d8, apksigner and aapt2 are all on PATH", version,
        )
    }

    private fun probeStorage(workDir: File): DoctorCheck {
        // usableSpace is 0 for a path that does not exist yet — and the
        // work dir is only created when the run starts — so read the
        // nearest existing ancestor instead. Without this, the Doctor
        // would block every first run with "0 MB free".
        var dir: File? = workDir.absoluteFile
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val free = try {
            dir?.usableSpace ?: 0L
        } catch (e: Exception) {
            return DoctorCheck(ID_STORAGE, "Free storage", DoctorStatus.UNKNOWN, "free storage could not be read: ${e.message}")
        }
        val mb = free / MB
        return when {
            free >= WARN_FREE_BYTES -> DoctorCheck(ID_STORAGE, "Free storage", DoctorStatus.PASS, "$mb MB free", "$free")
            free >= MIN_FREE_BYTES -> DoctorCheck(ID_STORAGE, "Free storage", DoctorStatus.WARN, "only $mb MB free — a run writes Gradle caches and reports", "$free")
            else -> DoctorCheck(ID_STORAGE, "Free storage", DoctorStatus.FAIL, "only $mb MB free — under 500 MB a run cannot write its work safely", "$free")
        }
    }

    private fun probeRam(): DoctorCheck {
        val meminfo = try {
            File("/proc/meminfo").readText()
        } catch (e: Exception) {
            return DoctorCheck(ID_RAM, "Free RAM", DoctorStatus.UNKNOWN, "free RAM could not be read: ${e.message}")
        }
        val kb = Regex("MemAvailable:\\s+(\\d+) kB").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: return DoctorCheck(ID_RAM, "Free RAM", DoctorStatus.UNKNOWN, "MemAvailable is not in /proc/meminfo")
        val bytes = kb * 1024
        val mb = bytes / MB
        return when {
            bytes >= WARN_FREE_RAM_BYTES -> DoctorCheck(ID_RAM, "Free RAM", DoctorStatus.PASS, "$mb MB available", "$bytes")
            bytes >= MIN_FREE_RAM_BYTES -> DoctorCheck(ID_RAM, "Free RAM", DoctorStatus.WARN, "only $mb MB available — Gradle is memory-hungry", "$bytes")
            else -> DoctorCheck(ID_RAM, "Free RAM", DoctorStatus.FAIL, "only $mb MB available — under 512 MB Gradle may run out of memory", "$bytes")
        }
    }

    private suspend fun probeNetwork(networkProbe: suspend () -> Boolean): DoctorCheck {
        val reachable = try {
            networkProbe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (reachable) {
            DoctorCheck(ID_NETWORK, "Network", DoctorStatus.PASS, "repo1.maven.org:443 is reachable")
        } else {
            DoctorCheck(ID_NETWORK, "Network", DoctorStatus.FAIL, "repo1.maven.org:443 is not reachable — Gradle cannot download its plugin and models cannot be called")
        }
    }

    private suspend fun pingRole(caller: EvolveModelCaller, role: EvolveRole, configured: Boolean): DoctorCheck {
        if (!configured) {
            return DoctorCheck(pingId(role), "${role.name.lowercase()} model ping", DoctorStatus.FAIL, "not configured — no model to ping")
        }
        // Retried (P10): a single empty answer from a free-tier model
        // proves nothing — on 2026-11-28 the failures rotated between
        // roles across two Doctor runs with the same model. Only a ping
        // that fails every attempt blocks the run.
        var lastFailure: String? = null
        repeat(PING_MAX_ATTEMPTS) { attempt ->
            try {
                val result = caller.complete(
                    role,
                    "You are a connectivity check. Reply with exactly one word: ok.",
                    "Reply with exactly one word: ok.",
                    maxOutputTokens = 8,
                )
                if (result.text.isNotBlank()) {
                    return DoctorCheck(pingId(role), "${role.name.lowercase()} model ping", DoctorStatus.PASS, "the model answered", result.text.trim().take(40))
                }
                lastFailure = "the ${role.name.lowercase()} model answered with empty text"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The role is in the detail: the block reason must say WHICH
                // model failed, not just that a ping failed.
                lastFailure = "the ${role.name.lowercase()} ping failed: ${e.message}".take(200)
            }
            if (attempt < PING_MAX_ATTEMPTS - 1) delay(PING_RETRY_DELAYS_MS[attempt])
        }
        return DoctorCheck(
            pingId(role),
            "${role.name.lowercase()} model ping",
            DoctorStatus.FAIL,
            "${lastFailure ?: "the ${role.name.lowercase()} model did not answer its ping"} (after $PING_MAX_ATTEMPTS attempts)",
        )
    }

    private fun probeFixtures(): DoctorCheck {
        val snapshots = listOf(
            RealProjectTaskData.GENOME_KT_BROKEN, RealProjectTaskData.GENOME_TEST_KT,
            RealProjectTaskData.BUDGET_LEDGER_KT_BROKEN, RealProjectTaskData.BUDGET_LEDGER_TEST_KT,
            RealProjectTaskData.TRACE_MINER_KT_BROKEN, RealProjectTaskData.TRACE_MINER_TEST_KT,
            RealProjectTaskData.SECRET_REDACTOR_KT, RealProjectTaskData.SECRET_REDACTOR_KT_BROKEN,
            RealProjectTaskData.SECRET_REDACTOR_TEST_KT,
        )
        return when {
            LiveEvaluator.tasks.isEmpty() || RealProjectEvaluator.tasks.isEmpty() ->
                DoctorCheck(ID_FIXTURES, "Suite fixtures", DoctorStatus.FAIL, "a task suite is empty")
            snapshots.any { it.isBlank() } ->
                DoctorCheck(ID_FIXTURES, "Suite fixtures", DoctorStatus.FAIL, "a real-task snapshot is empty")
            else -> DoctorCheck(
                ID_FIXTURES, "Suite fixtures", DoctorStatus.PASS,
                "${LiveEvaluator.tasks.size} live tasks, ${RealProjectEvaluator.tasks.size} real tasks, snapshots present",
            )
        }
    }

    private suspend fun probeSmoke(commandRunner: TestCommandRunner, workDir: File): DoctorCheck {
        val dir = File(workDir, "doctor-smoke")
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            File(dir, "settings.gradle.kts").writeText("rootProject.name = \"doctor-smoke\"\n")
            File(dir, "build.gradle.kts").writeText(
                """
                plugins {
                    kotlin("jvm") version "2.5.0-RC2"
                }
                repositories { mavenCentral() }
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC2")
                    testImplementation("org.jetbrains.kotlin:kotlin-test:2.5.0-RC2")
                }
                """.trimIndent() + "\n",
            )
            val main = File(dir, "src/main/kotlin/hello").apply { mkdirs() }
            File(main, "Hello.kt").writeText("package hello\n\nfun hello(): Int = 1 + 1\n")
            val test = File(dir, "src/test/kotlin/hello").apply { mkdirs() }
            File(test, "HelloTest.kt").writeText(
                "package hello\n\nimport kotlin.test.Test\nimport kotlin.test.assertEquals\n\nclass HelloTest {\n    @Test fun adds() { assertEquals(2, hello()) }\n}\n",
            )
            val result = runCommand(commandRunner, "gradle test --console=plain", dir, SMOKE_TIMEOUT_MS)
                ?: return DoctorCheck(ID_SMOKE, "Hello-world build smoke test", DoctorStatus.UNKNOWN, "the smoke test could not run")
            when {
                result.timedOut -> DoctorCheck(
                    ID_SMOKE, "Hello-world build smoke test", DoctorStatus.FAIL,
                    "timed out after ${SMOKE_TIMEOUT_MS / 1000}s — first runs download the Kotlin plugin and are slow; the real tasks retry with 10 minutes",
                )
                result.exitCode == 0 -> DoctorCheck(ID_SMOKE, "Hello-world build smoke test", DoctorStatus.PASS, "a tiny Gradle project built and its test passed")
                else -> DoctorCheck(
                    ID_SMOKE, "Hello-world build smoke test", DoctorStatus.FAIL,
                    "gradle test failed (exit ${result.exitCode}): ${(result.output + "\n" + result.stderr).takeLast(500).trim()}",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DoctorCheck(ID_SMOKE, "Hello-world build smoke test", DoctorStatus.UNKNOWN, "the smoke test could not run: ${e.message}")
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }
}
