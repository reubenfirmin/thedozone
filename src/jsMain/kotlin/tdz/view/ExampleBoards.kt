package tdz.view

import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.rootCanvas

/**
 * The curated example boards shown in the intro wizard's "Columns and cards" step. Each is pure
 * Markdown-per-column data; [ExampleLoader] drops one onto the current board so the user can see —
 * and immediately drag — real cards. Image cards use Pexels CDN URLs that end in `.jpeg` (the
 * renderer only treats a link as an image when it ends in an image extension, so no `?w=` sizing
 * params — they would break detection and serve full-res images instead).
 */
object ExampleBoards {

    /** A weekday across five side-by-side columns: a column heading plus a few draggable cards. */
    private val WEEK = run {
        val root = ColumnId(0)
        val days = (1..5).map(::ColumnId)
        val md = mapOf(
            days[0] to """
                # Monday

                ## Standup
                9am - share blockers

                ## To do
                * /Review the PRs
                * Write the spec
                * Email the vendor
            """.trimIndent(),
            days[1] to """
                # Tuesday

                ## To do
                * Design the sync flow
                * Gym at 6

                Drag a card to another day to reschedule it.
            """.trimIndent(),
            days[2] to """
                # Wednesday

                ## To do
                * /Dentist
                * 1:1 with Sam
                * Draft the newsletter
            """.trimIndent(),
            days[3] to """
                # Thursday

                ## To do
                * Ship the release
                * Team lunch
            """.trimIndent(),
            days[4] to """
                # Friday

                ## To do
                * Retro
                * /Plan next week
                * Inbox zero
            """.trimIndent(),
        )
        ExampleBoard(
            title = "Week planner",
            tagline = "Five days, one column each.",
            columns = listOf(root) + days,
            layouts = listOf(ColumnLayoutSpec(root, Split.VERTICAL, days)),
            content = md,
        )
    }

    /** A 3×3 grid mixing photo cards, pinned maps, and itinerary cards. */
    private val VACATION = run {
        val root = ColumnId(0)
        val rows = listOf(ColumnId(10), ColumnId(11), ColumnId(12))
        val cells = (1..9).map(::ColumnId)
        fun image(id: Long, caption: String) =
            "[https://images.pexels.com/photos/$id/pexels-photo-$id.jpeg ($caption)]"
        val md = mapOf(
            cells[0] to "# Costa del Sol\n\nFive days on the southern coast of Spain.",
            cells[1] to image(37800921, "Marbella coastline"),
            cells[2] to "# Málaga\n\n[map(36.7213, -4.4214, 12, 200) (Old town)]",
            cells[3] to image(22033733, "Beach day in Málaga"),
            cells[4] to """
                # Itinerary
                * Old town walking tour
                * /Book the paella dinner
                * Afternoon on the beach
            """.trimIndent(),
            cells[5] to image(19835141, "Puerto Banús"),
            cells[6] to "# Marbella\n\n[map(36.5101, -4.8858, 13, 200) (Marbella)]",
            cells[7] to image(36288577, "Whitewashed villas"),
            cells[8] to """
                # Packing
                * Sunscreen
                * /Passports
                * Swimsuits
            """.trimIndent(),
        )
        ExampleBoard(
            title = "Vacation planner",
            tagline = "Photos, maps, and plans in a grid.",
            columns = listOf(root) + rows + cells,
            layouts = listOf(
                ColumnLayoutSpec(root, Split.HORIZONTAL, rows),
                ColumnLayoutSpec(rows[0], Split.VERTICAL, cells.subList(0, 3)),
                ColumnLayoutSpec(rows[1], Split.VERTICAL, cells.subList(3, 6)),
                ColumnLayoutSpec(rows[2], Split.VERTICAL, cells.subList(6, 9)),
            ),
            content = md,
        )
    }

    /** Two columns of notes: headings, callouts, a blockquote, and a flow diagram. */
    private val MEETING = run {
        val root = ColumnId(0)
        val left = ColumnId(1)
        val right = ColumnId(2)
        val md = mapOf(
            left to """
                # Product sync

                ## Attendees
                Sam, Priya, Alex

                - Keep updates under two minutes
                -- Decisions only, park the deep dives

                ## Notes
                * Roadmap looks good for Q3
                * /Confirm the launch date
                * Follow up on pricing
            """.trimIndent(),
            right to """
                # Flow

                ---
                [Idea] -> [Design]
                [Design] -> [Build]
                [Build] -> [Ship]
                ---

                > Ship the smallest thing that teaches us something.

                - Park the deep dives for next week
                -- Needs legal sign-off before launch
            """.trimIndent(),
        )
        ExampleBoard(
            title = "Meeting notes",
            tagline = "Callouts and a diagram, side by side.",
            columns = listOf(root, left, right),
            layouts = listOf(ColumnLayoutSpec(root, Split.VERTICAL, listOf(left, right))),
            content = md,
        )
    }

