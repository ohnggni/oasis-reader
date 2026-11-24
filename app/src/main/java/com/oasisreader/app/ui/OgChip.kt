package com.oasisreader.app.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * The app's choice chip (93). Material's FilterChip draws a border on the chips that are NOT
 * picked and a pale fill on the picked one, so the bordered one looks pressed. Here the picked
 * chip is filled with the accent and bold, the others are plain words with no border.
 * Same parameters as the FilterChip calls it replaces.
 */
@Composable
fun OgChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val eink = com.oasisreader.app.ui.theme.ThemeMode.eink
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)) { label() }
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = if (eink) Color.Black else MaterialTheme.colorScheme.primary,
            selectedLabelColor = if (eink) Color.White else MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Color.Transparent, selectedBorderColor = Color.Transparent,
        ),
    )
}
