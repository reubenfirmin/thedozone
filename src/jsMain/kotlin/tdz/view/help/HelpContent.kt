package tdz.view.help

object HelpContent {
    val SHORTCUTS = """
        # Keyboard Shortcuts

        Drive boards, columns, and the editor without touching the mouse. Modifier keys use Windows/Linux on the left and macOS on the right.

        ## Boards & Columns
        - Toggle the focused column between Card and Editor — Ctrl+' (or i) / ⌘+' (or i)
        - Focus the previous / next column — ← / →
        - Focus the column above / below — ↑ / ↓
        - Clear completed tasks on the board — Ctrl+; / ⌘+;
        - Split column, side by side — Ctrl+Alt+← / → / ⌘+⌥+← / →
        - Split column, stacked — Ctrl+Alt+↑ / ↓ / ⌘+⌥+↑ / ↓

        ## Boards & Navigation
        - Open the board palette — Ctrl+K or Ctrl+L (or Page Down) / ⌘+K or ⌘+L (or fn+↓)
        - Go to the parent board — Page Up / fn+↑
        In the palette: type a path (like /work/today) and Enter to go there, creating it if new; type to filter the current board's children; ↑ / ↓ to move and Enter to open; Page Up jumps to the parent; Esc closes.

        ## App
        - Open Sync — Ctrl+Shift+D / ⌘+⇧+D
        - Open this Keyboard Shortcuts page — F1 / F1
        - Open the Card Formatting page — F2 / F2
        - Close an open menu or popover — Esc / Esc
        - Exit the editor back to cards — Esc / Esc

        ## Editor
        Text navigation follows standard conventions: Windows/Linux line and document keys, and their ⌘-based equivalents on macOS.
        - Continue the list on a new line — Enter / Return
        - Indent the list item — Tab / Tab
        - Outdent the list item — Shift+Tab / ⇧+Tab
        - Beginning / end of line — Home / End / ⌘+← / →
        - Beginning / end of document — Ctrl+Home / End / ⌘+↑ / ↓
        - Jump to the previous / next heading — Ctrl+↑ / ↓ / ⌃+↑ / ↓
        - Focus the previous / next column from the editor — Alt+← / → / ⌥+← / →
        - Undo / redo — Ctrl+Z / Ctrl+Shift+Z / ⌘+Z / ⇧+⌘+Z
        - Select all — Ctrl+A / ⌘+A
        - Find / replace — Ctrl+F / ⌘+F
        - Find next / previous — Ctrl+G / Ctrl+Shift+G / ⌘+G / ⇧+⌘+G
        - Accept a board-link suggestion — Tab or Enter / Tab or Return
        - Move the line up / down — Alt+↑ / ↓ / ⌥+↑ / ↓
        - Delete the line — Ctrl+Shift+K / ⇧+⌘+K

        ## Mouse
        - Show the context menu — Right-click
        - Open a column in the editor (press Esc to go back) — Double-click the column
        - Focus a column — Hover over the column
    """.trimIndent()

