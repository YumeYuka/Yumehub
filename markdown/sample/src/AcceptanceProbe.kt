package moe.yumeyuka.markdown.sample

import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** 对测试公开实际布局和事件；状态不参与页面显示，也不代替行为断言。 */
class AcceptanceProbe {
    @Volatile var phase: String = "created"
    @Volatile
    var identity: String = ""
        private set

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var stable: Boolean = false
        private set

    @Volatile
    var failure: String? = null
        private set

    @Volatile var contentBounds: Rect = Rect()
    @Volatile var contentRootBounds: RectF = RectF()
    @Volatile var nativeMetadata: JSONObject? = null
    @Volatile
    var referenceMetadata: JSONObject? = null
        private set

    @Volatile var documentText: String = ""
    @Volatile var nativeNodeKinds: Map<String, String> = emptyMap()
    @Volatile
    var drawnIdentity: String? = null
        private set

    @Volatile
    var drawCount: Long = 0
        private set

    @Volatile
    var drawnUptimeMillis: Long = 0
        private set

    @Volatile
    var lastAsyncChange: Long = SystemClock.uptimeMillis()
        private set

    private val pending = AtomicInteger()
    private val events = CopyOnWriteArrayList<JSONObject>()
    private val imageEvidence = ConcurrentHashMap<String, JSONObject>()

    fun reset(config: AcceptanceConfig) {
        identity = config.identity
        phase = "configured"
        ready = false
        stable = false
        failure = null
        referenceMetadata = null
        nativeMetadata = null
        documentText = ""
        nativeNodeKinds = emptyMap()
        drawnIdentity = null
        drawCount = 0
        drawnUptimeMillis = 0
        pending.set(0)
        events.clear()
        imageEvidence.clear()
        lastAsyncChange = SystemClock.uptimeMillis()
    }

    fun markReady(source: String) {
        if (source == identity) ready = true
    }

    fun markStable(source: String, metadata: JSONObject? = null) {
        if (source != identity) return
        referenceMetadata = metadata ?: referenceMetadata
        stable = true
    }

    fun fail(source: String, problem: String) {
        if (source == identity && failure == null) failure = problem
    }

    fun markDrawn(source: String) {
        if (source != identity) return
        drawnIdentity = source
        drawCount++
        drawnUptimeMillis = SystemClock.uptimeMillis()
    }

    fun asyncStarted(source: String) {
        if (source != identity) return
        pending.incrementAndGet()
        lastAsyncChange = SystemClock.uptimeMillis()
    }

    fun asyncFinished(source: String) {
        if (source != identity) return
        pending.decrementAndGet()
        lastAsyncChange = SystemClock.uptimeMillis()
    }

    fun isSettled(): Boolean =
        ready &&
            stable &&
            drawnIdentity == identity &&
            drawCount > 0 &&
            failure == null &&
            pending.get() == 0 &&
            SystemClock.uptimeMillis() - lastAsyncChange > 100

    fun event(source: String, kind: String, payload: JSONObject = JSONObject()) {
        events +=
            JSONObject()
                .put("identity", source)
                .put("kind", kind)
                .put("accepted", source == identity)
                .put("payload", payload)
                .put("atUptimeMillis", SystemClock.uptimeMillis())
    }

    fun eventSnapshot(): List<JSONObject> = events.toList()

    fun recordImage(source: String, nodeKey: String, evidence: JSONObject) {
        if (source == identity && nodeKey.isNotBlank()) imageEvidence[nodeKey] = evidence
    }

    fun imageEvidenceSnapshot(): Map<String, JSONObject> = imageEvidence.toMap()

    fun metadata(config: AcceptanceConfig): JSONObject =
        JSONObject()
            .put("identity", identity)
            .put("phase", phase)
            .put("case", config.caseName)
            .put("mode", config.mode.name)
            .put("dark", config.dark)
            .put("widthDp", config.widthDp)
            .put("heightDp", config.heightDp)
            .put("fontScale", config.fontScale)
            .put("fontScaleSource", "native-density/reference-textZoom")
            .put("revision", config.revision)
            .put("ready", ready)
            .put("stable", stable)
            .put("settled", isSettled())
            .put("drawnIdentity", drawnIdentity ?: JSONObject.NULL)
            .put("drawCount", drawCount)
            .put("drawnUptimeMillis", drawnUptimeMillis)
            .put("pendingResources", pending.get())
            .put("failure", failure ?: JSONObject.NULL)
            .put("documentText", documentText)
            .put(
                "contentBoundsPx",
                JSONArray()
                    .put(contentBounds.left)
                    .put(contentBounds.top)
                    .put(contentBounds.right)
                    .put(contentBounds.bottom),
            )
            .put("reference", referenceMetadata ?: JSONObject.NULL)
            .put("native", nativeMetadata ?: JSONObject.NULL)
            .put("events", JSONArray(events.toList()))
}
