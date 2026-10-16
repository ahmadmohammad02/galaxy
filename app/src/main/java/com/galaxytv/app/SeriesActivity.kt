package com.galaxytv.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private data class SeriesCategoryEntry(val id: String, val label: String)

class SeriesActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val posterLoader = MoviePosterLoader()
    private lateinit var activeCode: String
    private lateinit var categoriesList: ListView
    private lateinit var seriesGrid: GridView
    private lateinit var categoryStatus: TextView
    private lateinit var gridTitle: TextView
    private lateinit var gridStatus: TextView
    private lateinit var searchInput: EditText
    private lateinit var categoryAdapter: ArrayAdapter<String>
    private lateinit var seriesAdapter: SeriesGridAdapter
    private var categoryEntries = emptyList<SeriesCategoryEntry>()
    private var series = emptyList<SeriesItem>()
    private var requestGeneration = 0
    private var expiryWatcher: ActivationExpiryWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeCode = ActivationSession.activeCode(this).orEmpty()
        if (!LoginCodeValidator.isValidCode(activeCode)) {
            Toast.makeText(this, "Activate your code to browse series.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_movies)
        findViewById<RadioGroup>(R.id.catalogSourceGroup).visibility = View.GONE
        categoriesList = findViewById(R.id.movieCategoryList)
        categoriesList.requestFocus()
        seriesGrid = findViewById(R.id.movieGrid)
        categoryStatus = findViewById(R.id.movieCategoryStatus)
        gridTitle = findViewById(R.id.movieGridTitle)
        gridStatus = findViewById(R.id.movieGridStatus)
        searchInput = findViewById(R.id.movieSearchInput)
        searchInput.hint = "Search series"
        categoryAdapter = createTextAdapter()
        categoriesList.adapter = categoryAdapter
        seriesAdapter = SeriesGridAdapter()
        seriesGrid.adapter = seriesAdapter
        seriesGrid.emptyView = findViewById(R.id.movieGridEmpty)
        seriesGrid.setOnItemClickListener { _, _, position, _ ->
            seriesAdapter.visibleSeries.getOrNull(position)?.let(::openSeriesDetails)
        }

        categoriesList.setOnItemClickListener { _, _, position, _ ->
            categoryEntries.getOrNull(position)?.let(::loadSeries)
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                seriesAdapter.filter(text?.toString().orEmpty())
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        findViewById<ImageButton>(R.id.refreshMovieCategories).setOnClickListener { loadCategories() }
        loadCategories()
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

    private fun loadCategories() {
        categoryStatus.text = "Loading categories..."
        findViewById<ImageButton>(R.id.refreshMovieCategories).isEnabled = false
        executor.execute {
            val categories = SeriesService().fetchCategories(activeCode)
            val entries = categories.flatMap { category ->
                category.subCategories.map { subCategory ->
                    val name = if (subCategory.name.equals(category.name, ignoreCase = true)) {
                        subCategory.name
                    } else {
                        "${category.name} / ${subCategory.name}"
                    }
                    SeriesCategoryEntry(subCategory.id, "${subCategory.id.padStart(3, '0')} | $name")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<ImageButton>(R.id.refreshMovieCategories).isEnabled = true
                categoryEntries = entries
                categoryAdapter.clear()
                categoryAdapter.addAll(entries.map { it.label })
                categoryStatus.text = if (entries.isEmpty()) "No series categories available." else "Series categories"
                if (entries.isNotEmpty()) {
                    categoriesList.setSelection(0)
                    categoriesList.setItemChecked(0, true)
                    categoriesList.post { categoriesList.requestFocus() }
                    loadSeries(entries.first())
                } else {
                    showSeries(emptyList())
                    gridStatus.text = "The series server is unavailable or has no categories."
                }
            }
        }
    }

    private fun loadSeries(category: SeriesCategoryEntry) {
        categoriesList.setItemChecked(categoryEntries.indexOf(category), true)
        gridTitle.text = category.label.substringAfter(" | ")
        gridStatus.text = "Loading series..."
        showSeries(emptyList())
        val generation = ++requestGeneration
        executor.execute {
            val result = SeriesService().fetchSeries(activeCode, category.id)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
                showSeries(result)
                if (seriesAdapter.count > 0) seriesGrid.setSelection(0)
                gridStatus.text = if (result.isEmpty()) "No series in this category." else "${result.size} series"
            }
        }
    }

    private fun showSeries(items: List<SeriesItem>) {
        series = items
        seriesAdapter.filter(searchInput.text.toString())
    }

    private fun openSeriesDetails(item: SeriesItem) {
        startActivity(Intent(this, SeriesDetailsActivity::class.java).apply {
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_ID, item.id)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_TITLE, item.title)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_IMAGE, item.iconBig.ifBlank { item.icon })
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_GENRE, item.genre)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_PLOT, item.plot)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_CAST, item.cast)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_RATING, item.rating ?: -1.0)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_DIRECTOR, item.director)
            putExtra(SeriesDetailsActivity.EXTRA_SERIES_RELEASE_DATE, item.releaseDate)
        })
    }

    private fun createTextAdapter(): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(this@SeriesActivity)
                row.text = getItem(position).orEmpty()
                row.setTextColor(Color.WHITE)
                row.textSize = 15f
                row.setPadding(12, 12, 10, 12)
                row.minHeight = 54
                return row
            }
        }
    }

    private inner class SeriesGridAdapter : BaseAdapter() {
        var visibleSeries = emptyList<SeriesItem>()
            private set
        private val inflater = LayoutInflater.from(this@SeriesActivity)

        fun filter(query: String) {
            visibleSeries = if (query.isBlank()) series else series.filter {
                it.title.contains(query.trim(), ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visibleSeries.size
        override fun getItem(position: Int): SeriesItem = visibleSeries[position]
        override fun getItemId(position: Int): Long = getItem(position).id.toLongOrNull() ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_movie, parent, false)
            val item = getItem(position)
            view.isFocusable = false
            view.isClickable = false
            view.findViewById<TextView>(R.id.moviePosterTitle).text = item.title
            view.findViewById<TextView>(R.id.moviePosterRating).apply {
                val rating = item.rating
                text = if (rating == null || rating <= 0.0) "" else "★ ${rating.toInt()}"
                visibility = if (rating == null || rating <= 0.0) View.GONE else View.VISIBLE
            }
            posterLoader.load(
                item.iconBig.ifBlank { item.icon },
                view.findViewById<ImageView>(R.id.moviePosterImage),
                view.findViewById<TextView>(R.id.moviePosterPlaceholder)
            )
            return view
        }
    }

    override fun onDestroy() {
        expiryWatcher?.stop()
        executor.shutdownNow()
        posterLoader.close()
        super.onDestroy()
    }
}