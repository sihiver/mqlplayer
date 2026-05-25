package com.sihiver.mqltv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi

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
    private const val KEY_LABEL_FIX_VERSION = "label_fix_version"
    private const val LABEL_FIX_VERSION = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * Sekali setelah update label TV: hapus saluran lama di TvProvider agar launcher memuat ulang
     * metadata (ikon saluran memakai [application label] di perangkat TV).
     */
    fun applyLabelFixOnceIfNeeded(context: Context) {
        if (!isSupported(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LABEL_FIX_VERSION, 0) >= LABEL_FIX_VERSION) return
        prefs.edit { putInt(KEY_LABEL_FIX_VERSION, LABEL_FIX_VERSION) }
        resetAllPreviewChannels(context.applicationContext)
        Log.d(TAG, "Applied TV home label fix v$LABEL_FIX_VERSION — preview channel reset")
    }

    fun resetAllPreviewChannels(context: Context) {
        if (!isSupported(context)) return
        val appContext = context.applicationContext
        findAllOurChannelIds(appContext).forEach { deleteChannel(appContext, it) }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_CHANNEL_ID)
            putBoolean(KEY_BROWSABLE_REQUESTED, false)
        }
    }

    /** Sinkronkan saluran + program di background (panggil setelah menonton / onResume). */
    @UnstableApi
    fun syncAsync(context: Context, activity: Activity? = null) {
        if (!isSupported(context)) return
        val appContext = context.applicationContext
        scope.launch {
            syncMutex.withLock {
                runCatching {
                    ensureChannelAndSyncPrograms(
                        appContext,
                        fullReplacePrograms = false,
                        touchChannel = false,
                    )
                }.onFailure { Log.e(TAG, "syncAsync failed", it) }
            }
            activity?.let { act ->
                withContext(Dispatchers.Main) {
                    requestChannelBrowsableIfNeeded(act)
                }
            }
        }
    }

    /**
     * Sync agresif saat user ke Home / app background — Google TV Launcher baru refresh
     * baris preview setelah app tidak foreground (bukan cukup upsert saat masih nonton).
     */
    @UnstableApi
    fun syncForLauncherRefresh(context: Context) {
        if (!isSupported(context)) return
        val appContext = context.applicationContext
        scope.launch {
            syncMutex.withLock {
                runCatching {
                    ensureChannelAndSyncPrograms(
                        appContext,
                        fullReplacePrograms = true,
                        touchChannel = true,
                    )
                }.onFailure { Log.e(TAG, "syncForLauncherRefresh failed", it) }
            }
        }
    }

    /** Sama [syncForLauncherRefresh] tetapi blocking — dipanggil dari [Activity.onUserLeaveHint]. */
    @UnstableApi
    fun syncForLauncherRefreshBlocking(context: Context) {
        if (!isSupported(context)) return
        runBlocking {
            withContext(Dispatchers.IO) {
                syncMutex.withLock {
                    runCatching {
                        ensureChannelAndSyncPrograms(
                            context.applicationContext,
                            fullReplacePrograms = true,
                            touchChannel = true,
                        )
                    }.onFailure { Log.e(TAG, "syncForLauncherRefreshBlocking failed", it) }
                }
            }
        }
    }

    /** Dipanggil dari [TvProgramsInitializeReceiver] saat app diinstal di TV. */
    @UnstableApi
    fun syncBlocking(context: Context) {
        if (!isSupported(context)) return
        runBlocking {
            syncMutex.withLock {
                runCatching {
                    ensureChannelAndSyncPrograms(
                        context.applicationContext,
                        fullReplacePrograms = true,
                        touchChannel = true,
                    )
                }.onFailure { Log.e(TAG, "syncBlocking failed", it) }
            }
        }
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
            prefs.edit { putBoolean(KEY_BROWSABLE_REQUESTED, true) }
            Log.d(TAG, "requestChannelBrowsable for channelId=$channelId")
        } catch (e: Exception) {
            Log.w(TAG, "requestChannelBrowsable failed", e)
        }
    }

    /**
     * Kembalikan semua channel preview milik package ini dari TvProvider.
     */
    @SuppressLint("RestrictedApi")
    private fun findAllOurChannelIds(context: Context): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID),
                null, null, null
            )?.use { cursor ->
                val colId = cursor.getColumnIndex(TvContractCompat.Channels._ID)
                while (cursor.moveToNext()) {
                    if (colId >= 0) ids.add(cursor.getLong(colId))
                }
            }
        } catch (_: Exception) {}
        return ids
    }

    @UnstableApi
    private fun getOrCreatePreviewChannelId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(KEY_CHANNEL_ID, -1L)

        // Ambil semua channel milik app ini dari TvProvider
        val allIds = findAllOurChannelIds(context)
        Log.d(TAG, "TvProvider channels for our package: $allIds (savedId=$savedId)")

        // Kalau channel yang disimpan masih ada, hapus semua sisanya
        if (savedId > 0L && allIds.contains(savedId)) {
            val extras = allIds.filter { it != savedId }
            extras.forEach { deleteChannel(context, it) }
            if (extras.isNotEmpty()) Log.d(TAG, "Deleted ${extras.size} duplicate channel(s): $extras")
            return savedId
        }

        // savedId tidak valid — coba reuse channel pertama yang ada, hapus sisanya
        if (allIds.isNotEmpty()) {
            val reuse = allIds.first()
            val extras = allIds.drop(1)
            extras.forEach { deleteChannel(context, it) }
            if (extras.isNotEmpty()) Log.d(TAG, "Deleted ${extras.size} duplicate channel(s): $extras")
            prefs.edit { putLong(KEY_CHANNEL_ID, reuse) }
            Log.d(TAG, "Reusing existing TV preview channel id=$reuse")
            return reuse
        }

        // Tidak ada channel sama sekali — buat baru
        val channelUri = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            buildPreviewChannelValues(context),
        ) ?: throw IllegalStateException("Failed to insert TV preview channel")

        val channelId = ContentUris.parseId(channelUri)
        prefs.edit {
            putLong(KEY_CHANNEL_ID, channelId)
            putBoolean(KEY_BROWSABLE_REQUESTED, false) // paksa request browsable untuk channel baru
        }
        Log.d(TAG, "Created TV preview channel id=$channelId")
        return channelId
    }

    private fun deleteChannel(context: Context, channelId: Long) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildChannelUri(channelId), null, null
            )
            Log.d(TAG, "Deleted channel id=$channelId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete channel id=$channelId", e)
        }
    }

    private fun storeChannelLogo(context: Context, channelId: Long) {
        val bitmap = TvChannelLogoHelper.renderChannelIconBitmap(context)
        if (bitmap != null) {
            val ok = ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
            if (!bitmap.isRecycled) bitmap.recycle()
            Log.d(TAG, "storeChannelLogo id=$channelId ok=$ok")
        } else {
            Log.w(TAG, "storeChannelLogo: bitmap null, skip")
        }
    }

    @UnstableApi
    private fun ensureChannelAndSyncPrograms(
        context: Context,
        fullReplacePrograms: Boolean,
        touchChannel: Boolean,
    ) {
        ChannelRepository.loadChannels(context)
        if (ChannelRepository.getRecentlyWatched().isEmpty()) {
            ChannelRepository.loadRecentlyWatched(context)
        }
        val recentlyWatched = ChannelRepository.getRecentlyWatched()
        if (recentlyWatched.isEmpty()) {
            Log.d(TAG, "No recently watched channels — skip TV home sync")
            return
        }

        val channelId = getOrCreatePreviewChannelId(context)
        storeChannelLogo(context, channelId)
        // Selalu perbarui metadata (termasuk app_link_text) agar label ikon saluran benar di launcher.
        updatePreviewChannelMetadata(
            context,
            channelId,
            recentCount = recentlyWatched.size,
            includeSyncStamp = touchChannel,
        )
        syncPreviewPrograms(
            context,
            channelId,
            recentlyWatched,
            fullReplace = fullReplacePrograms,
        )
        notifyLauncherProgramsChanged(context, channelId)
        Log.d(
            TAG,
            "Synced ${recentlyWatched.size} program(s) to TV home channel $channelId " +
                "(fullReplace=$fullReplacePrograms touchChannel=$touchChannel)",
        )
    }

    /**
     * Metadata saluran preview. Di Google TV Launcher, label di ikon saluran biasanya dari
     * application label (lihat values-television/strings.xml), bukan hanya display_name.
     */
    @UnstableApi
    private fun buildPreviewChannelValues(
        context: Context,
        recentCount: Int = 0,
        includeSyncStamp: Boolean = false,
    ): android.content.ContentValues {
        val channelTitle = context.getString(R.string.tv_channel_recently_watched)
        val description = if (includeSyncStamp) {
            context.getString(R.string.tv_channel_recently_watched_desc) +
                " · $recentCount · ${System.currentTimeMillis()}"
        } else {
            context.getString(R.string.tv_channel_recently_watched_desc)
        }
        val appLinkIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val appLinkIntentUri = appLinkIntent.toUri(Intent.URI_INTENT_SCHEME).toUri()
        val iconUri = "android.resource://${context.packageName}/${R.mipmap.ic_channel}".toUri()

        val values = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(channelTitle)
            .setDescription(description)
            .setAppLinkIntentUri(appLinkIntentUri)
            .setAppLinkText(channelTitle)
            .setAppLinkIconUri(iconUri)
            .build()
            .toContentValues()

        // Pastikan kolom app_link terisi (untuk launcher yang membacanya).
        values.put(TvContractCompat.Channels.COLUMN_APP_LINK_TEXT, channelTitle)
        values.put(TvContractCompat.Channels.COLUMN_APP_LINK_ICON_URI, iconUri.toString())
        return values
    }

    @UnstableApi
    private fun updatePreviewChannelMetadata(
        context: Context,
        tvChannelId: Long,
        recentCount: Int,
        includeSyncStamp: Boolean,
    ) {
        try {
            context.contentResolver.update(
                TvContractCompat.buildChannelUri(tvChannelId),
                buildPreviewChannelValues(context, recentCount, includeSyncStamp),
                null,
                null,
            )
            logChannelMetadata(context, tvChannelId)
        } catch (e: Exception) {
            Log.w(TAG, "updatePreviewChannelMetadata failed", e)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun logChannelMetadata(context: Context, tvChannelId: Long) {
        try {
            context.contentResolver.query(
                TvContractCompat.buildChannelUri(tvChannelId),
                Channel.PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val ch = Channel.fromCursor(cursor)
                Log.d(
                    TAG,
                    "Channel $tvChannelId metadata: displayName=${ch.displayName}, " +
                        "appLinkText=${ch.appLinkText}, package=${ch.packageName}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "logChannelMetadata failed", e)
        }
    }

    /** Beri tahu launcher TV agar baris saluran di layar utama di-refresh segera. */
    private fun notifyLauncherProgramsChanged(context: Context, tvChannelId: Long) {
        try {
            val channelUri = TvContractCompat.buildChannelUri(tvChannelId)
            val programsUri = TvContractCompat.buildPreviewProgramsUriForChannel(tvChannelId)
            val flags = ContentResolver.NOTIFY_INSERT or
                ContentResolver.NOTIFY_UPDATE or
                ContentResolver.NOTIFY_DELETE
            context.contentResolver.notifyChange(channelUri, null, flags)
            context.contentResolver.notifyChange(programsUri, null, flags)
            context.contentResolver.notifyChange(TvContractCompat.PreviewPrograms.CONTENT_URI, null, flags)
            context.contentResolver.notifyChange(TvContractCompat.Channels.CONTENT_URI, null, flags)
        } catch (e: Exception) {
            Log.w(TAG, "notifyChange failed", e)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun loadExistingProgramIds(context: Context, tvChannelId: Long): Map<String, Long> {
        val map = linkedMapOf<String, Long>()
        try {
            context.contentResolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(tvChannelId),
                arrayOf(
                    TvContractCompat.PreviewPrograms._ID,
                    TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContractCompat.PreviewPrograms._ID)
                val providerCol =
                    cursor.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    if (idCol < 0 || providerCol < 0) continue
                    val providerId = cursor.getString(providerCol) ?: continue
                    map[providerId] = cursor.getLong(idCol)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load existing preview programs", e)
        }
        return map
    }

    @UnstableApi
    @SuppressLint("RestrictedApi")
    private fun syncPreviewPrograms(
        context: Context,
        tvChannelId: Long,
        channels: List<IptvChannel>,
        fullReplace: Boolean,
    ) {
        val cacheBust = System.currentTimeMillis()
        val existingByProviderId = loadExistingProgramIds(context, tvChannelId)

        if (fullReplace) {
            clearAllPreviewPrograms(context, tvChannelId, existingByProviderId)
            insertPreviewPrograms(context, tvChannelId, channels, cacheBust, existingByProviderId = emptyMap())
            return
        }

        val desiredProviderIds = channels.map { internalProviderId(it.id) }.toSet()
        for ((providerId, programId) in existingByProviderId) {
            if (providerId !in desiredProviderIds) {
                deletePreviewProgram(context, programId, providerId)
            }
        }
        insertPreviewPrograms(context, tvChannelId, channels, cacheBust, existingByProviderId)
    }

    private fun clearAllPreviewPrograms(
        context: Context,
        tvChannelId: Long,
        existingByProviderId: Map<String, Long>,
    ) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(tvChannelId),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bulk delete preview programs failed, deleting one-by-one", e)
            for ((providerId, programId) in existingByProviderId) {
                deletePreviewProgram(context, programId, providerId)
            }
        }
    }

    private fun deletePreviewProgram(context: Context, programId: Long, providerId: String) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(programId),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete program $providerId", e)
        }
    }

    @UnstableApi
    @SuppressLint("RestrictedApi")
    private fun insertPreviewPrograms(
        context: Context,
        tvChannelId: Long,
        channels: List<IptvChannel>,
        cacheBust: Long,
        existingByProviderId: Map<String, Long>,
    ) {
        val defaultPoster =
            "android.resource://${context.packageName}/${R.mipmap.ic_banner}".toUri()

        channels.forEachIndexed { index, ch ->
            val providerId = internalProviderId(ch.id)
            val playIntent = Intent(context, PlayerActivityExo::class.java).apply {
                putExtra("CHANNEL_ID", ch.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val posterUri = posterUriForLauncher(ch.logo, defaultPoster, cacheBust)

            val programBuilder = PreviewProgram.Builder()
                .setChannelId(tvChannelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
                .setTitle(ch.name)
                .setDescription(
                    ch.category.trim().ifBlank { context.getString(R.string.tv_program_live_tv) },
                )
                .setPosterArtUri(posterUri)
                .setIntentUri(playIntent.toUri(Intent.URI_INTENT_SCHEME).toUri())
                .setInternalProviderId(providerId)
                .setWeight(1_000 - index)

            val values = programBuilder.build().toContentValues()
            try {
                val existingId = existingByProviderId[providerId]
                if (existingId != null) {
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(existingId),
                        values,
                        null,
                        null,
                    )
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        values,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upsert preview program for ${ch.name}", e)
            }
        }
    }

    /** Cache-bust poster agar launcher tidak pakai gambar lama. */
    private fun posterUriForLauncher(logo: String, defaultPoster: android.net.Uri, cacheBust: Long): android.net.Uri {
        val trimmed = logo.trim()
        if (!trimmed.startsWith("http", ignoreCase = true)) return defaultPoster
        return trimmed.toUri().buildUpon()
            .appendQueryParameter("mqltv_v", cacheBust.toString())
            .build()
    }

    private fun internalProviderId(channelId: Int) = "mqltv_ch_$channelId"
}