    /** A focused project board: lots of task cards across three columns, plus one full-bleed image. */
    val PROJECT = run {
        val root = ColumnId(0)
        val cols = listOf(ColumnId(1), ColumnId(2), ColumnId(3))
        val md = mapOf(
            cols[0] to """
                # Backlog

                ## Content
                * Write the About page
                * Draft the FAQ
                * Collect three testimonials

                ## SEO
                * Meta descriptions
                * Generate the sitemap
                * Alt text on every image
            """.trimIndent(),
            cols[1] to """
                # In progress

                [https://images.pexels.com/photos/39284/macbook-apple-imac-computer-39284.jpeg (Design reference)]

                ## Homepage
                * Hero section
                * /Top navigation
                * Footer links

                ## Pricing
                * Plan comparison table
                * Annual toggle
            """.trimIndent(),
            cols[2] to """
                # Done

                * /Register the domain
                * /Finalize the logo
                * /Set up hosting
                * /Pick the colour palette
                * /Kickoff meeting
            """.trimIndent(),
        )
        ExampleBoard(
            title = "Project board",
            tagline = "A website launch in tasks.",
            columns = listOf(root) + cols,
            layouts = listOf(ColumnLayoutSpec(root, Split.VERTICAL, cols)),
            content = md,
        )
    }

    /** A one-column board (SINGLE_PANE) at [name], holding [markdown]. */
    private fun singleColumn(
        title: String, tagline: String, name: CanvasName, parent: CanvasName?, markdown: String,
    ): ExampleBoard {
        val root = ColumnId(0)
        val col = ColumnId(1)
        return ExampleBoard(
            title = title,
            tagline = tagline,
            columns = listOf(root, col),
            layouts = listOf(ColumnLayoutSpec(root, Split.HORIZONTAL, listOf(col))),
            content = mapOf(col to markdown),
            name = name,
            parent = parent,
        )
    }

    /**
     * A multi-board scene: a root board of links plus three populated child boards, each carrying a
     * link back to the root. The first board is the root; [ExampleLoader] creates the children and
     * wipes them when the wizard closes.
     */
    val MULTI_BOARD: List<ExampleBoard> = run {
        val home = "[_ (← Home Board)]"
        listOf(
            singleColumn(
                "Multiple boards", "One board links to the next.", rootCanvas, null,
                """
                    # Your boards

                    Each link below opens a board of its own, and the address is bookmarkable.

                    [Work]

                    [Home]

                    [Travel]
                """.trimIndent(),
            ),
            singleColumn(
                "Work", "", CanvasName("Work"), rootCanvas,
                """
                    # Work

                    ## Today
                    * Stand-up at 9
                    * Review the design doc
                    * /Reply to Sam

                    ## This week
                    * Ship the API
                    * Plan the Q3 roadmap

                    $home
                """.trimIndent(),
            ),
            singleColumn(
                "Home", "", CanvasName("Home"), rootCanvas,
                """
                    # Home

                    ## Chores
                    * /Water the plants
                    * Grocery run
                    * Fix the leaky tap

                    ## Weekend
                    * Saturday hike
                    * Call Mum

                    $home
                """.trimIndent(),
            ),
            singleColumn(
                "Travel", "", CanvasName("Travel"), rootCanvas,
                """
                    # Travel

                    ## Trip ideas
                    * Lisbon in spring
                    * Coast road trip

                    ## To book
                    * Flights
                    * /Hostel in Porto

                    $home
                """.trimIndent(),
            ),
        )
    }

    /** Step one — "Columns and cards": pick a board to see how columns and cards work. */
    val COLUMNS_AND_CARDS: List<ExampleBoard> = listOf(WEEK, VACATION, MEETING)

    /** Every example board, for invariants. */
    val ALL: List<ExampleBoard> = COLUMNS_AND_CARDS + listOf(PROJECT) + MULTI_BOARD
}
