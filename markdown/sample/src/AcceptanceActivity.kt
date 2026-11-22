package moe.yumeyuka.markdown.sample

import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.yumeyuka.markdown.compose.HtmlStyles
import moe.yumeyuka.markdown.compose.NativeHtmlActions
import moe.yumeyuka.markdown.compose.NativeHtmlDocument
import moe.yumeyuka.markdown.compose.NativeHtmlViewport
import moe.yumeyuka.markdown.core.GithubHtmlParser
import moe.yumeyuka.markdown.core.HtmlElement
import moe.yumeyuka.markdown.core.HtmlNode
import moe.yumeyuka.markdown.core.HtmlParseOptions
import org.json.JSONObject

/** 独立验收入口，不读取账号、不访问 GitHub，也不复用用户应用数据。 */
class AcceptanceActivity : ComponentActivity() {
    val probe = AcceptanceProbe()
    var currentConfig by mutableStateOf(AcceptanceConfig())
        private set

    var referenceView: ReferenceHtmlView? = null
        private set

    private var initializationFailure by mutableStateOf<String?>(null)
    private var statusWriter: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(AcceptanceConfig.fromIntent(intent))
        setContent { Box(Modifier.fillMaxSize().safeDrawingPadding()) { AcceptanceContent() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(AcceptanceConfig.fromIntent(intent))
    }

    fun show(config: AcceptanceConfig) {
        if (currentConfig == config && probe.identity == config.identity) return
        probe.reset(config)
        initializationFailure = null
        currentConfig = config
        statusWriter?.cancel()
        statusWriter = lifecycleScope.launch {
            val started = android.os.SystemClock.uptimeMillis()
            do {
                val metadata = probe.metadata(config).put("caseId", config.caseName)
                withContext(Dispatchers.IO) {
                    val output = File(filesDir, "acceptance").apply { mkdirs() }
                    val target = File(output, AcceptanceArtifacts.name(config) + "_status.json")
                    val temporary = File(output, target.name + ".tmp")
                    temporary.writeText(metadata.toString(2))
                    check(temporary.renameTo(target)) { "Cannot publish acceptance status" }
                }
                if (probe.failure != null || probe.isSettled()) break
                delay(200)
            } while (
                probe.identity == config.identity &&
                    android.os.SystemClock.uptimeMillis() - started < 120_000
            )
        }
    }

    @Composable
    private fun AcceptanceContent() {
        val config = currentConfig
        val context = LocalContext.current
        val baseDensity = LocalDensity.current
        val style by
            produceState<ConfiguredStyles?>(null, config.dark) {
                try {
                    probe.phase = "styles-loading"
                    value =
                        withContext(Dispatchers.IO) {
                            ConfiguredStyles(
                                config.dark,
                                AcceptanceStyles.load(context, config.dark),
                            )
                        }
                    probe.phase = "styles-ready"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val problem = "Fixture styles: ${error.javaClass.simpleName}: ${error.message}"
                    probe.fail(config.identity, problem)
                    initializationFailure = problem
                }
            }
        val fixture by
            produceState<AcceptanceFixture?>(null, config.caseName) {
                try {
                    value =
                        withContext(Dispatchers.IO) {
                            AcceptanceFixtures.load(context.assets, config.caseName)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val problem = "Fixture input: ${error.javaClass.simpleName}: ${error.message}"
                    probe.fail(config.identity, problem)
                    initializationFailure = problem
                }
            }
        if (initializationFailure != null) {
            BasicText(
                checkNotNull(initializationFailure),
                Modifier.fillMaxSize(),
                style = TextStyle(fontSize = 16.sp),
            )
            return
        }
        val currentStyle = style?.takeIf { it.dark == config.dark }?.styles
        if (currentStyle == null) {
            BasicText(
                "Preparing fixture",
                Modifier.fillMaxSize(),
                style = TextStyle(fontSize = 16.sp),
            )
            return
        }
        SideEffect {
            window.statusBarColor = currentStyle.palette.background.toArgb()
            window.navigationBarColor = currentStyle.palette.background.toArgb()
        }
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density, config.fontScale),
        ) {
            Box(Modifier.fillMaxSize().background(currentStyle.palette.background)) {
                Box(
                    Modifier.width(config.widthDp.dp)
                        .height(config.heightDp.dp)
                        .background(currentStyle.palette.background)
                        .onGloballyPositioned { coordinates ->
                            val b = coordinates.boundsInWindow()
                            val r = coordinates.boundsInRoot()
                            probe.contentBounds =
                                Rect(
                                    b.left.toInt(),
                                    b.top.toInt(),
                                    b.right.toInt(),
                                    b.bottom.toInt(),
                                )
                            probe.contentRootBounds = RectF(r.left, r.top, r.right, r.bottom)
                            val expectedWidth = config.widthDp * baseDensity.density
                            val expectedHeight = config.heightDp * baseDensity.density
                            if (
                                kotlin.math.abs(coordinates.size.width - expectedWidth) > 1.1f ||
                                    kotlin.math.abs(coordinates.size.height - expectedHeight) > 1.1f
                            ) {
                                probe.fail(
                                    config.identity,
                                    "Requested ${config.widthDp}x${config.heightDp}dp viewport is clipped by the safe drawing area",
                                )
                            }
                        },
                ) {
                    when {
                        fixture?.name == config.caseName ->
                            if (config.mode == RenderMode.Reference) {
                                ReferenceContent(checkNotNull(fixture), config, currentStyle)
                            } else {
                                NativeContent(checkNotNull(fixture), config, currentStyle)
                            }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReferenceContent(
        fixture: AcceptanceFixture,
        config: AcceptanceConfig,
        styles: HtmlStyles,
    ) {
        val context = LocalContext.current
        val view = remember(config.identity) { ReferenceHtmlView(context, fixture, config, probe) }
        DisposableEffect(view) {
            referenceView = view
            onDispose {
                if (referenceView === view) referenceView = null
                view.release()
            }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        LaunchedEffect(view, styles) { view.setBackgroundColor(styles.palette.background.toArgb()) }
    }

    @Composable
    private fun NativeContent(
        fixture: AcceptanceFixture,
        config: AcceptanceConfig,
        styles: HtmlStyles,
    ) {
        val document by
            produceState<ParsedDocument?>(null, config.identity) {
                try {
                    value =
                        withContext(Dispatchers.Default) {
                            ParsedDocument(
                                config.identity,
                                GithubHtmlParser.parse(
                                    fixture.html,
                                    HtmlParseOptions(
                                        pageUrl = fixture.pageUrl,
                                        repositoryRootUrl = fixture.repositoryRootUrl,
                                    ),
                                ),
                            )
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val problem = "Native parse: ${error.javaClass.simpleName}: ${error.message}"
                    probe.fail(config.identity, problem)
                    initializationFailure = problem
                }
            }
        val parsed = document?.takeIf { it.identity == config.identity }?.content
        if (parsed == null) {
            BasicText("Preparing document", style = styles.bodyStyle())
            return
        }
        val identity = config.identity
        val actions =
            NativeHtmlActions(
                onOpenLink = { href ->
                    probe.event(identity, "open_link", JSONObject().put("href", href))
                },
                onTaskChanged =
                    if (config.callbacks) {
                        { position, checked ->
                            probe.event(
                                identity,
                                "task_changed",
                                JSONObject()
                                    .put("taskPosition", position)
                                    .put("taskChecked", checked),
                            )
                        }
                    } else null,
                onCommitSuggestion =
                    if (config.callbacks) {
                        { id, html ->
                            probe.event(
                                identity,
                                "commit_suggestion",
                                JSONObject().put("suggestionId", id).put("previewHTML", html),
                            )
                        }
                    } else null,
                onReady = { probe.markReady(identity) },
                onError = { issue ->
                    probe.event(
                        identity,
                        "render_issue",
                        JSONObject()
                            .put("nodeKey", issue.nodeKey)
                            .put("code", issue.code)
                            .put("message", issue.message),
                    )
                    probe.fail(identity, "${issue.code}: ${issue.message}")
                },
            )
        val slots = AcceptanceMedia.slots(config, probe)
        if (config.preview) {
            NativeHtmlViewport(
                content = parsed,
                style = styles,
                modifier =
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        probe.markDrawn(identity)
                    },
                actions = actions,
                slots = slots,
                scrollToAnchor = config.scrollToAnchor,
                documentRevision = config.identity,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("acceptance:outer-list")) {
                item(key = identity) {
                    NativeHtmlDocument(
                        content = parsed,
                        style = styles,
                        modifier =
                            Modifier.width(config.widthDp.dp).drawWithContent {
                                drawContent()
                                probe.markDrawn(identity)
                            },
                        actions = actions,
                        slots = slots,
                        scrollToAnchor = config.scrollToAnchor,
                        documentRevision = config.identity,
                    )
                }
            }
        }
        LaunchedEffect(parsed, identity) {
            probe.documentText = parsed.plainText
            probe.nativeNodeKinds = buildMap {
                fun add(nodes: List<HtmlNode>) {
                    nodes.filterIsInstance<HtmlElement>().forEach { element ->
                        put(
                            element.attr("data-fixture-id") ?: element.attr("id") ?: element.key,
                            element.tag,
                        )
                        add(element.children)
                    }
                }
                add(parsed.children)
            }
            withFrameNanos {}
            withFrameNanos {}
            probe.markStable(identity)
        }
    }
}

private data class ParsedDocument(
    val identity: String,
    val content: moe.yumeyuka.markdown.core.HtmlDocument,
)

private data class ConfiguredStyles(val dark: Boolean, val styles: HtmlStyles)
