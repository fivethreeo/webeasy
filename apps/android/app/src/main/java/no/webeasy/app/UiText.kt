package no.webeasy.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

internal fun localizedUi(context: Context, text: String): String =
    UiCatalog.resources[text]?.let { context.getString(it) } ?: text

@Composable
internal fun ui(text: String): String = localizedUi(LocalContext.current, text)
