package moe.yumeyuka.yumehub.feature.markdown

import android.net.Uri
import android.view.View
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ImageResult
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.yumeyucca.lucide.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.yumeyuka.markdown.compose.NativeHtmlIconRequest
import moe.yumeyuka.markdown.compose.NativeHtmlImageRequest
import moe.yumeyuka.markdown.compose.NativeHtmlMediaRequest
import moe.yumeyuka.markdown.compose.NativeHtmlSlots
import moe.yumeyuka.yumehub.locale.LocalAppStrings
import moe.yumeyuka.yumehub.ui.component.AppIcon
import moe.yumeyuka.yumehub.ui.component.LoadFailureToast

@Composable
internal fun rememberNativeMarkdownSlots(
    isCurrentDocument: () -> Boolean,
    isInteractive: () -> Boolean,
    mediaActive: Boolean,
    resourceRevision: Any? = null,
): NativeHtmlSlots {
    val currentDocument by rememberUpdatedState(isCurrentDocument)
    val interactive by rememberUpdatedState(isInteractive)
    val resourceNamespace = remember(resourceRevision) { UUID.randomUUID().toString() }
    return NativeHtmlSlots(
        image = { request, modifier ->
            NativeMarkdownImage(request, modifier, resourceRevision, resourceNamespace) {
                currentDocument()
            }
        },
        media = { request, modifier ->
            key(resourceRevision, request.url) {
                NativeMarkdownMedia(request, modifier, mediaActive) { currentDocument() }
            }
        },
        icon = { request, modifier -> NativeMarkdownIcon(request, modifier) },
        actionButton = { request, modifier ->
            GlasmioButton(
                modifier = modifier,
                enabled = request.enabled && currentDocument() && interactive(),
                onClick = { if (currentDocument() && interactive()) request.onClick() },
                sampleBackdrop = false,
            ) {
                BasicText(request.label, style = GlasmioTheme.textStyles.footnote)
            }
        },
    )
}

@Composable
private fun NativeMarkdownIcon(request: NativeHtmlIconRequest, modifier: Modifier) {
    val icon =
        when (request.name.removePrefix("octicon-")) {
            "info",
            "note" -> Lucide.Info
            "light-bulb",
            "lightbulb",
            "tip" -> Lucide.Lightbulb
            "report",
            "important" -> Lucide.MessageSquareWarning
            "alert",
            "warning" -> Lucide.TriangleAlert
            "stop",
            "caution" -> Lucide.OctagonAlert
            "chevron-down",
            "expanded" -> Lucide.ChevronDown
            "chevron-right",
            "collapsed" -> Lucide.ChevronRight
            "copy" -> Lucide.Copy
            "check" -> Lucide.Check
            "link",
            "link-external" -> Lucide.Link
            "issue-opened" -> Lucide.CircleDot
            "issue-closed" -> Lucide.CircleCheck
            "skip" -> Lucide.CircleSlash
            "git-pull-request" -> Lucide.GitPullRequest
            "git-pull-request-closed" -> Lucide.GitPullRequestClosed
            "git-pull-request-draft" -> Lucide.GitPullRequestDraft
            "git-merge" -> Lucide.GitMerge
            else -> Lucide.Info
        }
    AppIcon(
        imageVector = icon,
        contentDescription = request.contentDescription,
        size = request.size,
        tint = request.tint,
        modifier = modifier,
    )
}

