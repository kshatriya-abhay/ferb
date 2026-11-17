package dev.abhay.assistant.reminders

/** A [ReminderStore] in memory, for the reminder tests (one copy, review P2-184: there were eight). */
class MemoryStore : ReminderStore {
    @Volatile var saved = emptyList<Reminder>()
    override fun load() = saved
    override fun save(reminders: List<Reminder>) { saved = reminders }
}
