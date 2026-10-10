package uk.xa0.dsh.widget

import java.io.File

/**
 * The one file a widget process reads.
 *
 * A plain file rather than `SharedPreferences` because the writer is a background
 * coroutine that must not hold a lock the main thread wants, and because the whole
 * document is replaced at once. Replacing it *atomically* is not a nicety: the
 * reader is a broadcast receiver that may run after a process death, so it can
 * genuinely observe the file mid-write, and a truncated JSON document reads as
 * "nothing has ever been established" — which would be drawn as the same thing a
 * reader sees on a fresh install. Write to a sibling and rename.
 *
 * Nothing here throws. A widget receiver must not crash on a corrupt byte, and a
 * snapshot it cannot read is exactly the state it already knows how to draw.
 */
class WidgetSnapshotStore(private val file: File) {

    fun read(): WidgetSnapshot? {
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        return WidgetSnapshot.decode(text)
    }

    fun write(snapshot: WidgetSnapshot) {
        runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) dir.mkdirs()
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(snapshot.encode())
            // rename(2) replaces the destination in one step, which is the whole point.
            if (!temp.renameTo(file)) {
                // Only reachable if the destination is on another filesystem or the
                // rename is refused; the delete-then-rename window is small and the
                // failure mode (a torn document) reads as "unknown" rather than as a
                // wrong number, so it is worth taking rather than throwing away the
                // reading entirely.
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
