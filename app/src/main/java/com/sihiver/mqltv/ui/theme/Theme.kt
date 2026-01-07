package com.sihiver.mqltv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.darkColorScheme as Material3DarkColorScheme
import androidx.compose.material3.lightColorScheme as Material3LightColorScheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MQLTVTheme(
    isInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tvColorScheme = if (isInDarkTheme) {
        darkColorScheme(
            primary = Purple80,
            secondary = PurpleGrey80,
            tertiary = Pink80
        )
    } else {
        lightColorScheme(
            primary = Purple40,
            secondary = PurpleGrey40,
            tertiary = Pink40
        )
    }

    val material3ColorScheme = if (isInDarkTheme) {
        Material3DarkColorScheme(
            primary = Purple80,
            secondary = PurpleGrey80,
            tertiary = Pink80,
        )
    } else {
        Material3LightColorScheme(
            primary = Purple40,
            secondary = PurpleGrey40,
            tertiary = Pink40,
        )
    }

    Material3Theme(colorScheme = material3ColorScheme) {
        TvMaterialTheme(
            colorScheme = tvColorScheme,
            typography = Typography,
            content = content,
        )
    }
}