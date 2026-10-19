package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What a failed send's banner says about the draft.
 *
 * The report that started this was "the app should restore text on send fail (e.g.
 * 504)", and the restore is only half of it: a reader who is told the send failed,
 * and nothing else, assumes their words are gone and retypes them. So the banner has
 * to carry the news — but only when it is true, because a banner that claims the text
 * is back while the box is empty is worse than one that says nothing.
 *
 * What this cannot show is where the banner sits on the screen, or whether it is
 * visible beside the box while the keyboard is up: the JVM harness measures neither
 * text nor placement, and the composition of `ErrorBanner` in `ui/ChatScreen.kt` is
 * not exercised here.
 */
class SendFailureCopyTest {

    /** A gateway answer ends without a full stop; the note must not run into it. */
    @Test
    fun `an unfinished failure sentence gets a full stop before the note`() {
        assertEquals(
            "Send failed: HTTP 504: the host did not answer; its web server replied instead. " +
                DRAFT_RESTORED_NOTE,
            failedSendBanner(
                "Send failed: HTTP 504: the host did not answer; its web server replied instead",
                restored = true,
            ),
        )
    }

    /** A failure that is already a sentence keeps its own full stop. */
    @Test
    fun `a finished failure sentence is joined with one space`() {
        assertEquals(
            "Send failed: session/not-found: no such session. $DRAFT_RESTORED_NOTE",
            failedSendBanner("Send failed: session/not-found: no such session.", restored = true),
        )
    }

    /** Other terminal marks count as finished too, so no second stop is added. */
    @Test
    fun `question and exclamation marks are left alone`() {
        assertEquals("Failed? $DRAFT_RESTORED_NOTE", failedSendBanner("Failed?", restored = true))
        assertEquals("Failed! $DRAFT_RESTORED_NOTE", failedSendBanner("Failed!", restored = true))
    }

    /** Nothing was put back, so nothing is claimed. */
    @Test
    fun `a restore that did not happen leaves the failure alone`() {
        val failure = "Send failed: HTTP 504: the host did not answer; its web server replied instead"

        val banner = failedSendBanner(failure, restored = false)

        assertEquals(failure, banner)
        assertFalse(banner.contains(DRAFT_RESTORED_NOTE))
    }

    /** Trailing whitespace in the failure is not left between the two sentences. */
    @Test
    fun `trailing whitespace is trimmed before the note`() {
        assertEquals(
            "Send failed: refused. $DRAFT_RESTORED_NOTE",
            failedSendBanner("Send failed: refused.   \n", restored = true),
        )
    }
}
