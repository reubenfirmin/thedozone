package tdz.view

import tdz.model.CanvasName
import tdz.model.ColumnId
import tdz.model.ColumnLayoutSpec
import tdz.model.Split
import tdz.rootCanvas
import tdz.view.theme.longFormTheme

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

                ## 🤯 Mind-blower
                About 80% of the features in a typical product are rarely or never used (Standish, Pendo). The hard part isn't building more. It's finding the 20% that matters.
            """.trimIndent(),
            right to """
                # Flow

                ---
                [Idea] -> [Spec]
                [Spec] -> [Build]
                [Build] -> [<choice>Review]
                [Review] ship -> [Ship]
                [Review] iterate -> [Build]
                [Ship] -> [Learn]
                [Learn] -> [Idea]
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
        val home = "[~ (← Home Board)]"
        // The root is a 3-column board: the board links on the left, a stack of cards to sort in the
        // middle (drag each over the matching link — the wormhole sends it to that board), spare right.
        val root = ColumnId(0)
        val linksCol = ColumnId(1)
        val sortCol = ColumnId(2)
        val spareCol = ColumnId(3)
        val rootBoard = ExampleBoard(
            title = "Multiple boards",
            tagline = "One board links to the next.",
            columns = listOf(root, linksCol, sortCol, spareCol),
            layouts = listOf(ColumnLayoutSpec(root, Split.VERTICAL, listOf(linksCol, sortCol, spareCol))),
            content = mapOf(
                linksCol to """
                    ## Work board
                    This links to my work board
                    [Work]

                    ## Home board
                    This links to my home board
                    [Home]

                    ## Travel board
                    This links to my travel board
                    [Travel]
                """.trimIndent(),
                sortCol to """
                    # Sort these cards

                    Drag these cards over the blue board links at the left.

                    ## Porto trip
                    * Book the hostel
                    * Find a cheap flight
                    * Pack the plug adapter

                    ## House
                    * Water the plants
                    * Take out the bins
                    * Fix the leaky tap

                    ## Monday standup
                    * Prep three talking points
                    * Review the design doc
                    * Reply to Sam
                """.trimIndent(),
                spareCol to "",
            ),
            name = rootCanvas,
            parent = null,
        )
        listOf(
            rootBoard,
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

    /**
     * A flashcard deck of flip cards: each card carries a `//` face boundary, so the question shows
     * first and the answer is one click (↻) away. A title banner with a BACKGROUND IMAGE spans the
     * top, with four category columns of flip cards below.
     *
     * It previews the Long Form theme (dark text on a white surface), which guarantees the dark text
     * reads over a LIGHT wallpaper — wallpaper legibility is otherwise the user's own responsibility,
     * since they can set any image under any theme.
     */
    val SPANISH_EXAM_PREP = run {
        val root = ColumnId(0)
        val banner = ColumnId(1)
        val gridRow = ColumnId(2)
        val cats = listOf(ColumnId(10), ColumnId(11), ColumnId(12), ColumnId(13))

        // A light, airy Mediterranean photo — dark Long Form text reads over it.
        fun wallpaper(url: String) = "<!--tdz-col:{\"wallpaper\":\"$url\"}-->"
        // One flip card: question on the front, answer (the `//` is the face boundary) on the back.
        fun card(front: String, back: String) = "$front\n//\n$back"
        fun deck(heading: String, vararg cards: String) = "$heading\n\n" + cards.joinToString("\n\n")

        val md = mapOf(
            banner to wallpaper("https://images.pexels.com/photos/37800921/pexels-photo-37800921.jpeg") +
                "\n# Spanish Exam Prep\n\nTap the ↻ on any card to flip the question to its answer.",
            cats[0] to deck(
                "# Greetings",
                card("\"Good morning\"", "Buenos días"),
                card("\"Good evening\"", "Buenas tardes"),
                card("\"How are you?\"", "¿Cómo estás?"),
                card("\"Nice to meet you\"", "Mucho gusto"),
                card("\"See you tomorrow\"", "Hasta mañana"),
            ),
            cats[1] to deck(
                "# Common verbs",
                card("to be (permanent)", "ser\nSoy estudiante."),
                card("to have", "tener\nTengo dos hermanos."),
                card("to go", "ir\nVoy al mercado."),
                card("to want", "querer\nQuiero aprender español."),
                card("to be able to", "poder\n¿Puedes ayudarme?"),
            ),
            cats[2] to deck(
                "# Food & dining",
                card("\"the bill, please\"", "La cuenta, por favor"),
                card("\"I'm hungry\"", "Tengo hambre"),
                card("breakfast", "el desayuno"),
                card("\"a table for two\"", "Una mesa para dos"),
                card("\"it's delicious\"", "Está delicioso"),
            ),
            cats[3] to deck(
                "# Numbers & time",
                card("seven", "siete"),
                card("\"What time is it?\"", "¿Qué hora es?"),
                card("today / tomorrow", "hoy / mañana"),
                card("\"on Monday\"", "el lunes"),
                card("one hundred", "cien"),
            ),
        )
        ExampleBoard(
            title = "Spanish exam prep",
            tagline = "Flip cards: question on the front, answer on the back.",
            columns = listOf(root, banner, gridRow) + cats,
            layouts = listOf(
                ColumnLayoutSpec(root, Split.HORIZONTAL, listOf(banner, gridRow)),
                ColumnLayoutSpec(gridRow, Split.VERTICAL, cats),
            ),
            content = md,
            theme = longFormTheme,   // dark-on-white, so the dark text reads over the light wallpaper
        )
    }

    /**
     * The "It's all markdown" step's playground: three columns holding one of (almost) every card
     * type — headings, text, a blockquote, callouts, a task list with a done item, a numbered list, a
     * code block, a photo, a map, a flip card, a diagram, and a canvas link — so the user can right-
     * click → Edit and poke at the raw markdown behind each.
     */
    val MARKDOWN_SHOWCASE = run {
        val root = ColumnId(0)
        val cols = listOf(ColumnId(1), ColumnId(2), ColumnId(3))
        val md = mapOf(
            cols[0] to """
                # Markdown playground

                Right-click a column and choose Edit (or press Ctrl+') to see the raw markdown behind these cards, then switch back.

                ## Headings & text
                Start a line with # for a big heading, ## for a smaller one. Everything else is plain text.

                > A blockquote starts its line with > and stands apart.

                - A single dash is a callout.
                -- Two dashes make a stronger one.

                ---
                [Write] -> [Edit]
                [Edit] -> [Ship]
                ---
            """.trimIndent(),
            cols[1] to """
                # To-do
                * Try editing this list
                * /This one is finished
                * Drag a card to another column

                ## Numbered steps
                1. First
                2. Second
                3. Third

                ```
                greet = name => "Hi " + name
                ```
            """.trimIndent(),
            cols[2] to """
                [https://images.pexels.com/photos/22033733/pexels-photo-22033733.jpeg (A photo card)]

                # Pin a place
                [map(36.7213, -4.4214, 12, 200) (Málaga)]

                Tap the ↻ to flip me
                //
                A // line turns one card into a flip card.
            """.trimIndent(),
        )
        ExampleBoard(
            title = "Markdown playground",
            tagline = "One of every card type to poke at.",
            columns = listOf(root) + cols,
            layouts = listOf(ColumnLayoutSpec(root, Split.VERTICAL, cols)),
            content = md,
        )
    }

    /** Step one — "Columns and cards": pick a board to see how columns and cards work. */
    val COLUMNS_AND_CARDS: List<ExampleBoard> = listOf(WEEK, VACATION, MEETING, SPANISH_EXAM_PREP)

    /** Every example board, for invariants. */
    val ALL: List<ExampleBoard> = COLUMNS_AND_CARDS + listOf(PROJECT, MARKDOWN_SHOWCASE) + MULTI_BOARD
}
