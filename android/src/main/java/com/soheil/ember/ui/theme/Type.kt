package com.soheil.ember.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.soheil.ember.R

// Inter (SIL OFL, see design/Inter-LICENSE.txt).
val EmberFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun style(weight: FontWeight, size: Int, lineHeight: Int) =
    TextStyle(fontFamily = EmberFontFamily, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp)

// Apple HIG text styles (design/tokens.json "type") mapped onto Material slots.
val EmberTypography = Typography(
    displayLarge = style(FontWeight.Bold, 34, 41),       // largeTitle
    displayMedium = style(FontWeight.Bold, 34, 41),
    displaySmall = style(FontWeight.Bold, 28, 34),
    headlineLarge = style(FontWeight.Bold, 28, 34),      // title1
    headlineMedium = style(FontWeight.SemiBold, 22, 28), // title2
    headlineSmall = style(FontWeight.SemiBold, 20, 25),  // title3
    titleLarge = style(FontWeight.SemiBold, 22, 28),
    titleMedium = style(FontWeight.SemiBold, 17, 22),    // headline
    titleSmall = style(FontWeight.Medium, 16, 21),       // callout
    bodyLarge = style(FontWeight.Normal, 17, 22),        // body
    bodyMedium = style(FontWeight.Normal, 15, 20),       // subheadline
    bodySmall = style(FontWeight.Normal, 13, 18),        // footnote
    labelLarge = style(FontWeight.Medium, 16, 21),
    labelMedium = style(FontWeight.Medium, 13, 18),
    labelSmall = style(FontWeight.Medium, 12, 16),       // caption
)
