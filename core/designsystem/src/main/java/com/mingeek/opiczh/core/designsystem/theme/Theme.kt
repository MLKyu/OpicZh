package com.mingeek.opiczh.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 브랜드 컬러: 중국 홍(红)과 금(金)을 모티브로 한 시험 앱 팔레트
private val LightColors = lightColorScheme(
    primary = Color(0xFFB3402A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3E0400),
    secondary = Color(0xFF77574F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD2),
    onSecondaryContainer = Color(0xFF2C1510),
    tertiary = Color(0xFF6F5C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAE0A6),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A3),
    onPrimary = Color(0xFF640D00),
    primaryContainer = Color(0xFF8D2A15),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFE7BDB3),
    onSecondary = Color(0xFF442A23),
    secondaryContainer = Color(0xFF5D4038),
    onSecondaryContainer = Color(0xFFFFDAD2),
    tertiary = Color(0xFFDDC48C),
    onTertiary = Color(0xFF3D2E04),
    tertiaryContainer = Color(0xFF564519),
    onTertiaryContainer = Color(0xFFFAE0A6),
    background = Color(0xFF1A110F),
    surface = Color(0xFF1A110F),
)

@Composable
fun OpicZhTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
