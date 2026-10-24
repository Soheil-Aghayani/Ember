package com.soheil.ember.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import java.util.Locale

@Composable
fun FlagIcon(countryCode: String, size: Dp) {
    val context = LocalContext.current
    val resourceId = remember(context, countryCode) {
        val code = countryCode.lowercase(Locale.ROOT)
        if (code.length == 2 && code.all { it in 'a'..'z' }) {
            context.resources.getIdentifier("flag_$code", "drawable", context.packageName)
        } else {
            0
        }
    }
    val modifier = Modifier.size(size).clip(CircleShape)
    if (resourceId != 0) {
        Image(painterResource(resourceId), contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}
