package com.mybotai.bacboanalyzer.ocr

import com.mybotai.bacboanalyzer.models.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultParserTest {

    @Test
    fun recognizesBlueVariants() {
        assertEquals(Outcome.BLUE, ResultParser.classify("AZUL"))
        assertEquals(Outcome.BLUE, ResultParser.classify("Azul vence"))
        assertEquals(Outcome.BLUE, ResultParser.classify("Blue wins"))
        assertEquals(Outcome.BLUE, ResultParser.classify(" 12 · Azul "))
    }

    @Test
    fun recognizesRedVariants() {
        assertEquals(Outcome.RED, ResultParser.classify("VERMELHO"))
        assertEquals(Outcome.RED, ResultParser.classify("Vermelho vence"))
        assertEquals(Outcome.RED, ResultParser.classify("red"))
    }

    @Test
    fun recognizesTieVariants() {
        assertEquals(Outcome.TIE, ResultParser.classify("EMPATE"))
        assertEquals(Outcome.TIE, ResultParser.classify("Empate"))
        assertEquals(Outcome.TIE, ResultParser.classify("TIE"))
        assertEquals(Outcome.TIE, ResultParser.classify("Empatou"))
    }

    @Test
    fun tieHasPriorityOverOtherWords() {
        assertEquals(Outcome.TIE, ResultParser.classify("Empate azul"))
    }

    @Test
    fun ignoresJunkAndUnknownLines() {
        assertNull(ResultParser.classify(""))
        assertNull(ResultParser.classify("Bac Bo"))
        assertNull(ResultParser.classify("Round 12"))
        assertNull(ResultParser.classify("Historico"))
        assertNull(ResultParser.classify("123456"))
    }

    @Test
    fun parsesMultipleLinesInOrder() {
        val text = """
            Bac Bo
            AZUL
            Ronda 3
            vermelho
            EMPATE
            azul
        """.trimIndent()
        assertEquals(
            listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE, Outcome.BLUE),
            ResultParser.parse(text)
        )
    }

    @Test
    fun emptyInputYieldsEmptyList() {
        assertEquals(emptyList<Outcome>(), ResultParser.parse(""))
    }
}
