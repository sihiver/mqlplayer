package com.sihiver.mqltv.tv

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.sihiver.mqltv.MainActivity
import com.sihiver.mqltv.PlayerActivityExo
import com.sihiver.mqltv.R
import com.sihiver.mqltv.model.Channel as IptvChannel
import com.sihiver.mqltv.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Saluran rekomendasi di beranda Android TV (layar utama).
 * Menampilkan daftar channel IPTV yang terakhir ditonton.
 *
 * @see <a href="https://developer.android.com/training/tv/discovery/recommendations-channel">Saluran di layar utama</a>
 */
object TvHomeRecommendations {

    private const val TAG = "TvHomeRecommendations"
    private const val PREFS = "tv_home_recommendations"
    private const val KEY_CHANNEL_ID = "preview_channel_id"
    private const val KEY_BROWSABLE_REQUESTED = "browsable_requested"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Sinkronkan saluran + program di background (panggil setelah menonton / onResume). */
    fun syncAsync(context: Context) {
        if (!isSupported(context)) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching { ensureChannelAndSyncPrograms(appContext) }
                .onFailure { Log.e(TAG, "syncAsync failed", it) }
        }
    }

    /** Dipanggil dari [TvProgramsInitializeReceiver] saat app diinstal di TV. */
    fun syncBlocking(context: Context) {
        if (!isSupported(context)) return
        runCatching { ensureChannelAndSyncPrograms(context.applicationContext) }
            .onFailure { Log.e(TAG, "syncBlocking failed", it) }
    }

    /**
     * Minta sistem menampilkan saluran default di beranda (hanya saat activity di foreground).
     */
    fun requestChannelBrowsableIfNeeded(activity: Activity) {
        if (!isSupported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BROWSABLE_REQUESTED, false)) return

        val channelId = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (channelId <= 0L) return

        try {
            TvContractCompat.requestChannelBrowsable(activity, channelId)
            prefs.edit().putBoolean(KEY_BROWSABLE_REQUESTED, true).apply()
            Log.d(TAG, "requestChannelBrowsable for channelId=$channelId")
        } catch (e: Exception) {
            Log.w(TAG, "requestChannelBrowsable failed", e)
        }
    }

    private fun ensureChannelAndSyncPrograms(context: Context) {
        ChannelRepository.loadChannels(context)
        ChannelRepository.loadRecentlyWatched(context)
        val recentlyWatched = ChannelRepository.getRecentlyWatched()
        if (recentlyWatched.isEmpty()) {
            Log.d(TAG, "No recently watched channels — skip TV home sync")
            return
        }

        val channelId = getOrCreatePreviewChannelId(context)
        syncPreviewPrograms(context, channelId, recentlyWatched)
        Log.d(TAG, "Synced ${recentlyWatched.size} program(s) to TV home channel $channelId")
    }

    private fun getOrCreatePreviewChannelId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_CHANNEL_ID, -1L)
        if (savedId > 0L && previewChannelExists(context, savedId)) {
            return savedId
        }

        val appLinkIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val builder = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(R.string.tv_channel_recently_watched))
            .setDescription(context.getString(R.string.tv_channel_recently_watched_desc))
            .setAppLinkIntentUri(Uri.parse(appLinkIntent.toUri(Intent.URI_INTENT_SCHEME)))

        val channelUri = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            builder.build().toContentValues(),
        ) ?: throw IllegalStateException("Failed to insert TV preview channel")

        val channelId = ContentUris.parseId(channelUri)
        storeChannelLogo(context, channelId)
        prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply()
        Log.d(TAG, "Created TV preview channel id=$channelId")
        return channelId
    }

    private fun previewChannelExists(context: Context, channelId: Long): Boolean {
        return try {
            context.contentResolver.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun storeChannelLogo(context: Context, channelId: Long) {
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_banner)
            if (bitmap != null) {
                ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "storeChannelLogo bitmap failed", e)
        }
        val logoUri = Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_banner}")
        ChannelLogoUtils.storeChannelLogo(context, channelId, logoUri)
    }

    private fun syncPreviewPrograms(
        context: Context,
        tvChannelId: Long,
        channels: List<IptvChannel>,
    ) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(tvChannelId),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear old preview programs", e)
        }

        val defaultPoster = Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_banner}")

        channels.forEachIndexed { index, ch ->
            val playIntent = Intent(context, PlayerActivityExo::class.java).apply {
                putExtra("CHANNEL_ID", ch.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val posterUri = ch.logo.trim().takeIf { it.startsWith("http", ignoreCase = true) }
                ?.let { Uri.parse(it) }
                ?: defaultPoster

            val programBuilder = PreviewProgram.Builder()
                .setChannelId(tvChannelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setTitle(ch.name)
                .setDescription(
                    ch.category.trim().ifBlank { context.getString(R.string.tv_program_live_tv) },
                )
                .setPosterArtUri(posterUri)
                .setIntentUri(Uri.parse(playIntent.toUri(Intent.URI_INTENT_SCHEME)))
                .setInternalProviderId("mqltv_ch_${ch.id}")
                // Semakin besar weight = tampil lebih awal; index 0 = terakhir ditonton
                .setWeight(1_000 - index)

            try {
                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    programBuilder.build().toContentValues(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert preview program for ${ch.name}", e)
            }
        }
    }
}
