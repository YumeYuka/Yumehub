package moe.yumeyuka.yumehub.feature.markdown

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownActions
import moe.yumeyuka.yumehub.ui.component.GithubMarkdownContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownWebViewLateBindTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun verticalSwipeFromInlineMarkdownScrollsTheParentList() {
        val html = "<p>Markdown card</p>"
        val content = GithubMarkdownContent(text = "Markdown card", html = html)
        val assets = MarkdownAssets.load(compose.activity.assets)
        val listState = LazyListState()
        val rendered = CompletableFuture<Unit>()
        compose.setContent {
            LazyColumn(Modifier.size(240.dp), state = listState) {
                item {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(200.dp).testTag("markdown-card"),
                        factory = { context ->
                            MarkdownWebView(context, content, html, assets, false, false, false)
                                .also { view ->
                                    view.initialize()
                                    view.updateCallbacks(
                                        { true },
                                        { GithubMarkdownActions({}) },
                                        {},
                                        { _, _ -> },
                                        {},
                                        onRendered = { rendered.complete(Unit) },
                                    )
                                    view.setHostResumed(true)
                                    view.updateContent(content, html)
                                }
                        },
                        onRelease = { it.release() },
                        update = {},
                    )
                }
                item { Spacer(Modifier.height(400.dp)) }
            }
        }

        compose.waitUntil(10_000) { rendered.isDone }
        compose.onNodeWithTag("markdown-card").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0,
            )
        }
    }

    @Test
    fun finishedTemplateDisplaysBodyWhenTheCardBindsLater() {
        val html = "<div style='height:160px;background:#19a54b'>Comment</div>"
        val content = GithubMarkdownContent(text = "Comment", html = html)
        val assets = MarkdownAssets.load(compose.activity.assets)
        lateinit var view: MarkdownWebView
        compose.setContent {
            AndroidView(
                modifier = Modifier.size(120.dp),
                factory = { context ->
                    MarkdownWebView(context, content, html, assets, false, false, false)
                        .also { view = it }
                        .also { it.initialize() }
                },
                onRelease = { it.release() },
                update = {},
            )
        }
        // 先让空模板完成初始化，再用与构造参数完全相同的正文绑定。
        compose.waitUntil(10_000) {
            val result = CompletableFuture<String>()
            compose.runOnUiThread {
                view.evaluateJavascript("!!window.androidMarkdown") { result.complete(it) }
            }
            result.get(2, TimeUnit.SECONDS) == "true"
        }
        compose.runOnUiThread {
            view.updateCallbacks({ true }, { GithubMarkdownActions({}) }, {}, { _, _ -> }, {})
            view.setHostResumed(true)
            view.updateContent(content, html)
        }
        // 检查窗口实际像素；DOM 有文字或收到 ready 都不能证明屏幕上已经画出正文。
        compose.waitUntil(10_000) {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val copied = CompletableFuture<Int>()
            compose.runOnUiThread {
                val pos = IntArray(2)
                view.getLocationInWindow(pos)
                val x = pos[0] + view.width / 2
                val y = pos[1] + view.height / 2
                PixelCopy.request(
                    compose.activity.window,
                    Rect(x, y, x + 1, y + 1),
                    bitmap,
                    { copied.complete(it) },
                    Handler(Looper.getMainLooper()),
                )
            }
            assertEquals(PixelCopy.SUCCESS, copied.get(2, TimeUnit.SECONDS))
            bitmap.getPixel(0, 0) == android.graphics.Color.rgb(25, 165, 75)
        }
    }
}
