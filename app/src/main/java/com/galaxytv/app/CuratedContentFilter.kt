package com.galaxytv.app

import java.util.Locale

enum class CuratedContentMode {
    KIDS,
    RAMADAN
}

object CuratedContentFilter {
    fun matchesCategory(mode: CuratedContentMode, parentName: String, subCategoryName: String): Boolean {
        return matches(mode, "$parentName $subCategoryName")
    }

    fun matchesSeries(mode: CuratedContentMode, item: SeriesItem): Boolean {
        val searchable = when (mode) {
            CuratedContentMode.KIDS -> "${item.genre} ${item.title}"
            CuratedContentMode.RAMADAN -> "${item.title} ${item.genre} ${item.plot}"
        }
        return matches(mode, searchable)
    }

    private fun matches(mode: CuratedContentMode, value: String): Boolean {
        if (mode == CuratedContentMode.RAMADAN) {
            val normalized = value.lowercase(Locale.ROOT)
                .replace("[\\u064B-\\u065F\\u0670\\u0640]".toRegex(), "")
                .replace("[^\\p{L}\\p{N}]".toRegex(), "")
            return listOf(
                "ramadan", "ramadhan", "ramadaan", "ramazaan", "ramazan", "ramzan", "ramdhan",
                "رمضان", "رمظان", "رمضن"
            ).any(normalized::contains)
        }

        val keywords = listOf(
            "kid", "child", "family", "animation", "cartoon", "أطفال", "اطفال", "عائلي", "كرتون", "رسوم"
        )
        return keywords.any { value.contains(it, ignoreCase = true) }
    }
}