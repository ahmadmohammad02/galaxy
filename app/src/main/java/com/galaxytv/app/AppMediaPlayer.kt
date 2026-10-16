package com.galaxytv.app

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

object AppMediaPlayer {
    fun create(context: Context): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                ActivationSession.userAgent(context)
                    ?: DeviceProfile.currentIdentity().activationUserAgent
            )
            .setDefaultRequestProperties(mapOf("Accept-Encoding" to "gzip, deflate"))
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setRenderersFactory(renderersFactory)
            .build()
    }
}
