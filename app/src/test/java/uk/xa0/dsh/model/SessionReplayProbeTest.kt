package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import java.io.File

/**
 * Replays a real session's events through the reducer, one at a time, reporting the
 * first event it cannot survive.
 *
 * A diagnostic, not an assertion: it exists because a session on the phone sat on
 * "Loading…" and the app's follow collector has no `try` around `applySnapshot`, so one
 * unparseable event cancels the stream and leaves the header unset — which is the
 * loading state, forever.
 *
 * Reads the decompressed session files staged in `app/build/replay` and does nothing when
 * there are none, so it is inert in a clean checkout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionReplayProbeTest {

    @Test
    fun `replay every event of each staged session`() {
        val dir = File("build/replay")
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            println("[replay] no staged sessions in ${dir.absolutePath}")
            return
        }
        for (file in files) {
            val reducer = TranscriptReducer()
            var n = 0
            var failed = 0
            var firstFailure: String? = null
            val started = System.currentTimeMillis()
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                n++
                try {
                    reducer.applyEvent(JSONObject(line))
                } catch (t: Throwable) {
                    failed++
                    if (firstFailure == null) {
                        firstFailure = "${t.javaClass.simpleName}: ${t.message} | " +
                            line.take(180)
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - started
            val entries = reducer.snapshot().size
            println(
                "[replay] ${file.name}: $n events, $entries entries, $failed failed, " +
                    "${elapsed}ms" + (firstFailure?.let { "\n[replay]   first failure: $it" } ?: ""),
            )
        }
    }
}
