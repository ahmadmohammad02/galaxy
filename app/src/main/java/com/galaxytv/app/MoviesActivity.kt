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

private data class MovieCategoryEntry(val id: String, val label: String)

class MoviesActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val posterLoader = MoviePosterLoader()
    private lateinit var activeCode: String
    private lateinit var categoriesList: ListView
    private lateinit var movieGrid: GridView
    private lateinit var categoryStatus: TextView
    private lateinit var gridTitle: TextView
    private lateinit var gridStatus: TextView
    private lateinit var searchInput: EditText
    private lateinit var categoryAdapter: ArrayAdapter<String>
    private lateinit var movieAdapter: MovieGridAdapter
    private var categoryEntries = emptyList<MovieCategoryEntry>()
    private var movies = emptyList<MovieItem>()
    private var requestGeneration = 0
    private var expiryWatcher: ActivationExpiryWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeCode = ActivationSession.activeCode(this).orEmpty()
        if (!LoginCodeValidator.isValidCode(activeCode)) {
            Toast.makeText(this, "Activate your code to browse movies.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_movies)
        findViewById<RadioGroup>(R.id.catalogSourceGroup).visibility = View.GONE
        categoriesList = findViewById(R.id.movieCategoryList)
        categoriesList.requestFocus()
        movieGrid = findViewById(R.id.movieGrid)
        categoryStatus = findViewById(R.id.movieCategoryStatus)
        gridTitle = findViewById(R.id.movieGridTitle)
        gridStatus = findViewById(R.id.movieGridStatus)
        searchInput = findViewById(R.id.movieSearchInput)
        categoryAdapter = createTextAdapter(15f)
        categoriesList.adapter = categoryAdapter
        movieAdapter = MovieGridAdapter()
        movieGrid.adapter = movieAdapter
        movieGrid.emptyView = findViewById(R.id.movieGridEmpty)
        movieGrid.setOnItemClickListener { _, _, position, _ ->
            movieAdapter.visibleMovies.getOrNull(position)?.let(::openMovieDetails)
        }

        categoriesList.setOnItemClickListener { _, _, position, _ ->
            categoryEntries.getOrNull(position)?.let(::loadMovies)
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                movieAdapter.filter(text?.toString().orEmpty())
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
            val result = MoviesService().fetchCategories(activeCode)
            val entries = result.flatMap { category ->
                category.subCategories.map { subCategory ->
                    val name = if (subCategory.name.equals(category.name, ignoreCase = true)) {
                        subCategory.name
                    } else {
                        "${category.name} / ${subCategory.name}"
                    }
                    MovieCategoryEntry(subCategory.id, "${subCategory.id.padStart(3, '0')} | $name")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<ImageButton>(R.id.refreshMovieCategories).isEnabled = true
                categoryEntries = entries
                categoryAdapter.clear()
                categoryAdapter.addAll(entries.map { it.label })
                categoryStatus.text = if (entries.isEmpty()) "No categories available." else "Categories"
                if (entries.isNotEmpty()) {
                    categoriesList.setSelection(0)
                    categoriesList.setItemChecked(0, true)
                    categoriesList.post { categoriesList.requestFocus() }
                    loadMovies(entries.first())
                } else {
                    showMovies(emptyList())
                }
            }
        }
    }

    private fun loadMovies(category: MovieCategoryEntry) {
        categoriesList.setItemChecked(categoryEntries.indexOf(category), true)
        gridTitle.text = category.label.substringAfter(" | ")
        gridStatus.text = "Loading movies..."
        showMovies(emptyList())
        val generation = ++requestGeneration
        executor.execute {
            val result = MoviesService().fetchMovies(activeCode, category.id)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != requestGeneration) return@runOnUiThread
                showMovies(result)
                if (movieAdapter.count > 0) movieGrid.setSelection(0)
                gridStatus.text = if (result.isEmpty()) "No movies in this category." else "${result.size} movies"
            }
        }
    }

    private fun showMovies(items: List<MovieItem>) {
        movies = items
        movieAdapter.filter(searchInput.text.toString())
    }

    private fun openMovieDetails(movie: MovieItem) {
        startActivity(Intent(this, MovieDetailsActivity::class.java).apply {
            putExtra(MovieDetailsActivity.EXTRA_MOVIE_ID, movie.id)
            putExtra(MovieDetailsActivity.EXTRA_MOVIE_TITLE, movie.title)
            putExtra(MovieDetailsActivity.EXTRA_MOVIE_IMAGE, movie.icon)
            putExtra(MovieDetailsActivity.EXTRA_MOVIE_URL, movie.streamUrl)
            putExtra(MovieDetailsActivity.EXTRA_MOVIE_CATEGORY, movie.categoryId)
        })
    }

    private fun createTextAdapter(size: Float): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? TextView) ?: TextView(this@MoviesActivity)
                row.text = getItem(position).orEmpty()
                row.setTextColor(Color.WHITE)
                row.textSize = size
                row.setPadding(12, 12, 10, 12)
                row.minHeight = 54
                return row
            }
        }
    }

    private inner class MovieGridAdapter : BaseAdapter() {
        var visibleMovies = emptyList<MovieItem>()
            private set
        private val inflater = LayoutInflater.from(this@MoviesActivity)

        fun filter(query: String) {
            visibleMovies = if (query.isBlank()) movies else movies.filter {
                it.title.contains(query.trim(), ignoreCase = true)
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visibleMovies.size
        override fun getItem(position: Int): MovieItem = visibleMovies[position]
        override fun getItemId(position: Int): Long = getItem(position).id.toLongOrNull() ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_movie, parent, false)
            val movie = getItem(position)
            view.isFocusable = false
            view.isClickable = false
            view.findViewById<TextView>(R.id.moviePosterTitle).text = movie.title
            view.findViewById<TextView>(R.id.moviePosterRating).visibility = View.GONE
            posterLoader.load(
                movie.icon,
                view.findViewById(R.id.moviePosterImage),
                view.findViewById(R.id.moviePosterPlaceholder)
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