package com.androidharness.app.evolve

import com.androidharness.app.AppContainer
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ChatModelChoice
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.effectiveApiKey
import kotlinx.coroutines.flow.first

/**
 * The real model caller, built on the same path the app's own agent uses
 * (AutomationAiPlanner): resolve the provider + model for a role, take the
 * stored key, and collect [ProviderFactory] `streamChat` into full text
 * plus provider-reported token counts. No tools, no conversation memory.
 *
 * A role's model comes from the evolve settings when the user picked one;
 * otherwise it falls back to the app's active provider and model, exactly
 * like the planner does. Local providers need no key; every other provider
 * without a stored key is reported as not configured.
 */
class AppModelCaller(
    private val container: AppContainer,
    private val settingsProvider: () -> EvolveSettings,
) : EvolveModelCaller {

    private data class Resolved(val config: ProviderConfig, val apiKey: String)

    private suspend fun resolve(role: EvolveRole): Resolved? {
        val appSettings = container.settings.settings.first()
        val choice: ChatModelChoice? = settingsProvider().choiceFor(role)
        val providers = container.providers.providers.first()
        val config = choice?.let { wanted ->
            providers.firstOrNull { it.id == wanted.providerId }?.copy(model = wanted.model)
        } ?: providers.firstOrNull { it.id == appSettings.activeProviderId }
            ?: providers.firstOrNull()
            ?: return null
        val model = choice?.model
            ?: appSettings.activeModel?.takeIf { it.isNotBlank() }
            ?: config.model
        val resolved = config.copy(model = model)
        val apiKey = if (resolved.id == HarnessProvider.ID) {
            runCatching { container.providers.harnessApiKey() }.getOrNull().orEmpty()
        } else {
            resolved.effectiveApiKey(container.providers.apiKey(resolved.id))
        }
        if (apiKey.isBlank()) return null
        return Resolved(resolved, apiKey)
    }

    override suspend fun isConfigured(role: EvolveRole): Boolean = resolve(role) != null

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult {
        val resolved = resolve(role) ?: throw NoModelConfigured(role)
        val output = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L
        try {
            ProviderFactory.create(resolved.config).streamChat(
                resolved.config,
                resolved.apiKey,
                system,
                listOf(ChatMessage(role = Role.USER, text = user)),
                emptyList(),
                RequestOptions(maxOutputTokens = maxOutputTokens, thinking = ThinkingLevel.OFF),
            ).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> output.append(event.text)
                    is StreamEvent.Usage -> {
                        promptTokens = event.inputTokens.toLong()
                        completionTokens = event.outputTokens.toLong()
                    }
                    is StreamEvent.Batch -> event.events.forEach { nested ->
                        when (nested) {
                            is StreamEvent.TextDelta -> output.append(nested.text)
                            is StreamEvent.Usage -> {
                                promptTokens = nested.inputTokens.toLong()
                                completionTokens = nested.outputTokens.toLong()
                            }
                            is StreamEvent.Failure -> throw ModelCallFailed(nested.message)
                            else -> Unit
                        }
                    }
                    is StreamEvent.Failure -> throw ModelCallFailed(event.message)
                    else -> Unit
                }
            }
        } catch (e: ModelCallFailed) {
            throw e
        } catch (e: Exception) {
            throw ModelCallFailed("Model call failed for $role: ${e.message}", e)
        }
        if (output.isBlank()) throw ModelCallFailed("Model returned an empty response for $role.")
        return ModelCallResult(output.toString(), promptTokens, completionTokens, "${resolved.config.id}/${resolved.config.model}")
    }
}
