package lab.doppio.bloom.agents

/** Limits of the research agent. The prompts and tool texts read the same values, so they cannot disagree with the code. */
object ResearchLimits {
    /** Koog stops the exploration after this many LLM or tool steps. */
    const val MAX_ITERATIONS = 20

    /** Pages the agent may read per account: reading more costs tokens without adding facts. */
    const val MAX_PAGES = 6

    /** Characters of a page returned to the agent. */
    const val PAGE_CHARS = 5_000

    /** A quote shorter than this proves nothing, a longer one is a paragraph rather than a quote. */
    const val QUOTE_MIN_CHARS = 12
    const val QUOTE_MAX_CHARS = 300

    /** The agent stops once it holds this many useful quotes (the lower bound is the target of the prompt). */
    const val USEFUL_QUOTES_MIN = 3
    const val USEFUL_QUOTES_MAX = 5

    /** The closing report of the exploration. */
    const val REPORT_MAX_LINES = 5

    /** The dossier asked of the synthesis step. */
    const val SUMMARY_MAX_SENTENCES = 3
    const val HYPOTHESES_MIN = 1
    const val HYPOTHESES_MAX = 3

    /** Characters of a fact excerpt shown to the synthesis step. */
    const val SYNTHESIS_EXCERPT_CHARS = 300

    /** Strength of the signal a verified quote creates, by category. */
    const val AI_USAGE_STRENGTH = 0.6
    const val MOBILE_PROJECT_STRENGTH = 0.6
    const val APP_ISSUE_STRENGTH = 0.4
}

/** Limits of the writer agent. */
object WriterLimits {
    /** Rewrites asked of the model after the guard blocks a draft. */
    const val MAX_REPAIRS = 2

    /** Messages corrected by the team shown as style examples. */
    const val HUMAN_EXAMPLES = 3
}

/** Limits of the reply classifier. */
object ReplyLimits {
    /** Characters of a reply read by the classifier, once the quoted thread is cut. */
    const val REPLY_CHARS = 4_000

    /** An unambiguous request to stop shows in the first characters of a reply. */
    const val HARD_STOP_SCAN_CHARS = 400
}

/** Limits of the weekly coach, shared with the descriptions of its tools. */
object CoachLimits {
    const val MAX_ITERATIONS = 15

    /** Accounts listed by the top accounts tool, and reasons kept for each. */
    const val TOP_ACCOUNTS = 15
    const val TOP_ACCOUNT_REASONS = 3

    /** Length of the Monday memo. */
    const val MEMO_MAX_WORDS = 200
}
