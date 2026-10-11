package com.appsventory.digiapps.nemo.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.appsventory.digiapps.nemo.FileTaskService
import com.appsventory.digiapps.nemo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Running copy/move (shown as a progress card and in the notification).
 * [total] is 0 while the sizes are still being counted ("Preparing…").
 */
class TaskProgress(
    val label: String,
    val done: Long,
    val total: Long,
    val current: String,
    val move: Boolean = false,
    /** Wall-clock start (ms), used for the "x min left" estimate. */
    val startedAt: Long = 0L,
    /** 1-based number of the top-level item being processed, and how many there are. */
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    /** Copy / move tasks still waiting behind this one. */
    val queued: Int = 0,
    /** Extract / compress (not a copy or move): picks the notification icon. */
    val archive: Boolean = false
)

/** Finished operation (shown as a snackbar-like card, optionally with Undo). */
class TaskNotice(val message: String, val undo: (() -> Unit)?)

/** Something waiting in the queue: a paste, or an archive job. */
private sealed interface TaskRequest

/** One "Paste here" waiting in line. */
private class PasteRequest(val board: FileClipboard, val destDir: String, val policy: ConflictPolicy) : TaskRequest

/**
 * Extract ([extract] = true: [sources] is the one archive, [destDir] the folder to put the files in) or
 * compress ([sources] are the items to pack, [destDir] where the archive goes, named [name] + the format's extension).
 */
private class ArchiveRequest(
    val extract: Boolean,
    val sources: List<String>,
    val destDir: String,
    val format: ArchiveFormat?,
    val name: String,
    val level: ArchiveLevel,
    /** Extract: the archive's password; compress: the password to protect it with. Wiped when the task ends. */
    val password: CharArray?,
    /** Extract only: move the archive to Trash once everything came out fine. */
    val trashAfter: Boolean,
    /**
     * Extract only: when non-null/non-empty, extract only these archive entry paths
     * (files and folders; folders are recursive). Null = full archive extract.
     */
    val selectedEntries: List<String>? = null
) : TaskRequest

/**
 * Runs copy / move in the background with progress, cancel and undo.
 * Lives at app-screen level so a running task survives navigating between screens.
 * Lists watch [version] and reload when it changes.
 */
