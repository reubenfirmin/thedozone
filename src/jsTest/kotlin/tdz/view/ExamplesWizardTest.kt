package tdz.view

import org.w3c.dom.HTMLElement
import kotlinx.browser.document
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExamplesWizardTest {

    @Test
    fun showsAndClosesViaTheCloseButton() {
        ExamplesWizard.show()
        try {
            assertNotNull(Zones.EXAMPLES_WIZARD.resolve(), "wizard scrim is mounted")
            val close = document.querySelector(".tdz-wiz-close") as? HTMLElement
            assertNotNull(close, "close button is rendered")
            close.click()
            assertNull(Zones.EXAMPLES_WIZARD.resolve(), "close button removes the wizard")
        } finally {
            ExamplesWizard.hide()
        }
    }
}
