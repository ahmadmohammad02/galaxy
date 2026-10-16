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

class CuratedCatalogActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CONTENT_MODE = "curated_content_mode"
    }

    private enum class Source { MOVIES, SERIES }

    private data class CategoryRow(
        val id: String,
        val label: String,
        val source: Source,
        val filterAllSeries: Boolean = false
    )

    private data class CatalogCard(
        val title: String,
        val image: String,
        val rating: Double?,
        val movie: MovieItem? = null,
        val series: SeriesItem? = null
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val posterLoader = MoviePosterLoader()
    private lateinit var mode: CuratedContentMode
    private lateinit var activeCode: String
    private lateinit var categoryList: ListView
    private lateinit var contentGrid: GridView
    private lateinit var categoryStatus: TextView
    private lateinit var gridTitle: TextView
    private lateinit var gridStatus: TextView
    private lateinit var searchInput: EditText
    private lateinit var categoryAdapter: ArrayAdapter<String>
    private lateinit var catalogAdapter: CatalogAdapter
    private var source = Source.MOVIES
    private var categories = emptyList<CategoryRow>()
    private var catalogItems = emptyList<CatalogCard>()
    private var requestGeneration = 0
    private var expiryWatcher: ActivationExpiryWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = runCatching {
            CuratedContentMode.valueOf(intent.getStringExtra(EXTRA_CONTENT_MODE).orEmpty())
        }.getOrDefault(CuratedContentMode.KIDS)
        activeCode = ActivationSession.activeCode(this).orEmpty()
        if (!LoginCodeValidator.isValidCode(activeCode)) {
            Toast.makeText(this, "Activate your code to browse this catalog.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_movies)
        categoryList = findViewById(R.id.movieCategoryList)
        categoryList.requestFocus()
        contentGrid = findViewById(R.id.movieGrid)
        categoryStatus = findViewById(R.id.movieCategoryStatus)
        gridTitle = findViewById(R.id.movieGridTitle)
        gridStatus = findViewById(R.id.movieGridStatus)
        searchInput = findViewById(R.id.movieSearchInput)
        val sourceGroup = findViewById<RadioGroup>(R.id.catalogSourceGroup)
        categoryAdapter = createCategoryAdapter()
        categoryList.adapter = categoryAdapter
        catalogAdapter = CatalogAdapter()
        contentGrid.adapter = catalogAdapter
        contentGrid.emptyView = findViewById(R.id.movieGridEmpty)
        contentGrid.setOnItemClickListener { _, _, position, _ ->
            catalogAdapter.visibleItems.getOrNull(position)?.let(::openDetails)
        }

        val modeTitle = if (mode == CuratedContentMode.KIDS) "KIDS" else "RAMADAN"
        gridTitle.text = modeTitle
        findViewById<TextView>(R.id.movieGridEmpty).text = "Select a matching category to browse $modeTitle content."
        searchInput.hint = "Search $modeTitle"
        if (mode == CuratedContentMode.RAMADAN) {
            source = Source.SERIES
            sourceGroup.check(R.id.catalogSeriesSource)
            sourceGroup.visibility = View.GONE
        } else {
            sourceGroup.visibility = View.VISIBLE
        }

        categoryList.setOnItemClickListener { _, _, position, _ ->
            categories.getOrNull(position)?.let(::loadContent)
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                catalogAdapter.filter(text?.toString().orEmpty())
            }
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
        findViewById<ImageButton>(R.id.refreshMovieCategories).setOnClickListener { loadCategories() }
        sourceGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedSource = if (checkedId == R.id.catalogSeriesSource) Source.SERIES else Source.MOVIES
            if (source != selectedSource) {
                source = selectedSource
                loadCategories()
            }
        }
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
        categoryStatus.text = "Loading ${source.name.lowercase()} categories..."
        findViewById<ImageButton>(R.id.refreshMovieCategories).isEnabled = false
        val generation = ++requestGeneration
        executor.execute {
            val result = when (source) {
                Source.MOVIES -> loadMovieCategories()
                Source.SERIES -> loadSeriesCategories()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
                findViewById<ImageButton>(R.id.refreshMovieCategories).isEnabled = true
                categories = result
                categoryAdapter.clear()
                categoryAdapter.addAll(result.map { it.label })
                categoryStatus.text = if (result.isEmpty()) {
                    "No matching ${source.name.lowercase()} categories."
                } else {
                    "${source.name.lowercase().replaceFirstChar { it.uppercase() }} categories"
                }
                if (result.isNotEmpty()) {
                    categoryList.setSelection(0)
                    categoryList.setItemChecked(0, true)
                    categoryList.post { categoryList.requestFocus() }
                    loadContent(result.first())
                } else {
                    showItems(emptyList())
                    gridStatus.text = "No matching content is currently available."
                }
            }
        }
    }

    private fun loadMovieCategories(): List<CategoryRow> {
        return MoviesService().fetchCategories(activeCode).flatMap { category ->
            category.subCategories.filter { subCategory ->
                CuratedContentFilter.matchesCategory(mode, category.name, subCategory.name)
            }.map { subCategory ->
                val name = if (subCategory.name.equals(category.name, ignoreCase = true)) {
                    subCategory.name
                } else {
                    "${category.name} / ${subCategory.name}"
                }
                CategoryRow(
                    id = subCategory.id,
                    label = "${subCategory.id.padStart(3, '0')} | $name",
                    source = Source.MOVIES
                )
            }
        }
    }

    private fun loadSeriesCategories(): List<CategoryRow> {
        val matchingCategories = SeriesService().fetchCategories(activeCode).flatMap { category ->
            category.subCategories.filter { subCategory ->
                CuratedContentFilter.matchesCategory(mode, category.name, subCategory.name)
            }.map { subCategory ->
                val name = if (subCategory.name.equals(category.name, ignoreCase = true)) {
                    subCategory.name
                } else {
                    "${category.name} / ${subCategory.name}"
                }
                CategoryRow(
                    id = subCategory.id,
                    label = "${subCategory.id.padStart(3, '0')} | $name",
                    source = Source.SERIES
                )
            }
        }
        if (matchingCategories.isNotEmpty()) return matchingCategories

        val label = if (mode == CuratedContentMode.KIDS) "ALL | Kids and family series" else "ALL | Ramadan series"
        return listOf(CategoryRow("all", label, Source.SERIES, filterAllSeries = true))
    }

    private fun loadContent(category: CategoryRow) {
        categoryList.setItemChecked(categories.indexOf(category), true)
        gridTitle.text = category.label.substringAfter(" | ")
        gridStatus.text = "Loading content..."
        showItems(emptyList())
        val generation = ++requestGeneration
        executor.execute {
            val items = when (category.source) {
                Source.MOVIES -> MoviesService().fetchMovies(activeCode, category.id).map { movie ->
                    CatalogCard(movie.title, movie.icon, null, movie = movie)
                }
                Source.SERIES -> {
                    val fetched = SeriesService().fetchSeries(activeCode, category.id)
                    val filtered = if (category.filterAllSeries) {
                        fetched.filter { CuratedContentFilter.matchesSeries(mode, it) }
                    } else {
                        fetched
                    }
                    filtered.map { item ->
                        CatalogCard(item.title, item.iconBig.ifBlank { item.icon }, item.rating, series = item)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
                showItems(items)
                if (catalogAdapter.count > 0) contentGrid.setSelection(0)
                gridStatus.text = if (items.isEmpty()) "No matching content in this category." else "${items.size} items"
            }
        }
    }

    private fun showItems(items: List<CatalogCard>) {
        catalogItems = items
        catalogAdapter.filter(searchInput.text.toString())
    }

    private fun openDetails(card: CatalogCard) {
        card.movie?.let { movie ->
            startActivity(Intent(this, MovieDetailsActivity::class.java).apply {
                putExtra(MovieDetailsActivity.EXTRA_MOVIE_ID, movie.id)
                putExtra(MovieDetailsActivity.EXTRA_MOVIE_TITLE, movie.title)
                putExtra(MovieDetailsActivity.EXTRA_MOVIE_IMAGE, movie.icon)
                putExtra(MovieDetailsActivity.EXTRA_MOVIE_URL, movie.streamUrl)
                putExtra(MovieDetailsActivity.EXTRA_MOVIE_CATEGORY, movie.categoryId)
            })
            return
        }

        val item = card.series ?: return
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

    private fun createCategoryAdapter(): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(this@CuratedCatalogActivity)
                row.text = getItem(position).orEmpty()
                row.setTextColor(Color.WHITE)
                row.textSize = 14f
                row.setPadding(10, 10, 8, 10)
                row.minHeight = 50
                return row
            }
        }
    }

    private inner class CatalogAdapter : BaseAdapter() {
        var visibleItems = emptyList<CatalogCard>()
            private set
        private val inflater = LayoutInflater.from(this@CuratedCatalogActivity)

        fun filter(query: String) {
            visibleItems = if (query.isBlank()) catalogItems else catalogItems.filter {
                it.title.contains(query.trim(), ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visibleItems.size
        override fun getItem(position: Int): CatalogCard = visibleItems[position]
        override fun getItemId(position: Int): Long {
            val id = getItem(position).movie?.id ?: getItem(position).series?.id
            return id?.toLongOrNull() ?: position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_movie, parent, false)
            val item = getItem(position)
            view.isFocusable = false
            view.isClickable = false
            view.findViewById<TextView>(R.id.moviePosterTitle).text = item.title
            view.findViewById<TextView>(R.id.moviePosterRating).apply {
                text = item.rating?.takeIf { it > 0.0 }?.let { "★ ${it.toInt()}" }.orEmpty()
                visibility = if (item.rating == null || item.rating <= 0.0) View.GONE else View.VISIBLE
            }
            posterLoader.load(
                item.image,
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