@Composable
private fun NativeMarkdownImage(
    request: NativeHtmlImageRequest,
    modifier: Modifier,
    resourceRevision: Any?,
    resourceNamespace: String? = null,
    isCurrentDocument: () -> Boolean,
) {
    val context = LocalContext.current
    val imageNamespace =
        resourceNamespace ?: remember(resourceRevision) { UUID.randomUUID().toString() }
    val currentDocument by rememberUpdatedState(isCurrentDocument)
    val latestRequest by rememberUpdatedState(request)
    val strings = LocalAppStrings.current
    val primaryUrl = request.url.takeIf { nativeMarkdownHttpsUri(it) != null }
    val fallbackUrl = request.fallbackUrl?.takeIf { nativeMarkdownHttpsUri(it) != null }
    val contentScale =
        if (
            request.width?.let { it.isFinite() && it > 0f } == true &&
                request.height?.let { it.isFinite() && it > 0f } == true
        )
            ContentScale.FillBounds
        else ContentScale.Fit
    if (primaryUrl == null) {
        LaunchedEffect(request.url, resourceRevision) {
            if (currentDocument()) latestRequest.onError?.invoke(strings.Markdown.ImageLoadFailed)
        }
        BasicText(
            request.alt ?: strings.Markdown.ImageLoadFailed,
            modifier = modifier,
            style = GlasmioTheme.textStyles.footnote,
        )
        return
    }
    var currentUrl by
        remember(imageNamespace, primaryUrl, fallbackUrl) { mutableStateOf(primaryUrl) }
    key(imageNamespace, currentUrl) {
        var displaySize by remember { mutableStateOf(IntSize.Zero) }
        val state = rememberAsyncImageState()
        val imageRequest =
            remember(context, currentUrl, imageNamespace, displaySize) {
                nativeMarkdownImageRequest(context, currentUrl, imageNamespace, displaySize)
            }
        val result = state.result
        val failed = result is ImageResult.Error && currentUrl == (fallbackUrl ?: primaryUrl)
        LaunchedEffect(resourceRevision) {
            if (currentDocument() && state.result is ImageResult.Error) state.restart()
        }
        LaunchedEffect(result, resourceRevision) {
            if (!currentDocument()) return@LaunchedEffect
            when (result) {
                is ImageResult.Success -> {
                    latestRequest.onIntrinsicSize?.invoke(
                        result.imageInfo.width,
                        result.imageInfo.height,
                    )
                }
                is ImageResult.Error -> {
                    if (
                        currentUrl == primaryUrl && fallbackUrl != null && fallbackUrl != primaryUrl
                    ) {
                        currentUrl = fallbackUrl
                    } else {
                        latestRequest.onError?.invoke(strings.Markdown.ImageLoadFailed)
                    }
                }
                else -> Unit
            }
        }
        Box(
            modifier.onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) displaySize = size
            },
            propagateMinConstraints = true,
        ) {
            AsyncImage(
                request = imageRequest,
                contentDescription = request.alt,
                state = state,
                modifier = Modifier.fillMaxWidth(),
                contentScale = contentScale,
                alignment = Alignment.Center,
                filterQuality = FilterQuality.High,
            )
            if (failed && currentDocument())
                LoadFailureToast(result, strings.Markdown.ImageLoadFailed)
        }
    }
}

/** 播放状态属于当前文档；隐藏、后台和账号失效均暂停，销毁时释放原生播放器。 */
@Composable
private fun NativeMarkdownMedia(
    request: NativeHtmlMediaRequest,
    modifier: Modifier,
    active: Boolean,
    isCurrentDocument: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    val currentDocument by rememberUpdatedState(isCurrentDocument)
    val latestRequest by rememberUpdatedState(request)
    val url = request.url.takeIf { nativeMarkdownHttpsUri(it) != null }
    if (url == null) {
        LaunchedEffect(request.url) {
            if (currentDocument()) latestRequest.onError?.invoke(strings.Markdown.MediaLoadFailed)
        }
        if (active && currentDocument())
            LoadFailureToast(request.url, strings.Markdown.MediaLoadFailed)
        return
    }
    NativeMarkdownMediaPlayer(request, Uri.parse(url), modifier, active, isCurrentDocument)
}

