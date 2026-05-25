package com.sihiver.mqltv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.sihiver.mqltv.R

/**
 * Render ikon saluran (ic_channel) ke Bitmap untuk ChannelLogoUtils.storeChannelLogo.
 * Diperlukan karena ic_channel adalah adaptive icon (XML), tidak bisa di-decode dengan BitmapFactory.
 */
object TvChannelLogoHelper {

    private const val SIZE = 256

    fun renderChannelIconBitmap(context: Context): Bitmap? {
        // Prioritas 1: render foreground ic_channel_foreground langsung
        renderForegroundOnly(context)?.let { return it }
        // Prioritas 2: render adaptive icon melalui PackageManager (ic_launcher)
        renderFromPackageManager(context)?.let { return it }
        return null
    }

    private fun renderForegroundOnly(context: Context): Bitmap? {
        return try {
            val fg = AppCompatResources.getDrawable(context, R.mipmap.ic_channel_foreground)
                ?: return null
            val bgColor = ResourcesCompat.getColor(context.resources, R.color.ic_channel_background, null)
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(bgColor)
            fg.setBounds(0, 0, SIZE, SIZE)
            fg.draw(canvas)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun renderFromPackageManager(context: Context): Bitmap? {
        return try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(canvas)
            } else {
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(canvas)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
