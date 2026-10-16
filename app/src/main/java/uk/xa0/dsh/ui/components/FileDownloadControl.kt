package uk.xa0.dsh.ui.components

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.xa0.dsh.model.DownloadResult
import uk.xa0.dsh.model.FileDownloader
import uk.xa0.dsh.model.downloadSaveOf
import uk.xa0.dsh.model.formatSize
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** Where a failed download says so in the log, for a reader who was not looking. */
private const val TAG = "DshFiles"

/** How long "Saved" stays up, matching the copy buttons' confirmation window. */
private const val SAVED_MS = 1600L

/**
 * The state one download is in; every one of these reads differently on screen.
 *
 * The failure's *sentence* is not held here: it travels to the caller through
 * `onMessage`, because the control's own label is one word and a one-word failure is
 * the silent defect this app keeps paying for. The message has to reach a line of its
 * own, and the place it belongs is beside the file.
 */
private sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val done: Long, val total: Long?) : DownloadState
    data object Saving : DownloadState
    data object Saved : DownloadState
    data object Failed : DownloadState
}

/**
 * Take one workspace file and put it on the phone.
 *
 * The reader picks the destination through the system's own save dialog
 * (`ACTION_CREATE_DOCUMENT`, via `CreateDocument`), which is the only mechanism here
 * that needs no storage permission, no `FileProvider` and no manifest entry — and the
 * only one where the reader chooses where the file goes rather than the app guessing.
 *
 * **The bytes are fetched before the dialog opens.** The order is deliberate: the
 * dialog creates the destination file the moment it is answered, so fetching afterwards
 * would mean a cancelled or failed read leaves a zero-byte or half-written file under a
 * name the reader chose. Fetching first means a failure never creates anything, and the
 * dialog only appears when there is something to put in it. The cost is that the whole
 * file is held in memory until then, which is why `model/readWholeFile` refuses anything
 * above its ceiling with the file's own size rather than fetching what it can.
 *
 * [onMessage] is how the reason reaches a line of its own: the caller draws the sentence
 * beside the file, and passes null when a retry starts or the reader moves to another
 * file. The control itself only ever says `Download`, a percentage, `Saved`, `Retry` or
 * a refusal — never nothing.
 *
 * It is drawn by both surfaces that name a file — the Files preview header and the
 * presented card — so the same file cannot behave differently depending on how the
 * reader arrived at it.
 */
@Composable
fun FileDownloadControl(
    path: String,
    downloader: FileDownloader,
    onMessage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(path) { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var job by remember(path) { mutableStateOf<Job?>(null) }
    // Filled only when a fetch has succeeded, and consumed by the save dialog.
    var fetched by remember(path) { mutableStateOf<ByteArray?>(null) }

    // A different file is a different download; the old sentence would be about a file
    // that is no longer on screen.
    LaunchedEffect(path) { onMessage(null) }

    // The dialog appears only once there is something to save, so a refused or cancelled
    // fetch never opens one.
    val target = if (fetched != null) downloadSaveOf(path) else null
    if (target != null) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(target.mimeType),
        ) { uri ->
            val data = fetched
            fetched = null
            if (uri == null) {
                // The reader dismissed the dialog. Nothing was written and nothing
                // failed, so this is Idle, not a failure.
                state = DownloadState.Idle
                onMessage(null)
            } else if (data == null) {
                state = DownloadState.Idle
            } else {
                state = DownloadState.Saving
                scope.launch {
                    val written = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                                ?: error("the destination could not be opened")
                        }
                    }
                    if (written.isSuccess) {
                        state = DownloadState.Saved
                        onMessage(null)
                    } else {
                        val message = "Could not save ${target.fileName}."
                        Log.w(TAG, "download: writing $path to the chosen document failed", written.exceptionOrNull())
                        state = DownloadState.Failed
                        onMessage(message)
                    }
                }
            }
        }
        LaunchedEffect(target) { launcher.launch(target.fileName) }
    }

    LaunchedEffect(state) {
        if (state == DownloadState.Saved) {
            delay(SAVED_MS)
            state = DownloadState.Idle
        }
    }

    val start: () -> Unit = {
        onMessage(null)
        state = DownloadState.Running(0L, null)
        job = scope.launch {
            try {
                val result = downloader.download(path) { done, total ->
                    state = DownloadState.Running(done, total)
                }
                when (result) {
                    is DownloadResult.Ready -> {
                        fetched = result.bytes
                        // The dialog is opened by the effect above, once this has
                        // recomposed; `Saving` is what the control draws meanwhile.
                        state = DownloadState.Saving
                    }

                    is DownloadResult.Refused -> {
                        Log.w(TAG, "download refused: $path: ${result.message}")
                        state = DownloadState.Failed
                        onMessage(result.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                // Cancel is a reader's decision, not a failure: the control goes back to
                // offering the download and says nothing.
                fetched = null
                state = DownloadState.Idle
                onMessage(null)
                throw cancelled
            }
        }
    }

    // Read into a local `val` before matching: `state` is a delegated property, and
    // Kotlin will not smart-cast one of those into a member access.
    val current = state
    val label = when (current) {
        DownloadState.Idle -> "Download"
        is DownloadState.Running -> progressLabel(current.done, current.total)
        DownloadState.Saving -> "Saving…"
        DownloadState.Saved -> "Saved"
        DownloadState.Failed -> "Retry"
    }
    val tint = when (current) {
        DownloadState.Idle, DownloadState.Failed -> colors.link
        DownloadState.Saved -> colors.success
        else -> colors.labelTertiary
    }
    // Cancel while a fetch is in flight; Retry after a refusal; nothing while the dialog
    // is up or the confirmation is showing.
    val actionable = current == DownloadState.Idle ||
        current is DownloadState.Running ||
        current == DownloadState.Failed

    Text(
        text = label,
        style = DshType.bodySmall,
        color = tint,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple(enabled = actionable) {
                if (state is DownloadState.Running) {
                    job?.cancel()
                    job = null
                    fetched = null
                    state = DownloadState.Idle
                    onMessage(null)
                } else if (actionable) {
                    start()
                }
            }
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
    )
}

/** What a fetch in flight says: a proportion when the host reported a total, else bytes. */
private fun progressLabel(done: Long, total: Long?): String =
    if (total != null && total > 0L) {
        "${(done * 100 / total).coerceIn(0L, 100L)}%"
    } else {
        formatSize(done)
    }
