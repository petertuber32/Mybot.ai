package com.mybotai.bacboanalyzer.game

import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Source
import com.mybotai.bacboanalyzer.vision.DiceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MatchMemoryTest {

    private fun read(b1: Int, b2: Int, r1: Int, r2: Int, confidence: Double = 0.9) =
        DiceRead(b1 = b1, b2 = b2, r1 = r1, r2 = r2, blueDiceFound = 2, redDiceFound = 2, confidence = confidence)

    private fun tempDir(): File = Files.createTempDirectory("bacbo-mem").toFile()

    @Test
    fun commitsNewRoundWithDerivedOutcome() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        val res = mem.commit(read(3, 5, 2, 4), confirmedOutcome = null, now = 1_000L, motionSinceLastCommit = true)
        assertTrue(res is CommitResult.New)
        val round = (res as CommitResult.New).round
        assertEquals(1, round.roundNumber)
        assertEquals(8, round.blueSum)
        assertEquals(6, round.redSum)
        assertEquals(Outcome.BLUE, round.outcome)
        assertEquals(Source.VISION, round.source)
        assertEquals(1, mem.roundCount())
    }

    @Test
    fun sameSignatureWithoutMotionIsDuplicate() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        mem.commit(read(3, 5, 2, 4), null, 1_000L, motionSinceLastCommit = true)
        // Same image re-appears several times without a new animation:
        val res2 = mem.commit(read(3, 5, 2, 4), null, 5_000L, motionSinceLastCommit = false)
        assertEquals(CommitResult.Duplicate, res2)
        val res3 = mem.commit(read(3, 5, 2, 4), null, 60_000L, motionSinceLastCommit = false)
        assertEquals(CommitResult.Duplicate, res3)
        assertEquals(1, mem.roundCount())
    }

    @Test
    fun sameSignatureWithMotionInsideWindowIsDuplicate() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        mem.commit(read(4, 4, 1, 6), null, 1_000L, motionSinceLastCommit = true)
        // New animation but identical dice within the 150s window: treated as
        // a repeated display of the same round (defensive against flicker).
        val res = mem.commit(read(4, 4, 1, 6), null, 60_000L, motionSinceLastCommit = true)
        assertEquals(CommitResult.Duplicate, res)
        assertEquals(1, mem.roundCount())
    }

    @Test
    fun sameSignatureAfterWindowWithNewMotionIsGenuinelyNew() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        mem.commit(read(4, 4, 1, 6), null, 1_000L, motionSinceLastCommit = true)
        val res = mem.commit(read(4, 4, 1, 6), null, 1_000L + ObservationConfig.SAME_SIGNATURE_WINDOW_MS + 1, true)
        assertTrue(res is CommitResult.New)
        assertEquals(2, mem.roundCount())
        assertEquals(2, mem.rounds().last().roundNumber)
    }

    @Test
    fun differentSignatureAfterMotionIsNew() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        mem.commit(read(3, 5, 2, 4), null, 1_000L, true)
        val res = mem.commit(read(1, 2, 3, 4), null, 10_000L, true)
        assertTrue(res is CommitResult.New)
        assertEquals(2, mem.roundCount())
    }

    @Test
    fun confirmedOutcomeOverridesDerivedOutcome() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        // Dice read 8 vs 6 (BLUE) but the user confirms what the platform showed.
        val res = mem.commit(read(3, 5, 2, 4), confirmedOutcome = Outcome.TIE, now = 1_000L, motionSinceLastCommit = true)
        assertEquals(Outcome.TIE, (res as CommitResult.New).round.outcome)
    }

    @Test
    fun missingValuesAreAmbiguousNeverInvented() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        val broken = DiceRead(b1 = 3, b2 = null, r1 = 2, r2 = 4, blueDiceFound = 2, redDiceFound = 2, confidence = 0.4)
        val res = mem.commit(broken, null, 1_000L, true)
        assertTrue(res is CommitResult.Ambiguous)
        assertEquals(0, mem.roundCount())
    }

    @Test
    fun invalidDiceAreAmbiguous() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        val bad = DiceRead(b1 = 9, b2 = 1, r1 = 2, r2 = 3, blueDiceFound = 2, redDiceFound = 2, confidence = 0.9)
        assertTrue(mem.commit(bad, null, 1_000L, true) is CommitResult.Ambiguous)
    }

    @Test
    fun sessionRecoversFromDiskAndContinues() {
        val dir = tempDir()
        val mem1 = MatchMemory(SessionFileStore(dir))
        mem1.commit(read(3, 5, 2, 4), null, 1_000L, true)
        mem1.commit(read(2, 2, 6, 6), null, 30_000L, true) // 4 vs 12 RED

        // New process: fresh instance over the same directory.
        val mem2 = MatchMemory(SessionFileStore(dir))
        assertEquals(2, mem2.roundCount())
        assertEquals(2, mem2.rounds().last().roundNumber)
        assertEquals("2,2,6,6", mem2.lastSignature())
        // Duplicate guard survives the restart.
        assertEquals(
            CommitResult.Duplicate,
            mem2.commit(read(2, 2, 6, 6), null, 35_000L, motionSinceLastCommit = false)
        )
        // And a new round continues the numbering.
        val res = mem2.commit(read(1, 1, 1, 2), null, 200_000L, true)
        assertEquals(3, (res as CommitResult.New).round.roundNumber)
    }

    @Test
    fun toRoundsMapsToGlobalHistory() {
        val mem = MatchMemory(SessionFileStore(tempDir()))
        mem.commit(read(3, 5, 2, 4), null, 1_000L, true)
        val rounds = mem.toRounds()
        assertEquals(1, rounds.size)
        assertEquals(Source.VISION, rounds[0].source)
        assertEquals(Outcome.BLUE, rounds[0].outcome)
        assertTrue(rounds[0].note!!.contains("3+5"))
        assertTrue(rounds[0].note!!.contains("2+4"))
    }
}
