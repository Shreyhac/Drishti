package com.drishti.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val DrishtiTypography = Typography(
    titleMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 4.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.5.sp),
)
