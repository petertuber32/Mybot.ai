package com.mybotai.bacboanalyzer.data

import android.content.Context
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round

/**
 * App-level singleton delegating to [RoundFileStore].
 * Call [init] once (MainActivity.onCreate) before any other method.
 */
object RoundStore {

    @Volatile
    private var delegate: RoundFileStore? = null

    fun init(context: Context) {
        if (delegate == null) {
            synchronized(this) {
                if (delegate == null) {
                    delegate = RoundFileStore(context.applicationContext.filesDir)
                }
            }
        }
    }

    private fun store(): RoundFileStore =
        delegate ?: throw IllegalStateException("RoundStore.init(context) was not called")

    fun getAll(): List<Round> = store().getAll()
    fun add(round: Round) = store().add(round)
    fun updateOutcome(index: Int, outcome: Outcome) = store().updateOutcome(index, outcome)
    fun removeAt(index: Int) = store().removeAt(index)
    fun clear() = store().clear()
    fun exportJson(): String = store().exportJson()
    fun count(): Int = store().count()
    fun addListener(l: () -> Unit) = store().addListener(l)
    fun removeListener(l: () -> Unit) = store().removeListener(l)
}
