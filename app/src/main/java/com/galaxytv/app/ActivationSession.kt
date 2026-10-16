package com.galaxytv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SubscriptionExpiry {
    private val durationPattern = Regex("(\\d+)\\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)", RegexOption.IGNORE_CASE)
    private val datePatterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy")

    fun expirationTime(expire: String, activatedAt: Long): Long? {
        val normalized = expire.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        if (listOf("lifetime", "unlimited", "forever", "never").any(normalized::contains)) {
            return Long.MAX_VALUE
        }
        if (listOf("expired", "inactive").any(normalized::contains)) return activatedAt

        val duration = durationPattern.find(normalized)
        if (duration != null) {
            val amount = duration.groupValues[1].toLongOrNull() ?: return null
            val unit = duration.groupValues[2].lowercase(Locale.ROOT)
            val multiplier = when {
                unit.startsWith("second") -> 1_000L
                unit.startsWith("minute") -> 60_000L
                unit.startsWith("hour") -> 3_600_000L
                unit.startsWith("day") -> 86_400_000L
                unit.startsWith("week") -> 604_800_000L
                unit.startsWith("month") -> 2_592_000_000L
                else -> 31_536_000_000L
            }
            val durationMs = if (amount > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else amount * multiplier
            return if (durationMs == Long.MAX_VALUE || activatedAt > Long.MAX_VALUE - durationMs) {
                Long.MAX_VALUE
            } else {
                activatedAt + durationMs
            }
        }

        for (pattern in datePatterns) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }
            val parsed = runCatching { formatter.parse(expire) }.getOrNull() ?: continue
            val dateEnd = if (pattern == "yyyy-MM-dd" || pattern == "dd/MM/yyyy" || pattern == "dd-MM-yyyy") {
                parsed.time + 86_399_999L
            } else {
                parsed.time
            }
            return dateEnd
        }
        return null
    }
}

object ActivationSession {
    const val PREFERENCES_NAME = "activation"
    const val KEY_ACTIVE_CODE = "active_code"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_EXPIRE_TEXT = "expire_text"
    const val KEY_USER_AGENT = "user_agent"
    private const val KEY_EXPIRED_NOTICE = "expired_notice"

    fun save(
        context: Context,
        code: String,
        expire: String,
        userAgent: String = "",
        now: Long = System.currentTimeMillis()
    ) {
        val expiresAt = SubscriptionExpiry.expirationTime(expire, now) ?: Long.MAX_VALUE
        preferences(context).edit()
            .putString(KEY_ACTIVE_CODE, code.trim())
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_EXPIRE_TEXT, expire)
            .putString(KEY_USER_AGENT, userAgent.trim())
            .putBoolean(KEY_EXPIRED_NOTICE, false)
            .apply()
    }

    fun activeCode(context: Context, now: Long = System.currentTimeMillis()): String? {
        val store = preferences(context)
        val code = store.getString(KEY_ACTIVE_CODE, null)?.trim()
        if (code.isNullOrEmpty() || !LoginCodeValidator.isValidCode(code)) return null

        val expiresAt = store.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= now) {
            store.edit().clear().putBoolean(KEY_EXPIRED_NOTICE, expiresAt > 0L).apply()
            return null
        }
        return code
    }

    fun expirationTime(context: Context): Long? {
        val expiresAt = preferences(context).getLong(KEY_EXPIRES_AT, 0L)
        return expiresAt.takeIf { it > 0L }
    }

    fun userAgent(context: Context): String? {
        return preferences(context).getString(KEY_USER_AGENT, null)?.takeIf { it.isNotBlank() }
    }

    fun consumeExpiredNotice(context: Context): Boolean {
        val store = preferences(context)
        val wasExpired = store.getBoolean(KEY_EXPIRED_NOTICE, false)
        if (wasExpired) store.edit().remove(KEY_EXPIRED_NOTICE).apply()
        return wasExpired
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

class ActivationExpiryWatcher(
    context: Context,
    private val onExpired: () -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var checkTask: Runnable? = null

    fun start() {
        stop()
        val task = object : Runnable {
            override fun run() {
                if (ActivationSession.activeCode(appContext) == null) {
                    onExpired()
                    return
                }

                val expiresAt = ActivationSession.expirationTime(appContext) ?: return
                if (expiresAt == Long.MAX_VALUE) return
                val remaining = expiresAt - System.currentTimeMillis()
                if (remaining <= 0L) {
                    ActivationSession.activeCode(appContext)
                    onExpired()
                } else {
                    handler.postDelayed(this, minOf(remaining, 60_000L))
                }
            }
        }
        checkTask = task
        task.run()
    }

    fun stop() {
        checkTask?.let(handler::removeCallbacks)
        checkTask = null
    }
}