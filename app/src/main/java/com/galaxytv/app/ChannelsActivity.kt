package com.galaxytv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object SatChannelReceiverContract {
    const val ACTION_SCRAMBLED_CHANNEL_SELECTED =
        "com.galaxytv.app.action.SCRAMBLED_CHANNEL_SELECTED"
    const val EXTRA_CHANNEL_NAME = "com.galaxytv.app.extra.CHANNEL_NAME"
}

class ChannelsActivity : AppCompatActivity() {
    companion object {
        const val PREFERENCES_NAME = ActivationSession.PREFERENCES_NAME
        const val KEY_ACTIVE_CODE = ActivationSession.KEY_ACTIVE_CODE
        const val EXTRA_PACKAGE_CATEGORY = "package_category"
        const val CATEGORY_SPORTS = "sports"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var activeCode: String
    private lateinit var statusText: TextView
    private lateinit var playerView: PlayerView
    private lateinit var channelPanels: View
    private lateinit var packageAdapter: ArrayAdapter<String>
    private lateinit var channelAdapter: ArrayAdapter<String>
    private val logoLoader = MoviePosterLoader()
    private var litePackages = emptyList<LitePackage>()
    private var liteChannels = emptyList<LiteChannel>()
    private var satChannelsCache: List<Sat2IptvChannel>? = null
    private var satLookupInProgress = false
    private var channelRequestGeneration = 0
    private var selectedLiteChannelId: Long? = null
    private var expiryWatcher: ActivationExpiryWatcher? = null
    private var player: ExoPlayer? = null
    private var sportsOnly = false
    private lateinit var device: DeviceIdentity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeCode = ActivationSession.activeCode(this).orEmpty()
        device = DeviceIdentity.read(this)
        if (!LoginCodeValidator.isValidCode(activeCode)) {
            val message = if (ActivationSession.consumeExpiredNotice(this)) {
                "Your activation has expired. Please sign in again."
            } else {
                "Activate your code to access channels."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_channels)
        sportsOnly = intent.getStringExtra(EXTRA_PACKAGE_CATEGORY) == CATEGORY_SPORTS
        statusText = findViewById(R.id.channelStatus)
        playerView = findViewById(R.id.channelsVideoView)
        channelPanels = findViewById(R.id.channelPanels)
        findViewById<TextView>(R.id.packagePaneTitle).text = if (sportsOnly) "SPORTS PACKAGES" else "PACKAGES"
        player = AppMediaPlayer.create(this).also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && channelPanels.visibility == View.VISIBLE) {
                        statusText.text = "Playing channel"
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    statusText.text = "Unable to play this channel."
                    statusText.visibility = View.VISIBLE
                    showChannelPanels()
                }
            })
        }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE && channelPanels.visibility != View.VISIBLE) {
                    showChannelPanels()
                }
            }
        )
        playerView.setOnClickListener {
            if (channelPanels.visibility == View.VISIBLE) hideChannelPanels()
            else showChannelPanels()
        }
        val packageList = findViewById<ListView>(R.id.litePackageList)
        val channelList = findViewById<ListView>(R.id.liteChannelList)
        packageAdapter = createDarkAdapter { litePackages.getOrNull(it)?.pkgIcon.orEmpty() }
        channelAdapter = createDarkAdapter { liteChannels.getOrNull(it)?.streamIcon.orEmpty() }
        packageList.adapter = packageAdapter
        channelList.adapter = channelAdapter

        packageList.setOnItemClickListener { _, _, position, _ ->
            litePackages.getOrNull(position)?.let(::loadLiteChannels)
        }
        channelList.setOnItemClickListener { _, _, position, _ ->
            val channel = liteChannels.getOrNull(position) ?: return@setOnItemClickListener
            channelList.setItemChecked(position, true)
            if (selectedLiteChannelId == channel.id) {
                hideChannelPanels()
            } else {
                selectedLiteChannelId = channel.id
                openPlayer(channel.streamDisplayName, channel.streamUrl)
            }
        }

        val requestedChannel = intent.getStringExtra(SatChannelReceiverContract.EXTRA_CHANNEL_NAME)
        if (requestedChannel.isNullOrBlank()) {
            loadLitePackages()
        } else {
            findSatChannel(requestedChannel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(SatChannelReceiverContract.EXTRA_CHANNEL_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.let { channelName ->
                findSatChannel(channelName)
            }
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.stop()
        super.onPause()
    }

    private fun findSatChannel(scrambledName: String) {
        val name = scrambledName.trim()
        if (name.isEmpty()) {
            statusText.text = "Enter a channel name first."
            return
        }
        if (satLookupInProgress) return

        satLookupInProgress = true
        statusText.text = "Searching for channel..."
        executor.execute {
            val channels = satChannelsCache ?: createService().fetchChannels(
                code = activeCode,
                mac = device.macAddress,
                sn = device.serialNumber,
                chipId = device.chipId,
                model = device.model,
                firmwareVersion = device.firmwareVersion
            ).also { if (it.isNotEmpty()) satChannelsCache = it }
            val match = Sat2IptvChannelMatcher.findMatch(name, channels)
            runOnUiThread {
                satLookupInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (match == null) {
                    statusText.text = "No matching channel found or the server is unavailable."
                } else {
                    openPlayer(match.channelName, match.streamUrl)
                }
            }
        }
    }

    private fun loadLitePackages() {
        statusText.text = "Loading packages..."
        executor.execute {
            val result = createService().fetchPackages(
                code = activeCode,
                mac = device.macAddress,
                sn = device.serialNumber,
                chipId = device.chipId,
                model = device.model,
                firmwareVersion = device.firmwareVersion
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                litePackages = if (sportsOnly) LitePackageCategory.sportsOnly(result) else result
                packageAdapter.clear()
                packageAdapter.addAll(litePackages.mapIndexed { index, pkg ->
                    "${(index + 1).toString().padStart(2, '0')}  ${pkg.pkgName}"
                })
                statusText.text = if (litePackages.isEmpty()) {
                    if (sportsOnly) "No sports packages are currently available."
                    else "No packages are currently available."
                } else {
                    "Loading the first package..."
                }
                if (litePackages.isNotEmpty()) {
                    findViewById<ListView>(R.id.litePackageList).setItemChecked(0, true)
                    loadLiteChannels(litePackages.first(), autoPlayFirst = true)
                }
            }
        }
    }

    private fun loadLiteChannels(pkg: LitePackage, autoPlayFirst: Boolean = false) {
        val generation = ++channelRequestGeneration
        selectedLiteChannelId = null
        statusText.text = "Loading ${pkg.pkgName}..."
        channelAdapter.clear()
        executor.execute {
            val service = createService()
            val result = if (pkg.usesLiteChannels || pkg.channels.isEmpty()) {
                val channels = service.fetchLiteChannels(
                    code = activeCode,
                    mac = device.macAddress,
                    sn = device.serialNumber,
                    chipId = device.chipId,
                    model = device.model,
                    firmwareVersion = device.firmwareVersion,
                    pkgId = pkg.id
                )
                if (channels.isNotEmpty()) {
                    channels.sortedBy { it.viewOrder }
                } else {
                    service.fetchPackages(
                        code = activeCode,
                        mac = device.macAddress,
                        sn = device.serialNumber,
                        chipId = device.chipId,
                        model = device.model,
                        firmwareVersion = device.firmwareVersion
                    ).firstOrNull {
                        it.id == pkg.id || it.pkgName.equals(pkg.pkgName, ignoreCase = true)
                    }?.channels.orEmpty().sortedBy { it.viewOrder }
                }
            } else {
                pkg.channels.takeIf { it.isNotEmpty() }?.sortedBy { it.viewOrder }
                    ?: service.fetchLiteChannels(
                        code = activeCode,
                        mac = device.macAddress,
                        sn = device.serialNumber,
                        chipId = device.chipId,
                        model = device.model,
                        firmwareVersion = device.firmwareVersion,
                        pkgId = pkg.id
                    ).sortedBy { it.viewOrder }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != channelRequestGeneration) return@runOnUiThread
                liteChannels = result
                channelAdapter.clear()
                channelAdapter.addAll(result.map { it.streamDisplayName })
                if (result.isNotEmpty() && autoPlayFirst) {
                    val firstChannel = result.first()
                    findViewById<ListView>(R.id.liteChannelList).apply {
                        setItemChecked(0, true)
                        requestFocus()
                    }
                    openPlayer(firstChannel.streamDisplayName, firstChannel.streamUrl)
                } else if (result.isEmpty() && autoPlayFirst) {
                    val nextPackage = litePackages.getOrNull(litePackages.indexOf(pkg) + 1)
                    if (nextPackage != null) {
                        statusText.text = "Package is empty. Loading the next package..."
                        loadLiteChannels(nextPackage, autoPlayFirst = true)
                    } else {
                        statusText.text = "No channels are currently available."
                    }
                } else {
                    statusText.text = if (result.isEmpty()) {
                        "Could not load this package's channels, or the package is empty."
                    } else {
                        "Select a channel, then select it again to play."
                    }
                    if (result.isNotEmpty()) findViewById<ListView>(R.id.liteChannelList).requestFocus()
                }
            }
        }
    }

    private fun openPlayer(name: String, streamUrl: String) {
        val uri = Uri.parse(streamUrl)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.text = "The channel URL is invalid."
            statusText.visibility = View.VISIBLE
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText.visibility = View.VISIBLE
        statusText.text = "Loading $name..."
        player?.apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && currentMediaItem?.localConfiguration?.uri == uri) {
                        statusText.text = "Playing $name"
                        removeListener(this)
                    }
                }
            })
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    private fun hideChannelPanels() {
        channelPanels.visibility = View.GONE
        statusText.visibility = View.GONE
        playerView.hideController()
    }

    private fun showChannelPanels() {
        channelPanels.visibility = View.VISIBLE
        findViewById<ListView>(R.id.liteChannelList).requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::channelPanels.isInitialized &&
            channelPanels.visibility != View.VISIBLE &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER
            )
        ) {
            showChannelPanels()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun createService() = Sat2IptvService()

    private fun createDarkAdapter(iconAt: (Int) -> String): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_channel_list, parent, false)
                row.findViewById<TextView>(R.id.channelListName).text = getItem(position).orEmpty()
                logoLoader.load(
                    iconAt(position),
                    row.findViewById<ImageView>(R.id.channelListIcon),
                    R.drawable.logo_icon
                )
                return row
            }
        }
    }

    override fun onDestroy() {
        expiryWatcher?.stop()
        executor.shutdownNow()
        logoLoader.close()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}