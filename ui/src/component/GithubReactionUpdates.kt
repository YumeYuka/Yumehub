package moe.yumeyuka.yumehub.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.yumeyuka.yumehub.data.repository.GithubReactionGroup
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdate
import moe.yumeyuka.yumehub.data.repository.GithubReactionUpdates

@Composable
fun GithubReactionUpdates.observeReaction(
    subjectKey: String,
    source: List<GithubReactionGroup>,
): GithubReactionUpdate? =
    key(this, subjectKey) {
        val flow = remember { observe(subjectKey) }
        val update by flow.collectAsStateWithLifecycle(initialValue = current(subjectKey, source))
        update?.takeIf { it.appliesTo(source) }
    }
