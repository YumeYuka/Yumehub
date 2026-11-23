package moe.yumeyuka.markdown.sample

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.json.JSONObject

/** 仅在验收 APK 中使用原资源，生产原生库不依赖此 WebView。 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReferenceHtmlView(
    context: Context,
    private val fixture: AcceptanceFixture,
    private val config: AcceptanceConfig,
    private val probe: AcceptanceProbe,
) :
    WebView(
        ContextThemeWrapper(
            context,
            if (config.dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar,
        ),
    ) {
    private val template = if (config.dark) "template_dark.html" else "template.html"
    private val templateUrl = "$Origin$WebPath$template"
    private var disposed = false
    private var loaded = false
    private val identity = config.identity
    private val hostThemeIsLight =
        TypedValue().let { value ->
            if (getContext().theme.resolveAttribute(android.R.attr.isLightTheme, value, true))
                value.data != 0
            else null
        }
    private val diagramInput = fixture.engineCases.singleOrNull { it.kind == "mermaid" }
    private val needsMathReference = fixture.engineCases.any { it.kind == "math" }
    private val enginePageUrl =
        "https://viewscreen.githubusercontent.com/native-html-reference/index-${if (config.dark) "dark" else "light"}.html"
    private var referenceSvg: String? = null
    private var engineReferenceManifest: JSONObject? = null
    private var measurementSequence = 0L

    init {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isAlgorithmicDarkeningAllowed = false
            textZoom = (config.fontScale * 100).roundToInt()
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isForceDarkAllowed = false
        addJavascriptInterface(Bridge(), "native")
        webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse = resource(request)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    probe.event(identity, "navigation", JSONObject().put("href", request.url))
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url != templateUrl && url != enginePageUrl) {
                        stopLoading()
                        probe.fail(identity, "Unexpected reference navigation")
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (disposed) return
                    if (url == enginePageUrl) {
                        renderMermaidReference()
                        return
                    }
                    if (loaded || url != templateUrl) return
                    loaded = true
                    val adapter =
                        context.assets.open("${AssetPath}android-adapter.js").bufferedReader().use {
                            it.readText()
                        }
                    evaluateJavascript(adapter) { if (!disposed) bindFixture() }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        probe.fail(identity, "Reference load failed: ${error.errorCode}")
                    }
                }
            }
        if (diagramInput != null || needsMathReference) {
            try {
                engineReferenceManifest =
                    JSONObject(
                        context.assets.open("manifest.json").bufferedReader().use { it.readText() },
                    )
                if (diagramInput != null) {
                    require(
                        engineReferenceManifest?.optString("observedMermaidVersion") == "12.2.0",
                    )
                    loadUrl(enginePageUrl)
                } else loadUrl(templateUrl)
            } catch (error: Exception) {
                probe.fail(identity, "Pinned engine reference bundle unavailable: ${error.message}")
            }
        } else loadUrl(templateUrl)
    }

    private fun renderMermaidReference() {
        val source = checkNotNull(diagramInput).source
        evaluateJavascript(
            """
            (async function() {
              document.dispatchEvent(new CustomEvent('code_rendering_service:data:ready', {
                detail: {data:${JSONObject.quote(source)},width:${config.widthDp}}
              }));
              const started=performance.now();
              while (!document.querySelector('.mermaid-view .mermaid svg')) {
                if (performance.now()-started > 15000) throw new Error('Mermaid reference timeout');
                await new Promise(function(resolve) { setTimeout(resolve,20); });
              }
              await document.fonts.ready;
              await new Promise(function(resolve) { requestAnimationFrame(function() { requestAnimationFrame(resolve); }); });
              window.native.engineReferenceReady(document.querySelector('.mermaid-view .mermaid svg').outerHTML);
            })().catch(function(error) { window.native.baselineFailed(String(error)); });
            """
                .trimIndent(),
            null,
        )
    }

    private fun bindFixture() {
        val script =
            "window.androidMarkdown.setViewport(true);" +
                "window.androidMarkdown.setActive(true);" +
                "window.androidMarkdown.load(" +
                "${JSONObject.quote(identity)},${JSONObject.quote(fixture.html)}," +
                "${config.callbacks},${config.callbacks}," +
                "${JSONObject.quote(fixture.pageUrl)}," +
                "${JSONObject.quote(fixture.repositoryRootUrl)},false,false);" +
                captureObjectInputsScript() +
                referenceSvg
                    ?.let { svg ->
                        "document.querySelector('section[data-type=mermaid]').innerHTML=${JSONObject.quote(svg)};"
                    }
                    .orEmpty()
        evaluateJavascript(script) {
            if (disposed) return@evaluateJavascript
            config.scrollToAnchor?.let { anchor ->
                evaluateJavascript(
                    "window.androidMarkdown.anchor(${JSONObject.quote(anchor)});",
                    null,
                )
            }
            if (needsMathReference) renderMathReference() else waitForResourcesAndMeasure()
        }
    }

    private fun captureObjectInputsScript(): String =
        """
        window.__fixtureObjectInputs=Object.create(null);
        (function() {
          const root=document.getElementById('content');
          const tasks=Array.from(root.querySelectorAll('.task-list-item-checkbox,.tlb-checkbox,.tlb-issue-reference-number'));
          function attribute(element,names) {
            for (const name of names) { const value=element.getAttribute(name); if (value && value.trim()) return value; }
            return null;
          }
          function jsonSource(value,depth) {
            if (depth >= 64) throw new Error('Reference source JSON exceeds depth budget');
            if (typeof value === 'string') return value;
            if (Array.isArray(value)) { for (const item of value) {const found=jsonSource(item,depth+1);if(found!==null)return found;} }
            else if (value && typeof value === 'object') {
              for (const key of ['source','code','raw','text']) if (typeof value[key] === 'string') return value[key];
              for (const key of ['data','payload','content']) if (key in value) {const found=jsonSource(value[key],depth+1);if(found!==null)return found;}
            }
            return null;
          }
          for (const element of root.querySelectorAll('[data-fixture-object-id]')) {
            const id=element.getAttribute('data-fixture-object-id'),tag=element.tagName.toLowerCase();
            if (Object.prototype.hasOwnProperty.call(window.__fixtureObjectInputs,id)) throw new Error('Duplicate reference object '+id);
            let kind,semantics={};
            if (tag === 'math-renderer' || tag === 'math' || ['math','math-inline','math-display','js-inline-math','js-display-math'].some(c=>element.classList.contains(c))) {
              kind='math';
              const annotation=element.querySelector('annotation[encoding="application/x-tex"]');
              const math=element.querySelector('.math');
              let source=(attribute(element,['data-math','data-latex','data-source','data-code']) || (annotation && annotation.textContent) || (math && math.textContent) || element.textContent).trim();
              const display=['display','block'].includes(element.getAttribute('data-type')) || element.getAttribute('display')==='block' ||
                ['math-display','js-display-math','display-math'].some(c=>element.classList.contains(c)) || source.startsWith('$$') || source.startsWith('\\[');
              for (const pair of [['$$','$$'],['\\[','\\]'],['\\(','\\)'],['$','$']]) {
                if (source.length >= pair[0].length+pair[1].length && source.startsWith(pair[0]) && source.endsWith(pair[1])) {source=source.substring(pair[0].length,source.length-pair[1].length);break;}
              }
              semantics={source:source,display:display};
            } else if (element.getAttribute('data-type') === 'mermaid' || element.classList.contains('mermaid') || element.querySelector('[data-json]')) {
              kind='mermaid';
              const carrier=element.hasAttribute('data-json') ? element : element.querySelector('[data-json],[data-plain]') || element;
              const json=carrier.getAttribute('data-json');
              const plain=carrier.getAttribute('data-plain');
              const code=element.querySelector('pre code');
              semantics={source:(json && jsonSource(JSON.parse(json),0)) || attribute(carrier,['data-source','data-code']) ||
                (plain && plain!=='true' && plain!=='false' ? plain : null) || (code && code.textContent) || element.textContent};
            } else if (tag === 'img') {kind='image';semantics={alt:element.getAttribute('alt')};}
            else if (tag === 'svg') {
              kind='svg';const clone=element.cloneNode(true);
              for (const node of [clone,...clone.querySelectorAll('*')]) for (const attr of Array.from(node.attributes)) if (attr.name.startsWith('data-fixture-')) node.removeAttribute(attr.name);
              clone.setAttribute('xmlns','http://www.w3.org/2000/svg');clone.setAttribute('xmlns:xlink','http://www.w3.org/1999/xlink');
              semantics={source:new XMLSerializer().serializeToString(clone)};
            } else if (tag === 'video' || tag === 'audio') {
              kind=tag;const source=element.querySelector('source[src]');
              semantics={url:element.currentSrc || element.src || (source && source.src) || null,
                controls:element.hasAttribute('controls'),loop:element.hasAttribute('loop'),muted:element.hasAttribute('muted')};
            } else {kind='task';semantics={checked:!!element.checked,disabled:!!element.disabled,position:tasks.indexOf(element)};}
            window.__fixtureObjectInputs[id]={kind:kind,semantics:semantics};
          }
        })();
        """
            .trimIndent()

    private fun renderMathReference() {
        evaluateJavascript(
            """
            (async function() {
              await Promise.race([
                new Promise(function(resolve,reject) {
                  const module=document.createElement('script');
                  module.type='module';
                  module.src='https://appassets.androidplatform.net/native-html-reference/mathjax-reference/bridge.mjs';
                  module.onload=resolve;
                  module.onerror=function() { reject(new Error('Math reference module failed to load')); };
                  document.head.appendChild(module);
                }),
                new Promise(function(_,reject) { setTimeout(function() {
                  reject(new Error('Math reference module timeout'));
                },15000); })
              ]);
              const reference=await window.githubMathReferenceReady;
              if (reference.version !== '4.1.3') throw new Error('Unexpected Math reference version');
              window.__githubMathReferenceResults=reference.renderAll(document.getElementById('content'));
              window.native.mathReferenceReady();
            })().catch(function(error) { window.native.baselineFailed(String(error)); });
            """
                .trimIndent(),
            null,
        )
    }

    private fun waitForResourcesAndMeasure(): Long {
        val measurement = ++measurementSequence
        evaluateJavascript(
            """
            (async function() {
              await document.fonts.ready;
              await Promise.all(Array.from(document.images).map(function(image) {
                return image.decode ? image.decode().catch(function() {}) : Promise.resolve();
              }));
              await new Promise(function(resolve) {
                requestAnimationFrame(function() { requestAnimationFrame(resolve); });
              });
              ${ReferenceObjectScripts.measure(fixture, config.dark)}
              function measureTextLines(element, force) {
                if (!force && !/^(P|H[1-6]|PRE|TD|TH|SUMMARY|LI|DT|DD)$/.test(element.tagName)) return null;
                const initial = element.getBoundingClientRect();
                if (initial.bottom <= 0 || initial.top >= innerHeight || initial.height === 0) {
                  return {status:'outside-viewport'};
                }
                const originalHTML = element.innerHTML;
                const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                const texts = [];
                while (walker.nextNode()) {
                  const node = walker.currentNode;
                  if (node.parentElement.closest('[data-fixture-object-id]')) continue;
                  const owner = node.parentElement.closest('p,h1,h2,h3,h4,h5,h6,pre,td,th,summary,li,dt,dd');
                  if (owner === element || force && !owner) texts.push(node);
                }
                if (texts.reduce(function(n,t) { return n+t.length; },0) > 20000) {
                  return {status:'text-budget-exceeded'};
                }
                const lines = [];
                for (const text of texts) {
                  const flowKey=objectState.flowKeyFor(text);
                  for (let offset=0; offset<text.length; offset++) {
                    const range = document.createRange();
                    range.setStart(text,offset); range.setEnd(text,offset+1);
                    const r = range.getBoundingClientRect();
                    if (!(r.width > 0) || !(r.height > 0)) continue;
                    let target = null, overlap = 0;
                    for (const candidate of lines) {
                      if (candidate.flowKey!==flowKey) continue;
                      const value = Math.min(candidate.bottom,r.bottom)-Math.max(candidate.top,r.top);
                      if (value > overlap) { target=candidate; overlap=value; }
                    }
                    if (!target) {
                      target={top:r.top,bottom:r.bottom,left:r.left,right:r.right,text:'',node:text,offset:offset,flowKey:flowKey};
                      lines.push(target);
                    }
                    if (/^[ \t\r\n\f]$/.test(target.node.data.charAt(target.offset)) && !/^[ \t\r\n\f]$/.test(text.data.charAt(offset))) {
                      target.node=text; target.offset=offset;
                    }
                    target.text += text.data.charAt(offset);
                    target.top=Math.min(target.top,r.top); target.bottom=Math.max(target.bottom,r.bottom);
                    target.left=Math.min(target.left,r.left); target.right=Math.max(target.right,r.right);
                  }
                }
                lines.sort(function(a,b) { return a.top-b.top; });
                for (const line of lines) {
                  const node=line.node, offset=line.offset;
                  const segments=typeof Intl.Segmenter === 'function'
                    ? Array.from(new Intl.Segmenter(undefined,{granularity:'grapheme'}).segment(node.data)) : [];
                  const anchor=segments.find(function(s) { return s.index <= offset && offset < s.index+s.segment.length; });
                  if (!anchor || node.parentElement.closest('sup,sub')) {
                    line.baseline=null;
                    line.baselineStatus=anchor ? 'shifted-inline-anchor' : 'grapheme-anchor-unavailable';
                    delete line.node; delete line.offset;
                    continue;
                  }
                  const word=Array.from(new Intl.Segmenter(undefined,{granularity:'word'}).segment(node.data))
                    .find(function(s) { return s.index <= offset && offset < s.index+s.segment.length; });
                  const anchorStart=anchor.index;
                  const anchorEnd=word ? word.index+word.segment.length : anchor.index+anchor.segment.length;
                  const beforeRange=document.createRange();
                  beforeRange.setStart(node,anchorStart); beforeRange.setEnd(node,anchorEnd);
                  const before=beforeRange.getBoundingClientRect();
                  const anchorOnLine=Array.from(beforeRange.getClientRects()).every(function(r) {
                    return r.bottom > line.top+0.1 && r.top < line.bottom-0.1;
                  });
                  if (!anchorOnLine) {
                    line.baseline=null; line.baselineStatus='anchor-spans-visual-lines';
                    delete line.node; delete line.offset;
                    continue;
                  }
                  const tail=node.splitText(anchorEnd);
                  const marker=document.createElement('span');
                  marker.setAttribute('aria-hidden','true');
                  marker.style.cssText='display:inline-block!important;width:0!important;height:0!important;padding:0!important;margin:0!important;border:0!important;line-height:0!important;vertical-align:baseline!important;';
                  tail.parentNode.insertBefore(marker,tail);
                  const markerTop=marker.getBoundingClientRect().top;
                  const afterRange=document.createRange();
                  afterRange.setStart(node,anchorStart); afterRange.setEnd(node,anchorEnd);
                  const after=afterRange.getBoundingClientRect();
                  const stable=Math.abs(before.x-after.x)<0.1 && Math.abs(before.y-after.y)<0.1 &&
                    Math.abs(before.width-after.width)<0.1 && Math.abs(before.height-after.height)<0.1;
                  const sameLine=markerTop >= before.top-0.1 && markerTop <= before.bottom+0.1;
                  line.baseline=stable && sameLine ? markerTop : null;
                  line.baselineStatus=!stable ? 'probe-changed-glyph-layout' : sameLine ? 'measured' : 'marker-not-on-anchor-line';
                  marker.remove(); node.appendData(tail.data); tail.remove();
                  delete line.node; delete line.offset;
                }
                if (element.innerHTML !== originalHTML) throw new Error('Baseline probe changed fixture DOM');
                return {status:'measured', lines:lines};
              }
              const root = document.getElementById('content');
              const bodyStyle = getComputedStyle(root);
              const nodes = Array.from(root.querySelectorAll('*'));
              const geometry = nodes.map(function(element, index) {
                const rawTextFragments=[];
                const rawWalker=document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                while(rawWalker.nextNode())rawTextFragments.push(rawWalker.currentNode.data);
                const bounds = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return {
                  index: index, tag: element.tagName.toLowerCase(),
                  id: element.id, fixtureId: element.getAttribute('data-fixture-id'), classes: element.className.baseVal || element.className,
                  x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height,
                  rawText: typeof element.innerText === 'string' ? element.innerText : '',
                  rawTextFragments: rawTextFragments,
                  visibleText: objectState.visibleText(element),
                  fontFamily: style.fontFamily, fontSize: style.fontSize,
                  lineHeight: style.lineHeight, fontWeight: style.fontWeight,
                  color: style.color, backgroundColor: style.backgroundColor,
                  marginTop: style.marginTop, marginBottom: style.marginBottom,
                  padding: style.padding, display: style.display, visibility: style.visibility,
                  scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
                  textLayout: objectState.withObjects(element, measureTextLines(element)),
                  rects: Array.from(element.getClientRects()).map(function(r) {
                    return {x:r.x,y:r.y,width:r.width,height:r.height};
                  })
                };
              });
              objectState.finish();
              window.native.baselineReady(JSON.stringify({
                id: ${JSONObject.quote(identity)}, text: root.innerText,
                measurementId: $measurement,
                geometryNodeIds: ${org.json.JSONArray(fixture.geometryNodeIds)},
                objectNodeIds: ${org.json.JSONArray(fixture.objectNodeIds)},
                objects: objectState.objects, objectCoverage: objectState.coverage,
                viewportWidth: innerWidth, viewportHeight: innerHeight,
                devicePixelRatio: window.devicePixelRatio,
                requestedColorScheme: ${JSONObject.quote(if (config.dark) "dark" else "light")},
                webViewThemeIsLight: $hostThemeIsLight,
                prefersColorSchemeDark: matchMedia('(prefers-color-scheme: dark)').matches,
                prefersColorSchemeLight: matchMedia('(prefers-color-scheme: light)').matches,
                contentWidth: root.getBoundingClientRect().width,
                contentHeight: root.getBoundingClientRect().height,
                fontFamily: bodyStyle.fontFamily, fontSize: bodyStyle.fontSize,
                lineHeight: bodyStyle.lineHeight, color: bodyStyle.color,
                backgroundColor: getComputedStyle(document.body).backgroundColor,
                fontFaces: Array.from(document.fonts).map(function(face) { return {family:face.family,status:face.status,weight:face.weight}; }),
                unresolvedMath: root.querySelectorAll('math-renderer:not([data-reference-rendered])').length,
                unresolvedMermaid: Array.from(root.querySelectorAll('[data-type="mermaid"]')).filter(function(n) { return !n.querySelector('svg'); }).length,
                geometry: geometry,
                referenceEngine: ${if (referenceSvg != null) "{kind:'mermaid',version:'12.2.0',source:'GitHub deployed offline bundle'}" else "null"},
                mathReferenceEngine: window.githubMathReference ? {
                  kind:'math',version:window.githubMathReference.version,
                  source:window.githubMathReference.source,
                  sourceSha256:window.githubMathReference.sourceSha256,
                  results:window.__githubMathReferenceResults,
                  visualBaselineEligible:window.__githubMathReferenceResults.every(function(r) { return !r.error; })
                } : null,
                images: Array.from(document.images).map(function(image) {
                  return {src:image.src,currentSrc:image.currentSrc,srcset:image.srcset,sizes:image.sizes,
                    id:image.id,fixtureId:image.getAttribute('data-fixture-id'),
                    complete:image.complete,width:image.naturalWidth,height:image.naturalHeight};
                }),
                pictures: Array.from(root.querySelectorAll('picture')).map(function(picture) {
                  const image=picture.querySelector('img');
                  return {id:picture.id,fixtureId:picture.getAttribute('data-fixture-id'),
                    currentSrc:image ? image.currentSrc : null,imgSrc:image ? image.src : null,
                    sources:Array.from(picture.children).filter(function(node) { return node.tagName === 'SOURCE'; })
                      .map(function(source) { return {srcset:source.srcset,sizes:source.sizes,type:source.type,
                        media:source.media,matches:!source.media || matchMedia(source.media).matches}; })};
                })
              }));
            })().catch(function(error) {
              window.native.baselineFailed(String(error));
            });
            """
                .trimIndent(),
            null,
        )
        return measurement
    }

    /** 仪器测试在滚动、折叠后重新采样真实 DOM 几何。 */
    fun refreshMetadata(): Long {
        check(!disposed && loaded) { "Reference document is not active" }
        return waitForResourcesAndMeasure()
    }

    fun release() {
        if (disposed) return
        disposed = true
        stopLoading()
        removeJavascriptInterface("native")
        loadUrl("about:blank")
        destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun sendMessage(raw: String) {
            val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
            if (message.optString("id") != identity) return
            val kind = message.optString("messageName")
            post {
                if (!disposed) {
                    probe.event(identity, kind, message)
                    if (kind == "error") probe.fail(identity, message.optString("message"))
                }
            }
        }

        @JavascriptInterface
        fun baselineReady(raw: String) {
            val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return
            if (metadata.optString("id") != identity) return
            post {
                if (disposed) return@post
                if (
                    metadata.optBoolean("prefersColorSchemeDark") != config.dark ||
                        metadata.optBoolean("prefersColorSchemeLight") == config.dark
                ) {
                    probe.fail(
                        identity,
                        "Reference prefers-color-scheme does not match the requested theme",
                    )
                }
                if (
                    metadata.optInt("unresolvedMath") > 0 ||
                        metadata.optInt("unresolvedMermaid") > 0
                ) {
                    probe.fail(identity, "Reference contains unresolved math or diagram content")
                }
                val images = metadata.optJSONArray("images")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val image = images.getJSONObject(i)
                        if (
                            (image.optString("src").startsWith("https://fixture.invalid/media/") ||
                                image
                                    .optString("currentSrc")
                                    .startsWith("https://fixture.invalid/media/")) &&
                                image.optInt("width") == 0
                        ) {
                            probe.fail(
                                identity,
                                "Reference fixture image did not decode: ${image.optString("src")}",
                            )
                        }
                    }
                }
                probe.documentText = metadata.optString("text")
                probe.markReady(identity)
                postVisualStateCallback(
                    config.revision,
                    object : VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (!disposed) {
                                val previous =
                                    probe.referenceMetadata?.optLong("measurementId", -1) ?: -1
                                if (metadata.optLong("measurementId") >= previous) {
                                    probe.markStable(identity, metadata)
                                }
                                probe.markDrawn(identity)
                            }
                        }
                    },
                )
            }
        }

        @JavascriptInterface
        fun mathReferenceReady() {
            post { if (!disposed) waitForResourcesAndMeasure() }
        }

        @JavascriptInterface
        fun engineReferenceReady(svg: String) {
            post {
                if (disposed || referenceSvg != null) return@post
                if (!svg.contains("<svg") || svg.length > 16 * 1024 * 1024) {
                    probe.fail(identity, "Invalid Mermaid reference SVG")
                    return@post
                }
                referenceSvg = svg
                loadUrl(templateUrl)
            }
        }

        @JavascriptInterface
        fun baselineFailed(message: String) {
            post { if (!disposed) probe.fail(identity, message) }
        }
    }

    private fun resource(request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        if (request.method != "GET" || uri.scheme != "https" || uri.userInfo != null)
            return blocked()
        val path = uri.path ?: return blocked()
        if (path.split('/').any { it == ".." }) return blocked()
        val asset =
            when {
                uri.host == "viewscreen.githubusercontent.com" &&
                    path.startsWith("/native-html-reference/") ->
                    path.substringAfterLast('/').takeIf {
                        it == "index-light.html" || it == "index-dark.html"
                    } ?: return blocked()
                uri.host == "viewscreen.githubusercontent.com" &&
                    path.startsWith("/static/assets/") -> path.removePrefix("/")
                uri.host == "appassets.androidplatform.net" &&
                    path.startsWith("/native-html-reference/mathjax-reference/") ->
                    path.removePrefix("/native-html-reference/")
                uri.host == "appassets.androidplatform.net" && path.startsWith(WebPath) -> {
                    val name = path.removePrefix(WebPath)
                    if (name !in AssetNames) return blocked()
                    if (name.endsWith(".ttf")) "fonts/$name"
                    else "$AssetPath$name"
                }
                uri.host == "fixture.invalid" && path.startsWith("/media/") ->
                    path.removePrefix("/")
                else -> return blocked()
            }
        return try {
            val mime =
                when (asset.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "css" -> "text/css"
                    "js",
                    "mjs" -> "application/javascript"
                    "json" -> "application/json"
                    "ttf" -> "font/ttf"
                    "png" -> "image/png"
                    "svg" -> "image/svg+xml"
                    else -> return blocked()
                }
            val bytes = context.assets.open(asset).use { it.readBytes() }
            if (
                uri.host == "viewscreen.githubusercontent.com" ||
                    asset.startsWith("mathjax-reference/")
            ) {
                val manifest = engineReferenceManifest ?: return blocked()
                val entries = manifest.getJSONArray("files")
                val expected =
                    (0 until entries.length())
                        .map { entries.getJSONObject(it) }
                        .firstOrNull { it.getString("path") == asset } ?: return blocked()
                val digest =
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                if (digest != expected.getString("sha256")) {
                    post { probe.fail(identity, "Reference asset checksum mismatch: $asset") }
                    return blocked()
                }
            }
            WebResourceResponse(
                mime,
                if (mime.startsWith("font/")) null else "UTF-8",
                ByteArrayInputStream(bytes),
            )
        } catch (_: java.io.IOException) {
            blocked()
        }
    }

    private fun blocked() =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            410,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    companion object {
        private const val Origin = "https://appassets.androidplatform.net"
        private const val WebPath = "/assets/github-markdown/webview/"
        private const val AssetPath = "github-markdown/webview/"
        private val AssetNames =
            setOf(
                "template.html",
                "template_dark.html",
                "colors_light.css",
                "colors_dark.css",
                "markdown.css",
                "syntax.css",
                "syntax_dark.css",
                "font.css",
                "suggested_changes.css",
                "markdown.js",
                "android-adapter.js",
                "Ubuntu-Regular.ttf",
            )
    }
}
