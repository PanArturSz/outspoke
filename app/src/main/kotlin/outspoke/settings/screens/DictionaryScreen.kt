package dev.brgr.outspoke.settings.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.inference.UserDictionary
import dev.brgr.outspoke.settings.preferences.PreferencesViewModel

/**
 * Słownik nazw ASZ — edycja reguł jako zwykły tekst, wczytanie z pliku (np. notatki z vaulta)
 * i podgląd na żywo, co słownik zrobi z podanym zdaniem.
 */
@Composable
fun DictionaryScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val saved by viewModel.userDictionaryRules.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    var probe by remember { mutableStateOf("") }
    val context = LocalContext.current
    val dictionary = remember(draft) { UserDictionary.parse(draft) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) draft = text
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Jedna reguła na linię. Sama fraza (np. „Claude Code”) ustala pisownię i łapie " +
                "sklejenia oraz wielkość liter. Para „klot kot | clot code => Claude Code” zamienia " +
                "każde źródło na cel. Znak # zaczyna komentarz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildString {
                append("Reguł: ${dictionary.size}")
                if (dictionary.errors.isNotEmpty()) append(" · błędy: ${dictionary.errors.joinToString("; ")}")
                if (draft != saved) append(" · niezapisane zmiany")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (dictionary.errors.isEmpty()) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.setUserDictionaryRules(draft) },
                enabled = draft != saved,
            ) { Text("Zapisz") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Wczytaj z pliku") }
            OutlinedButton(onClick = { draft = UserDictionary.DEFAULT_RULES }) { Text("Domyślne") }
        }

        HorizontalDivider()

        Text(
            text = "Sprawdź słownik",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = probe,
            onValueChange = { probe = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Wpisz, co usłyszał model") },
        )
        if (probe.isNotBlank()) {
            Text(
                text = "→ " + dictionary.apply(probe),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
