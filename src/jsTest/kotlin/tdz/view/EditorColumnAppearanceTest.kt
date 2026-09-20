package tdz.view

import kotlinx.css.*
import kotlinx.html.div
import tdz.controller.events.Event
import tdz.model.CanvasName
import tdz.model.ColumnStyle
import tdz.model.LayoutTemplate.SINGLE_PANE
import tdz.model.LayoutTemplates.canvasFromTemplate
import tdz.model.Model
import tdz.pollUntil
import tdz.view.theme.Theme
import tdz.view.theme.ThemeManager
import tdz.view.theme.blueRoomTheme
import tdz.view.theme.metroTheme
import tdz.view.theme.poodleRocksTheme
import web.dom.getComputedStyle
import web.html.HTMLElement
import zoned.framework.dom.Ref
import zoned.framework.dom.ref
import zoned.framework.events.EventBus
import zoned.framework.interop.addToBody
import zoned.framework.interop.css
import zoned.framework.interop.getHtmlElement
import kotlin.js.Promise
import kotlin.test.*

class EditorColumnAppearanceTest {
    private val originalTheme = ThemeManager.current
    private val host = Ref<HTMLElement>()
    private var columns: CanvasColumns? = null

    @AfterTest
    fun cleanup() {
        columns?.saveEditors()
        host.getOrNull()?.remove()
        ThemeManager.use(originalTheme)
    }

    private fun checkSurface(theme: Theme, override: ColumnStyle = ColumnStyle(null, null)): Promise<Unit> {
        ThemeManager.use(theme)
        val name = CanvasName("_editor-appearance")
        val model = Model(name)
        model.addCanvas(canvasFromTemplate(name, null, SINGLE_PANE))
        val canvas = model.getCanvas(name)!!
        val column = canvas.firstNonLayout()
        model.updateColumn(name, column, "# Heading\n\nFirst line\nSecond line\n")
        model.setColumnStyle(name, column, override)
        val view = CanvasColumns(canvas, EventBus<Event>(), model, isMobile = { false }) { model.cards(name, it) }
        columns = view
        addToBody {
            div {
                ref(host)
                css { backgroundColor = theme.appBg; backgroundImage = Image(theme.appBgImage) }
                with(view) { render(canvas.leafColumns()) }
            }
        }
        val readSurface = getComputedStyle(getHtmlElement(DomIds.column(column))!!)
        val readColor = readSurface.backgroundColor
        val readImage = readSurface.backgroundImage
        val appImage = getComputedStyle(host.element).backgroundImage
        if (override.wallpaper != null) assertNotEquals("none", readImage)

        view.toggleMode(column)
        return pollUntil("editor inside styled column") { view.editorCache[column]?.editor != null }.then {
            val editor = view.editorCache[column]!!.editor!!
            fun assertAppearance() {
                val surface = getComputedStyle(getHtmlElement(DomIds.column(column))!!)
                assertEquals(readColor, surface.backgroundColor, "editing preserves the read-view column color")
                assertEquals(readImage, surface.backgroundImage, "editing preserves the column wallpaper")
                assertEquals(appImage, getComputedStyle(host.element).backgroundImage)
                // Every layer between the column surface and its text must let that surface show.
                listOf(editor.view.dom, editor.view.scrollDOM, editor.view.contentDOM).forEach {
                    assertEquals("rgba(0, 0, 0, 0)", getComputedStyle(it).backgroundColor)
                }
                val gutter = editor.view.dom.querySelector(".cm-gutters")
                if (theme.editorPlain) {
                    assertNull(gutter)
                } else {
                    assertNotNull(gutter)
                    assertEquals("rgba(0, 0, 0, 0)", getComputedStyle(gutter).backgroundColor,
                        "the gutter shows the same column surface as the text")
                    val numbers = editor.view.dom.querySelector(".cm-lineNumbers")!!
                    assertEquals(getComputedStyle(editor.view.dom).color, getComputedStyle(numbers).color,
                        "line numbers use the readable text color")
                    assertNotEquals(readColor, getComputedStyle(numbers).color)
                }
            }
            editor.focus()
            editor.insert("!")
            assertAppearance()
            view.toggleMode(column)
            assertEquals(readColor, getComputedStyle(getHtmlElement(DomIds.column(column))!!).backgroundColor)
            view.toggleMode(column)
            assertSame(editor, view.editorCache[column]!!.editor)
            assertAppearance()
        }
    }

    @Test
    fun blueColumnKeepsItsSurfaceWithAnUnrelatedMenuColor(): Promise<Unit> =
        checkSurface(blueRoomTheme.copy(menuBg = Color("#ff00ff")))

    @Test
    fun paletteColumnKeepsItsTileColor(): Promise<Unit> = checkSurface(metroTheme)

    @Test
    fun customColumnColorAndWallpaperShowThroughEditorAndGutter(): Promise<Unit> = checkSurface(
        metroTheme,
        ColumnStyle("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", "#123456"),
    )

    @Test
    fun headerAccentDoesNotBecomeTheEditorBackground(): Promise<Unit> =
        checkSurface(poodleRocksTheme, ColumnStyle(null, "#0050ef"))
}
