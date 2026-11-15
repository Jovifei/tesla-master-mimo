package com.matelink.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matelink.R

@Composable
internal fun HistoryDateFilterMenu(
    label: String,
    choices: List<String>,
    selectedIndex: Int,
    testTag: String,
    onSelect: (Int) -> Unit,
    onChooseDates: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(testTag),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { index, choice ->
                DropdownMenuItem(
                    text = { Text(choice, fontWeight = if (index == selectedIndex) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                    onClick = { expanded = false; onSelect(index) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_choose_date)) },
                onClick = { expanded = false; onChooseDates() }
            )
        }
    }
}