/** URI 由宿主先验证；分离播放器，允许仪器测试用本地资源验证同一条原生播放链路。 */
@Composable
internal fun NativeMarkdownMediaPlayer(
    request: NativeHtmlMediaRequest,
    source: Uri,
    modifier: Modifier,
    active: Boolean,
    isCurrentDocument: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    val currentDocument by rememberUpdatedState(isCurrentDocument)
    val latestRequest by rememberUpdatedState(request)
    val latestActive by rememberUpdatedState(active)
    var view by remember(request.url) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(request.url) { mutableStateOf(false) }
    var playing by remember(request.url) { mutableStateOf(false) }
    var hasPlayed by remember(request.url) { mutableStateOf(false) }
    var failed by remember(request.url) { mutableStateOf(false) }
    var duration by remember(request.url) { mutableIntStateOf(0) }
    var position by remember(request.url) { mutableIntStateOf(0) }
    var ratio by remember(request.url) { mutableFloatStateOf(16f / 9f) }
    LaunchedEffect(active, view) {
        if (!active || !currentDocument()) {
            view?.pause()
            playing = false
        }
    }
    LaunchedEffect(playing, active, view) {
        while (isActive && playing && active && currentDocument()) {
            view?.let { player ->
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
                playing = player.isPlaying
            }
            delay(250)
        }
    }
    Column(modifier) {
        Box(
            if (request.audio) Modifier.fillMaxWidth().height(1.dp).clearAndSetSemantics {}
            else Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = 600.dp),
        ) {
            key(source) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        VideoView(context).also { candidate ->
                            candidate.importantForAccessibility =
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            candidate.setOnPreparedListener { media ->
                                if (view === candidate && currentDocument()) {
                                    prepared = true
                                    failed = false
                                    media.isLooping = latestRequest.loop
                                    val volume = if (latestRequest.muted) 0f else 1f
                                    media.setVolume(volume, volume)
                                    duration = media.duration.coerceAtLeast(0)
                                    if (media.videoWidth > 0 && media.videoHeight > 0) {
                                        ratio = media.videoWidth.toFloat() / media.videoHeight
                                    }
                                    if (!latestActive) candidate.pause()
                                }
                            }
                            candidate.setOnCompletionListener {
                                if (view === candidate && currentDocument()) {
                                    playing = false
                                    position = duration
                                }
                            }
                            candidate.setOnErrorListener { _, _, _ ->
                                if (view === candidate && currentDocument()) {
                                    failed = true
                                    playing = false
                                    latestRequest.onError?.invoke(strings.Markdown.MediaLoadFailed)
                                }
                                true
                            }
                            view = candidate
                            candidate.setVideoURI(source)
                        }
                    },
                    update = { candidate -> if (!active || !currentDocument()) candidate.pause() },
                    onRelease = { candidate ->
                        if (view === candidate) view = null
                        candidate.stopPlayback()
                    },
                )
            }
            val posterUrl = request.posterUrl
            if (!request.audio && !hasPlayed && posterUrl != null) {
                NativeMarkdownImage(
                    request = NativeHtmlImageRequest(url = posterUrl),
                    modifier = Modifier.fillMaxSize(),
                    resourceRevision = request.url,
                    isCurrentDocument = isCurrentDocument,
                )
            }
        }
        if (failed) {
            if (active && currentDocument())
                LoadFailureToast(request.url, strings.Markdown.MediaLoadFailed)
        } else if (request.controls) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlasmioIconButton(
                    enabled = active && prepared && currentDocument(),
                    onClick = {
                        val player = view
                        if (player != null && active && prepared && currentDocument()) {
                            if (playing) player.pause()
                            else {
                                hasPlayed = true
                                player.start()
                            }
                            playing = player.isPlaying
                        }
                    },
                    sampleBackdrop = false,
                ) {
                    AppIcon(
                        imageVector = if (playing) Lucide.Pause else Lucide.Play,
                        contentDescription =
                            if (playing) strings.Markdown.PauseMedia
                            else strings.Markdown.PlayMedia,
                    )
                }
                GlasmioSlider(
                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = { target ->
                        if (active && prepared && currentDocument()) {
                            position = target.toInt().coerceIn(0, duration)
                            view?.seekTo(position)
                        }
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = active && prepared && duration > 0 && currentDocument(),
                    modifier =
                        Modifier.weight(1f).semantics {
                            contentDescription = strings.Markdown.MediaProgress
                        },
                )
                BasicText(
                    text = nativeMediaTime(position) + " / " + nativeMediaTime(duration),
                    modifier = Modifier.padding(start = 8.dp),
                    style = GlasmioTheme.textStyles.caption1,
                )
            }
        }
    }
}

@Composable
internal fun rememberNativeMarkdownResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by
        remember(owner) {
            mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

private fun nativeMediaTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
