package com.androidharness.app.evolve

/** A write, a promote or a genome that names the trusted core was refused. */
class ProtectedPathException(message: String) : Exception(message)

/**
 * The fixed point of the loop. A genome may change instructions, skills, hooks and agent
 * profiles; it may never name, create, patch or disable the files that decide whether the
 * loop is honest. This list is compiled into the app, is not part of any genome, and is
 * checked on every genome write and every promotion, including text a hook would run.
 *
 * Paths are repository-relative and case-insensitive: a phone filesystem may or may not
 * be, and a check that only works on one of them is not a check.
 */
object ProtectedPaths {

    private const val APP = "app/src/main/java/com/androidharness/app/"

    /** Exact files no genome may touch. */
    val files: Set<String> = setOf(
        "${APP}evolve/ProtectedPaths.kt",
        "${APP}evolve/GenomeRules.kt",
        "${APP}evolve/GenomeStore.kt",
        "${APP}evolve/Archive.kt",
        "${APP}evolve/PromotionGate.kt",
        "${APP}evolve/LiveEvaluator.kt",
        "${APP}evolve/RealProjectEvaluator.kt",
        "${APP}evolve/GenomeBackup.kt",
        "${APP}evolve/TestCommandRunner.kt",
        "${APP}evolve/ShellTestCommandRunner.kt",
        "${APP}evolve/RealProjectTaskData.kt",
        "${APP}evolve/LlmProposer.kt",
        "${APP}evolve/SafetyProbes.kt",
        "${APP}evolve/BudgetLedger.kt",
        "${APP}tools/ShellPolicy.kt",
        "${APP}tools/SecretRedactor.kt",
        "app/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/libs.versions.toml",
        "app/proguard-rules.pro",
        "app/src/main/AndroidManifest.xml",
    )

    /** Folders whose whole contents are trusted core, including the holdout suite. */
    val folders: Set<String> = setOf(
        "signing-keys/",
        "app/src/main/assets/evolve/suite/",
        "${APP}evolve/trusted/",
    )

    fun normalize(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").replace(Regex("/{2,}"), "/").lowercase()

    /** The same lists, lowercased once, so a case-mixed phone filesystem cannot slip past. */
    private val normalizedFiles: Set<String> = files.map(::normalize).toSet()
    private val normalizedFolders: Set<String> = folders.map(::normalize).toSet()

    private val SECRET_FILE = Regex("""[^\s\"']+\.(jks|keystore|p12)\b""")

    fun isProtected(path: String): Boolean {
        val candidate = normalize(path)
        if (candidate.isEmpty()) return false
        if (SECRET_FILE.containsMatchIn(candidate)) return true
        // A full path from the filesystem still points at a protected file; match its tail too.
        if (normalizedFiles.any { candidate == it || candidate.endsWith("/$it") }) return true
        return normalizedFolders.any { folder ->
            candidate == folder.removeSuffix("/") || candidate.startsWith(folder) || candidate.contains("/$folder")
        }
    }

    /** Refuse a write before it happens. Every write in the evolve package goes through here. */
    fun requireWritable(path: String) {
        if (isProtected(path)) {
            throw ProtectedPathException("$path is part of the trusted core and cannot be changed by a genome.")
        }
    }

    /**
     * The trusted files a piece of genome text names, by full path or by file name.
     * Promotion refuses a genome that names any of them, so an instruction, a skill or a
     * hook command cannot quietly point the next run at the evaluator.
     */
    fun references(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val haystack = normalize(text)
        val found = linkedSetOf<String>()
        SECRET_FILE.findAll(haystack).forEach { found += it.value }
        for (file in normalizedFiles) {
            val name = file.substringAfterLast('/')
            if (haystack.contains(file) || haystack.contains(name)) found += file
        }
        for (folder in normalizedFolders) {
            // The folder name alone is too easy to say by accident ("trusted"); require a separator.
            val name = folder.removeSuffix("/").substringAfterLast('/') + "/"
            if (haystack.contains(folder) || haystack.contains(name)) found += folder
        }
        return found.toList()
    }
}
