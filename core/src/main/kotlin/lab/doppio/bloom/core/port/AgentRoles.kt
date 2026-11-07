package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.Enrollment
import lab.doppio.bloom.core.model.ReplyClassification
import lab.doppio.bloom.core.model.Sequence
import lab.doppio.bloom.core.model.SequenceStep
import lab.doppio.bloom.core.scoring.ScoreBreakdown

/**
 * Roles the LLM agents play for the rest of the application. The use cases and the routes depend on these,
 * never on the Koog classes that implement them, so the orchestration can be tested with fakes.
 */

/** Writes the message of a sequence step and files it for human review. Returns the draft id. */
interface Drafter {
    suspend fun draft(enrollment: Enrollment, sequence: Sequence, step: SequenceStep): Long
}

/** Collects facts about an account, writes its dossier and recomputes its score. */
interface Researcher {
    suspend fun research(accountId: Long): ScoreBreakdown
}

/** Reads an inbound reply. Decisions that matter (stopping a sequence, opposition) stay in code. */
interface ReplyClassifier {
    suspend fun classify(subject: String?, body: String, accountId: Long?): ReplyClassification
}

/** Writes the Monday memo from the prospecting metrics. */
interface Coach {
    suspend fun weeklyMemo(): String
}
