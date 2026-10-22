package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageTest {
    private fun content(line: String) = Json.parseToJsonElement(line).jsonObject
        .getValue("message").jsonObject.getValue("content").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `screenshot and pdf become inline blocks after the prompt`() {
        val shot = Attachment("screenshot.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val pdf = Attachment("spec.pdf", "application/pdf", byteArrayOf(4))
        val built = UserMessage.build("what is this?", shot, false, listOf(pdf))
        val blocks = content(built.line)
        assertEquals(listOf("text", "image", "document"), blocks.map { it.str("type") })
        assertTrue(blocks[0].str("text").startsWith("[Attached: a live screenshot of my phone screen.]"))
        assertTrue(blocks[0].str("text").endsWith("what is this?"))
        assertEquals("AQID", blocks[1].getValue("source").jsonObject.str("data"))
        assertEquals("application/pdf", blocks[2].getValue("source").jsonObject.str("media_type"))
        assertTrue(built.rejected.isEmpty())
    }

    @Test
    fun `the screenshot note carries the image size the tools use as coordinates`() {
        val shot = Attachment("screenshot.jpg", "image/jpeg", byteArrayOf(1))
        val text = content(UserMessage.build("", shot, false, emptyList(), screenshotSize = 720 to 1568).line)[0].str("text")
        assertTrue(text.startsWith("[Attached: a live screenshot of my phone screen, 720×1568 px.]"))
    }

    @Test
    fun `envelope is a stream-json user message`() {
        val root = Json.parseToJsonElement(UserMessage.build("hi", null, false, emptyList()).line).jsonObject
        assertEquals("user", root.str("type"))
        assertEquals("user", root.getValue("message").jsonObject.str("role"))
        assertEquals("hi", content(root.toString()).single().str("text"))
    }

    @Test
    fun `unsupported, empty and oversized files are named with a reason`() {
        val built = UserMessage.build(
            "x", null, false,
            listOf(
                Attachment("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", byteArrayOf(1)),
                Attachment("empty.png", "image/png", ByteArray(0)),
                Attachment("huge.png", "image/png", ByteArray(UserMessage.MAX_IMAGE_BYTES + 1)),
            ),
        )
        assertEquals(listOf("notes.docx", "empty.png", "huge.png"), built.rejected.map { it.name })
        assertEquals(1, content(built.line).size)
    }

    @Test
    fun `an unchanged screen points the model at the earlier screenshot`() {
        val text = UserMessage.promptText("", screenshot = false, unchanged = true, files = 0)
        assertTrue(text.startsWith("[My screen is UNCHANGED"))
        assertTrue(text.contains("hasn't changed"))
    }
}
