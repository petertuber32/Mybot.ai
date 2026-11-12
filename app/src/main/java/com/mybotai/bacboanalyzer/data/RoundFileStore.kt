package com.mybotai.bacboanalyzer.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JSON-file backed round store. Pure JVM (no Android imports) so that
 * persistence is covered by unit tests with a temporary directory.
 *
 * Writes are atomic-ish: data goes to "<fileName>.tmp" first, then is
 * moved over the real file, so a crash mid-write cannot corrupt history.
 */
class RoundFileStore(
    private val dir: File,
    private val fileName: String = "rounds.json"
) {

    private val file = File(dir, fileName)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var cache: MutableList<Round>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun getAll(): List<Round> {
        loadIfNeeded()
        return cache!!.toList()
    }

    @Synchronized
    fun add(round: Round) {
        loadIfNeeded()
        cache!!.add(round)
        persist()
        notifyChanged()
    }

    @Synchronized
    fun updateOutcome(index: Int, outcome: Outcome) {
        loadIfNeeded()
        val list = cache ?: return
        if (index !in list.indices) return
        list[index] = list[index].copy(outcome = outcome)
        persist()
        notifyChanged()
    }

    @Synchronized
    fun removeAt(index: Int) {
        loadIfNeeded()
        val list = cache ?: return
        if (index !in list.indices) return
        list.removeAt(index)
        persist()
        notifyChanged()
    }

    @Synchronized
    fun clear() {
        cache = mutableListOf()
        if (file.exists()) file.delete()
        notifyChanged()
    }

    @Synchronized
    fun exportJson(): String {
        val type = object : TypeToken<List<Round>>() {}.type
        return GsonBuilder().setPrettyPrinting().create().toJson(getAll(), type)
    }

    fun count(): Int = getAll().size

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    private fun loadIfNeeded() {
        if (cache != null) return
        cache = if (file.exists()) {
            runCatching {
                val type = object : TypeToken<MutableList<Round>>() {}.type
                Gson().fromJson<MutableList<Round>>(file.readText(), type) ?: mutableListOf()
            }.getOrDefault(mutableListOf())
        } else {
            mutableListOf()
        }
    }

    private fun persist() {
        dir.mkdirs()
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(gson.toJson(cache ?: mutableListOf<Round>()))
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
