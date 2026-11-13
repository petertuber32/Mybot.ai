package com.mybotai.bacboanalyzer.game

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.models.Source
import com.mybotai.bacboanalyzer.vision.DiceRead
import java.io.File

/**
 * One fully observed and validated round (spec section 3 — memória da partida).
 */
data class ObservedRound(
    val roundNumber: Int,
    val b1: Int,
    val b2: Int,
    val r1: Int,
    val r2: Int,
    val blueSum: Int,
    val redSum: Int,
    val outcome: Outcome,
    val recognizedAt: Long,
    val confidence: Double,
    val source: Source
) {
    val signature: String get() = "$b1,$b2,$r1,$r2"
}

sealed class CommitResult {
    data class New(val round: ObservedRound) : CommitResult()
    object Duplicate : CommitResult()
    data class Ambiguous(val reason: String) : CommitResult()
}

/**
 * JSON-file backed session store (pure JVM, atomic writes). Keeps the
 * observed rounds, the round counter and the last signature so a session
 * survives process death and device restarts (recuperação de sessões).
 */
class SessionFileStore(
    private val dir: File,
    private val fileName: String = "observation_session.json"
) {

    data class SessionData(
        val roundNumber: Int = 0,
        val lastSignature: String? = null,
        val lastCommitAt: Long = 0L,
        val rounds: List<ObservedRound> = emptyList()
    )

    private val file = File(dir, fileName)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): SessionData = runCatching {
        if (!file.exists()) return SessionData()
        gson.fromJson(file.readText(), SessionData::class.java) ?: SessionData()
    }.getOrDefault(SessionData())

    fun save(data: SessionData) {
        dir.mkdirs()
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(gson.toJson(data))
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * Persistent match memory with duplicate-round protection.
 *
 * A read is treated as a DUPLICATE (not committed) when its dice signature
 * equals the last committed signature AND either (a) no dice animation was
 * observed since the last commit (the same image re-appeared), or (b) the
 * same signature reappears within [ObservationConfig.SAME_SIGNATURE_WINDOW_MS].
 * Two genuinely identical consecutive results after a new animation and
 * outside the window are still committed — honestly, because that is what
 * the table showed.
 */
class MatchMemory(private val store: SessionFileStore) {

    private var data: SessionFileStore.SessionData = store.load()

    fun rounds(): List<ObservedRound> = data.rounds

    fun roundCount(): Int = data.rounds.size

    fun lastSignature(): String? = data.lastSignature

    /**
     * Attempts to commit a read as a settled round.
     * @param confirmedOutcome outcome chosen by the user for ambiguous reads
     *        (overrides the dice-derived outcome; the user sees the platform).
     */
    fun commit(
        read: DiceRead,
        confirmedOutcome: Outcome?,
        now: Long,
        motionSinceLastCommit: Boolean
    ): CommitResult {
        val b1 = read.b1 ?: return CommitResult.Ambiguous("dado azul 1 não reconhecido")
        val b2 = read.b2 ?: return CommitResult.Ambiguous("dado azul 2 não reconhecido")
        val r1 = read.r1 ?: return CommitResult.Ambiguous("dado vermelho 1 não reconhecido")
        val r2 = read.r2 ?: return CommitResult.Ambiguous("dado vermelho 2 não reconhecido")

        val validation = BacBoRules.validate(b1, b2, r1, r2)
        if (!validation.valid) return CommitResult.Ambiguous(validation.reason ?: "valores inválidos")

        val outcome = confirmedOutcome ?: validation.derived!!
        val signature = "$b1,$b2,$r1,$r2"
        val last = data.rounds.lastOrNull()

        val duplicate = last != null && signature == data.lastSignature && (
            !motionSinceLastCommit ||
                (now - data.lastCommitAt) < ObservationConfig.SAME_SIGNATURE_WINDOW_MS
            )
        if (duplicate) return CommitResult.Duplicate

        val observed = ObservedRound(
            roundNumber = data.roundNumber + 1,
            b1 = b1, b2 = b2, r1 = r1, r2 = r2,
            blueSum = validation.blueSum!!,
            redSum = validation.redSum!!,
            outcome = outcome,
            recognizedAt = now,
            confidence = read.confidence,
            source = Source.VISION
        )
        data = SessionFileStore.SessionData(
            roundNumber = observed.roundNumber,
            lastSignature = signature,
            lastCommitAt = now,
            rounds = (data.rounds + observed).takeLast(MAX_SESSION_ROUNDS)
        )
        store.save(data)
        return CommitResult.New(observed)
    }

    /** Maps the observed session to the global history format. */
    fun toRounds(): List<Round> = data.rounds.map {
        Round(
            timestamp = it.recognizedAt,
            outcome = it.outcome,
            source = it.source,
            note = "B ${it.b1}+${it.b2} · V ${it.r1}+${it.r2}"
        )
    }

    companion object {
        const val MAX_SESSION_ROUNDS = 500
    }
}
