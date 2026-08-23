package com.matelink.ui.screens.auth

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R

@Composable
fun DebugMockLoginEntry(
    onLoginSuccess: () -> Unit,
    viewModel: DebugMockLoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    Button(
        onClick = { viewModel.login(onLoginSuccess) },
        enabled = !state.loading,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(stringResource(R.string.debug_mock_login))
    }
    state.error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
}
