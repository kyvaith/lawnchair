package app.lawnchair.folders

import java.text.Collator
import java.util.Locale

enum class FolderSortMode {
    MANUAL,
    ALPHABETICAL,
    MOST_USED,
}

data class FolderSortEntry(
    val id: Int,
    val identity: String,
    val label: String,
    val usageKey: String?,
) {
    // Include both placement ID and identity: ordinary folders may contain the same app twice,
    // and a reused database ID must not inherit a different item's original position.
    val stableToken: String get() = "$id:${identity.length}:$identity"
}

data class FolderSortPlan(
    val orderedEntries: List<FolderSortEntry>,
    val originalTokens: List<String>,
)

/** Pure ordering policy shared by the launcher and regression tests. */
object FolderSortPolicy {
    @JvmStatic
    fun plan(
        mode: FolderSortMode,
        entries: List<FolderSortEntry>,
        originalIdentityOrder: List<String>,
        counts: Map<String, Long>,
        locale: Locale,
    ): FolderSortPlan {
        require(entries.map { it.id }.distinct().size == entries.size) { "Folder item IDs must be unique." }
        val current = entries.associateBy { it.stableToken }
        val baseline = buildList {
            val seen = HashSet<String>()
            originalIdentityOrder.forEach { token ->
                if (token in current && seen.add(token)) add(token)
            }
            entries.forEach { entry ->
                if (seen.add(entry.stableToken)) add(entry.stableToken)
            }
        }
        val originalEntries = baseline.map { current.getValue(it) }
        val originalIndex = baseline.withIndex().associate { it.value to it.index }
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val ordered = when (mode) {
            FolderSortMode.MANUAL -> originalEntries

            FolderSortMode.ALPHABETICAL -> originalEntries.sortedWith { left, right ->
                val labelOrder = collator.compare(left.label, right.label)
                if (labelOrder != 0) labelOrder else originalIndex.getValue(left.stableToken).compareTo(originalIndex.getValue(right.stableToken))
            }

            FolderSortMode.MOST_USED -> originalEntries.sortedWith { left, right ->
                val leftCount = left.usageKey?.let { counts[it] }?.coerceAtLeast(0L) ?: 0L
                val rightCount = right.usageKey?.let { counts[it] }?.coerceAtLeast(0L) ?: 0L
                val frequencyOrder = rightCount.compareTo(leftCount)
                if (frequencyOrder != 0) frequencyOrder else originalIndex.getValue(left.stableToken).compareTo(originalIndex.getValue(right.stableToken))
            }
        }
        return FolderSortPlan(ordered, baseline)
    }
}
