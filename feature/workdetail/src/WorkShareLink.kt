package moe.yumeyuka.yumehub.feature.workdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import moe.yumeyuka.yumehub.core.model.CollaborationKind

internal fun shareWorkLink(
    context: Context,
    owner: String,
    name: String,
    kind: CollaborationKind,
    number: Int,
    title: String,
    failure: String,
) {
    val section = if (kind == CollaborationKind.PullRequest) "pull" else "issues"
    val url = "https://github.com/$owner/$name/$section/$number"
    try {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                title,
            ),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
    }
}
