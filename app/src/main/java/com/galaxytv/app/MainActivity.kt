package com.galaxytv.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {
    companion object {
        private const val EXPIRED_MESSAGE = "Your activation has expired. Please renew your code."
    }

    private var homeVisible = false
    private var expiryWatcher: ActivationExpiryWatcher? = null
    private var splashPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSplashScreen()
    }

    private fun showSplashScreen() {
        setContentView(R.layout.activity_splash)
        val splashVideo = findViewById<PlayerView>(R.id.splashVideo)
        val splashLogo = findViewById<ImageView>(R.id.splashLogo)
        splashPlayer = AppMediaPlayer.create(this).also { player ->
            splashVideo.player = player
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    splashVideo.visibility = View.VISIBLE
                    splashLogo.visibility = View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    splashVideo.visibility = View.GONE
                    splashLogo.visibility = View.VISIBLE
                }
            })
            player.setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.logo_video}"))
            player.prepare()
            player.playWhenReady = true
        }

        Handler(Looper.getMainLooper()).postDelayed({
            restoreSessionOrShowLogin()
        }, 4000)
    }

    private fun restoreSessionOrShowLogin() {
        releaseSplashPlayer()
        if (ActivationSession.activeCode(this) != null) {
            showHomeScreen()
        } else {
            val message = if (ActivationSession.consumeExpiredNotice(this)) EXPIRED_MESSAGE else null
            showLoginScreen(message)
        }
    }

    private fun showLoginScreen(message: String? = null) {
        homeVisible = false
        expiryWatcher?.stop()
        expiryWatcher = null
        setContentView(R.layout.activity_main)

        val codeInput = findViewById<EditText>(R.id.codeInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val macAddress = DeviceMacAddress.read() ?: getString(R.string.device_mac_unavailable)
        findViewById<TextView>(R.id.deviceMacAddress).text =
            getString(R.string.device_mac_label, macAddress)
        statusText.text = message.orEmpty()
        if (message != null) statusText.setTextColor(Color.parseColor("#FCA5A5"))

        loginButton.setOnClickListener {
            val enteredCode = codeInput.text.toString()

            if (!LoginCodeValidator.isValidCode(enteredCode)) {
                statusText.text = "Enter a valid 12-digit activation code."
                statusText.setTextColor(Color.parseColor("#FCA5A5"))
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            statusText.text = "Checking activation..."
            statusText.setTextColor(Color.parseColor("#CBD5E1"))
            Thread {
                val device = DeviceIdentity.read(this)
                val service = ActivationService()
                val response = service.activate(
                    code = enteredCode,
                    mac = device.macAddress,
                    sn = device.serialNumber,
                    chipId = device.chipId,
                    model = device.model,
                    firmwareVersion = device.firmwareVersion,
                    osdTest = "Hi this is test osd"
                )

                runOnUiThread {
                    loginButton.isEnabled = true
                    if (response == null) {
                        statusText.text = "Server is down, please try again later."
                        statusText.setTextColor(Color.parseColor("#FCA5A5"))
                        return@runOnUiThread
                    }

                    if (response.status == 100) {
                        ActivationSession.save(this, enteredCode, response.expire, response.userAgent)
                        if (ActivationSession.activeCode(this) == null) {
                            ActivationSession.consumeExpiredNotice(this)
                            statusText.text = EXPIRED_MESSAGE
                            statusText.setTextColor(Color.parseColor("#FCA5A5"))
                            return@runOnUiThread
                        }
                        val notices = listOf(response.message, response.expire, response.osd)
                            .filter { it.isNotBlank() }
                        if (notices.isNotEmpty()) {
                            Toast.makeText(this, notices.joinToString("\n"), Toast.LENGTH_LONG).show()
                        }
                        statusText.text = response.message.ifBlank { "Activation successful." }
                        statusText.setTextColor(Color.parseColor("#A7F3D0"))
                        Handler(Looper.getMainLooper()).postDelayed({
                            showHomeScreen()
                        }, 1_800)
                    } else {
                        if (response.message.contains("expir", ignoreCase = true)) {
                            ActivationSession.clear(this)
                            statusText.text = EXPIRED_MESSAGE
                        } else {
                            statusText.text = response.message.ifBlank { "Activation failed. Check your code." }
                        }
                        if (response.osd.isNotBlank()) {
                            Toast.makeText(this, response.osd, Toast.LENGTH_LONG).show()
                        }
                        statusText.setTextColor(Color.parseColor("#FCA5A5"))
                    }
                }
            }.start()
        }
    }

    private fun showHomeScreen() {
        homeVisible = true
        setContentView(R.layout.activity_home)
        findViewById<LinearLayout>(R.id.satIptvCard).apply {
            requestFocus()
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ChannelsActivity::class.java))
            }
        }
        findViewById<LinearLayout>(R.id.sportsCard).setOnClickListener {
            startActivity(Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_PACKAGE_CATEGORY, ChannelsActivity.CATEGORY_SPORTS)
            })
        }
        findViewById<LinearLayout>(R.id.moviesCard).setOnClickListener {
            startActivity(Intent(this, MoviesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.seriesCard).setOnClickListener {
            startActivity(Intent(this, SeriesActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.settingsCard).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.kidsCard).setOnClickListener {
            startActivity(Intent(this, CuratedCatalogActivity::class.java).apply {
                putExtra(CuratedCatalogActivity.EXTRA_CONTENT_MODE, CuratedContentMode.KIDS.name)
            })
        }
        findViewById<LinearLayout>(R.id.ramadanCard).setOnClickListener {
            startActivity(Intent(this, CuratedCatalogActivity::class.java).apply {
                putExtra(CuratedCatalogActivity.EXTRA_CONTENT_MODE, CuratedContentMode.RAMADAN.name)
            })
        }
        expiryWatcher = ActivationExpiryWatcher(this) { showExpiredSession() }.also { it.start() }
    }

    private fun showExpiredSession() {
        ActivationSession.consumeExpiredNotice(this)
        Toast.makeText(this, EXPIRED_MESSAGE, Toast.LENGTH_LONG).show()
        showLoginScreen(EXPIRED_MESSAGE)
    }

    override fun onResume() {
        super.onResume()
        if (homeVisible && ActivationSession.activeCode(this) == null) {
            val message = if (ActivationSession.consumeExpiredNotice(this)) {
                EXPIRED_MESSAGE
            } else {
                "Please sign in to continue."
            }
            showLoginScreen(message)
        }
    }

    override fun onPause() {
        expiryWatcher?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        expiryWatcher?.stop()
        releaseSplashPlayer()
        super.onDestroy()
    }

    private fun releaseSplashPlayer() {
        splashPlayer?.release()
        splashPlayer = null
    }
}
