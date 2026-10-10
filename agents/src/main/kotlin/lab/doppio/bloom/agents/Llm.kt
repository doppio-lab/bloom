package lab.doppio.bloom.agents

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import lab.doppio.bloom.data.db.Repository

/**
 * Choix des modèles par tâche. Le rédacteur et le chercheur ont besoin de jugement,
 * le classifieur de réponses doit être rapide et peu coûteux.
 */
class Llm(apiKey: String) {
    val executor: PromptExecutor = simpleAnthropicExecutor(apiKey)
    val research: LLModel = AnthropicModels.Sonnet_5
    val writer: LLModel = AnthropicModels.Sonnet_5
    val fast: LLModel = AnthropicModels.Haiku_4_5
}

/** Mesure chaque exécution d'agent (durée, tokens, échec) dans agent_runs pour le dashboard. */
class RunTracker(private val repo: Repository) {
    suspend fun <T> track(agent: String, accountId: Long?, model: LLModel, block: suspend () -> Pair<T, StructuredResponse<*>?>): T {
        val start = System.currentTimeMillis()
        return try {
            val (value, response) = block()
            val meta = response?.message?.metaInfo
            repo.logAgentRun(agent, accountId, model.id, meta?.inputTokensCount ?: 0, meta?.outputTokensCount ?: 0, System.currentTimeMillis() - start, true, null)
            value
        } catch (e: Exception) {
            repo.logAgentRun(agent, accountId, model.id, 0, 0, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }
}
