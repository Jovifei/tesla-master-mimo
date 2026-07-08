package com.matelink.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.ui.theme.swissPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    val palette = swissPalette()

    Scaffold(
        containerColor = palette.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BrandCard()
            InfoCard(
                title = stringResource(R.string.about_tech_stack),
                lines = listOf(
                    stringResource(R.string.about_tech_android),
                    stringResource(R.string.about_tech_ios),
                    stringResource(R.string.about_tech_web)
                )
            )
            InfoCard(
                title = stringResource(R.string.about_data_source),
                lines = listOf(
                    stringResource(R.string.about_data_source_self_hosted),
                    stringResource(R.string.about_data_source_no_tesla),
                    stringResource(R.string.about_data_source_no_collection)
                )
            )
            InfoCard(
                title = stringResource(R.string.about_links),
                lines = listOf(
                    stringResource(R.string.about_link_github),
                    stringResource(R.string.about_link_license)
                )
            )
            Text(
                text = stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun BrandCard() {
    val palette = swissPalette()

    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink
            )
            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = palette.ink,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    val palette = swissPalette()

    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AboutScreenPreview() {
    MateLinkTheme(darkTheme = false) {
        AboutScreen(onNavigateBack = {})
    }
}
