package com.pokecurator.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Tiny network client (no third-party HTTP dependency). */
object Api {

    sealed class Result {
        data class Ok(val plan: Plan) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun fetchPlan(planUrl: String): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val separator = if (planUrl.contains("?")) "&" else "?"
            val cacheBustedUrl = "$planUrl${separator}_=${System.currentTimeMillis()}"
            conn = (URL(cacheBustedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                useCaches = false
                setRequestProperty("User-Agent", "PokeCurator-Companion")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
            }
            val code = conn.responseCode
            if (code == 401) return@withContext Result.Error(
                "Server rejected the token \u2013 re-copy your sync URL from the app."
            )
            if (code !in 200..299) return@withContext Result.Error("Server error $code.")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Result.Ok(Plan.parse(body))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error.")
        } finally {
            conn?.disconnect()
        }
    }
}
