package tdz.sync

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceIdTest {
    @BeforeTest fun clear() { window.localStorage.clear() }
    @AfterTest  fun clean() { window.localStorage.clear() }

    @Test fun stableAcrossCalls() {
        val a = DeviceId.get()
        val b = DeviceId.get()
        assertEquals(a, b, "same id within a session")
        assertTrue(a.isNotEmpty())
    }

    @Test fun persistsInLocalStorage() {
        val a = DeviceId.get()
        assertEquals(a, window.localStorage.getItem("tdz.sync.deviceId"))
    }
}
