package com.galaxytv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MoviesProtocolTest {
    @Test
    fun categoryParserUsesSubcategoryIdsAndSortOrder() {
        val categories = MovieResponseParser.categories(
            """[
              {"cat_id":"158","cat_name":"English","cat_view_order":"6","sub_cats":[
                {"sub_id":"159","sub_name":"English Action","sub_view_order":"2"}
              ]},
              {"cat_id":"118","cat_name":"Kids","cat_view_order":"1","sub_cats":[]}
            ]"""
        )

        assertEquals("Kids", categories[0].name)
        assertEquals("159", categories[1].subCategories[0].id)
        assertEquals("English Action", categories[1].subCategories[0].name)
    }

    @Test
    fun movieListParserSortsMoviesAndRetainsStreamUrl() {
        val movies = MovieResponseParser.movies(
            """[
              {"id":"2","title":"Second","catid":"10","view_order":2,"stream_url":"http://example.com/2.mp4"},
              {"id":"1","title":"First","catid":"10","view_order":1,"stream_url":"http://example.com/1.mp4"}
            ]"""
        )

        assertEquals("1", movies.first().id)
        assertEquals("http://example.com/1.mp4", movies.first().streamUrl)
    }

    @Test
    fun movieInfoParsesPlaybackAndDetailFields() {
        val info = MovieResponseParser.movieInfo(
            """[{"id":"138468","stream_display_name":"Sergio 2020","stream_url":"http://example.com/movie.mp4","plot":"A story","duration":"01:58:39","bitrate":1358,"rating":6.7}]"""
        )

        assertNotNull(info)
        assertEquals("Sergio 2020", info?.title)
        assertEquals("http://example.com/movie.mp4", info?.streamUrl)
        assertEquals("A story", info?.plot)
        assertEquals(1358, info?.bitrate)
        assertEquals(6.7, info?.rating!!, 0.0)
    }

    @Test
    fun movieInfoReturnsNullForEmptyResponse() {
        assertNull(MovieResponseParser.movieInfo("[]"))
    }
}