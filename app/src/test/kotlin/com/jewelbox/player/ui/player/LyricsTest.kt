package com.jewelbox.player.ui.player

import com.jewelbox.player.data.net.LyricLineDto
import com.jewelbox.player.data.net.LyricsDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {

    private val lines = listOf(LyricLineDto(10.0, "One"), LyricLineDto(20.0, "Two"), LyricLineDto(30.0, ""))

    @Test
    fun `the line being sung, a little ahead`() {
        assertEquals(-1, currentLineIndex(lines, 0.0))
        assertEquals(0, currentLineIndex(lines, 9.8))
        assertEquals(1, currentLineIndex(lines, 25.0))
        assertEquals(2, currentLineIndex(lines, 300.0))
        assertEquals(-1, currentLineIndex(emptyList(), 5.0))
    }

    @Test
    fun `reads the server answer`() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString(
            LyricsDto.serializer(),
            """{"source":"lrclib","instrumental":false,"synced":true,"lines":[{"time":24.24,"text":"In the next world war"}],"plain":"In the next world war"}""",
        )
        assertEquals(24.24, dto.lines!!.single().time, 0.0)
        assertEquals("lrclib", dto.source)
        val none = json.decodeFromString(LyricsDto.serializer(), """{"source":null,"instrumental":false,"synced":false,"lines":null,"plain":null}""")
        assertEquals(null, none.lines)
    }
}
