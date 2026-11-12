package com.mybotai.bacboanalyzer.data

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RoundFileStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("rounds-test").toFile()

    private fun r(o: Outcome, t: Long) = Round(t, o, Source.MANUAL)

    @Test
    fun persistenceRoundTripAcrossStoreInstances() {
        val dir = tempDir()
        val s1 = RoundFileStore(dir)
        s1.add(r(Outcome.BLUE, 1))
        s1.add(r(Outcome.RED, 2))
        s1.add(r(Outcome.TIE, 3))

        // New instance simulates an app restart
        val s2 = RoundFileStore(dir)
        assertEquals(3, s2.count())
        assertEquals(listOf(Outcome.BLUE, Outcome.RED, Outcome.TIE), s2.getAll().map { it.outcome })
        assertEquals(listOf(1L, 2L, 3L), s2.getAll().map { it.timestamp })
    }

    @Test
    fun updateAndRemovePersist() {
        val dir = tempDir()
        val s1 = RoundFileStore(dir)
        s1.add(r(Outcome.BLUE, 1))
        s1.add(r(Outcome.RED, 2))
        s1.updateOutcome(0, Outcome.TIE)
        s1.removeAt(1)

        val s2 = RoundFileStore(dir)
        assertEquals(1, s2.count())
        assertEquals(Outcome.TIE, s2.getAll().first().outcome)
    }

    @Test
    fun clearRemovesEverything() {
        val dir = tempDir()
        val s = RoundFileStore(dir)
        s.add(r(Outcome.BLUE, 1))
        s.clear()
        assertEquals(0, s.count())
        assertEquals(0, RoundFileStore(dir).count())
    }

    @Test
    fun exportJsonContainsRoundFields() {
        val dir = tempDir()
        val s = RoundFileStore(dir)
        s.add(r(Outcome.RED, 42))
        val json = s.exportJson()
        assertTrue(json.contains("\"outcome\""))
        assertTrue(json.contains("RED"))
        assertTrue(json.contains("MANUAL"))
    }

    @Test
    fun corruptFileFallsBackToEmptyList() {
        val dir = tempDir()
        File(dir, "rounds.json").writeText("{{{not json")
        val s = RoundFileStore(dir)
        assertEquals(0, s.count())
        s.add(r(Outcome.BLUE, 1))
        assertEquals(1, RoundFileStore(dir).count())
    }

    @Test
    fun outOfRangeIndexesAreIgnored() {
        val dir = tempDir()
        val s = RoundFileStore(dir)
        s.add(r(Outcome.BLUE, 1))
        s.removeAt(5)
        s.updateOutcome(-1, Outcome.RED)
        assertEquals(1, s.count())
        assertEquals(Outcome.BLUE, s.getAll().first().outcome)
    }
}
