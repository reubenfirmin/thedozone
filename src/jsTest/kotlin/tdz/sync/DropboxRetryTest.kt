package tdz.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropboxRetryTest {

    @Test
    fun detectsRateLimitedWrites() {
        assertTrue(isWriteRateLimited("too_many_write_operations/.", null), "namespace write contention")
        assertTrue(isWriteRateLimited("too_many_requests/...", null), "rpc rate limit")
        assertTrue(isWriteRateLimited("", 429), "HTTP 429")
    }

    @Test
    fun leavesRealErrorsAlone() {
        // Conflicts and not-found must NOT be treated as retryable rate limits.
        assertFalse(isWriteRateLimited("path/conflict/file/.", 409))
        assertFalse(isWriteRateLimited("path/not_found/.", 409))
        assertFalse(isWriteRateLimited("path/malformed_path/.", 400))
    }

    @Test
    fun honorsRetryAfterWhenGiven() {
        assertEquals(3000L, writeRetryDelayMs(0, 3.0), "retry_after seconds win over backoff")
        assertEquals(500L, writeRetryDelayMs(99, 0.5))
    }

    @Test
    fun fallsBackToCappedExponentialBackoff() {
        assertEquals(250L, writeRetryDelayMs(0, null))
        assertEquals(500L, writeRetryDelayMs(1, null))
        assertEquals(1000L, writeRetryDelayMs(2, null))
        assertEquals(4000L, writeRetryDelayMs(10, null), "capped at 4s")
    }
}
