package com.galaxytv.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SeriesDetailsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_TITLE = "series_title"
        const val EXTRA_SERIES_IMAGE = "series_image"
        const val EXTRA_SERIES_GENRE = "series_genre"
        const val EXTRA_SERIES_PLOT = "series_plot"
        const val EXTRA_SERIES_CAST = "series_cast"
        const val EXTRA_SERIES_RATING = "series_rating"
        const val EXTRA_SERIES_DIRECTOR = "series_director"
        const val EXTRA_SERIES_RELEASE_DATE = "series_release_date"
        private const val PLAYER_NAME = "channel_name"
        private const val PLAYER_URL = "stream_url"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val posterLoader = MoviePosterLoader()
    private var expiryWatcher: ActivationExpiryWatcher? = null
    private var episodes = emptyList<SeriesEpisode>()
    private var visibleEpisodes = emptyList<SeriesEpisode>()
    private var selectedEpisode: SeriesEpisode? = null
    private lateinit var episodeAdapter: ArrayAdapter<String>
    private lateinit var seasonSpinner: Spinner
    private lateinit var episodeList: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = ActivationSession.activeCode(this)
        if (code == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_series_details)

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        val imageUrl = intent.getStringExtra(EXTRA_SERIES_IMAGE).orEmpty()
        findViewById<TextView>(R.id.seriesDetailTitle).text = title
        findViewById<TextView>(R.id.seriesDetailMeta).text = buildMeta()
        findViewById<TextView>(R.id.seriesDetailPlot).text =
            intent.getStringExtra(EXTRA_SERIES_PLOT).orEmpty().ifBlank { "No plot available." }
        findViewById<TextView>(R.id.seriesDetailCast).text =
            intent.getStringExtra(EXTRA_SERIES_CAST).orEmpty().takeIf { it.isNotBlank() }?.let { "Cast: $it" }.orEmpty()
        findViewById<TextView>(R.id.seriesDetailDirector).text =
            intent.getStringExtra(EXTRA_SERIES_DIRECTOR).orEmpty().takeIf { it.isNotBlank() }?.let { "Director: $it" }.orEmpty()
        findViewById<TextView>(R.id.seriesDetailReleaseDate).text =
            intent.getStringExtra(EXTRA_SERIES_RELEASE_DATE).orEmpty().takeIf { it.isNotBlank() }?.let { "Release date: $it" }.orEmpty()
        findViewById<Button>(R.id.seriesBackButton).setOnClickListener { finish() }
        posterLoader.load(
            imageUrl,
            findViewById<ImageView>(R.id.seriesDetailPoster),
            findViewById<TextView>(R.id.seriesDetailPosterPlaceholder)
        )

        seasonSpinner = findViewById(R.id.seriesSeasonSpinner)
        episodeList = findViewById(R.id.seriesEpisodeList)
        episodeAdapter = createAdapter()
        episodeList.adapter = episodeAdapter
        episodeList.setOnItemClickListener { _, _, position, _ ->
            selectedEpisode = visibleEpisodes.getOrNull(position)
            episodeList.setItemChecked(position, true)
            playSelectedEpisode()
        }
        seasonSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val season = parent?.getItemAtPosition(position)?.toString() ?: return
                showSeason(season)
            }
        }
        loadEpisodes(code, seriesId)
    }

    private fun buildMeta(): String {
        val genre = intent.getStringExtra(EXTRA_SERIES_GENRE).orEmpty()
        val rating = intent.getDoubleExtra(EXTRA_SERIES_RATING, -1.0)
        return listOfNotNull(
            genre.takeIf { it.isNotBlank() },
            rating.takeIf { it > 0.0 }?.let { "★ $it/10" }
        ).joinToString("  •  ")
    }

    private fun loadEpisodes(code: String, seriesId: String) {
        if (seriesId.isBlank()) {
            findViewById<TextView>(R.id.seriesEpisodeStatus).text = "Series ID is missing."
            return
        }
        executor.execute {
            val result = SeriesService().fetchEpisodes(code, seriesId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                episodes = result
                val seasons = result.map { it.seasonNumber }.distinct()
                    .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                if (seasons.isEmpty()) {
                    findViewById<TextView>(R.id.seriesEpisodeStatus).text =
                        "No episodes available or the server is unavailable."
                    return@runOnUiThread
                }
                val seasonAdapter = createAdapter().apply { addAll(seasons.map { "Season $it" }) }
                seasonSpinner.adapter = seasonAdapter
                findViewById<TextView>(R.id.seriesEpisodeStatus).text = "${result.size} episodes"
            }
        }
    }

    private fun showSeason(seasonLabel: String) {
        val season = seasonLabel.removePrefix("Season ")
        visibleEpisodes = episodes.filter { it.seasonNumber == season }
        selectedEpisode = null
        episodeList.clearChoices()
        episodeAdapter.clear()
        episodeAdapter.addAll(visibleEpisodes.map { episode ->
            episode.episodeName.ifBlank { "Episode ${episode.episodeNumber}" }
        })
        if (visibleEpisodes.isNotEmpty()) {
            selectedEpisode = visibleEpisodes.first()
            episodeList.setSelection(0)
            episodeList.setItemChecked(0, true)
            episodeList.post { episodeList.requestFocus() }
        }
    }

    private fun createAdapter(): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = styleRow(position, convertView)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = styleRow(position, convertView)

            private fun styleRow(position: Int, convertView: View?): View {
                val row = (convertView as? TextView) ?: TextView(this@SeriesDetailsActivity)
                row.text = getItem(position).orEmpty()
                row.setTextColor(Color.WHITE)
                row.textSize = 16f
                row.setPadding(12, 12, 12, 12)
                row.setBackgroundColor(Color.rgb(12, 20, 34))
                return row
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun playSelectedEpisode() {
        val episode = selectedEpisode ?: return
        val uri = runCatching { android.net.Uri.parse(episode.streamUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            findViewById<TextView>(R.id.seriesEpisodeStatus).text = "The episode stream URL is invalid."
            return
        }
        startActivity(Intent(this, StreamPlayerActivity::class.java).apply {
            putExtra(PLAYER_NAME, episode.episodeName)
            putExtra(PLAYER_URL, episode.streamUrl)
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