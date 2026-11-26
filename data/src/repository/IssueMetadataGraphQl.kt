package moe.yumeyuka.yumehub.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import moe.yumeyuka.yumehub.data.api.dto.IssueField
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldKind
import moe.yumeyuka.yumehub.data.api.dto.IssueFieldValue
import moe.yumeyuka.yumehub.data.api.dto.IssueFields
import moe.yumeyuka.yumehub.data.api.dto.IssuePropertyChoices
import moe.yumeyuka.yumehub.data.api.dto.WorkPropertyOption

internal class IssueMetadataGraphQl(
    private val owner: String,
    private val repository: String,
    private val number: Int,
    private val execute: suspend (String, JsonObject, Boolean) -> JsonObject,
) {
    private suspend fun target(fields: String): JsonObject =
        execute(
                """
                query IssueMetadataTarget($RepositoryVariables, ${'$'}number: Int!) {
                    $RepositoryScope { issue(number: ${'$'}number) { id $fields } }
                }
                """
                    .trimIndent(),
                variables { put("number", number) },
                false,
            )
            .graphObject("repository")
            .graphObject("issue")

    private fun variables(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("owner", owner)
        put("name", repository)
        extra()
    }

    suspend fun types(): IssuePropertyChoices {
        val target = target("viewerCanType issueType { id name description color }")
        val selected = (target["issueType"] as? JsonObject)?.choice()
        val editable = target.flag("viewerCanType")
        val options =
            if (editable) {
                repositoryNodes("issueTypes", "id name description color isEnabled")
                    .filter { it.flag("isEnabled") }
                    .map { it.choice() }
            } else emptyList()
        return IssuePropertyChoices(
            target.graphRequiredText("id"),
            listOfNotNull(selected),
            (listOfNotNull(selected) + options).distinctBy { it.id },
            editable,
        )
    }

    suspend fun saveType(issueId: String, original: String?, desired: String?) {
        val target = target("viewerCanType issueType { id }")
        checkTarget(target, issueId, "viewerCanType")
        val actual = (target["issueType"] as? JsonObject)?.graphRequiredText("id")
        if (actual == desired) return
        if (actual != original) throw WorkDetailReviewChangedException()
        val result =
            mutate(
                    "updateIssueIssueType",
                    "UpdateIssueIssueTypeInput",
                    "issue { id issueType { id } }",
                ) {
                    put("issueId", issueId)
                    put("issueTypeId", desired?.let(::JsonPrimitive) ?: JsonNull)
                }
                .graphObject("issue")
        if (
            result.graphRequiredText("id") != issueId ||
                (result["issueType"] as? JsonObject)?.graphRequiredText("id") != desired
        )
            throw GithubPageException()
    }

    suspend fun fields(): IssueFields = coroutineScope {
        val target = target("viewerCanSetFields issueType { pinnedFields { ... on Node { id } } }")
        val id = target.graphRequiredText("id")
        val definitions = async { repositoryNodes("issueFields", FieldDefinition) }
        val values = async { values(id) }
        val pinned = async {
            val type = target["issueType"] as? JsonObject
            if (type == null) repositoryNodes("pinnedIssueFields", "... on Node { id }")
            else (type["pinnedFields"] as? JsonArray).orEmpty().map { it.jsonObject }
        }
        val positions =
            pinned
                .await()
                .mapIndexed { index, field -> field.graphRequiredText("id") to index }
                .toMap()
        val current = values.await()
        IssueFields(
            id,
            definitions
                .await()
                .mapNotNull { field ->
                    val fieldId = field.graphRequiredText("id")
                    val kind =
                        when (field.graphRequiredText("dataType")) {
                            "DATE" -> IssueFieldKind.Date
                            "NUMBER" -> IssueFieldKind.Number
                            "TEXT" -> IssueFieldKind.Text
                            "SINGLE_SELECT" -> IssueFieldKind.SingleSelect
                            "MULTI_SELECT" -> IssueFieldKind.MultiSelect
                            else -> return@mapNotNull null
                        }
                    IssueField(
                        fieldId,
                        field.graphRequiredText("name"),
                        field.graphText("description"),
                        kind,
                        (field["options"] as? JsonArray).orEmpty().map { it.jsonObject.choice() },
                        current[fieldId],
                        fieldId in positions,
                    )
                }
                .distinctBy { it.id }
                .sortedBy { positions[it.id] ?: Int.MAX_VALUE },
            target.flag("viewerCanSetFields"),
        )
    }

    suspend fun saveField(issueId: String, field: IssueField, desired: IssueFieldValue?) {
        val target = target("viewerCanSetFields")
        checkTarget(target, issueId, "viewerCanSetFields")
        val actual = values(issueId)[field.id]
        if (actual == desired) return
        if (actual != field.value) throw WorkDetailReviewChangedException()
        if (desired == null) {
            val result =
                mutate(
                    "deleteIssueFieldValue",
                    "DeleteIssueFieldValueInput",
                    "success issue { id }",
                ) {
                    put("issueId", issueId)
                    put("fieldId", field.id)
                }
            if (
                !result.flag("success") ||
                    result.graphObject("issue").graphRequiredText("id") != issueId
            )
                throw GithubPageException()
        } else {
            val result =
                mutate(
                    "setIssueFieldValue",
                    "SetIssueFieldValueInput",
                    "issue { id } issueFieldValues { $FieldValue }",
                ) {
                    put("issueId", issueId)
                    putJsonArray("issueFields") {
                        addJsonObject {
                            put("fieldId", field.id)
                            when (desired) {
                                is IssueFieldValue.Date -> put("dateValue", desired.value)
                                is IssueFieldValue.Number -> put("numberValue", desired.value)
                                is IssueFieldValue.Text -> put("textValue", desired.value)
                                is IssueFieldValue.SingleSelect ->
                                    put("singleSelectOptionId", desired.id)
                                is IssueFieldValue.MultiSelect ->
                                    putJsonArray("multiSelectOptionIds") {
                                        desired.ids.forEach { add(it) }
                                    }
                            }
                        }
                    }
                }
            if (
                result.graphObject("issue").graphRequiredText("id") != issueId ||
                    (result["issueFieldValues"] as? JsonArray).orEmpty().none {
                        (it as? JsonObject)?.fieldId() == field.id
                    }
            )
                throw GithubPageException()
        }
    }

    suspend fun fieldValue(issueId: String, fieldId: String): IssueFieldValue? =
        values(issueId)[fieldId]

    private suspend fun values(id: String): Map<String, IssueFieldValue> = collectNodes { after ->
        val issue =
            execute(
                    """
                query IssueFieldValues(${'$'}id: ID!, ${'$'}after: String) {
                    node(id: ${'$'}id) { ... on Issue {
                        issueFieldValues(first: 100, after: ${'$'}after) {
                            $PageInfo nodes { $FieldValue }
                        }
                    } }
                }
                """
                        .trimIndent(),
                    buildJsonObject {
                        put("id", id)
                        put("after", after)
                    },
                    false,
                )
                .graphObject("node")
        issue["issueFieldValues"] as? JsonObject
    }
        .mapNotNull { node ->
            val id = node.fieldId() ?: return@mapNotNull null
            val value =
                when (node.graphRequiredText("__typename")) {
                    "IssueFieldDateValue" -> IssueFieldValue.Date(node.graphRequiredText("date"))
                    "IssueFieldNumberValue" ->
                        IssueFieldValue.Number(node.getValue("number").jsonPrimitive.double)
                    "IssueFieldTextValue" ->
                        IssueFieldValue.Text(node.graphText("text") ?: throw GithubPageException())
                    "IssueFieldSingleSelectValue" ->
                        IssueFieldValue.SingleSelect(node.graphRequiredText("optionId"))
                    "IssueFieldMultiSelectValue" ->
                        IssueFieldValue.MultiSelect(
                            node.getValue("options").jsonArray.mapTo(linkedSetOf()) {
                                it.jsonObject.graphRequiredText("id")
                            },
                        )
                    else -> return@mapNotNull null
                }
            id to value
        }
        .toMap()

    private suspend fun repositoryNodes(connection: String, selection: String): List<JsonObject> =
        collectNodes { after ->
            execute(
                    """
                query IssueMetadataOptions($RepositoryVariables, ${'$'}after: String) {
                    $RepositoryScope {
                        $connection(first: 100, after: ${'$'}after) { $PageInfo nodes { $selection } }
                    }
                }
                """
                        .trimIndent(),
                    variables { put("after", after) },
                    false,
                )
                .graphObject("repository")[connection]
                as? JsonObject
        }

    private suspend fun collectNodes(page: suspend (String?) -> JsonObject?): List<JsonObject> {
        val nodes = mutableListOf<JsonObject>()
        val cursors = hashSetOf<String>()
        var after: String? = null
        do {
            val connection =
                page(after)
                    ?: if (after == null) return emptyList() else throw GithubPageException()
            nodes.addAll(connection.graphNodes())
            after = connection.graphCursor(after)
            if (after != null && (!cursors.add(after) || cursors.size >= 100))
                throw GithubPageException()
        } while (after != null)
        return nodes
    }

    private suspend fun mutate(
        name: String,
        type: String,
        fields: String,
        input: JsonObjectBuilder.() -> Unit,
    ) =
        execute(
                "mutation SaveIssueMetadata(${'$'}input: $type!) { $name(input: ${'$'}input) { $fields } }",
                buildJsonObject { putJsonObject("input", input) },
                true,
            )
            .graphObject(name)

    private fun checkTarget(target: JsonObject, id: String, permission: String) {
        if (target.graphRequiredText("id") != id) throw WorkDetailReviewChangedException()
        if (!target.flag(permission)) throw GithubPageException()
    }

    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.fieldId() = (get("field") as? JsonObject)?.graphRequiredText("id")

    private fun JsonObject.choice() =
        WorkPropertyOption(
            graphRequiredText("id"),
            graphRequiredText("name"),
            subtitle = graphText("description"),
            color = graphText("color"),
        )

    private companion object {
        const val RepositoryVariables = "\$owner: String!, \$name: String!"
        const val RepositoryScope = "repository(owner: \$owner, name: \$name)"
        const val PageInfo = "pageInfo { hasNextPage endCursor }"
        const val FieldOptions = "options { id name description color }"
        const val FieldDefinition =
            """
            ... on Node { id }
            ... on IssueFieldCommon { name description dataType }
            ... on IssueFieldSingleSelect { $FieldOptions }
            ... on IssueFieldMultiSelect { $FieldOptions }
        """
        const val FieldValue =
            """
            __typename
            ... on IssueFieldValueCommon { field { ... on Node { id } } }
            ... on IssueFieldDateValue { date: value }
            ... on IssueFieldNumberValue { number: value }
            ... on IssueFieldTextValue { text: value }
            ... on IssueFieldSingleSelectValue { optionId }
            ... on IssueFieldMultiSelectValue { options { id } }
        """
    }
}
