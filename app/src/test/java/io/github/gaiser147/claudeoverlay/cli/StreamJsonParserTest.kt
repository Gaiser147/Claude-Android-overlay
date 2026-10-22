package io.github.gaiser147.claudeoverlay.cli

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamJsonParserTest {

    /** A real turn from the CLI (Bash tool call, then a text reply), with ids anonymised. */
    private fun fixture(): List<ChatEvent> {
        val parser = StreamJsonParser()
        val lines = javaClass.getResourceAsStream("/stream-tool-turn.jsonl")!!.bufferedReader().readLines()
        return lines.flatMap(parser::parse)
    }

    @Test
    fun `real tool turn becomes init, tool chip lifecycle, streamed text and result`() {
        val events = fixture()
        assertEquals(
            listOf(
                ChatEvent.SessionStarted("00000000-0000-4000-8000-000000000001", "claude-haiku-5-5"),
                ChatEvent.ToolStarted("toolu_011NhEcT7WffSqja5ADgv15Q", "Bash"),
                ChatEvent.ToolCall("toolu_011NhEcT7WffSqja5ADgv15Q", "Bash", "Print hi via echo"),
                ChatEvent.ToolFinished("toolu_011NhEcT7WffSqja5ADgv15Q", isError = false),
                ChatEvent.TextDelta("done"),
            ),
            events.dropLast(1),
        )
        val result = events.last() as ChatEvent.TurnResult
        assertEquals(false, result.isError)
        assertEquals("success", result.subtype)
        assertEquals("done", result.text)
        assertTrue(result.contextTokens > 0)
    }

    @Test
    fun `streamed text is not repeated by the final assistant message`() {
        assertEquals(0, fixture().count { it is ChatEvent.AssistantText })
    }

    @Test
    fun `text that never streamed is passed on whole`() {
        val parser = StreamJsonParser()
        val events = parser.parse(
            """{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"text","text":"hello"}]}}"""
        )
        assertEquals(listOf(ChatEvent.AssistantText("hello")), events)
    }

    @Test
    fun `error results and model switches are reported`() {
        val parser = StreamJsonParser()
        val err = parser.parse(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Please run /login","session_id":"s"}"""
        ).single() as ChatEvent.TurnResult
        assertTrue(err.isError)
        assertEquals("Please run /login", err.text)
        assertEquals(
            listOf(ChatEvent.ModelChanged("claude-sonnet-5-5")),
            parser.parse(
                """{"type":"control_response","response":{"subtype":"success","request_id":"req_2","response":{"model":"claude-sonnet-5-5"}}}"""
            ),
        )
    }

    @Test
    fun `garbage and unknown lines are ignored`() {
        val parser = StreamJsonParser()
        assertEquals(emptyList<ChatEvent>(), parser.parse("not json"))
        assertEquals(emptyList<ChatEvent>(), parser.parse("""{"type":"rate_limit_event"}"""))
        assertEquals(emptyList<ChatEvent>(), parser.parse("[1,2]"))
    }

    @Test
    fun `tool summaries prefer the most telling field and stay one short line`() {
        val long = "x".repeat(200)
        assertEquals("/a/b.txt", StreamJsonParser.summarizeTool("Read", buildJsonObject { put("file_path", "/a/b.txt") }))
        assertEquals("ls -la", StreamJsonParser.summarizeTool("Bash", buildJsonObject { put("command", "ls\n  -la") }))
        assertEquals(80, StreamJsonParser.summarizeTool("Bash", buildJsonObject { put("command", long) }).length)
    }
}
