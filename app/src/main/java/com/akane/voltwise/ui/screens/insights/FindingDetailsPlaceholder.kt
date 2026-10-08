package com.akane.voltwise.ui.screens.insights

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.akane.voltwise.R
import com.akane.voltwise.ui.components.DetailTopBar

/**
 * whittle: the [com.akane.voltwise.ui.navigation.Routes.FindingDetails] entry until the Finding details screen (U2)
 * lands; it only offers Back. Replace it with that screen, not extend it.
 */
@Composable
fun FindingDetailsPlaceholder(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { DetailTopBar(title = stringResource(R.string.insights_details), onBack = onBack) }) { }
}
