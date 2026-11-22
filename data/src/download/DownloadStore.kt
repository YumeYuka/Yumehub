package moe.yumeyuka.yumehub.data.download

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.tencent.mmkv.MMKV
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.yumeyuka.yumehub.data.repository.WorkflowArtifact
import moe.yumeyuka.yumehub.data.repository.WorkflowArtifactExpired
import moe.yumeyuka.yumehub.data.repository.WorkflowRepository
import moe.yumeyuka.yumehub.data.storage.mmkv.TokenStore
import okhttp3.Call
import okhttp3.OkHttpClient

class DownloadStore(
    context: Context,
    private val storage: MMKV,
    private val tokens: TokenStore,
    private val workflows: WorkflowRepository,
    private val starter: DownloadStarter,
    client: OkHttpClient,
) {
    private val destination = DownloadDestination(context)
    private val transfer = DownloadTransfer(client, destination)
    private val queue = LinkedHashMap<String, Entry>()
    private val current = MutableStateFlow(restore())
    val tasks = current.asStateFlow()
    private val folder = MutableStateFlow(storage.decodeString("directory"))
    val directory = folder.asStateFlow()

    init {
        // 回收上个进程遗留的半成品，不触碰已发布的文件。
        val incomplete =
            current.value.filter {
                !it.active && it.status != DownloadStatus.COMPLETED && it.uri != null
            }
        if (incomplete.isNotEmpty())
            CoroutineScope(Dispatchers.IO).launch {
                incomplete.forEach { discard(it.id, Uri.parse(it.uri)) }
            }
    }

    private class Entry(
        val generation: Long,
        val tree: String?,
        val resolve: suspend () -> String,
        var call: Call? = null,
        var worker: Job? = null,
    )

    private fun restore(): List<DownloadTask> =
        interruptedDownloads(storage.decodeString("history"))

    @Synchronized
    fun setDirectory(value: String?) {
        if (value == null) storage.removeValueForKey("directory")
        else check(storage.encode("directory", value))
        folder.value = value
    }

    fun enqueueRelease(
        account: String,
        name: String,
        url: String,
        size: Long?,
        digest: String?,
        sourceArchive: Boolean,
    ) = enqueue(account, name, size, digest, sourceArchive) { url }

    fun enqueueArtifact(account: String, owner: String, repo: String, artifact: WorkflowArtifact) =
        enqueue(account, "${artifact.name}.zip", artifact.bytes, artifact.digest) {
            if (artifact.expired) throw WorkflowArtifactExpired()
            workflows.artifactDownload(account, owner, repo, artifact.id)
        }

    @Synchronized
    private fun enqueue(
        account: String,
        name: String,
        size: Long?,
        digest: String?,
        sourceArchive: Boolean = false,
        resolve: suspend () -> String,
    ) {
        val generation =
            synchronized(tokens.responseCacheSession.lock) {
                tokens.prepareResponseCacheSession()
                tokens.sessionGeneration.value.also { tokens.requireSession(account, it) }
            }
        val filename = safeDownloadName(name)
        val mime =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase())
        val task =
            DownloadTask(
                UUID.randomUUID().toString(),
                account,
                filename,
                mime ?: "application/octet-stream",
                digest,
                size,
                sourceArchive = sourceArchive,
            )
        queue[task.id] = Entry(generation, directory.value, resolve)
        current.value = listOf(task) + current.value
        persist()
        try {
            starter.start(task.id)
        } catch (e: RuntimeException) {
            queue.remove(task.id)
            finish(task.id, DownloadStatus.FAILED, DownloadError.INTERRUPTED)
        }
    }

    @Synchronized
    fun dismiss(id: String) {
        val task = current.value.find { it.id == id && it.active } ?: return
        cancel(task.account, id)
    }

    @Synchronized
    private fun cancel(account: String, id: String) {
        if (current.value.none { it.id == id && it.account == account && it.active }) return
        queue.remove(id)?.worker?.cancel()
        finish(id, DownloadStatus.CANCELLED)
    }

    @Synchronized
    fun setPaused(id: String, paused: Boolean) {
        val entry = queue[id] ?: return
        val task = current.value.find { it.id == id } ?: return
        val expected = if (paused) DownloadStatus.DOWNLOADING else DownloadStatus.PAUSED
        if (
            task.status != expected ||
                entry.generation != tokens.sessionGeneration.value ||
                !task.account.equals(tokens.accountLogin.value, true)
        )
            return
        update(id) {
            it.copy(
                status = if (paused) DownloadStatus.PAUSED else DownloadStatus.DOWNLOADING,
                speed = 0,
            )
        }
        if (paused) entry.call?.cancel()
    }

    @Synchronized
    fun interruptActive() {
        queue.forEach { (id, entry) ->
            entry.worker?.cancel()
            finish(id, DownloadStatus.FAILED, DownloadError.INTERRUPTED)
        }
        queue.clear()
    }

    @Synchronized
    fun cancelStaleSessions() {
        current.value
            .filter { it.active }
            .forEach { task ->
                val entry = queue[task.id] ?: return@forEach
                if (
                    entry.generation != tokens.sessionGeneration.value ||
                        !task.account.equals(tokens.accountLogin.value, ignoreCase = true)
                ) {
                    cancel(task.account, task.id)
                }
            }
    }

    suspend fun download(id: String): DownloadTask? =
        withContext(Dispatchers.IO) {
            val worker = currentCoroutineContext().job
            val (task, entry) =
                synchronized(this@DownloadStore) {
                    val entry = queue[id] ?: return@withContext null
                    val task =
                        current.value.find { it.id == id && it.status == DownloadStatus.QUEUED }
                            ?: return@withContext null
                    entry.worker = worker
                    update(id) { it.copy(status = DownloadStatus.DOWNLOADING) }
                    task to entry
                }
            runTask(task, entry)
        }

    private suspend fun runTask(task: DownloadTask, entry: Entry): DownloadTask? {
        val id = task.id
        var output: Uri? = null
        fun guard() {
            if (queue[id] !== entry) throw CancellationException("Download cancelled")
            tokens.requireSession(task.account, entry.generation)
        }
        try {
            val result =
                transfer.receive(
                    task,
                    entry.resolve,
                    awaitReady = {
                        tasks.first { rows ->
                            rows.none { it.id == id && it.status == DownloadStatus.PAUSED }
                        }
                        synchronized(this) { guard() }
                    },
                    onCall = { call ->
                        synchronized(this) {
                            guard()
                            entry.call = call
                            if (
                                current.value.any {
                                    it.id == id && it.status == DownloadStatus.PAUSED
                                }
                            )
                                call.cancel()
                        }
                    },
                    create = {
                        synchronized(this) { guard() }
                        destination.create(task, entry.tree).also {
                            output = it
                            synchronized(this) {
                                guard()
                                update(id, true) { t -> t.copy(uri = it.toString()) }
                            }
                        }
                    },
                    onProgress = { bytes, total, speed ->
                        synchronized(this) {
                            guard()
                            update(id) {
                                it.copy(
                                    bytes = bytes,
                                    total = total,
                                    speed = if (it.status == DownloadStatus.PAUSED) 0 else speed,
                                )
                            }
                        }
                    },
                    onVerify = {
                        synchronized(this) {
                            guard()
                            update(id) { it.copy(status = DownloadStatus.VERIFYING, speed = 0) }
                        }
                    },
                )
            synchronized(this) {
                guard()
                destination.publish(checkNotNull(output))
                val completed =
                    current.value
                        .first { it.id == id }
                        .copy(
                            status = DownloadStatus.COMPLETED,
                            sha256 = result,
                            speed = 0,
                            completedAtMillis = System.currentTimeMillis(),
                        )
                update(id, true) { completed }
                output = null
                return completed
            }
        } catch (e: CancellationException) {
            synchronized(this) { if (queue.containsKey(id)) finish(id, DownloadStatus.CANCELLED) }
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            val error =
                when (e) {
                    is DownloadFailure -> e.reason
                    is WorkflowArtifactExpired -> DownloadError.EXPIRED
                    else -> DownloadError.NETWORK
                }
            synchronized(this) {
                if (queue.containsKey(id)) finish(id, DownloadStatus.FAILED, error)
            }
        } finally {
            synchronized(this) { queue.remove(id) }
            output?.let { discard(id, it) }
        }
        return null
    }

    private fun discard(id: String, uri: Uri) {
        try {
            destination.discard(uri)
            synchronized(this) { update(id, true) { it.copy(uri = null) } }
        } catch (_: Exception) {
            // 文档提供方可能暂时不在线，保留 URI 以便下次重试。
        }
    }

    private fun finish(id: String, status: DownloadStatus, error: DownloadError? = null) =
        update(id, true) { it.copy(status = status, error = error, speed = 0) }

    private fun update(id: String, save: Boolean = false, change: (DownloadTask) -> DownloadTask) {
        current.value =
            current.value.mapNotNull {
                if (it.id != id) it
                else
                    change(it).takeIf { updated ->
                        updated.active ||
                            (updated.uri != null && updated.status != DownloadStatus.COMPLETED)
                    }
            }
        if (save) persist()
    }

    private fun persist() = check(storage.encode("history", Json.encodeToString(current.value)))
}
