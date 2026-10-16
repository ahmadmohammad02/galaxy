package com.galaxytv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedContentFilterTest {
    @Test
    fun kidsCategoriesMatchEnglishAndArabicLabels() {
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.KIDS, "ARAB KIDS", ""))
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.KIDS, "", "رسوم متحركة"))
        assertFalse(CuratedContentFilter.matchesCategory(CuratedContentMode.KIDS, "SPORT WORLD", ""))
    }

    @Test
    fun ramadanCategoriesMatchNamesWithoutOtherCategories() {
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "SERIE RAMADAN 2022", ""))
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "", "مسلسلات رمضان"))
        assertFalse(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "SERIE TUNISIE", ""))
    }

    @Test
    fun ramadanCategoriesMatchCommonSpellingAndArabicDiacriticVariants() {
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "Ramadhan Specials", ""))
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "Ramazan Series", ""))
        assertTrue(CuratedContentFilter.matchesCategory(CuratedContentMode.RAMADAN, "", "رَمَـضَان ٢٠٢٥"))
    }

    @Test
    fun kidsSeriesUseGenreMetadata() {
        val kidsSeries = SeriesItem(
            id = "1", title = "Ada Twist", icon = "", iconBig = "", categoryId = "190",
            genre = "Kids, Animation", plot = "", cast = "", rating = 8.0, director = "", releaseDate = ""
        )
        val drama = kidsSeries.copy(id = "2", title = "Drama", genre = "Drama")

        assertTrue(CuratedContentFilter.matchesSeries(CuratedContentMode.KIDS, kidsSeries))
        assertFalse(CuratedContentFilter.matchesSeries(CuratedContentMode.KIDS, drama))
    }

    @Test
    fun ramadanSeriesUseSeriesMetadata() {
        val ramadanSeries = SeriesItem(
            id = "1", title = "Ramadan Nights", icon = "", iconBig = "", categoryId = "1",
            genre = "Drama", plot = "A family during Ramadan", cast = "", rating = 7.0,
            director = "", releaseDate = ""
        )
        assertTrue(CuratedContentFilter.matchesSeries(CuratedContentMode.RAMADAN, ramadanSeries))
    }
}