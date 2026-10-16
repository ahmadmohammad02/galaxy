package com.galaxytv.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MoviePosterLoader : AutoCloseable {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    fun load(url: String, image: ImageView, placeholder: TextView) {
        placeholder.visibility = android.view.View.VISIBLE
        load(url, image, 0) {
            placeholder.visibility = android.view.View.GONE
        }
    }

    fun load(url: String, image: ImageView, fallbackResource: Int) {
        load(url, image, fallbackResource) {}
    }

    private fun load(url: String, image: ImageView, fallbackResource: Int, onLoaded: () -> Unit) {
        image.tag = url
        val cached = synchronized(cache) { cache.get(url) }
        if (cached != null) {
            image.setImageBitmap(cached)
            onLoaded()
            return
        }

        if (fallbackResource == 0) image.setImageDrawable(null)
        else image.setImageResource(fallbackResource)
        if (url.isBlank()) return

        executor.execute {
            val bitmap = download(url)
            if (bitmap != null) synchronized(cache) { cache.put(url, bitmap) }
            image.post {
                if (image.tag == url && bitmap != null) {
                    image.setImageBitmap(bitmap)
                    onLoaded()
                }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val uri = runCatching { URL(url) }.getOrNull() ?: return null
        if (uri.protocol !in setOf("http", "https")) return null
        val connection = runCatching { uri.openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_POSTER_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (ignored: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > TARGET_POSTER_WIDTH * 2 || height / sample > TARGET_POSTER_HEIGHT * 2) {
            sample *= 2
        }
        return sample
    }

    override fun close() {
        executor.shutdownNow()
        synchronized(cache) { cache.evictAll() }
    }

    private companion object {
        const val MAX_POSTER_BYTES = 3 * 1024 * 1024
        const val TARGET_POSTER_WIDTH = 360
        const val TARGET_POSTER_HEIGHT = 540
    }
}