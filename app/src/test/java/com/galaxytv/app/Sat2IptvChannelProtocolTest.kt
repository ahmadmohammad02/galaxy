package com.galaxytv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Sat2IptvChannelProtocolTest {
    @Test
    fun normalPackagesIncludeEmbeddedLiveChannels() {
        val packages = ChannelPackageResponseParser.parse(
            """[{"pkg_id":"2","pkg_name":"ALGERIA","pkg_type":0,"pkg_channels":[{"stream_id":21,"stream_name":"News","stream_pkg_id":"2","stream_icon":"logo.png","stream_order":1,"stream_url":"https://example.com/live"}]}]""",
            useLite = false
        )

        assertEquals(1, packages.size)
        assertEquals("2", packages.single().id)
        assertEquals("https://example.com/live", packages.single().channels.single().streamUrl)
        assertEquals(false, packages.single().usesLiteChannels)
    }

    @Test
    fun normalPackagesCanBeReadFromServerPackagesObject() {
        val packages = ChannelPackageResponseParser.parse(
            """{"packages":[{"pkg_id":"2","pkg_name":"ALGERIA","pkg_type":0,"pkg_channels":[{"stream_id":21,"stream_name":"News","stream_pkg_id":"2","stream_icon":"logo.png","stream_order":1,"stream_url":"https://example.com/live"}]}]}""",
            useLite = false
        )

        assertEquals(1, packages.size)
        assertEquals("ALGERIA", packages.single().pkgName)
        assertEquals("News", packages.single().channels.single().streamDisplayName)
        assertEquals("https://example.com/live", packages.single().channels.single().streamUrl)
    }

    @Test
    fun litePackagesDoNotExpectEmbeddedChannels() {
        val packages = ChannelPackageResponseParser.parse(
            """[{"id":"2","pkg_name":"ALGERIA","pkg_icon":"logo.png"}]""",
            useLite = true
        )

        assertEquals("ALGERIA", packages.single().pkgName)
        assertEquals(emptyList<LiteChannel>(), packages.single().channels)
        assertEquals(true, packages.single().usesLiteChannels)
    }

    @Test
    fun litePackagesAcceptPackageIdWhenServerUsesNormalResponseField() {
        val packages = ChannelPackageResponseParser.parse(
            """[{"pkg_id":2,"pkg_name":"SPORTS"}]""",
            useLite = true
        )

        assertEquals("2", packages.single().id)
        assertEquals(true, packages.single().usesLiteChannels)
    }

    @Test
    fun sportsPackagesOnlyIncludesMatchingPackageNames() {
        val packages = listOf(
            LitePackage("1", "SPORT FRANCE", ""),
            LitePackage("2", "PAKISTAN", ""),
            LitePackage("3", "beIN SPORTS", ""),
            LitePackage("4", "رياضة عربية", "")
        )

        assertEquals(
            listOf("SPORT FRANCE", "beIN SPORTS", "رياضة عربية"),
            LitePackageCategory.sportsOnly(packages).map { it.pkgName }
        )
    }

    @Test
    fun matchingChannelNameIsFoundCaseInsensitive() {
        val list = listOf(
            Sat2IptvChannel(
                channelName = "beIN Sports 1 HD",
                angle = "",
                tp = null,
                pol = "",
                sid = null,
                streamUrl = "http://example.com/1"
            ),
            Sat2IptvChannel(
                channelName = "beIN Sports 2 HD",
                angle = "",
                tp = null,
                pol = "",
                sid = null,
                streamUrl = "http://example.com/2"
            )
        )

        val match = Sat2IptvChannelMatcher.findMatch("bein sports 1 hd", list)
        assertNotNull(match)
        assertEquals("http://example.com/1", match?.streamUrl)
    }

    @Test
    fun unmatchedChannelReturnsNull() {
        val list = listOf(
            Sat2IptvChannel(
                channelName = "beIN Sports 1 HD",
                angle = "",
                tp = null,
                pol = "",
                sid = null,
                streamUrl = "http://example.com/1"
            )
        )

        val match = Sat2IptvChannelMatcher.findMatch("unknown channel", list)
        assertNull(match)
    }

    @Test
    fun matchingDoesNotConfuseChannelNumbers() {
        val channels = listOf(
            Sat2IptvChannel("beIN Sports 10 HD", streamUrl = "http://example.com/10"),
            Sat2IptvChannel("beIN Sports 1 HD", streamUrl = "http://example.com/1")
        )

        assertEquals(
            "http://example.com/1",
            Sat2IptvChannelMatcher.findMatch("beIN Sports 1", channels)?.streamUrl
        )
    }

    @Test
    fun matchingSupportsUnicodeChannelNamesAndSuffixes() {
        val channels = listOf(
            Sat2IptvChannel("قناة الرياضة HD", streamUrl = "http://example.com/sports")
        )

        assertEquals(
            "http://example.com/sports",
            Sat2IptvChannelMatcher.findMatch("قناة الرياضة", channels)?.streamUrl
        )
    }
}
