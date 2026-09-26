package org.mozilla.fenix.upgrid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mozilla.components.concept.engine.translate.Language
import mozilla.components.concept.engine.translate.TranslationError
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.translations.TranslationsDialogState

/** A searchable destination picker. Source selection is only a correction affordance. */
@Composable
fun UpgridTranslationSheet(
    translationsDialogState: TranslationsDialogState,
    learnMoreUrl: String,
    showPageSettings: Boolean,
    showFirstTime: Boolean,
    onSettingClicked: () -> Unit,
    onLearnMoreClicked: () -> Unit,
    onPositiveButtonClicked: () -> Unit,
    onNegativeButtonClicked: () -> Unit,
    onFromSelected: (Language) -> Unit,
    onToSelected: (Language) -> Unit,
    controller: UpgridTranslations = LocalContext.current.components.upgridTranslations,
) {
    var automatic by remember { mutableStateOf(controller.automatic) }
    var picker by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    BackHandler(enabled = picker != null) { picker = null; query = "" }
    val state = translationsDialogState
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (picker != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picker = null; query = "" }) { Text("Назад") }
                Text(if (picker == "to") "Язык перевода" else "Язык страницы", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Найти язык") }, shape = RoundedCornerShape(16.dp))
            val languages = if (picker == "to") state.toLanguages else state.fromLanguages
            val chosen = if (picker == "to") state.initialTo else state.initialFrom
            val filtered = languages.orEmpty().filter {
                val locale = java.util.Locale.forLanguageTag(it.code)
                query.isBlank() || it.localizedDisplayName.orEmpty().contains(query, ignoreCase = true) ||
                    it.code.contains(query, ignoreCase = true) || locale.getDisplayName(locale).contains(query, ignoreCase = true)
            }.sortedBy { it.localizedDisplayName ?: it.code }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered, key = { it.code }) { language ->
                    val selected = chosen?.code == language.code
                    Surface(shape = RoundedCornerShape(14.dp), color = if (selected) colors.secondaryContainer else colors.surface) {
                        Row(Modifier.fillMaxWidth().clickable {
                            if (picker == "to") { controller.rememberTarget(language); onToSelected(language) }
                            else onFromSelected(language)
                            picker = null; query = ""
                        }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(language.localizedDisplayName ?: language.code, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                val locale = java.util.Locale.forLanguageTag(language.code)
                                Text(locale.getDisplayName(locale), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            if (selected) Text("✓", color = colors.primary)
                        }
                    }
                }
                if (filtered.isEmpty()) item { Text("Язык не найден", Modifier.padding(16.dp), color = colors.onSurfaceVariant) }
            }
        } else {
            Text("Перевод страницы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Язык страницы определяется автоматически", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Surface(onClick = { picker = "to" }, enabled = !state.toLanguages.isNullOrEmpty(),
                shape = RoundedCornerShape(20.dp), color = colors.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ПЕРЕВЕСТИ НА", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.initialTo?.localizedDisplayName ?: "Выбрать язык", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Переводить автоматически", fontWeight = FontWeight.Medium)
                    Text("Все поддерживаемые языки → выбранный язык", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(checked = automatic, modifier = Modifier.semantics { contentDescription = "Переводить автоматически" }, onCheckedChange = {
                    automatic = it
                    state.initialTo?.let(controller::rememberTarget)
                    controller.automatic = it
                    controller.translateCurrentPageIfAutomatic()
                })
            }
            val error = when (state.error) {
                null -> null
                is TranslationError.CouldNotLoadLanguagesError -> "Не удалось загрузить языки. Проверьте подключение и повторите."
                is TranslationError.EngineNotSupportedError -> "Локальный перевод недоступен на этом устройстве."
                is TranslationError.LanguageNotSupportedError -> "Этот язык пока не поддерживается. Если он определён неверно, укажите язык страницы."
                else -> "Не удалось перевести страницу. Можно повторить попытку."
            }
            if (error != null) Text(error, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            if (state.isTranslationInProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
            val retryLanguages = state.error is TranslationError.CouldNotLoadLanguagesError
            Button(onClick = {
                state.initialTo?.let(controller::rememberTarget)
                onPositiveButtonClicked()
            }, enabled = !state.isTranslationInProgress &&
                (retryLanguages || (state.initialFrom != null && state.initialTo != null && state.initialFrom.code != state.initialTo.code && state.error !is TranslationError.EngineNotSupportedError)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (retryLanguages) "Повторить загрузку" else if (state.isTranslationInProgress) "Переводим…" else "Перевести")
            }
            if (state.isTranslated || state.isTranslationInProgress) TextButton(onClick = {
                controller.keepOriginal(); onNegativeButtonClicked()
            }, modifier = Modifier.fillMaxWidth()) { Text("Показать оригинал") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { picker = "from" }, enabled = !state.fromLanguages.isNullOrEmpty()) {
                    Text("Авто · " + (state.initialFrom?.localizedDisplayName ?: "Не определён"), style = MaterialTheme.typography.labelMedium)
                }
                if (showPageSettings) TextButton(onClick = onSettingClicked) { Text("Исключения") }
            }
            Text("Перевод выполняется на устройстве. При первом запуске загружается языковая модель.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
