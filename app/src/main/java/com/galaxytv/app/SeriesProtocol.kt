package com.galaxytv.app

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

private const val SERIES_CONNECT_TIMEOUT_MS = 30_000
private const val SERIES_READ_TIMEOUT_MS = 30_000

data class SeriesItem(
    val id: String,
    val title: String,
    val icon: String,
    val iconBig: String,
    val categoryId: String,
    val genre: String,
    val plot: String,
    val cast: String,
    val rating: Double?,
    val director: String,
    val releaseDate: String
)

data class SeriesEpisode(
    val seasonNumber: String,
    val episodeNumber: Int,
    val episodeName: String,
    val streamUrl: String,
    val streamIcon: String
)

object SeriesResponseParser {
    fun series(jsonText: String): List<SeriesItem> {
        return runCatching {
            JsonParser.parseString(jsonText).asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val id = obj.stringValue("id") ?: return@mapNotNull null
                SeriesItem(
                    id = id,
                    title = obj.stringValue("title").orEmpty(),
                    icon = obj.stringValue("icon").orEmpty(),
                    iconBig = obj.stringValue("icon_big").orEmpty(),
                    categoryId = obj.stringValue("catid").orEmpty(),
                    genre = obj.stringValue("genre").orEmpty(),
                    plot = obj.stringValue("plot").orEmpty(),
                    cast = obj.stringValue("cast").orEmpty(),
                    rating = obj.doubleValueOrNull("rating"),
                    director = obj.stringValue("director").orEmpty(),
                    releaseDate = obj.stringValue("releaseDate").orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun episodes(jsonText: String): List<SeriesEpisode> {
        return runCatching {
            JsonParser.parseString(jsonText).asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val streamUrl = obj.stringValue("stream_url")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                SeriesEpisode(
                    seasonNumber = obj.stringValue("season_num").orEmpty(),
                    episodeNumber = obj.intValue("episode_num"),
                    episodeName = obj.stringValue("episode_name").orEmpty(),
                    streamUrl = streamUrl,
                    streamIcon = obj.stringValue("stream_icon").orEmpty()
                )
            }.sortedWith(compareBy<SeriesEpisode>({ it.seasonNumber.toIntOrNull() ?: Int.MAX_VALUE }, { it.episodeNumber }))
        }.getOrDefault(emptyList())
    }

    private fun com.google.gson.JsonObject.stringValue(name: String): String? {
        val value: JsonElement = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
    }

    private fun com.google.gson.JsonObject.intValue(name: String): Int {
        val value = get(name) ?: return 0
        if (value.isJsonNull) return 0
        return runCatching { value.asInt }.getOrDefault(0)
    }

    private fun com.google.gson.JsonObject.doubleValueOrNull(name: String): Double? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asDouble }.getOrNull()
    }
}

class SeriesService(
    private val hostManager: FailoverHostManager = FailoverHostManager()
) {
    fun fetchCategories(code: String): List<MovieCategory> {
        val response = request("series_cat", code) ?: return emptyList()
        return MovieResponseParser.categories(response)
    }

    fun fetchSeries(code: String, categoryId: String): List<SeriesItem> {
        val response = request("series_list", code, mapOf("catid" to categoryId)) ?: return emptyList()
        return SeriesResponseParser.series(response)
    }

    fun fetchEpisodes(code: String, seriesId: String): List<SeriesEpisode> {
        val response = request("series_info", code, mapOf("series_id" to seriesId)) ?: return emptyList()
        return SeriesResponseParser.episodes(response)
    }

    private fun request(mode: String, code: String, extras: Map<String, String> = emptyMap()): String? {
        val device = DeviceProfile.currentIdentity()
        val values = linkedMapOf(
            "mode" to mode,
            "code" to code,
            "mac" to device.macAddress,
            "sn" to device.serialNumber,
            "chipid" to device.chipId,
            "model" to device.model,
            "firmware_ver" to device.firmwareVersion
        )
        values.putAll(extras)
        val encrypted = XorCrypto.encrypt(Gson().toJson(values))
        val host = hostManager.currentHost()
        return try {
            XorCrypto.decryptResponse(post(host, encrypted)).takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            hostManager.markHostDown(host)
            try {
                val backupHost = hostManager.currentHost()
                if (backupHost == host) return null
                XorCrypto.decryptResponse(post(backupHost, encrypted)).takeIf { it.isNotBlank() }
            } catch (ignored: Exception) {
                null
            }
        }
    }

    private fun post(host: String, encryptedJson: String): ByteArray {
        val connection = URL("${host.trimEnd('/')}$API_PATH").openConnection() as HttpURLConnection
        try {
            val multipart = createMultipartFormBody("json", encryptedJson)
            connection.requestMethod = "POST"
            connection.connectTimeout = SERIES_CONNECT_TIMEOUT_MS
            connection.readTimeout = SERIES_READ_TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(multipart.bytes.size)
            connection.setRequestProperty("Content-Type", multipart.contentType)
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("User-Agent", DeviceProfile.currentIdentity().activationUserAgent)
            connection.outputStream.use { it.write(multipart.bytes) }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Server response: ${connection.responseCode}")
            }
            return readApiResponse(connection.inputStream, connection.getHeaderField("Content-Encoding"))
        } finally {
            connection.disconnect()
        }
    }
}