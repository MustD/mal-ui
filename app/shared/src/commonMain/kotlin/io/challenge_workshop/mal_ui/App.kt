package io.challenge_workshop.mal_ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.challenge_workshop.mal_ui.auth.MalLoginScreen

@Composable
@Preview
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier) {
            MalLoginScreen()
        }
    }
}
