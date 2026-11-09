package app.lawnchair.folders

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FolderSortPolicyTest {
    @Test
    fun alphabeticalUsesRequestedLocaleAndOriginalOrderForCollationTies() {
        val swedish = listOf(entry(1, "Örebro"), entry(2, "Zulu"), entry(3, "Åland"), entry(4, "Alpha"))
        assertEquals(listOf(4, 2, 3, 1), plan(FolderSortMode.ALPHABETICAL, swedish, locale = Locale.forLanguageTag("sv-SE")).orderedEntries.map { it.id })
        val ties = listOf(entry(1, "éclair"), entry(2, "Eclair"), entry(3, "apple"))
        assertEquals(listOf(3, 1, 2), plan(FolderSortMode.ALPHABETICAL, ties).orderedEntries.map { it.id })
    }

    @Test
    fun frequencyHasStableOriginalTiesAndDoesNotOverflow() {
        val entries = listOf(entry(1, "A"), entry(2, "B"), entry(3, "C"), entry(4, "D"))
        val counts = mapOf("A" to 2L, "B" to Long.MAX_VALUE, "C" to 2L, "D" to -5L)
        assertEquals(listOf(2, 1, 3, 4), plan(FolderSortMode.MOST_USED, entries, counts = counts).orderedEntries.map { it.id })
    }

    @Test
    fun duplicateAppsStaySeparateAndReusedIdsDoNotInheritOldPositions() {
        val first = FolderSortEntry(1, "same/app/0", "Same", "same/app/0")
        val second = first.copy(id = 2)
        assertEquals(listOf(1, 2), plan(FolderSortMode.MOST_USED, listOf(first, second)).orderedEntries.map { it.id })
        val replacement = first.copy(identity = "different/app/0")
        val restored = FolderSortPolicy.plan(FolderSortMode.MANUAL, listOf(replacement, second), listOf(first.stableToken, second.stableToken), emptyMap(), Locale.US)
        assertEquals(listOf(2, 1), restored.orderedEntries.map { it.id })
    }

    @Test
    fun removedItemsAreNotRevivedAndNewMembersAppendToTheOriginalBaseline() {
        val original = listOf(entry(1, "A"), entry(2, "B"), entry(3, "C"))
        val current = listOf(original[2], entry(4, "D"), original[0])
        val restored = FolderSortPolicy.plan(FolderSortMode.MANUAL, current, original.map { it.stableToken }, emptyMap(), Locale.US)
        assertEquals(listOf(1, 3, 4), restored.orderedEntries.map { it.id })
        assertEquals(restored.orderedEntries.map { it.stableToken }, restored.originalTokens)
    }

    @Test
    fun duplicatePlacementIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            plan(FolderSortMode.ALPHABETICAL, listOf(entry(1, "A"), entry(1, "B")))
        }
    }

    @Test
    fun randomizedPlansPreserveExactlyCurrentMembershipAndAreIdempotent() {
        val random = Random(7138)
        repeat(3000) {
            val original = (0 until random.nextInt(1, 30)).map { entry(it, ('A' + random.nextInt(6)).toString()) }
            val current = original.filter { random.nextBoolean() }.shuffled(random) +
                (100 until 100 + random.nextInt(10)).map { entry(it, ('A' + random.nextInt(6)).toString()) }
            val counts = ('A'..'F').associate { it.toString() to random.nextLong(10) }
            FolderSortMode.entries.forEach { mode ->
                val result = FolderSortPolicy.plan(mode, current, original.map { it.stableToken }, counts, Locale.US)
                assertEquals(current.map { it.id }.toSet(), result.orderedEntries.map { it.id }.toSet())
                assertEquals(current.size, result.orderedEntries.size)
                val again = FolderSortPolicy.plan(mode, result.orderedEntries, result.originalTokens, counts, Locale.US)
                assertEquals(result, again)
            }
        }
    }

    private fun entry(id: Int, label: String) = FolderSortEntry(id, "component/$id/profile0", label, label)
    private fun plan(mode: FolderSortMode, entries: List<FolderSortEntry>, counts: Map<String, Long> = emptyMap(), locale: Locale = Locale.US) =
        FolderSortPolicy.plan(mode, entries, entries.map { it.stableToken }, counts, locale)
}
