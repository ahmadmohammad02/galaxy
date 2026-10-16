package com.galaxytv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionExpiryTest {
    @Test
    fun parsesServerDurationInDays() {
        val activatedAt = 1_000_000L
        assertEquals(activatedAt + 538L * 86_400_000L, SubscriptionExpiry.expirationTime("538 Days", activatedAt))
    }

    @Test
    fun parsesHourDurationCaseInsensitively() {
        val activatedAt = 2_000_000L
        assertEquals(activatedAt + 12L * 3_600_000L, SubscriptionExpiry.expirationTime("12 HOURS", activatedAt))
    }

    @Test
    fun expiredSubscriptionExpiresImmediately() {
        val activatedAt = 3_000_000L
        assertEquals(activatedAt, SubscriptionExpiry.expirationTime("Expired", activatedAt))
    }

    @Test
    fun lifetimeSubscriptionDoesNotExpire() {
        assertEquals(Long.MAX_VALUE, SubscriptionExpiry.expirationTime("Lifetime", 0L))
    }

    @Test
    fun unknownExpiryFormatIsNotGuessed() {
        assertNull(SubscriptionExpiry.expirationTime("some server text", 0L))
    }
}