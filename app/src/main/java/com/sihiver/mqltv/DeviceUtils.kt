package com.sihiver.mqltv

import android.os.Build

fun isLikelyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val brand = Build.BRAND.lowercase()
    val device = Build.DEVICE.lowercase()
    val product = Build.PRODUCT.lowercase()
    val hardware = Build.HARDWARE.lowercase()

    return fingerprint.contains("generic") ||
        fingerprint.contains("vbox") ||
        fingerprint.contains("test-keys") ||
        model.contains("google sdk") ||
        model.contains("android sdk built for") ||
        brand.startsWith("generic") ||
        device.startsWith("generic") ||
        product.contains("sdk") ||
        product.contains("emulator") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        hardware.contains("vbox")
}