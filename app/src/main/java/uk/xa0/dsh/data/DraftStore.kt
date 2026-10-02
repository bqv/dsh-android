package uk.xa0.dsh.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The composer's drafts, on disk so a restart does not eat them.
 *
 * The draft was `rememberSaveable` before this, which survives a rotation and a
 * process death Android chooses to restore — but nothing wrote it anywhere, so a cold
 * start or a swipe-away from Recents lost whatever was typed. This is that missing
 * half.
 *
 * **Plain preferences, not the encrypted store.** A draft is not a credential; the
 * file lives in the app's private storage exactly as the cookie jar does, and the
 * encrypted store re-encrypts on every write, which is the wrong shape for something
 * that changes with each keystroke.
 *
 * Keyed per session, because that is what a draft belongs to: what is half-written to
 * one session is not offerable to another. The pending new-session composer has a key
 * of its own for the same reason.
 */
class DraftStore(
    context: Context,
    private val preferences: SharedPreferences =
        context.getSharedPreferences("dsh_drafts", Context.MODE_PRIVATE),
) {

    /** The stored draft for [key], or empty when there is none. */
    fun load(key: String): String = preferences.getString(PREFIX + key, "").orEmpty()

    /**
     * Stores [key]'s draft. An empty draft is a *deletion* rather than an entry:
     * keeping empty strings would grow the file with keys that say nothing, and would
     * make "no draft" and "an empty draft" two different states for no gain.
     */
    fun save(key: String, text: String, now: Long = System.currentTimeMillis()) {
        if (text.isEmpty()) {
            clear(key)
            return
        }
        if (text.length > MAX_DRAFT_CHARS) {
            // Too big to be worth carrying: pasted logs and whole files land here, and
            // an unbounded entry per session would grow the file without limit.
            clear(key)
            return
        }
        preferences.edit()
            .putString(PREFIX + key, text)
            .putLong(PREFIX + key + STAMP, now)
            .apply()
        prune()
    }

    /**
     * Moves a draft to another key, when the thing it was written for changes identity.
     *
     * The new-session composer is the case: text typed on the hero belongs to the
     * session the send (or an attachment) is about to create, and without this the
     * reader's words would be filed under a key nothing reads again — a draft lost by
     * the very act of using it.
     *
     * The destination wins if it somehow has a draft of its own: a session's own text
     * is newer than the hero's by construction, and a rename must never overwrite it.
     */
    fun rename(from: String, to: String, now: Long = System.currentTimeMillis()) {
        if (from == to) return
        val text = load(from)
        if (text.isEmpty()) return
        if (load(to).isNotEmpty()) {
            clear(from)
            return
        }
        save(to, text, now)
        clear(from)
    }

    fun clear(key: String) {
        preferences.edit()
            .remove(PREFIX + key)
            .remove(PREFIX + key + STAMP)
            .apply()
    }

    /**
     * Keeps the newest [MAX_DRAFTS] drafts.
     *
     * A phone accumulates sessions — this one's host has 929 — and a draft per session
     * forever is a slow leak in a file the user never sees. Oldest first, by the stamp
     * written beside each draft.
     */
    private fun prune() {
        val stamps = preferences.all
            .filterKeys { it.startsWith(PREFIX) && !it.endsWith(STAMP) }
            .mapValues { (key, _) -> preferences.getLong(key + STAMP, 0L) }
        val evict = draftsToEvict(stamps, MAX_DRAFTS)
        if (evict.isEmpty()) return
        val editor = preferences.edit()
        for (key in evict) editor.remove(key).remove(key + STAMP)
        editor.apply()
    }

    private companion object {
        const val PREFIX = "draft."
        const val STAMP = ".at"

        /** Enough for a real prompt with a pasted stack trace, small enough to bound the file. */
        const val MAX_DRAFT_CHARS = 32_000

        /** A session's draft is only worth keeping while it is plausibly still wanted. */
        const val MAX_DRAFTS = 25
    }
}

/**
 * Which stored drafts to drop, oldest first, so the file cannot grow without bound.
 *
 * Pure, and on its own, because this is the part with a rule in it: a phone
 * accumulates sessions — the host this was written against has 929 — and a draft per
 * session kept forever is a slow leak in a file nobody looks at. `max` is a count of
 * drafts to *keep*.
 */
fun draftsToEvict(stamps: Map<String, Long>, max: Int): List<String> {
    if (max < 0 || stamps.size <= max) return emptyList()
    return stamps.entries
        .sortedWith(compareBy({ it.value }, { it.key }))
        .take(stamps.size - max)
        .map { it.key }
}
