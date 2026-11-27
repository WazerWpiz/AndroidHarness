package com.androidharness.app.evolve

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Persists the evolve settings (caps + the three role models) under the
 * evolve directory, next to the store and the archive. Reads tolerate a
 * missing or corrupt file by falling back to the defaults — a bad
 * settings file must never block the harness.
 */
class EvolveSettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): EvolveSettings = runCatching {
        EvolveIo.read(file)?.let { json.decodeFromString(EvolveSettings.serializer(), it) }
    }.getOrNull() ?: EvolveSettings.DEFAULT.normalized()

    fun save(settings: EvolveSettings) {
        EvolveIo.write(file, json.encodeToString(EvolveSettings.serializer(), settings.normalized()))
    }

    companion object {
        fun at(root: File): EvolveSettingsStore = EvolveSettingsStore(File(root, "settings.json"))
    }
}
