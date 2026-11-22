package moe.yumeyuka.yumehub.data.repository

import moe.yumeyuka.yumehub.data.network.useResponse
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class WorkflowRunDocument(val summaryHtml: List<String>)

internal fun runWebUrl(owner: String, name: String, run: Long): HttpUrl {
    require(run > 0)
    return HttpUrl.Builder()
        .scheme("https")
        .host("github.com")
        .addPathSegment(owner)
        .addPathSegment(name)
        .addPathSegments("actions/runs/$run")
        .build()
}

internal suspend fun loadRunDocument(
    client: OkHttpClient,
    owner: String,
    name: String,
    run: Long,
): WorkflowRunDocument {
    val url = runWebUrl(owner, name, run)
    val request = Request.Builder().url(url).header("Accept", "text/html").build()
    return client.newCall(request).useResponse { response ->
        if (!response.isSuccessful || response.request.url != url) throw GithubPageException()
        val source = response.body.source()
        if (source.request(4L * 1024 * 1024 + 1)) throw GithubPageException()
        parseRunDocument(Jsoup.parse(source.inputStream(), null, url.toString()))
    }
}

internal fun parseRunDocument(doc: Document): WorkflowRunDocument {
    val root = doc.selectFirst("run-summary") ?: throw GithubPageException()
    return WorkflowRunDocument(root.select(".markdown-body").map { it.html() })
}
