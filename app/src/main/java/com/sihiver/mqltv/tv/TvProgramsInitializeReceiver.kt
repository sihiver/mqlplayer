package com.sihiver.mqltv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Dipanggil sistem TV setelah app diinstal untuk mengisi saluran beranda awal.
 */
class TvProgramsInitializeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TvContractCompat.ACTION_INITIALIZE_PROGRAMS) return
        Log.d(TAG, "INITIALIZE_PROGRAMS — syncing recently watched to TV home")
        TvHomeRecommendations.syncBlocking(context)
    }

    companion object {
        private const val TAG = "TvProgramsInitReceiver"
    }
}
