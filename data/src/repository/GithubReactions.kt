package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.yumeyuka.yumehub.data.cache.CachedDataRepository

private const val RetainedReactionUpdates = 256

@Serializable
enum class GithubReactionContent(val emoji: String) {
    THUMBS_UP("👍"),
    THUMBS_DOWN("👎"),
    LAUGH("😄"),
    HOORAY("🎉"),
    CONFUSED("😕"),
    HEART("❤️"),
    ROCKET("🚀"),
    EYES("👀");

    companion object {
        val Standard = listOf(THUMBS_UP, LAUGH, HOORAY, HEART, ROCKET, EYES)
    }
}

@Serializable
data class GithubReactionGroup(
    val content: GithubReactionContent,
    val count: Int,
    val viewerReacted: Boolean,
)

data class GithubReactionUpdate(
    val source: List<GithubReactionGroup>,
    val reactions: List<GithubReactionGroup>,
    val pending: Boolean,
) {
    // 只覆盖仍显示提交前计数的缓存，不遮盖后续查询拿到的新计数。
    fun appliesTo(groups: List<GithubReactionGroup>): Boolean =
        pending || (groups == source && groups != reactions)
}

class GithubReactionUpdates {
    private val updates = MutableStateFlow<Map<String, GithubReactionUpdate>>(emptyMap())

    fun observe(subjectId: String) = updates.map { it[subjectId] }.distinctUntilChanged()

    fun current(subjectId: String, source: List<GithubReactionGroup>): GithubReactionUpdate? =
        updates.value[subjectId]?.takeIf { it.appliesTo(source) }

    fun set(subjectId: String, update: GithubReactionUpdate?) {
        updates.update { current ->
            val next = current - subjectId
            if (update == null) return@update next
            val updated = next + (subjectId to update)
            val oldestConfirmed =
                if (updated.size > RetainedReactionUpdates) {
                    updated.entries.firstOrNull { !it.value.pending }
                } else null
            if (oldestConfirmed == null) updated else updated - oldestConfirmed.key
        }
    }
}

internal fun CachedDataRepository.reactionUpdates(account: String): GithubReactionUpdates =
    sessionResource(account, "reaction-updates-v1") { GithubReactionUpdates() }

fun List<GithubReactionGroup>.withToggledReaction(
    content: GithubReactionContent,
): List<GithubReactionGroup> {
    val existing =
        firstOrNull { it.content == content }
            ?: return this + GithubReactionGroup(content, 1, viewerReacted = true)
    val count = existing.count + if (existing.viewerReacted) -1 else 1
    if (count == 0) return filterNot { it.content == content }
    return map {
        if (it.content == content) it.copy(count = count, viewerReacted = !it.viewerReacted) else it
    }
}

suspend fun convergeGithubReactions(
    initial: List<GithubReactionGroup>,
    desired: List<GithubReactionGroup>,
    onConfirmed: (List<GithubReactionGroup>) -> Unit,
    toggle: suspend (GithubReactionContent, Boolean) -> List<GithubReactionGroup>,
): List<GithubReactionGroup> {
    var current = initial
    for (content in GithubReactionContent.entries) {
        val currentReacted = current.any { it.content == content && it.viewerReacted }
        val desiredReacted = desired.any { it.content == content && it.viewerReacted }
        if (currentReacted != desiredReacted) {
            current = toggle(content, currentReacted)
            onConfirmed(current)
        }
    }
    return current
}

internal const val ReactionFragment =
    "reactionGroups { content viewerHasReacted reactors(first: 1) { totalCount } }"

internal fun JsonObject.reactionGroups(): List<GithubReactionGroup> =
    (get("reactionGroups") as? JsonArray ?: throw GithubPageException()).mapNotNull {
        val group = it as? JsonObject ?: throw GithubPageException()
        val count =
            group.graphObject("reactors").graphRequiredText("totalCount").toIntOrNull()?.takeIf {
                it >= 0
            } ?: throw GithubPageException()
        if (count == 0) return@mapNotNull null
        val name = group.graphRequiredText("content")
        val content =
            GithubReactionContent.entries.firstOrNull { it.name == name }
                ?: throw GithubPageException()
        GithubReactionGroup(
            content,
            count,
            group.graphRequiredText("viewerHasReacted").toBooleanStrict(),
        )
    }

internal suspend fun GithubGraphQlSession.toggleGithubReaction(
    subjectId: String,
    content: GithubReactionContent,
    reacted: Boolean,
): List<GithubReactionGroup> {
    val field = if (reacted) "removeReaction" else "addReaction"
    val operation = if (reacted) RemoveGithubReaction else AddGithubReaction
    val subject =
        execute(
                operation,
                buildJsonObject {
                    put("subject_id", subjectId)
                    put("content", content.name)
                },
                write = true,
            )
            .graphObject(field)
            .graphObject("subject")
    if (subject.graphRequiredText("id") != subjectId) {
        throw SerializationException("Reaction target did not match")
    }
    if (!subject.graphRequiredText("viewerCanReact").toBooleanStrict()) {
        throw SerializationException("Reaction permission was not confirmed")
    }
    val groups = subject.reactionGroups()
    if (groups.any { it.content == content && it.viewerReacted } == reacted) {
        throw SerializationException("Reaction state did not change")
    }
    ensureCurrent()
    return groups
}

private const val AddGithubReaction =
    """
    mutation AddReactionMutation(${'$'}subject_id: ID!, ${'$'}content: ReactionContent!) {
        addReaction(input: { subjectId: ${'$'}subject_id content: ${'$'}content }) {
            subject { id viewerCanReact $ReactionFragment }
        }
    }
"""

private const val RemoveGithubReaction =
    """
    mutation RemoveReactionMutation(${'$'}subject_id: ID!, ${'$'}content: ReactionContent!) {
        removeReaction(input: { subjectId: ${'$'}subject_id content: ${'$'}content }) {
            subject { id viewerCanReact $ReactionFragment }
        }
    }
"""
