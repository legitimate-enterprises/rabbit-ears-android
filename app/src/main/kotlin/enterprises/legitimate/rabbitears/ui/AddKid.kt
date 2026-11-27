package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.Account
import enterprises.legitimate.rabbitears.api.models.FilterMode
import enterprises.legitimate.rabbitears.kit.AddKidForm
import enterprises.legitimate.rabbitears.kit.AddKidForm.Field
import enterprises.legitimate.rabbitears.kit.AppError
import enterprises.legitimate.rabbitears.kit.AppModel
import enterprises.legitimate.rabbitears.kit.Format
import enterprises.legitimate.rabbitears.kit.HouseholdModel
import enterprises.legitimate.rabbitears.ui.theme.Palette
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * New kid: name, birthday, a filter by ratings or by hand, and an optional PIN. Given [kid] and the
 * [household] that saves it, edits that kid instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKid(
    app: AppModel,
    kid: Account? = null,
    household: HouseholdModel? = null,
    close: () -> Unit,
) {
    val form = remember { AddKidForm(editing = kid) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val errors = if (shown) form.errors else emptyMap()

    LaunchedEffect(form.birthday) { app.suggestCeilings(form)?.let(form::apply) }

    fun add() {
        shown = true
        if (form.errors.isNotEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                if (kid != null && household != null) household.saveKid(kid, form)
                else app.addKid(form)
                close()
            } catch (e: AppError) {
                error = e.message
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 600.dp)
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(R.drawable.ic_close, "Cancel", close)
            Text(
                kid?.let { "Edit ${it.name}" } ?: "New kid",
                Modifier.weight(1f).padding(start = 8.dp),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Pill(if (kid == null) "Add" else "Save", ::add, primary = true, enabled = !busy)
        }
        Column(
            Modifier.widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                form.name,
                { form.name = it },
                Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
                isError = Field.Name in errors,
                supportingText = errors[Field.Name]?.let { { Text(it) } },
            )
            Box {
                OutlinedTextField(
                    Format.date(form.birthday),
                    {},
                    Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("Birthday") },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_calendar), "Choose date") },
                    isError = Field.Birthday in errors,
                    supportingText = (errors[Field.Birthday] ?: form.hint)?.let { { Text(it) } },
                )
                Box(Modifier.matchParentSize().clickable { picking = true })
            }
            Text("Filter", style = MaterialTheme.typography.titleMedium)
            Choice(
                "Ratings",
                "Pick the highest rating for movies and TV. Suggested from the birthday.",
                form.filterMode == FilterMode.ratings,
                { form.filterMode = FilterMode.ratings },
            ) {
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Picker(
                        "Movies up to",
                        form.movieCeiling.value,
                        AddKidForm.movieRatings,
                        { it.value },
                        form::pickMovieCeiling,
                        Modifier.weight(1f),
                    )
                    Picker(
                        "TV up to",
                        form.tvCeiling.value,
                        AddKidForm.tvRatings,
                        { it.value },
                        form::pickTvCeiling,
                        Modifier.weight(1f),
                    )
                }
            }
            Choice(
                "Only titles I choose",
                "Nothing shows until you add it.",
                form.filterMode == FilterMode.allow_list,
                { form.filterMode = FilterMode.allow_list },
            ) {
                // Allow-list changes apply at once; a new kid gets one after it is saved.
                if (kid != null && household != null) AllowListEditor(kid, household)
            }
            Column(
                Modifier.fillMaxWidth()
                    .background(Palette.raised, MaterialTheme.shapes.medium)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Require a PIN", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Only you can set or change it. Leave off for a young kid.",
                            color = Palette.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        form.requirePin,
                        { form.requirePin = it },
                        colors =
                            SwitchDefaults.colors(
                                checkedTrackColor = Palette.accent,
                                checkedThumbColor = Palette.onAccent,
                            ),
                    )
                }
                if (form.requirePin) {
                    val pin = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    OutlinedTextField(
                        form.pin,
                        { form.pin = it.take(4) },
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = {
                            Text(if (kid?.hasPin == true) "New PIN (blank keeps it)" else "PIN")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = pin,
                        isError = Field.Pin in errors,
                    )
                    OutlinedTextField(
                        form.pinConfirmation,
                        { form.pinConfirmation = it.take(4) },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = pin,
                        isError = Field.Pin in errors,
                        supportingText = errors[Field.Pin]?.let { { Text(it) } },
                    )
                }
            }
            error?.let { Text(it, color = Palette.accent) }
            val who = form.trimmedName.ifEmpty { "this kid" }
            Text(
                "Music and YouTube are hidden from kids. Watch history is kept separately for $who.",
                color = Palette.dim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (picking) {
        val state =
            rememberDatePickerState(
                form.birthday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
        DatePickerDialog(
            { picking = false },
            confirmButton = {
                TextButton(
                    {
                        state.selectedDateMillis?.let {
                            form.birthday =
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        picking = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    }
}

/** A radio card; the chosen one is tinted goldenrod and shows its [details]. */
@Composable
private fun Choice(
    title: String,
    body: String,
    selected: Boolean,
    onSelect: () -> Unit,
    details: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) Palette.accent.copy(alpha = 0.12f) else Palette.raised,
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onSelect)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected,
            onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
        )
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Palette.secondary, style = MaterialTheme.typography.bodyMedium)
            if (selected) details()
        }
    }
}

/** An outlined field that opens a menu of [options]. */
@Composable
fun <T> Picker(
    label: String,
    value: String,
    options: List<T>,
    name: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value,
            {},
            Modifier.fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_chevron_down), null) },
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach {
                DropdownMenuItem(
                    { Text(name(it)) },
                    {
                        onPick(it)
                        open = false
                    },
                )
            }
        }
    }
}
