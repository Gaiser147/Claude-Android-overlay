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
    /** Effort levels the model takes, lowest first (e.g. low … max); empty if none. */
    val effortLevels: List<String> = emptyList(),
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
                effortLevels = (o["supportedEffortLevels"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .takeIf { (o["supportsEffort"] as? JsonPrimitive)?.content != "false" }
                    .orEmpty(),
            )
        }

        fun toJson(models: List<ModelInfo>): String = buildJsonArray {
            models.forEach { m ->
                add(buildJsonObject {
                    put("value", m.value)
                    m.resolvedModel?.let { put("resolvedModel", it) }
                    put("displayName", m.displayName)
                    put("description", m.description)
                    if (m.effortLevels.isNotEmpty()) {
                        put("supportsEffort", true)
                        put("supportedEffortLevels", buildJsonArray { m.effortLevels.forEach { add(it) } })
                    }
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

        /** What the CLI offers when it hasn't said what a model takes (e.g. a custom model id). */
        val DEFAULT_EFFORT_LEVELS = listOf("low", "medium", "high")

        /**
         * The effort levels to offer for [model] (by alias or concrete id): what the CLI
         * reported, none for a model without effort, the basic three for an id it didn't list.
         */
        fun effortLevels(models: List<ModelInfo>, model: String): List<String> =
            (models.firstOrNull { it.value == model } ?: models.firstOrNull { it.resolvedModel == model })
                ?.effortLevels
                ?: DEFAULT_EFFORT_LEVELS

        private fun JsonObject.str(key: String) =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
