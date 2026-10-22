package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInfoTest {
    /** A real `initialize` control response from CLI 2.1.308, account details removed. */
    private fun fromFixture(): List<ModelInfo> {
        val line = javaClass.getResourceAsStream("/initialize-response.json")!!.bufferedReader().readText()
        val event = StreamJsonParser().parse(line).single() as ChatEvent.ModelsAvailable
        return event.models
    }

    @Test
    fun `initialize response becomes the versioned model menu`() {
        val models = fromFixture()
        assertEquals(listOf("default", "sonnet", "fable", "opus", "haiku"), models.map { it.value })
        val opus = models.single { it.value == "opus" }
        assertEquals("claude-opus-5-5", opus.resolvedModel)
        assertEquals("Opus 5.5", opus.title)
        assertEquals("Best for everyday, complex tasks", opus.subtitle)
    }

    @Test
    fun `labels prefer the versioned title, by alias or concrete id`() {
        val models = fromFixture()
        assertEquals("Opus 5.5", ModelInfo.label(models, "opus"))
        assertEquals("Haiku 5.5", ModelInfo.label(models, "claude-haiku-5-5"))
        assertEquals("claude-opus-4-8", ModelInfo.label(models, "claude-opus-4-8"))
    }

    @Test
    fun `descriptions without a version fall back to the display name`() {
        val m = ModelInfo("x", null, "Mystery", "no separator here")
        assertEquals("Mystery", m.title)
        assertEquals("", m.subtitle)
    }

    @Test
    fun `cached list round-trips`() {
        val models = fromFixture()
        val back = ModelInfo.parseList(Json.parseToJsonElement(ModelInfo.toJson(models)).jsonArray)
        assertEquals(models, back)
    }

    @Test
    fun `set_model replies still read as a model change`() {
        val events = StreamJsonParser().parse(
            """{"type":"control_response","response":{"subtype":"success","request_id":"req_2","response":{"model":"claude-sonnet-5-5"}}}"""
        )
        assertTrue(events.single() is ChatEvent.ModelChanged)
    }
}
