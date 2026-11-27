package com.androidharness.app.evolve

/**
 * The seam between the trusted evolve core and the app's model layer.
 * The core never touches providers, keys or the network: it asks this
 * interface for a completion and gets text plus real token counts back.
 * The app supplies the real implementation (built on ProviderFactory);
 * tests supply a fake. If no model is configured for a role, [complete]
 * throws [NoModelConfigured] — the runner refuses to run rather than
 * silently falling back to a fake score.
 */
enum class EvolveRole { PROPOSER, WORKER, CRITIC }

data class ModelCallResult(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
    /** "providerId/model" of the model that answered, for the report's model pin. */
    val model: String = "",
)

class NoModelConfigured(role: EvolveRole) :
    IllegalStateException("No model configured for $role. Choose a provider and model in Settings → Coding → Recursive harness.")

class ModelCallFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

interface EvolveModelCaller {
    suspend fun isConfigured(role: EvolveRole): Boolean

    /**
     * One completion, no tools, no conversation memory. The caller returns
     * the model's full text and the provider-reported token counts.
     */
    suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int = 8192,
    ): ModelCallResult
}
