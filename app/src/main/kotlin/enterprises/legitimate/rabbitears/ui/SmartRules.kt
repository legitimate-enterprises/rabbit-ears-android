package enterprises.legitimate.rabbitears.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import enterprises.legitimate.rabbitears.R
import enterprises.legitimate.rabbitears.api.models.PlaylistKind
import enterprises.legitimate.rabbitears.api.models.SmartMatch
import enterprises.legitimate.rabbitears.kit.SmartCatalog
import enterprises.legitimate.rabbitears.kit.SmartDraft
import enterprises.legitimate.rabbitears.kit.SmartType
import enterprises.legitimate.rabbitears.ui.theme.Palette
import kotlinx.coroutines.launch

/** The Smart badge beside a smart list's name. */
@Composable
fun SmartBadge() {
    Row(
        Modifier.background(Palette.tag, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_sparkle),
            null,
            Modifier.size(12.dp),
            tint = Palette.text,
        )
        Text("Smart", style = MaterialTheme.typography.labelSmall)
    }
}

private fun matchLabel(m: SmartMatch) =
    when (m) {
        SmartMatch.all -> "all"
        SmartMatch.any -> "any"
    }

/**
 * Edits [draft] and saves it through [onSave], which is false while the server refuses; the refusal
 * then shows on its rows and the dialog stays. A kind is offered only to a new playlist, and a name
 * only when [named]. Rules are written online only.
 */
@Composable
fun SmartRulesDialog(
    title: String,
    draft: SmartDraft,
    onSave: suspend () -> Boolean,
    kindLocked: Boolean = true,
    named: Boolean = true,
    onDone: () -> Unit,
) {
    val offline = LocalNav.current.app.offline
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val enabled = !offline && !busy
    AlertDialog(
        onDone,
        confirmButton = {
            TextButton(
                {
                    busy = true
                    scope.launch {
                        val saved = onSave()
                        busy = false
                        if (saved) onDone()
                    }
                },
                enabled = enabled,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
        title = { Text(title) },
        containerColor = Palette.raised,
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (offline) Text("Rules are edited while online.", color = Palette.accent)
                if (named)
                    OutlinedTextField(
                        draft.name,
                        { draft.name = it },
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text("Name") },
                        singleLine = true,
                        isError = draft.nameError != null,
                        supportingText = draft.nameError?.let { { Text(it) } },
                    )
                val kind = draft.kind
                if (kind != null && !kindLocked)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaylistKind.entries.forEach {
                            Chip(kindLabel(it), it == kind) { if (enabled) draft.choose(it) }
                        }
                    }
                val many = draft.groups.size > 1
                MenuField(
                    if (many) "Match … of these groups" else "Match … of these conditions",
                    matchLabel(draft.match),
                    SmartMatch.entries,
                    ::matchLabel,
                    enabled = enabled,
                ) {
                    draft.match = it
                }
                draft.groups.forEachIndexed { g, group ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Palette.tonal, MaterialTheme.shapes.small)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (many)
                            MenuField(
                                "Match … of",
                                matchLabel(group.match),
                                SmartMatch.entries,
                                ::matchLabel,
                                enabled = enabled,
                            ) {
                                draft.setMatch(g, it)
                            }
                        group.conditions.forEachIndexed { c, _ ->
                            ConditionRow(draft, g, c, enabled)
                        }
                        TextButton({ draft.addCondition(g) }, enabled = enabled) {
                            Text("+ Condition")
                        }
                    }
                }
                TextButton({ draft.addGroup() }, enabled = enabled) { Text("+ Group") }
                draft.error?.let { Text(it, color = Palette.accent) }
                if (draft.personal)
                    Text(
                        "Watched, plays and favorites are yours, the owner's.",
                        color = Palette.dim,
                        fontSize = 13.sp,
                    )
            }
        },
    )
}

/** [Field] [Operator] [value] [−], with the row's refusal under it. */
@Composable
private fun ConditionRow(draft: SmartDraft, g: Int, c: Int, enabled: Boolean) {
    val row = draft.groups[g].conditions[c]
    val type = SmartCatalog.type(row.field)
    val error = draft.errors[g to c]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuField(
                "Field",
                SmartCatalog.label(row.field),
                draft.fields,
                SmartCatalog::label,
                Modifier.weight(1f),
                enabled = enabled,
            ) {
                draft.setField(g, c, it)
            }
            MenuField(
                "Operator",
                SmartCatalog.label(row.operator, row.field),
                SmartCatalog.operators(row.field),
                { SmartCatalog.label(it, row.field) },
                Modifier.weight(1f),
                enabled = enabled,
            ) {
                draft.setOperator(g, c, it)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (type != SmartType.Boolean)
                ValueField(draft, g, c, enabled, error != null, Modifier.weight(1f))
            else Box(Modifier.weight(1f))
            if (draft.removable)
                IconButton({ draft.remove(g, c) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_close), "Remove condition")
                }
        }
        error?.let { Text(it, color = Palette.accent, fontSize = 13.sp) }
    }
}

/** Text, with the rating scale to pick from for Content rating, or a whole number and its unit. */
@Composable
private fun ValueField(
    draft: SmartDraft,
    g: Int,
    c: Int,
    enabled: Boolean,
    error: Boolean,
    modifier: Modifier,
) {
    val row = draft.groups[g].conditions[c]
    val number = SmartCatalog.type(row.field) == SmartType.Number
    val suggestions = SmartCatalog.suggestions(row.field, draft.kind)
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(row.field) { open = false }
    Box(modifier) {
        OutlinedTextField(
            row.value,
            { draft.setValue(g, c, it) },
            Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("Value") },
            singleLine = true,
            isError = error,
            suffix = SmartCatalog.unit(row.field)?.let { { Text(it) } },
            keyboardOptions =
                if (number) KeyboardOptions(keyboardType = KeyboardType.Number)
                else KeyboardOptions.Default,
            trailingIcon =
                if (suggestions.isEmpty()) null
                else {
                    {
                        IconButton({ open = true }, enabled = enabled) {
                            Icon(painterResource(R.drawable.ic_chevron_down), "Ratings")
                        }
                    }
                },
        )
        DropdownMenu(open, { open = false }) {
            suggestions.forEach {
                MenuAction(it) {
                    draft.setValue(g, c, it)
                    open = false
                }
            }
        }
    }
}

/**
 * Loads a smart list's rules, then edits them; a failed read closes it, the list's screen showing
 * why.
 */
@Composable
fun EditRules(
    load: suspend () -> SmartDraft?,
    onSave: suspend (SmartDraft) -> Boolean,
    named: Boolean = true,
    onDone: () -> Unit,
) {
    var draft by remember { mutableStateOf<SmartDraft?>(null) }
    LaunchedEffect(Unit) { draft = load() ?: return@LaunchedEffect onDone() }
    draft?.let {
        SmartRulesDialog("Edit rules", it, { onSave(it) }, named = named, onDone = onDone)
    }
}
