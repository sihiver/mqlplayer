package com.sihiver.mqltv.model

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logo: String = "",
    val category: String = "General"
)
