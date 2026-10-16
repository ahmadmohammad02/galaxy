package com.galaxytv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MovieDetailsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MOVIE_ID = "movie_id"
        const val EXTRA_MOVIE_TITLE = "movie_title"
        const val EXTRA_MOVIE_IMAGE = "movie_image"
        const val EXTRA_MOVIE_URL = "movie_url"
        const val EXTRA_MOVIE_CATEGORY = "movie_category"
        private const val PLAYER_NAME = "channel_name"
        private const val PLAYER_URL = "stream_url"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val posterLoader = MoviePosterLoader()
    private var expiryWatcher: ActivationExpiryWatcher? = null
    private var selectedStreamUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = ActivationSession.activeCode(this)
        if (code == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_movie_details)
        val title = intent.getStringExtra(EXTRA_MOVIE_TITLE).orEmpty()
        val imageUrl = intent.getStringExtra(EXTRA_MOVIE_IMAGE).orEmpty()
        selectedStreamUrl = intent.getStringExtra(EXTRA_MOVIE_URL).orEmpty()
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID).orEmpty()

        findViewById<TextView>(R.id.movieDetailTitle).text = title
        findViewById<TextView>(R.id.movieDetailMeta).text = "Loading movie details..."
        findViewById<TextView>(R.id.movieDetailPlot).text = ""
        findViewById<TextView>(R.id.movieDetailCast).text = ""
        findViewById<Button>(R.id.movieBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.moviePlayButton).setOnClickListener { playSelectedMovie(title) }
        posterLoader.load(
            imageUrl,
            findViewById<ImageView>(R.id.movieDetailPoster),
            findViewById<TextView>(R.id.movieDetailPosterPlaceholder)
        )
        updatePlayButton()
        findViewById<Button>(R.id.moviePlayButton).requestFocus()

        if (movieId.isBlank()) {
            findViewById<TextView>(R.id.movieDetailStatus).text = "Movie details are unavailable."
        } else {
            executor.execute {
                val info = MoviesService().fetchMovieInfo(code, movieId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    displayMovieInfo(info, title, imageUrl)
                }
            }
        }
    }

    private fun displayMovieInfo(info: MovieInfo?, fallbackTitle: String, fallbackImage: String) {
        if (info == null) {
            findViewById<TextView>(R.id.movieDetailTitle).text = fallbackTitle
            findViewById<TextView>(R.id.movieDetailMeta).text = ""
            findViewById<TextView>(R.id.movieDetailPlot).text = ""
            findViewById<TextView>(R.id.movieDetailCast).text = ""
            findViewById<TextView>(R.id.movieDetailStatus).text =
                "Details could not be loaded. Playback is available if the stream link works."
            if (fallbackImage.isNotBlank()) loadPoster(fallbackImage)
            updatePlayButton()
            return
        }

        findViewById<TextView>(R.id.movieDetailTitle).text = info.title.ifBlank { fallbackTitle }
        val meta = listOfNotNull(
            info.duration.takeIf { it.isNotBlank() },
            info.rating?.let { "Rating $it" },
            info.bitrate?.let { "$it kbps" }
        ).joinToString("  •  ")
        findViewById<TextView>(R.id.movieDetailMeta).text = meta
        findViewById<TextView>(R.id.movieDetailGenre).text = info.genre
        findViewById<TextView>(R.id.movieDetailPlot).text = info.plot.ifBlank { "No plot available." }
        findViewById<TextView>(R.id.movieDetailCast).text = info.cast.takeIf { it.isNotBlank() }?.let { "Cast: $it" }.orEmpty()
        findViewById<TextView>(R.id.movieDetailStatus).text = ""
        selectedStreamUrl = info.streamUrl.ifBlank { selectedStreamUrl }
        updatePlayButton()

        val posterUrl = info.movieImage.ifBlank { info.icon }.ifBlank { fallbackImage }
        if (posterUrl.isNotBlank()) loadPoster(posterUrl)
    }

    private fun loadPoster(url: String) {
        posterLoader.load(
            url,
            findViewById(R.id.movieDetailPoster),
            findViewById(R.id.movieDetailPosterPlaceholder)
        )
    }

    private fun updatePlayButton() {
        val uri = runCatching { Uri.parse(selectedStreamUrl) }.getOrNull()
        findViewById<Button>(R.id.moviePlayButton).isEnabled =
            uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()
    }

    private fun playSelectedMovie(title: String) {
        val uri = Uri.parse(selectedStreamUrl)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            findViewById<TextView>(R.id.movieDetailStatus).text = "The movie stream URL is invalid."
            return
        }
        startActivity(Intent(this, StreamPlayerActivity::class.java).apply {
            putExtra(PLAYER_NAME, title)
            putExtra(PLAYER_URL, selectedStreamUrl)
            putExtra(StreamPlayerActivity.EXTRA_MIN_HD, true)
        })
    }

    override fun onResume() {
        super.onResume()
        expiryWatcher = ActivationExpiryWatcher(this) {
            Toast.makeText(this, "Your activation has expired. Please sign in again.", Toast.LENGTH_LONG).show()
            finish()
        }.also { it.start() }
    }

    override fun onPause() {
        expiryWatcher?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        expiryWatcher?.stop()
        executor.shutdownNow()
        posterLoader.close()
        super.onDestroy()
    }
}