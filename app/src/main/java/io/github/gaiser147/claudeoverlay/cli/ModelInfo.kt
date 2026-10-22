package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One entry of the CLI's own model menu, as its `initialize` control response reports it:
 * only models this login is entitled to, each with the concrete version it resolves to.
 */
data class ModelInfo(
    /** What to pass to `--model` / `set_model`, e.g. "opus". */
    val value: String,
    /** The concrete model it currently resolves to, e.g. "claude-opus-5-5". */
    val resolvedModel: String?,
    val displayName: String,
    /** e.g. "Opus 5.5 · Best for everyday, complex tasks". */
    val description: String,
) {
    /** "Opus 5.5": the versioned name leads the CLI's description. */
    val title: String
        get() = description.substringBefore(SEPARATOR, "").trim().ifEmpty { displayName }

    /** The rest of the description, e.g. "Best for everyday, complex tasks". */
    val subtitle: String
        get() = description.substringAfter(SEPARATOR, "").trim()

    companion object {
        private const val SEPARATOR = " · "

        fun parseList(models: JsonArray): List<ModelInfo> = models.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val value = o.str("value") ?: return@mapNotNull null
            ModelInfo(
                value = value,
                resolvedModel = o.str("resolvedModel"),
                displayName = o.str("displayName") ?: value,
                description = o.str("description") ?: "",
            )
        }

        fun toJson(models: List<ModelInfo>): String = buildJsonArray {
            models.forEach { m ->
                add(buildJsonObject {
                    put("value", m.value)
                    m.resolvedModel?.let { put("resolvedModel", it) }
                    put("displayName", m.displayName)
                    put("description", m.description)
                })
            }
        }.toString()

        /**
         * A short label for whatever model is in use: the versioned title when the id is in the
         * list (by alias or by concrete id), else the id itself (a custom pinned version).
         */
        fun label(models: List<ModelInfo>, model: String): String =
            models.firstOrNull { it.value == model }?.title
                ?: models.firstOrNull { it.resolvedModel == model }?.title
                ?: model

        private fun JsonObject.str(key: String) =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
