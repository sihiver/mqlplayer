package com.sihiver.mqltv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Pengguna menghapus kartu program dari beranda TV — hapus dari penyedia agar tidak muncul lagi.
 */
class TvPreviewProgramRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED) return

        val programId = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
        if (programId <= 0L) return

        try {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramUri(programId),
                null,
                null,
            )
            Log.d(TAG, "Removed preview program id=$programId from provider")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove preview program id=$programId", e)
        }
    }

    companion object {
        private const val TAG = "TvPreviewRemoved"
    }
}