class FileTasks private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: FileTasks? = null

        fun get(context: Context): FileTasks = instance ?: synchronized(this) {
            instance ?: FileTasks(context.applicationContext).also { instance = it }
        }
    }

    private val app = context.applicationContext
    // app-wide scope: a running task must not die with a screen
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var progress by mutableStateOf<TaskProgress?>(null)
        private set
    var notice by mutableStateOf<TaskNotice?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set

    /** Pastes waiting behind the running one: they start one after the other, in order. */
    var queued by mutableIntStateOf(0)
        private set

    private val queue = ArrayDeque<TaskRequest>()
    private var worker: Job? = null   // takes requests off the queue one at a time
    private var current: Job? = null  // the task running right now (what Cancel stops)
    private var undoJob: Job? = null

    /** Cap on files handed to MediaStore per operation, so a giant folder doesn't stall the end of the task. */
    private val MEDIA_LIMIT = 5000

    val isBusy: Boolean get() = worker?.isActive == true || undoJob?.isActive == true

    fun notifyChanged() {
        version++
    }

    fun dismissNotice() {
        notice = null
    }

    /** Stops the running task; the next one in the queue (if any) starts. */
    fun cancel() {
        current?.cancel()
    }

    /** Stops the running task and drops everything waiting behind it. */
    fun cancelAll() {
        queue.clear()
        queued = 0
        current?.cancel()
    }

    /** Adds a paste to the queue (starting it right away when nothing else is running). */
    fun paste(board: FileClipboard, destDir: String, policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH) {
        enqueue(PasteRequest(board, destDir, policy))
    }

    /**
     * Unpacks [archivePath] into [destDir]. Existing files are never overwritten (the new one becomes "name (1)").
     * Honors the "delete archive after extracting" setting (to Trash, so it can be brought back).
     */
    fun extract(archivePath: String, destDir: String, password: CharArray? = null) {
        enqueue(ArchiveRequest(true, listOf(archivePath), destDir, null, "", ArchiveLevel.NORMAL, password, AppSettings.deleteArchiveAfterExtract))
    }

    /**
     * Selective extract: only [entryPaths] (files and/or folders) from [archivePath] into [destDir].
     * Folder entries unpack recursively. Does not fall back to full-archive extraction.
     */
    fun extractSelected(
        archivePath: String,
        destDir: String,
        entryPaths: List<String>,
        password: CharArray? = null
    ) {
        enqueue(
            ArchiveRequest(
                true, listOf(archivePath), destDir, null, "", ArchiveLevel.NORMAL,
                password, trashAfter = false, selectedEntries = entryPaths
            )
        )
    }

    /** Packs [paths] into "[destDir]/[name].<format>" ([password] only for formats that can be encrypted). */
    fun compress(
        paths: List<String>,
        destDir: String,
        name: String,
        format: ArchiveFormat,
        level: ArchiveLevel = ArchiveLevel.NORMAL,
        password: CharArray? = null
    ) {
        enqueue(ArchiveRequest(false, paths, destDir, format, name, level, password, false))
    }

    private fun enqueue(request: TaskRequest) {
        notice = null
        Notifications.cancelTaskResult(app)
        queue.addLast(request)
        if (worker?.isActive == true) queued = queue.size
        try {
            FileTaskService.start(app)
        } catch (_: Exception) {
            // the system refused a foreground service (app not in the foreground): the copy still runs
        }
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                undoJob?.join()
                val request = queue.removeFirstOrNull() ?: break
                queued = queue.size
                // a child job, so Cancel stops this task only and the loop moves on to the next
                val task = launch {
                    when (request) {
                        is PasteRequest -> runPaste(request)
                        is ArchiveRequest -> runArchive(request)
                    }
                }
                current = task
                task.join()
            }
            current = null
            queued = 0
        }
    }

    private suspend fun runPaste(request: PasteRequest) {
        val board = request.board
        val destDir = request.destDir
        val move = board.move
        val label = app.getString(if (move) R.string.task_moving else R.string.task_copying)
        val icon = if (move) R.drawable.ic_notif_move else R.drawable.ic_notif_copy
        val startedAt = System.currentTimeMillis()
        // Show the card right away: counting the sizes of a big folder (or an SMB share) can take a while
        fun progressOf(done: Long, total: Long, name: String, index: Int, count: Int) =
            TaskProgress(label, done, total, name, move, startedAt, index, count, queued)
        progress = progressOf(0, 0, "", 0, 0)

        val undoable = mutableListOf<Pair<String, String>>() // source -> created target (replaced items excluded)
        var done = 0
        var failed = 0
        var skipped = 0
        val removedPaths = mutableListOf<String>() // move: local files that disappear from their old place
        val addedPaths = mutableListOf<String>()   // local files that appeared in the destination

        fun finish(message: String, undo: (() -> Unit)?) {
            notice = TaskNotice(message, undo)
            // one notification for the whole queue, when its last task ends
            if (queue.isEmpty()) Notifications.postTaskResult(app, message, icon)
        }

        fun failedText() = app.resources.getQuantityString(R.plurals.task_failed_count, failed, failed)

        fun doneText() = app.resources.getQuantityString(
            if (move) R.plurals.task_moved else R.plurals.task_copied, done, done
        )

        fun report() {
            val message = when {
                done > 0 && failed > 0 -> app.getString(R.string.task_result_with_failures, doneText(), failedText())
                done > 0 -> doneText()
                failed > 0 -> failedText()
                skipped > 0 -> app.getString(R.string.task_skipped)
                else -> app.getString(R.string.task_failed)
            }
            val back = undoable.toList()
            finish(message, if (back.isNotEmpty()) ({ undoPaste(move, back) }) else null)
        }

        try {
            val sources = withContext(Dispatchers.IO) { board.paths.filter { Vfs.exists(it) } }
            if (sources.isEmpty()) {
                finish(app.getString(R.string.task_failed), null)
                return
            }
            val sizes = withContext(Dispatchers.IO) { sources.map { FileOps.sizeOf(it) } }
            val total = sizes.sum().coerceAtLeast(1L)

            // Fail early instead of after minutes of copying (a move on one disk needs no space)
            if (!move && !Vfs.isSmb(destDir)) {
                val free = withContext(Dispatchers.IO) { File(destDir).usableSpace }
                if (free in 1 until total) {
                    finish(app.getString(R.string.task_no_space, formatBytes(total), formatBytes(free)), null)
                    return
                }
            }

            progress = progressOf(0, total, "", 0, sources.size)
            var base = 0L
            var lastUpdate = 0L
            withContext(Dispatchers.IO) {
                sources.forEachIndexed { i, src ->
                    ensureActive()
                    var inSource = 0L
                    val name = Vfs.name(src)
                    val onBytes: (Long) -> Unit = { n ->
                        inSource += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) { // throttle recompositions
                            lastUpdate = now
                            progress = progressOf(base + inSource, total, name, i + 1, sources.size)
                        }
                    }
                    progress = progressOf(base, total, name, i + 1, sources.size)
                    if (move && removedPaths.size < MEDIA_LIMIT) {
                        removedPaths += FileOps.localFiles(src, MEDIA_LIMIT - removedPaths.size)
                    }
                    val result = if (move) FileOps.moveTracked(src, destDir, request.policy, onBytes)
                    else FileOps.copyTracked(src, destDir, request.policy, onBytes)
                    when (result) {
                        is OpResult.Done -> {
                            done++
                            if (!result.replaced) undoable += src to result.target
                            if (addedPaths.size < MEDIA_LIMIT) {
                                addedPaths += FileOps.localFiles(result.target, MEDIA_LIMIT - addedPaths.size)
                            }
                        }
                        OpResult.Skipped -> skipped++
                        OpResult.Failed -> failed++
                    }
                    base += sizes[i]
                    progress = progressOf(base, total, name, i + 1, sources.size)
                }
            }
            report()
        } catch (e: CancellationException) {
            if (done == 0) {
                finish(app.getString(R.string.task_cancelled), null)
            } else {
                // what was already finished stays; offer to take it back
                val back = undoable.toList()
                finish(
                    app.getString(R.string.task_cancelled_partial, doneText()),
                    if (back.isNotEmpty()) ({ undoPaste(move, back) }) else null
                )
            }
            throw e
        } catch (e: Exception) {
            failed = maxOf(failed, 1)
            report()
        } finally {
            progress = null
            // keep Gallery / Music in sync with the disk (also after a cancel: some items may be done)
            FileOps.scanMedia(app, removedPaths + addedPaths)
            version++
        }
    }

    private suspend fun runArchive(request: ArchiveRequest) {
        val label = app.getString(if (request.extract) R.string.task_extracting else R.string.task_compressing)
        val startedAt = System.currentTimeMillis()
        fun progressOf(done: Long, total: Long, name: String) =
            TaskProgress(label, done, total, name, false, startedAt, 0, 0, queued, archive = true)
        progress = progressOf(0, 0, "")

        val created = mutableListOf<String>() // everything this task made, parents first: what Undo / Cancel removes
        var failed = 0
        var lastUpdate = 0L
        var trashedArchive: File? = null

        fun finish(message: String, undo: (() -> Unit)?) {
            notice = TaskNotice(message, undo)
            if (queue.isEmpty()) Notifications.postTaskResult(app, message, R.drawable.ic_notif_archive)
        }

        fun removeCreated() {
            // files first, then the folders that are empty again (a folder that still has other files stays)
            created.asReversed().forEach { path -> File(path).delete() }
        }

        try {
            val doneCount: Int
            var total = 0L
            if (request.extract) {
                val archive = File(request.sources.first())
                val format = archiveFormatOf(archive.name)
                if (format == null || !archive.isFile) {
                    finish(app.getString(R.string.task_failed), null)
                    return
                }
                total = withContext(Dispatchers.IO) { Archives.progressTotal(archive, format, request.password) }
                progress = progressOf(0, total, archive.name)
                var done = 0L
                val onBytes: (Long) -> Unit = { n ->
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 100) {
                        lastUpdate = now
                        progress = progressOf(done.coerceAtMost(total), total, archive.name)
                    }
                }
                // "Extract to <name>" is just "extract" into a folder named after the archive: [destDir] already says which
                // Selective: only the chosen entries when [selectedEntries] is set; never falls back to full extract.
                val result = withContext(Dispatchers.IO) {
                    val selected = request.selectedEntries
                    if (!selected.isNullOrEmpty()) {
                        Archives.extractSelected(
                            archive, format, File(request.destDir), selected,
                            created, request.password, onBytes
                        )
                    } else {
                        Archives.extractAll(archive, format, File(request.destDir), created, request.password, onBytes)
                    }
                }
                failed = result.failed
                doneCount = result.extracted
                if (request.trashAfter && failed == 0 && doneCount > 0) {
                    // everything came out: the archive goes to Trash (not deleted for good), Undo brings it back
                    trashedArchive = withContext(Dispatchers.IO) { FileOps.trash(archive) }
                    FileOps.scanMedia(app, listOf(archive.path))
                }
            } else {
                val format = request.format ?: return
                val items = request.sources.map { File(it) }.filter { it.exists() }
                if (items.isEmpty()) {
                    finish(app.getString(R.string.task_failed), null)
                    return
                }
                total = withContext(Dispatchers.IO) { Archives.sizeOfSources(items) }
                val cleanName = request.name.trim().replace('/', '_').ifEmpty { "archive" }
                val target = File(withContext(Dispatchers.IO) {
                    Vfs.uniqueChild(request.destDir, "$cleanName.${format.extension}")
                })
                progress = progressOf(0, total, target.name)
                var done = 0L
                val onBytes: (Long) -> Unit = { n ->
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 100) {
                        lastUpdate = now
                        progress = progressOf(done.coerceAtMost(total), total, target.name)
                    }
                }
                created += target.path // so a failure / cancel removes the half-written archive
                withContext(Dispatchers.IO) { Archives.create(items, target, format, request.level, request.password, onBytes) }
                doneCount = 1
                val message = app.getString(R.string.task_archive_created, target.name)
                finish(message) { undoCreated(created.toList(), null) }
                return
            }
            val message = if (doneCount == 0 && failed > 0) {
                app.getString(R.string.task_failed)
            } else {
                val ok = app.resources.getQuantityString(R.plurals.task_extracted, doneCount, doneCount)
                if (failed > 0) {
                    app.getString(
                        R.string.task_result_with_failures, ok,
                        app.resources.getQuantityString(R.plurals.task_failed_count, failed, failed)
                    )
                } else ok
            }
            val back = trashedArchive
            val text = if (back != null) app.getString(R.string.task_result_with_failures, message, app.getString(R.string.task_archive_trashed)) else message
            finish(text, if (created.isNotEmpty() || back != null) ({ undoCreated(created.toList(), back) }) else null)
        } catch (e: CancellationException) {
            removeCreated()
            finish(app.getString(R.string.task_cancelled), null)
            throw e
        } catch (e: ArchiveNotEncryptedException) {
            removeCreated()
            finish(app.getString(R.string.task_archive_not_encrypted), null)
        } catch (e: ArchivePasswordException) {
            if (request.extract) ArchivePasswordCache.forget(File(request.sources.first()))
            removeCreated()
            finish(app.getString(R.string.task_archive_wrong_password), null)
        } catch (e: Throwable) {
            // unreadable / damaged / unsupported archive (e.g. RAR5), disk full...
            removeCreated()
            finish(app.getString(R.string.task_archive_failed), null)
        } finally {
            request.password?.fill('\u0000') // don't keep the password around
            progress = null
            FileOps.scanMedia(app, created.filter { File(it).isFile }.take(MEDIA_LIMIT))
            version++
        }
    }

    /** Undo of an extract / compress: removes what it created (folders only when they are empty again). */
    private fun undoCreated(paths: List<String>, trashedArchive: File?) {
        notice = null
        Notifications.cancelTaskResult(app)
        undoJob = scope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                paths.asReversed().forEach { File(it).delete() }
                if (trashedArchive != null) FileOps.restore(trashedArchive) // the archive comes back too
                FileOps.scanMedia(app, paths.filter { !File(it).exists() }.take(MEDIA_LIMIT))
            }
            notice = TaskNotice(app.getString(R.string.task_undone), null)
            version++
        }
    }

    private fun undoPaste(move: Boolean, results: List<Pair<String, String>>) {
        notice = null
        Notifications.cancelTaskResult(app)
        undoJob = scope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                val touched = mutableListOf<String>()
                results.forEach { (src, target) ->
                    if (touched.size < MEDIA_LIMIT) touched += FileOps.localFiles(target, MEDIA_LIMIT - touched.size)
                    if (move) {
                        // put it back where it came from
                        Vfs.parent(src)?.let { FileOps.moveTracked(target, it) { } }
                        if (touched.size < MEDIA_LIMIT) touched += FileOps.localFiles(src, MEDIA_LIMIT - touched.size)
                    } else {
                        Vfs.deleteRecursively(target)
                    }
                }
                FileOps.scanMedia(app, touched)
            }
            notice = TaskNotice(app.getString(R.string.task_undone), null)
            version++
        }
    }

    /** Rename on a background thread (SMB needs the network). [onResult] runs on the main thread. */
    fun rename(path: String, newName: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val oldName = Vfs.name(path)
            val target = withContext(Dispatchers.IO) { Vfs.rename(path, newName) }
            if (target == null) {
                onResult(false)
                return@launch
            }
            notice = TaskNotice(
                app.getString(R.string.task_renamed),
                undo = {
                    notice = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Vfs.rename(target, oldName) }
                        notice = TaskNotice(app.getString(R.string.task_undone), null)
                        version++
                    }
                }
            )
            version++
            onResult(true)
        }
    }
}
