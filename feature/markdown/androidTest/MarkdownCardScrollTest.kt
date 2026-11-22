package moe.yumeyuka.yumehub.feature.markdown

import android.graphics.Rect
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import com.tencent.mmkv.MMKV
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import moe.yumeyuka.yumehub.data.di.dataModule
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import moe.yumeyuka.yumehub.ui.component.GithubReadingGroup
import moe.yumeyuka.yumehub.ui.navigation.GithubPage
import moe.yumeyuka.yumehub.ui.navigation.GithubPageHost
import moe.yumeyuka.yumehub.ui.navigation.LocalGithubPageHost
import moe.yumeyuka.yumehub.ui.theme.YumehubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinApplication

class MarkdownCardScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reducedTransparencyKeepsFooterImagePaintedAfterScrolling() {
        MMKV.initialize(compose.activity.applicationContext)
        val ready = CompletableFuture<Unit>()
        val imageUrl = "https://image.invalid/footer.svg"
        val html = "<div style='height:4000px'></div><img id='footer' width='240' height='220'>"
        compose.setContent {
            KoinApplication(
                application = {
                    androidContext(compose.activity)
                    modules(dataModule)
                },
            ) {
                YumehubTheme(isDark = false, reduceTransparency = true) {
                    LazyColumn(Modifier.size(240.dp).testTag("page")) {
                        item {
                            OfficialGithubMarkdownRenderer.Render(
                                GithubMarkdownContent(text = "", html = html, readme = true),
                                Modifier,
                                GithubMarkdownActions({}, onReady = { ready.complete(Unit) }),
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { ready.isDone }
        compose.runOnUiThread {
            val web =
                (compose.activity.window.decorView as ViewGroup)
                    .descendants
                    .filterIsInstance<MarkdownWebView>()
                    .single()
            val client = web.webViewClient
            web.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (request.url.toString() != imageUrl)
                            return client.shouldInterceptRequest(view, request)
                        val svg =
                            "<svg xmlns='http://www.w3.org/2000/svg' width='240' height='220'>" +
                                "<rect width='240' height='220' fill='#19a54b'/></svg>"
                        return WebResourceResponse("image/svg+xml", "UTF-8", svg.byteInputStream())
                    }
                }
            web.evaluateJavascript("document.getElementById('footer').src='$imageUrl'", null)
        }
        val page = compose.onNodeWithTag("page")
        repeat(2) {
            page.performScrollToIndex(1)
            compose.waitUntil(5_000) {
                val pixels = page.captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2] == Color(0xff19a54b)
            }
            page.performScrollToIndex(0)
        }
    }

    @Test
    fun longReadmeScrollsWithPageAndKeepsItsLastLineReachable() {
        MMKV.initialize(compose.activity.applicationContext)
        val list = LazyListState()
        val ready = CompletableFuture<Unit>()
        val html =
            "<pre style='height:240px'>${"long line ".repeat(100)}</pre>" +
                "<div style='height:160000px'></div><p>Last line</p>"
        compose.setContent {
            KoinApplication(
                application = {
                    androidContext(compose.activity)
                    modules(dataModule)
                },
            ) {
                YumehubTheme(isDark = false, liquidGlassEnabled = true) {
                    LazyColumn(Modifier.size(240.dp).testTag("page"), state = list) {
                        item {
                            GithubReadingGroup {
                                OfficialGithubMarkdownRenderer.Render(
                                    GithubMarkdownContent(text = "", html = html, readme = true),
                                    Modifier,
                                    GithubMarkdownActions({}, onReady = { ready.complete(Unit) }),
                                )
                            }
                        }
                        item { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { ready.isDone }
        val web = compose.runOnIdle {
            (compose.activity.window.decorView as ViewGroup)
                .descendants
                .filterIsInstance<MarkdownWebView>()
                .single()
        }
        val page = compose.onNodeWithTag("page")
        page.performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) {
            val scrolled = CompletableFuture<String>()
            compose.runOnUiThread {
                web.evaluateJavascript("document.querySelector('pre').scrollLeft > 0") {
                    scrolled.complete(it)
                }
            }
            scrolled.get(2, TimeUnit.SECONDS) == "true"
        }
        page.performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Parent list must consume the drag", list.firstVisibleItemScrollOffset > 0)
            assertEquals(0, web.scrollY)
        }
        page.performScrollToIndex(1)
        compose.runOnIdle {
            val visible = Rect()
            assertTrue(web.getLocalVisibleRect(visible))
            assertTrue(
                "Last line must lie past the former card cap",
                visible.bottom > MarkdownMaxHeightPx,
            )
            assertEquals(web.height, visible.bottom)
            assertEquals(0, web.scrollY)
        }
    }

    @Test
    fun returningToRetainedAndEvictedCommentsPaintsTheRightBody() {
        val context = compose.activity.applicationContext
        MMKV.initialize(context)
        val ready = AtomicIntegerArray(9)
        val host = GithubPageHost(0.dp, false, {}, {})
        compose.setContent {
            KoinApplication(
                application = {
                    androidContext(context)
                    modules(dataModule)
                },
            ) {
                YumehubTheme(isDark = false, liquidGlassEnabled = true) {
                    CompositionLocalProvider(LocalGithubPageHost provides host) {
                        GithubPage(title = "Issue", back = false) {
                            items(9, key = { it }) { index ->
                                val color = if (index == 0) "#19a54b" else "#6334ab"
                                OfficialGithubMarkdownRenderer.Render(
                                    GithubMarkdownContent(
                                        text = "Comment $index",
                                        html =
                                            "<div style='height:900px;background:$color'>Comment $index</div>",
                                    ),
                                    Modifier.testTag("comment-$index"),
                                    GithubMarkdownActions(
                                        {},
                                        onReady = { ready.incrementAndGet(index) },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        fun scroll(index: Int) =
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        fun waitFor(index: Int) = compose.waitUntil(10_000) { ready.get(index) > 0 }
        fun checkFirstCard() {
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithTag("comment-0").captureToImage().toPixelMap()
                pixels[pixels.width / 2, pixels.height / 2] == Color(0xff19a54b)
            }
        }

        waitFor(0)
        checkFirstCard()
        scroll(2)
        waitFor(2)
        scroll(0)
        checkFirstCard()
        assertEquals(1, ready.get(0))

        for (index in 3..8) {
            scroll(index)
            waitFor(index)
        }
        scroll(0)
        compose.waitUntil(10_000) { ready.get(0) == 2 }
        checkFirstCard()
    }
}
