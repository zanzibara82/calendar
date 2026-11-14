package com.example.mycalendar.desktop.data

/** Хранилище токена в памяти: тестам, которым не важен файл, хватает и этого. */
class MemoryTokenVault : TokenVault {

    var value: String? = null
        private set

    override suspend fun load(): String? = value

    override suspend fun save(token: String) {
        value = token
    }

    override suspend fun clear() {
        value = null
    }
}
