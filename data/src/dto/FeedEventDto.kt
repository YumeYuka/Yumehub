package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FeedEventDto(
    val id: String,
    val type: String,
    val actor: UserDto? = null,
    val repo: FeedRepositoryDto? = null,
    val payload: FeedPayloadDto = FeedPayloadDto(),
    @SerialName("created_at") val createdAt: String? = null,
    val sourceType: String? = null,
    val repositoryDetails: RepositoryDto? = null,
    val viewerSelected: Boolean? = null,
    val contributorsCount: Int? = null,
    val previewImageUrl: String? = null,
    val subjectId: String? = null,
)

@Serializable data class FeedRepositoryDto(val name: String)

fun FeedEventDto.workItem(): FeedWorkDto? =
    payload.pullRequest
        ?: payload.issue
        ?: payload.discussion
        ?: payload.number?.takeIf { type.startsWith("PullRequest") }?.let { FeedWorkDto(number = it) }

fun FeedEventDto.isPullRequestWork(): Boolean =
    type.startsWith("PullRequest") || payload.pullRequest != null || payload.issue?.pullRequest != null

@Serializable
data class FeedPayloadDto(
    val action: String? = null,
    val target: UserDto? = null,
    @SerialName("ref_type") val refType: String? = null,
    val ref: String? = null,
    val number: Int? = null,
    val issue: FeedWorkDto? = null,
    @SerialName("pull_request") val pullRequest: FeedWorkDto? = null,
    val release: FeedReleaseDto? = null,
    val discussion: FeedWorkDto? = null,
)

@Serializable
data class FeedWorkDto(
    val number: Int? = null,
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val author: UserDto? = null,
    val merged: Boolean = false,
    val draft: Boolean = false,
    @SerialName("pull_request") val pullRequest: JsonObject? = null,
    @SerialName("merged_at") val mergedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class FeedReleaseDto(
    val name: String? = null,
    @SerialName("tag_name") val tagName: String? = null,
    val socialPreviewImageUrl: String? = null,
    val usesCustomOpenGraphImage: Boolean = false,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)
