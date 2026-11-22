package moe.yumeyuka.yumehub.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class GraphQlRequest(
    val query: String,
    val variables: JsonObject = JsonObject(emptyMap()),
    val operationName: String? = null,
)

@Serializable
data class GraphQlResponse(
    val data: JsonElement? = null,
    val errors: List<GraphQlError> = emptyList(),
)

@Serializable
data class GraphQlError(
    val message: String? = null,
    val type: String? = null,
    val extensions: JsonObject? = null,
)
