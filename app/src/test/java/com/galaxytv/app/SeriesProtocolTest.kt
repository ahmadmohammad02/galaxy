package com.galaxytv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesProtocolTest {
    @Test
    fun parsesSeriesMetadataAndRating() {
        val items = SeriesResponseParser.series(
            """[{"id":"1792","title":"Barbaroslar","icon":"poster.jpg","icon_big":"large.jpg","catid":"198","genre":"Action","plot":"Series summary","cast":"Lead actor","rating":"9","director":"Director","releaseDate":"2021-09-16"}]"""
        )

        assertEquals(1, items.size)
        assertEquals("Barbaroslar", items[0].title)
        assertEquals("large.jpg", items[0].iconBig)
        assertEquals(9.0, items[0].rating!!, 0.0)
        assertEquals("2021-09-16", items[0].releaseDate)
    }

    @Test
    fun parsesEpisodesAndOrdersBySeasonThenEpisode() {
        val episodes = SeriesResponseParser.episodes(
            """[
              {"season_num":"2","episode_num":1,"episode_name":"S02E01","stream_url":"https://example.com/s2e1.mp4"},
              {"season_num":"1","episode_num":2,"episode_name":"S01E02","stream_url":"https://example.com/s1e2.mp4"},
              {"season_num":"1","episode_num":1,"episode_name":"S01E01","stream_url":"https://example.com/s1e1.mp4"}
            ]"""
        )

        assertEquals(listOf("S01E01", "S01E02", "S02E01"), episodes.map { it.episodeName })
        assertTrue(episodes.all { it.streamUrl.endsWith(".mp4") })
    }

    @Test
    fun skipsEpisodesWithoutStreamUrl() {
        val episodes = SeriesResponseParser.episodes(
            """[{"season_num":"1","episode_num":1,"episode_name":"Missing URL"}]"""
        )

        assertTrue(episodes.isEmpty())
    }
}