    val FORMATTING = """
        # Card Formatting

        The Do Zone is a 3D kanban board: arrange columns in any 2D grid, then stack boards in a hierarchy for the third dimension. It's all plain Markdown, kept in your browser, downloadable as a file, and syncable to Dropbox or Google Drive. This guide covers the Markdown that fills each column. Every example below is a copyable snippet — hit the copy button to grab it.

        # The basics

        ## The big picture
        A Do Zone has three dimensions:
        - Columns. Split the screen into any 2D grid of columns, side by side and stacked.
        - Cards. Each column holds a stack of Markdown cards (the rest of this page).
        - Boards. A board can link to another board, so they nest into a hierarchy you drill down into and pop back out of. That nesting is the third dimension.
        Everything is Markdown text. Your boards live in the browser's local storage, download as a portable .tdz file at any time, and sync across devices through Dropbox or Google Drive (right-click, then Sync).

        ## Columns
        A column is a single Markdown document shown two ways. Normally you see rendered cards. Double-click the column (or press Ctrl+') to flip it into the editor and type Markdown directly, then press Esc to flip back.
        Right-click a column to Split it (side by side or stacked) or Distribute a new pane into its row. That is how you build the 2D grid. The full set of column and grid shortcuts lives on the Keyboard Shortcuts page.

        ## How cards work
        A column is just text. Consecutive lines belong to the same card; a blank line starts a new one. So this is two cards:
        > # Groceries
        > Milk, eggs, bread
        >
        > # Errands
        > Post office before 5pm
        That's the only structural rule: blank line = new card. Everything else is about what you put inside a card.

        ## Headings & sections
        Title a card with a # heading and plain lines beneath it are just text. The structure we recommend, though, is a single # heading alone on its own card — a section header — followed by a series of cards that each carry one ## heading. The ## cards group under the # header, so dragging the header moves the whole section as one block (the group runs until the next # header).
        > # Trip planning
        >
        > ## Flights
        > Outbound Friday, return Sunday night.
        >
        > ## Hotel
        > Something near the old town.
        That's three cards: the "Trip planning" section header, then "Flights" and "Hotel" beneath it. Drag "Trip planning" and both come along.

        ## Tasks & checklists
        Lines that start with * (or +) become checkable tasks. Click the checkbox in card view to complete one. In the editor you can mark a task done by putting a / right after the bullet (not that you'd ever need to; just click the checkbox).
        > # Launch
        > * Draft the announcement
        > * /Book the venue
        > * Send invites
        Pressing Enter at the end of a task continues the list automatically. Use numbered tasks with 1. and the numbers renumber themselves:
        > # Release steps
        > 1. Tag the build
        > 2. Run the smoke tests
        > 3. Publish
        Indent (Tab) to make sub-tasks; Shift+Tab outdents:
        > # Pack
        > * Clothes
        >   * Socks
        >   * Rain jacket
        > * Electronics
        >   * Charger
        >   * /Headphones
        Clear every completed task on the board at once with Ctrl+;.

        # Links & media

        ## Linking to other boards
        A bare name in square brackets links to another board. Click it to open the board; if it doesn't exist yet, it's created on the spot. This is how you nest boards inside boards.
        > [Groceries]
        Add a friendlier label in parentheses; the part before it is the board name, the part inside is what's shown:
        > [Groceries (Shopping list)]
        > [2026-budget (Budget)]
        Tip: press Page Down on a board to pop up a switcher of the sub-boards it links to, then press 1 to 9 to jump to one.

        ## Web links, images & maps
        The same [target (label)] syntax handles the outside world. If the target looks like a web address, it becomes a clickable link:
        > [https://anthropic.com (Anthropic)]
        > [mailto:hi@example.com (Email me)]
        If the address ends in an image extension (.png, .jpg, .gif, .svg, .webp), it renders inline as an image:
        > [https://example.com/photo.png (Holiday photo)]
        Drop a pin with map(latitude, longitude). Add an optional zoom level and pixel height:
        > [map(48.8584, 2.2945)]
        > [map(48.8584, 2.2945, 14)]
        > [map(48.8584, 2.2945, 14, 300)]

        # Rich content

        ## Code
        Wrap a few characters in backticks for inline code:
        > Run `git status` before you commit.
        For a whole block, fence it with a line of three backticks above and below:
        > ```
        > fun main() {
        >     println("Hello, Zone")
        > }
        > ```

        ## Diagrams
        Fence a block with a line of three dashes (---) above and below to draw a diagram. The body uses nomnoml syntax: names in square brackets, arrows with ->:
        > ---
        > [Idea] -> [Plan]
        > [Plan] -> [Build]
        > [Build] -> [Ship]
        > ---

        ## Callouts
        A line starting with a dash - is a callout: a non-interactive note (no checkbox) set off with an arrow. Use more dashes for a heavier arrow.
        > # Deploy notes
        > - Double-check the env vars first
        > -- Do not run this on a Friday
        Remember: * and + make checkable tasks, while - makes a callout. That's the one easy thing to mix up.

        ## Blockquotes
        Prefix lines with > to set them off as a blockquote. It renders as a boxed snippet with a copy button in the corner, so it's the right tool for anything a reader should grab verbatim — in fact every example on this page is a blockquote.
        > > To be, or not to be,
        > > that is the question.
        The contents are shown exactly as typed: Markdown inside a blockquote is not reprocessed, so it's also how you show example syntax without it turning into real cards, tasks, or links.
    """.trimIndent()

    val RELEASE_NOTES = """
        # Release Notes

        ## Full rewrite of The Do Zone
        Jun 10, 2026 — Latest
        Faster, smoother, and better looking.
    """.trimIndent()

    val PRIVACY = """
        # Privacy Policy

        The Do Zone runs entirely in your browser. There is no server of ours that stores your boards — we never see your data, and we don't want to.

        ## Where your data lives
        Your boards are saved locally in your browser, on your device. If you turn on sync, they are written directly to the cloud you choose — your Google Drive, your Dropbox, or a local folder — straight from your browser to that provider. They never pass through us, because there is no server of ours in the middle.

        ## What we collect
        Nothing. There is no account, no analytics on what you write, and no tracking of your content.

        ## Services you choose to use
        Cloud sync uses an access token your browser stores locally and sends only to your provider, never to us. Other features reach external hosts directly from your browser when you use them: map cards load tiles from OpenStreetMap, and image cards or wallpapers load from whatever image URL you add. Those requests go straight to those hosts under their own privacy policies — never through us.

        ## Your control
        Clearing your browser storage removes the local copy. Disconnecting sync stops further writes; deleting the app's folder in your cloud removes the synced copy.

        Questions? rf@4rc.io
    """.trimIndent()
}

enum class HelpTopic(val title: String, val markdown: String) {
    SHORTCUTS("Keyboard Shortcuts", HelpContent.SHORTCUTS),
    FORMATTING("Card Formatting", HelpContent.FORMATTING),
    RELEASE_NOTES("Release Notes", HelpContent.RELEASE_NOTES),
    PRIVACY("Privacy", HelpContent.PRIVACY),
}
