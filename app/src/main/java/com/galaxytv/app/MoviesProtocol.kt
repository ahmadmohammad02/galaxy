package com.galaxytv.app

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

private const val MOVIES_CONNECT_TIMEOUT_MS = 30_000
private const val MOVIES_READ_TIMEOUT_MS = 30_000

data class MovieSubCategory(
    val id: String,
    val name: String,
    val icon: String,
    val viewOrder: Int
)

data class MovieCategory(
    val id: String,
    val name: String,
    val icon: String,
    val viewOrder: Int,
    val subCategories: List<MovieSubCategory>
)

data class MovieItem(
    val id: String,
    val title: String,
    val categoryId: String,
    val icon: String,
    val viewOrder: Int,
    val streamUrl: String
)

data class MovieInfo(
    val id: String,
    val title: String,
    val categoryId: String,
    val icon: String,
    val streamUrl: String,
    val movieImage: String,
    val genre: String,
    val plot: String,
    val cast: String,
    val duration: String,
    val bitrate: Int?,
    val rating: Double?
)

object MovieResponseParser {
    fun categories(jsonText: String): List<MovieCategory> {
        return runCatching {
            JsonParser.parseString(jsonText).asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val id = obj.stringValue("cat_id") ?: return@mapNotNull null
                val name = obj.stringValue("cat_name").orEmpty()
                val subCategories = obj.get("sub_cats")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { subElement ->
                        if (!subElement.isJsonObject) return@mapNotNull null
                        val sub = subElement.asJsonObject
                        val subId = sub.stringValue("sub_id") ?: return@mapNotNull null
                        MovieSubCategory(
                            id = subId,
                            name = sub.stringValue("sub_name").orEmpty(),
                            icon = sub.stringValue("sub_icon").orEmpty(),
                            viewOrder = sub.intValue("sub_view_order")
                        )
                    }
                    .orEmpty()
                    .ifEmpty {
                        listOf(
                            MovieSubCategory(
                                id = id,
                                name = name,
                                icon = obj.stringValue("cat_icon").orEmpty(),
                                viewOrder = obj.intValue("cat_view_order")
                            )
                        )
                    }
                MovieCategory(
                    id = id,
                    name = name,
                    icon = obj.stringValue("cat_icon").orEmpty(),
                    viewOrder = obj.intValue("cat_view_order"),
                    subCategories = subCategories
                )
            }.sortedBy { it.viewOrder }
        }.getOrDefault(emptyList())
    }

    fun movies(jsonText: String): List<MovieItem> {
        return runCatching {
            JsonParser.parseString(jsonText).asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val id = obj.stringValue("id") ?: return@mapNotNull null
                MovieItem(
                    id = id,
                    title = obj.stringValue("title").orEmpty(),
                    categoryId = obj.stringValue("catid").orEmpty(),
                    icon = obj.stringValue("icon").orEmpty(),
                    viewOrder = obj.intValue("view_order"),
                    streamUrl = obj.stringValue("stream_url").orEmpty()
                )
            }.sortedBy { it.viewOrder }
        }.getOrDefault(emptyList())
    }

    fun movieInfo(jsonText: String): MovieInfo? {
        return runCatching {
            val root = JsonParser.parseString(jsonText)
            val obj = when {
                root.isJsonArray -> root.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                root.isJsonObject -> root.asJsonObject
                else -> null
            } ?: return null
            MovieInfo(
                id = obj.stringValue("id").orEmpty(),
                title = obj.stringValue("stream_display_name").orEmpty(),
                categoryId = obj.stringValue("category_id").orEmpty(),
                icon = obj.stringValue("stream_icon").orEmpty(),
                streamUrl = obj.stringValue("stream_url").orEmpty(),
                movieImage = obj.stringValue("movie_image").orEmpty(),
                genre = obj.stringValue("genre").orEmpty(),
                plot = obj.stringValue("plot").orEmpty(),
                cast = obj.stringValue("cast").orEmpty(),
                duration = obj.stringValue("duration").orEmpty(),
                bitrate = obj.intValueOrNull("bitrate"),
                rating = obj.doubleValueOrNull("rating") ?: obj.doubleValueOrNull("rate")
            )
        }.getOrNull()
    }

    private fun com.google.gson.JsonObject.stringValue(name: String): String? {
        val value: JsonElement = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
    }

    private fun com.google.gson.JsonObject.intValue(name: String): Int = intValueOrNull(name) ?: 0

    private fun com.google.gson.JsonObject.intValueOrNull(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun com.google.gson.JsonObject.doubleValueOrNull(name: String): Double? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asDouble }.getOrNull()
    }
}

class MoviesService(
    private val hostManager: FailoverHostManager = FailoverHostManager()
) {
    fun fetchCategories(code: String): List<MovieCategory> {
        val response = request("movies_cat", code) ?: return emptyList()
        return MovieResponseParser.categories(response)
    }

    fun fetchMovies(code: String, categoryId: String): List<MovieItem> {
        val response = request("movies_list", code, mapOf("catid" to categoryId)) ?: return emptyList()
        return MovieResponseParser.movies(response)
    }

    fun fetchMovieInfo(code: String, movieId: String): MovieInfo? {
        val response = request("movies_info", code, mapOf("movie_id" to movieId)) ?: return null
        return MovieResponseParser.movieInfo(response)
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
            connection.connectTimeout = MOVIES_CONNECT_TIMEOUT_MS
            connection.readTimeout = MOVIES_READ_TIMEOUT_MS
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