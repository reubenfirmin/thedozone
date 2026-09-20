package tdz.model.localstorage

import kotlinx.browser.window
import zoned.framework.Debugger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A debug storage dump must NEVER print the value of an auth secret (drive token / dropbox code
 * verifier) — only that it's present and its length. Regression guard for a leak where secret keys
 * fell through to the generic "$key $preview(value)" branch and printed the token verbatim.
 */
class StorageDumpRedactionTest {

    private val secret = "SUPER-SECRET-TOKEN-abc123-do-not-print"
    private var savedDebug = false

    @BeforeTest fun setup() {
        window.localStorage.clear()
        savedDebug = Debugger.debug
        Debugger.debug = true
    }

    @AfterTest fun teardown() {
        window.localStorage.clear()
        Debugger.debug = savedDebug
    }

    private fun capturedDump(): String {
        val captured = StringBuilder()
        val console = js("console")
        val original = console.log
        console.log = { msg: Any? -> captured.append(msg.toString()).append("\n") }
        try {
            StorageDump.dump()
        } finally {
            console.log = original
        }
        return captured.toString()
    }

    @Test
    fun driveTokenValueIsRedacted() {
        window.localStorage.setItem("tdz.sync.driveToken", secret)

        val output = capturedDump()

        assertTrue(output.contains("tdz.sync.driveToken"), "key itself is still listed")
        assertFalse(output.contains(secret), "the secret VALUE must not appear in the dump")
        assertTrue(output.contains("redacted"), "the secret is reported as redacted")
    }

    @Test
    fun dropboxCodeVerifierValueIsRedacted() {
        window.localStorage.setItem("tdz_dropbox_code_verifier", secret)

        val output = capturedDump()

        assertTrue(output.contains("tdz_dropbox_code_verifier"), "key itself is still listed")
        assertFalse(output.contains(secret), "the secret VALUE must not appear in the dump")
    }
}
