package uk.xa0.dsh.model

/** The sentence a failed send's banner gains once the text is back in the box. */
const val DRAFT_RESTORED_NOTE = "Your message is back in the composer."

/**
 * A failed send's banner: the failure's own words, then what became of the draft.
 *
 * The failure text is built elsewhere and is not always a finished sentence — a host
 * refusal is "session/not-found: no such session", a gateway answer is "HTTP 504: …"
 * — so a full stop is added when there is not one, rather than running the two
 * statements together.
 *
 * Only the composer knows whether the text actually went back: it refuses to restore
 * over anything typed since the send, and into a box the reader emptied by undo. That
 * is why [restored] is handed in instead of assumed. When nothing was put back the
 * failure is left to speak for itself — "your message is back in the composer" over a
 * box the reader has to fill themselves is worse than saying nothing, because it is
 * the one sentence that stops them looking for their words.
 *
 * This cannot say whether the reader *notices* the restored text, which is the whole
 * point of the sentence; that is a device claim about the banner being visible beside
 * the box, and no JVM test measures either.
 */
fun failedSendBanner(error: String, restored: Boolean): String {
    if (!restored) return error
    val base = error.trimEnd()
    val finished = base.endsWith(".") || base.endsWith("!") || base.endsWith("?")
    return if (finished) "$base $DRAFT_RESTORED_NOTE" else "$base. $DRAFT_RESTORED_NOTE"
}
