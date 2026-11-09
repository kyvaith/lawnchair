package app.lawnchair.deck

/**
 * Plans the single-layer invariant without depending on Android or the drawer UI.
 *
 * The caller supplies launchable component/profile identities, not package names. Missing entries
 * are added and duplicate application entries are removed. Absence from the catalog is deliberately
 * NOT a deletion signal: Android can temporarily hide a profile or an unavailable package.
 */
internal object DeckReconciler {
    data class Entry<K>(val id: Int, val key: K)

    data class Plan<K>(val missing: List<K>, val duplicateIds: Set<Int>)

    fun <K> plan(catalog: Collection<K>, entries: List<Entry<K>>): Plan<K> {
        val represented = HashSet<K>()
        val duplicates = LinkedHashSet<Int>()
        // Keep the oldest placement, including a placement in a folder or the hotseat.
        entries.sortedBy { it.id }.forEach { entry ->
            if (!represented.add(entry.key)) duplicates.add(entry.id)
        }
        return Plan(catalog.distinct().filterNot { it in represented }, duplicates)
    }
}
