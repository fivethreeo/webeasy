package no.webeasy.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.json.JSONObject

@Composable
internal fun RecordTextField(record: JSONObject, key: String, label: String, revision: Int, onChange: (String) -> Unit) {
    val value = remember(record, key, revision) { record.optString(key) }
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(ui(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = if (key == "slug") KeyboardCapitalization.None else KeyboardCapitalization.Sentences))
}
