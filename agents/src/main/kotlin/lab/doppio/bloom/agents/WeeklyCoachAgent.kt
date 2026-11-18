package lab.doppio.bloom.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import java.time.Clock
import java.time.LocalDate

class WeeklyCoachAgent(
    private val llm: Llm,
    private val eventRepo: EventRepository,
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val factRepo: FactRepository,
    private val agentRunRepo: AgentRunRepository,
    private val tracker: RunTracker,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Coach {
    override suspend fun weeklyMemo(): String {
        val tokens = TokenCounter()
        val agent = AIAgent(
            promptExecutor = llm.executor,
            llmModel = llm.writer,
            systemPrompt = Prompts.coach,
            toolRegistry = ToolRegistry { tools(MetricsTools(eventRepo, accountRepo, dealRepo, factRepo, agentRunRepo, clock)) },
            maxIterations = CoachLimits.MAX_ITERATIONS,
            installFeatures = tokens.feature,
        )
        return tracker.trackAgent(AgentName.WEEKLY_COACH, null, llm.writer, tokens) {
            agent.run(Prompts.coachTask(LocalDate.now(clock)))
        }
    }
}
