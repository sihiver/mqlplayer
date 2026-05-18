package com.sihiver.mqltv.playback

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import com.sihiver.mqltv.model.Channel
import org.json.JSONObject
import java.util.Locale

/**
 * DRM + header HTTP untuk stream v216 / Widevine.
 *
 * [Channel.drmLicenseUrl] dapat berisi:
 * - `clearkey:` + payload (base64 JSON keys, atau JSON keys mentah)
 * - `clearkey-hex:` + `kidHex:keyHex`
 * - URL Widevine (+ opsional `|key=value` header license)
 * - Segmen `|hdr:` + JSON/base64 header untuk manifest & segment (dari `header_iptv` v216)
 */
@UnstableApi
object DrmPlaybackHelper {

    private const val PREFIX_CLEARKEY = "clearkey:"
    private const val PREFIX_CLEARKEY_HEX = "clearkey-hex:"
    private const val HDR_SEGMENT = "hdr:"

    data class ParsedPlaybackDrm(
        val drmSessionManager: DrmSessionManager?,
        val streamHeaders: Map<String, String>,
    )

    fun parseStreamHeaders(drmLicenseUrl: String): Map<String, String> {
        return splitDrmAndHeaders(drmLicenseUrl.trim()).second
    }

    fun createHttpDataSourceFactory(
        drmLicenseUrl: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
    ): DefaultHttpDataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val headers = parseStreamHeaders(drmLicenseUrl)
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        return factory
    }

    fun createMediaItem(channel: Channel): MediaItem {
        val streamUrl = channel.url.trim()
        val drmRaw = stripNonDrmSegments(channel.drmLicenseUrl.trim())
        val isDashMpd = streamUrl.endsWith(".mpd", ignoreCase = true)
        if (drmRaw.isBlank() || !isDashMpd) {
            return MediaItem.fromUri(streamUrl)
        }
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
    }

    fun createPlaybackDrm(drmLicenseUrl: String): ParsedPlaybackDrm {
        val raw = drmLicenseUrl.trim()
        if (raw.isBlank()) {
            return ParsedPlaybackDrm(null, emptyMap())
        }
        val (drmPart, streamHeaders) = splitDrmAndHeaders(raw)

        if (drmPart.isBlank()) {
            return ParsedPlaybackDrm(null, streamHeaders)
        }

        val manager = when {
            drmPart.startsWith(PREFIX_CLEARKEY_HEX, ignoreCase = true) -> {
                createClearKeyFromHex(drmPart.removePrefix(PREFIX_CLEARKEY_HEX).trim())
            }
            drmPart.startsWith(PREFIX_CLEARKEY, ignoreCase = true) -> {
                createClearKeyFromPayload(drmPart.removePrefix(PREFIX_CLEARKEY).trim())
            }
            drmPart.startsWith("http://", ignoreCase = true) ||
                drmPart.startsWith("https://", ignoreCase = true) -> {
                createWidevine(drmPart)
            }
            looksLikeClearKeyPayload(drmPart) -> createClearKeyFromPayload(drmPart)
            else -> null
        }

        return ParsedPlaybackDrm(manager, streamHeaders)
    }

    fun createDrmSessionManager(drmLicenseUrl: String): DrmSessionManager? {
        return createPlaybackDrm(drmLicenseUrl).drmSessionManager
    }

    /** Gabungkan bagian DRM + header stream untuk disimpan di [Channel.drmLicenseUrl]. */
    fun encodeDrmLicenseUrl(
        drmPart: String,
        streamHeaders: Map<String, String>,
    ): String {
        val drm = drmPart.trim()
        if (streamHeaders.isEmpty()) return drm
        val hdrJson = JSONObject()
        streamHeaders.forEach { (k, v) -> hdrJson.put(k, v) }
        val hdrSegment = HDR_SEGMENT + Base64.encodeToString(
            hdrJson.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return if (drm.isBlank()) hdrSegment else "$drm|$hdrSegment"
    }

    /** Untuk impor JSON v216. */
    fun resolveV216JsonDrmLicenseUrl(item: JSONObject): String {
        val streamHeaders = parseV216StreamHeaders(item.optString("header_iptv", "").trim())
        val jenis = item.optString("jenis", "").trim().lowercase(Locale.US)

        val drmPart = when {
            jenis.contains("clearkey") -> resolveV216ClearKeyDrmPart(item)
            jenis.contains("widevine") -> resolveV216WidevineDrmPart(item)
            else -> resolveV216ClearKeyDrmPart(item).ifBlank { resolveV216WidevineDrmPart(item) }
        }

        return encodeDrmLicenseUrl(drmPart, streamHeaders)
    }

    /**
     * Channel `dash-clearkey` (MNCTV, SCTV, TransTV, …) sering punya URL Widevine di
     * `header_license` hanya sebagai metadata; kunci sebenarnya ada di `url_license`.
     * Jangan pakai Widevine untuk channel tersebut — itu penyebab ANTV jalan, RCTI/MNC gagal.
     */
    private fun resolveV216ClearKeyDrmPart(item: JSONObject): String {
        val urlLicense = item.optString("url_license", "").trim()
        if (looksLikeClearKeyPayload(urlLicense)) {
            return "$PREFIX_CLEARKEY$urlLicense"
        }

        val headerLicenseRaw = item.optString("header_license", "").trim()
        if (headerLicenseRaw.isNotBlank() && !headerLicenseRaw.equals("none", ignoreCase = true)) {
            try {
                val oldKey = JSONObject(headerLicenseRaw).optString("old_key", "").trim()
                if (oldKey.contains(":") && !oldKey.equals("none", ignoreCase = true)) {
                    return "$PREFIX_CLEARKEY_HEX$oldKey"
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return ""
    }

    private fun resolveV216WidevineDrmPart(item: JSONObject): String {
        val headerLicenseRaw = item.optString("header_license", "").trim()
        if (headerLicenseRaw.isNotBlank() && !headerLicenseRaw.equals("none", ignoreCase = true)) {
            try {
                val widevine = JSONObject(headerLicenseRaw).optString("widevine", "").trim()
                if (widevine.startsWith("http://", ignoreCase = true) ||
                    widevine.startsWith("https://", ignoreCase = true)
                ) {
                    return widevine
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        val urlLicense = item.optString("url_license", "").trim()
        if (urlLicense.startsWith("http://", ignoreCase = true) ||
            urlLicense.startsWith("https://", ignoreCase = true)
        ) {
            return urlLicense
        }
        return ""
    }

    fun looksLikeClearKeyPayload(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank() || v.equals("none", ignoreCase = true)) return false
        if (v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) {
            return false
        }
        if (v.startsWith("{")) return true
        if (v.contains(":") && v.length in 32..80 && v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) {
            return true
        }
        return v.startsWith("eyJ") // base64 JSON
    }

    private fun parseV216StreamHeaders(headerIptvRaw: String): Map<String, String> {
        if (headerIptvRaw.isBlank() || headerIptvRaw.equals("none", ignoreCase = true)) {
            return emptyMap()
        }
        return try {
            val json = JSONObject(headerIptvRaw)
            buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key, "").trim()
                    if (key.isNotBlank() && value.isNotBlank() && !value.equals("none", ignoreCase = true)) {
                        put(key, value)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Pisahkan bagian DRM (boleh berisi `|` untuk header Widevine) dari segmen `hdr:`. */
    private fun splitDrmAndHeaders(raw: String): Pair<String, Map<String, String>> {
        val drmParts = mutableListOf<String>()
        var streamHeaders = emptyMap<String, String>()
        for (part in raw.split("|")) {
            val p = part.trim()
            if (p.isBlank()) continue
            if (p.startsWith(HDR_SEGMENT, ignoreCase = true)) {
                streamHeaders = decodeHeaderMap(p.removePrefix(HDR_SEGMENT).trim())
            } else {
                drmParts.add(p)
            }
        }
        return drmParts.joinToString("|") to streamHeaders
    }

    private fun stripNonDrmSegments(raw: String): String {
        return splitDrmAndHeaders(raw).first
    }

    private fun decodeHeaderMap(payload: String): Map<String, String> {
        if (payload.isBlank()) return emptyMap()
        val jsonText = if (payload.startsWith("{")) {
            payload
        } else {
            try {
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                return emptyMap()
            }
        }
        return try {
            val json = JSONObject(jsonText)
            buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key, "").trim()
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun createClearKeyFromHex(hexPair: String): DrmSessionManager? {
        val parts = hexPair.split(":", limit = 2)
        if (parts.size != 2) return null
        val kidHex = parts[0].trim()
        val keyHex = parts[1].trim()
        val json = hexPairToClearKeyJson(kidHex, keyHex) ?: return null
        return buildClearKeySession(json)
    }

    private fun createClearKeyFromPayload(payload: String): DrmSessionManager? {
        val keySetJson = when {
            payload.startsWith("{") -> payload
            else -> {
                try {
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return buildClearKeySession(keySetJson)
    }

    private fun hexPairToClearKeyJson(kidHex: String, keyHex: String): String? {
        return try {
            val kidB64 = Base64.encodeToString(hexToBytes(kidHex), Base64.NO_WRAP)
            val kB64 = Base64.encodeToString(hexToBytes(keyHex), Base64.NO_WRAP)
            """{"keys":[{"kty":"oct","k":"$kB64","kid":"$kidB64"}]}"""
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase(Locale.US)
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun buildClearKeySession(keySetJson: String): DrmSessionManager? {
        return try {
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(keySetJson.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            android.util.Log.e("DrmPlaybackHelper", "ClearKey init failed", e)
            null
        }
    }

    private fun createWidevine(licenseUrl: String): DrmSessionManager? {
        return try {
            val parts = licenseUrl.split("|")
            val actualLicenseUrl = parts[0].trim()
            val customHeaders = mutableMapOf<String, String>()
            if (parts.size > 1) {
                parts.drop(1).forEach { header ->
                    if (header.trim().startsWith(HDR_SEGMENT, ignoreCase = true)) return@forEach
                    val keyValue = header.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        customHeaders[keyValue[0].trim()] = keyValue[1].trim()
                    }
                }
            }
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
            val drmCallback = HttpMediaDrmCallback(actualLicenseUrl, httpFactory)
            customHeaders.forEach { (k, v) -> drmCallback.setKeyRequestProperty(k, v) }
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(drmCallback)
        } catch (e: Exception) {
            android.util.Log.e("DrmPlaybackHelper", "Widevine init failed", e)
            null
        }
    }
}
