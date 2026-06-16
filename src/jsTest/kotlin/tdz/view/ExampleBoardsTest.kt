package tdz.view

import tdz.model.ColumnId
import tdz.model.markdown.MarkdownService
import tdz.rootCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the curated example boards: each must be a renderable layout whose content map covers
 * exactly its content columns, and each must actually demonstrate the feature it advertises
 * (week columns, image + map cards, callouts + a diagram).
 */
class ExampleBoardsTest {

    @Test
    fun stepOneOffersFourExamples() {
        assertEquals(4, ExampleBoards.COLUMNS_AND_CARDS.size,
            "week planner / vacation planner / meeting notes / spanish exam prep")
    }

    @Test
    fun spanishExamPrepIsAFlipCardDeckWithABackgroundImageAndAReadableTheme() {
        val spanish = ExampleBoards.COLUMNS_AND_CARDS.first { it.title.contains("Spanish") }
        // The decks are flip cards: parse a category column and confirm it has CardSideBreak faces.
        val greetings = spanish.content.entries.first { it.value.contains("Greetings") }.value
        val cards = MarkdownService.fromMd(greetings, ColumnId(0))
        val flipCards = cards.count { c -> c.segments.any { it is tdz.model.card.CardSideBreak } }
        assertTrue(flipCards >= 4, "the greetings deck is mostly flip cards; got $flipCards")
        // Exactly one column carries a wallpaper background image (the banner).
        val wallpapered = spanish.content.values.count { it.contains("<!--tdz-col:") && it.contains("wallpaper") }
        assertEquals(1, wallpapered, "one column has a background image")
        // It previews a dark-on-white theme so that dark text is legible over the light wallpaper.
        assertEquals("Long Form", spanish.theme?.name, "deck previews the Long Form (dark-on-white) theme")
    }

    @Test
    fun projectPresetHasManyTasksAndOneImage() {
        val project = ExampleBoards.PROJECT
        val tasks = project.content.values.joinToString("\n").lines().count { it.trimStart().startsWith("* ") }
        assertTrue(tasks >= 8, "lots of task cards; got $tasks")
        val images = project.content.values
            .flatMap { MarkdownService.fromMd(it, ColumnId(0)) }
            .count { it.isImageLink() }
        assertEquals(1, images, "exactly one image card")
    }

    @Test
    fun multiBoardSceneLinksRootToThreeChildrenAndBack() {
        val scene = ExampleBoards.MULTI_BOARD
        val root = scene.first { it.name == rootCanvas }
        val children = scene.filter { it.name != rootCanvas }
        assertEquals(3, children.size, "three child boards")

        fun links(b: ExampleBoard) = b.content.values
            .flatMap { MarkdownService.fromMd(it, ColumnId(0)) }.count { it.isCanvasLink() }

        assertEquals(3, links(root), "root links to the three child boards")
        children.forEach { child ->
            assertTrue(links(child) >= 1, "${child.name.normalized()} links back home")
            val tasks = child.content.values.joinToString("\n").lines().count { it.trimStart().startsWith("* ") }
            assertTrue(tasks >= 1, "${child.name.normalized()} carries example content")
        }
    }

    @Test
    fun everyExampleBoardIsRenderable() {
        ExampleBoards.ALL.forEach { ex ->
            assertTrue(ex.canvas().isLayoutSound(), "${ex.title} must be a sound layout")
        }
    }

    @Test
    fun everyContentColumnHasMarkdown() {
        ExampleBoards.ALL.forEach { ex ->
            assertEquals(
                ex.contentColumns().toSet(), ex.content.keys,
                "${ex.title}: content must cover exactly the content columns",
            )
        }
    }

    @Test
    fun weekPlannerHasFiveWeekdayColumns() {
        val week = ExampleBoards.ALL.first { it.title.contains("Week") }
        assertEquals(5, week.contentColumns().size, "five days, side by side")
        val all = week.content.values.joinToString("\n")
        listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday").forEach {
            assertTrue(all.contains(it), "week planner should head a column with $it")
        }
    }

    @Test
    fun vacationPlannerIsAThreeByThreeGridOfImagesAndMaps() {
        val vac = ExampleBoards.ALL.first { it.title.contains("Vacation") }
        assertEquals(9, vac.contentColumns().size, "a 3x3 grid")
        val all = vac.content.values.joinToString("\n")
        assertTrue(all.contains("images.pexels.com"), "uses pexels image cards")
        assertTrue(all.contains("map("), "uses map cards")
    }

    @Test
    fun vacationImageMarkdownActuallyParsesToImageCards() {
        val vac = ExampleBoards.ALL.first { it.title.contains("Vacation") }
        val anyImage = vac.content.values
            .flatMap { MarkdownService.fromMd(it, ColumnId(0)) }
            .any { it.isImageLink() }
        assertTrue(anyImage, "the pexels URLs must parse as image cards (extension at the end, no query string)")
    }

    @Test
    fun meetingNotesHasCalloutsAndADiagram() {
        val mtg = ExampleBoards.ALL.first { it.title.contains("Meeting") }
        assertEquals(2, mtg.contentColumns().size, "two columns")
        val all = mtg.content.values.joinToString("\n")
        assertTrue(all.contains("-- "), "uses a heavier callout")
        assertTrue(all.lines().any { it.trim() == "---" }, "has a nomnoml diagram fence")
    }
}
