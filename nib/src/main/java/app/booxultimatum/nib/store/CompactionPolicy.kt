package app.booxultimatum.nib.store

/**
 * When the journal is folded into a fresh `.nib` snapshot: every [everyCommands] commands, when the journal has grown
 * past [maxJournalBytes], and whenever the editor stops or closes with anything unsaved in the snapshot.
 */
class CompactionPolicy(val everyCommands: Int = 50, val maxJournalBytes: Long = 8L shl 20) {
    init {
        require(everyCommands > 0) { "everyCommands must be positive" }
    }

    /** After a command was journaled. */
    fun afterCommand(commandsSinceSnapshot: Int, journalBytes: Long): Boolean =
        commandsSinceSnapshot >= everyCommands || journalBytes >= maxJournalBytes

    /** When the editor stops or closes. */
    fun onStop(commandsSinceSnapshot: Int): Boolean = commandsSinceSnapshot > 0
}
