package app.lawnchair.deck

import kotlin.random.Random

/** Standalone JVM regression suite; no Android device, SDK stubs or test mocking is involved. */
fun main() {
    data class App(val component: String, val profile: Int)

    val personal = App("example/.Main", 0)
    val work = App("example/.Main", 10)
    val alias = App("example/.OtherActivity", 0)
    fun entry(id: Int, app: App) = DeckReconciler.Entry(id, app)

    check(DeckReconciler.plan(listOf(personal, work, alias), emptyList()).missing == listOf(personal, work, alias))
    check(DeckReconciler.plan(listOf(personal, work), listOf(entry(4, personal))).missing == listOf(work))
    check(DeckReconciler.plan(listOf(personal, alias), listOf(entry(4, personal))).missing == listOf(alias))
    // Input order must not displace the original folder/hotseat placement with a new duplicate.
    val duplicates = DeckReconciler.plan(listOf(personal), listOf(entry(9, personal), entry(2, personal)))
    check(duplicates.missing.isEmpty() && duplicates.duplicateIds == setOf(9))
    // A temporarily unavailable profile is not an uninstall: keep its only placement.
    check(DeckReconciler.plan(emptyList(), listOf(entry(7, work))).duplicateIds.isEmpty())
    check(DeckReconciler.plan(listOf(personal, personal), emptyList()).missing == listOf(personal))

    val random = Random(7138)
    val universe = (0 until 12).flatMap { component ->
        listOf(0, 10, 11).map { App("package/$component", it) }
    }
    repeat(5000) {
        val catalog = List(random.nextInt(90)) { universe.random(random) }
        val entries = List(random.nextInt(150)) { entry(it + 1, universe.random(random)) }.shuffled(random)
        val plan = DeckReconciler.plan(catalog, entries)
        val retained = entries.filterNot { it.id in plan.duplicateIds }
        val result = retained + plan.missing.mapIndexed { index, app -> entry(1000 + index, app) }
        // Exactly one placement per identity; same package in different profiles stays distinct.
        check(result.map { it.key }.distinct().size == result.size)
        check(result.map { it.key }.toSet().containsAll(catalog))
        // No missing/unavailable record was removed as a side effect of reconciling other apps.
        check(retained.map { it.key }.toSet() == entries.map { it.key }.toSet())
        entries.groupBy { it.key }.forEach { (key, group) ->
            check(retained.single { it.key == key }.id == group.minOf { it.id })
        }
        val second = DeckReconciler.plan(catalog, result)
        check(second.missing.isEmpty() && second.duplicateIds.isEmpty())
    }
    println("PASS: Deck identity/profile/alias/duplicate/unavailable/idempotence regressions + 5000 randomized cases")
}
