package lab.doppio.bloom.core.model

/** Every type written to the event journal. The enum name is the string stored in the database. */
enum class EventType {
    ACCOUNT_DISCOVERED,
    ACCOUNT_EXCLUDED,
    ACCOUNT_RESEARCHED,
    ACCOUNT_RESTORED,
    BOUNCED,
    DISCOVERY_RUN,
    DOSSIER_NO_FIT,
    DRAFT_APPROVED,
    DRAFT_CREATED,
    DRAFT_EDITED_APPROVED,
    DRAFT_REJECTED,
    EMAIL_FAILED,
    EMAIL_FOUND,
    EMAIL_NOT_FOUND,
    EMAIL_SENT,
    ENROLLED,
    ENROLLMENT_STOPPED_REJECTIONS,
    GDPR_PURGE,
    IMPORT,
    LINKEDIN_TASK_DONE,
    MEETING_BOOKED,
    PHONE_TASK_DONE,
    REPLY_POSITIVE,
    REPLY_RECEIVED,
    UNSUBSCRIBED,
    WEEKLY_MEMO;

    companion object {
        /** The event logged when a manual task on [channel] is marked done. */
        fun taskDone(channel: Channel): EventType = when (channel) {
            Channel.LINKEDIN -> LINKEDIN_TASK_DONE
            Channel.PHONE -> PHONE_TASK_DONE
            Channel.EMAIL -> throw IllegalArgumentException("Email drafts are sent, not completed by hand")
        }

        /** The event stored under [name], or null for a type this version does not know. */
        fun fromName(name: String): EventType? = entries.firstOrNull { it.name == name }
    }
}
