package com.cashcraft.ads.mediation.internal.nativeads

/** 主线程 position 索引。连接使用对象引用，不产生页面或 slot 编号。 */
internal class NativePositionRegistry<T : Any> {
    class Entry<T : Any>(val position: String, val value: T, var connection: Any?)
    private val entries = mutableMapOf<String, Entry<T>>()

    operator fun get(position: String): Entry<T>? = entries[position]

    fun claim(position: String, value: T, connection: Any): Entry<T>? {
        if (entries.containsKey(position)) return null
        return Entry(position, value, connection).also { entries[position] = it }
    }

    fun attach(entry: Entry<T>, connection: Any): Boolean {
        if (entries[entry.position] !== entry || (entry.connection != null && entry.connection !== connection)) return false
        entry.connection = connection
        return true
    }

    fun owns(entry: Entry<T>, connection: Any): Boolean =
        entries[entry.position] === entry && entry.connection === connection

    fun detach(entry: Entry<T>, connection: Any): Boolean {
        if (!owns(entry, connection)) return false
        entry.connection = null
        return true
    }

    fun remove(entry: Entry<T>): Boolean {
        if (entries[entry.position] !== entry) return false
        entries.remove(entry.position)
        entry.connection = null
        return true
    }
}
