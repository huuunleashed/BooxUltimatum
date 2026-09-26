package app.booxultimatum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.AppWork
import app.booxultimatum.core.Journal
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.core.Tier
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.tweaks.Catalog
import app.booxultimatum.core.tweaks.Tweak
import app.booxultimatum.core.tweaks.TweakState
import app.booxultimatum.ui.ErrorLine
import app.booxultimatum.ui.Format
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Lamp
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.launch

@Composable
fun TweaksScreen(readKey: Int, accessKey: Int, compact: Boolean, onReadAgain: () -> Unit, onOpenAccess: () -> Unit) {
    val context = LocalContext.current
    var changeKey by remember { mutableIntStateOf(0) }
    var journalKey by remember { mutableIntStateOf(0) }
    val key = Triple(readKey, accessKey, changeKey)
    val access = rememberReading(key) { PrivilegeStatus.check(context) to Privileged.ready() }
    val states = rememberReading(key) { Catalog.all.associate { it.id to it.state(context) } }
    // Fresh readings of single tweaks after Apply/Undo, so a row answers at once instead of waiting for a full re-read.
    val fresh = remember(key) { mutableStateMapOf<String, TweakState>() }
    val journal = rememberReading(Pair(key, journalKey)) { Journal.entries(context).take(10) }
    var busy by remember { mutableStateOf<String?>(null) }
    val errors = remember { mutableStateMapOf<String, String>() }

    fun run(t: Tweak, undo: Boolean) {
        busy = t.id
        errors.remove(t.id)
        AppWork.scope.launch {
            val r = if (undo) t.revert(context.applicationContext) else t.apply(context.applicationContext)
            val s = t.state(context.applicationContext)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                r.exceptionOrNull()?.let { errors[t.id] = it.message ?: it.toString() }
                fresh[t.id] = s
                busy = null
                journalKey++
            }
        }
    }

    InstrumentPage(compact) {
        item {
            val on = states?.let { s -> Catalog.all.count { (fresh[it.id] ?: s[it.id]) == TweakState.On } }
            ScreenHeader(
                stringResource(R.string.dest_tweaks),
                on?.let { stringResource(R.string.tweaks_subtitle, it, Catalog.all.size) },
            ) { Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true) }
            Paragraph(stringResource(R.string.tweaks_intro), color = Ink.Legend)
            Spacer(Modifier.height(Space.xl))
        }
        if (access == null || states == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val (status, ready) = access
        Catalog.byGroup().forEach { (group, tweaks) ->
            item(key = group.name) {
                Plate(stringResource(group.title)) {
                    Paragraph(stringResource(group.explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                    tweaks.forEach { t ->
                        TweakRow(
                            tweak = t,
                            state = fresh[t.id] ?: states[t.id] ?: TweakState.Unknown,
                            available = t.available(status, ready),
                            busy = busy == t.id,
                            anyBusy = busy != null,
                            error = errors[t.id],
                            onToggle = { undo -> run(t, undo) },
                            onOpenAccess = onOpenAccess,
                        )
                    }
                }
                Spacer(Modifier.height(Space.xxl))
            }
        }
        item {
            Plate(
                stringResource(R.string.plate_journal),
                action = if (!journal.isNullOrEmpty()) {
                    { Key(stringResource(R.string.action_clear_journal), onClick = { Journal.clearLog(context); journalKey++ }) }
                } else null,
            ) {
                if (journal.isNullOrEmpty()) {
                    Paragraph(stringResource(R.string.journal_empty), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                } else journal.forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                journalLine(e),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(end = Space.m),
                            )
                            Text(Format.clock(context, e.at), style = MaterialTheme.typography.labelMedium, color = Ink.Legend)
                        }
                        if (!e.ok) ErrorLine(stringResource(R.string.journal_failed, e.detail))
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
            }
        }
    }
}

@Composable
private fun TweakRow(
    tweak: Tweak,
    state: TweakState,
    available: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    error: String?,
    onToggle: (undo: Boolean) -> Unit,
    onOpenAccess: () -> Unit,
) {
    val on = state == TweakState.On
    val context = androidx.compose.ui.platform.LocalContext.current
    // Already on without BooxUltimatum having changed it: nothing of ours to undo.
    val preset = on && tweak.undoNeedsRecord && !tweak.hasRecord(context)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
            Lamp(on, Modifier.padding(top = 5.dp))
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(stringResource(tweak.title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(tweak.summary), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                tweak.note?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                }
                Spacer(Modifier.height(Space.s))
                val meta = listOf(
                    stringResource(tierNeed(tweak.tier)),
                    stringResource(tweak.risk.label),
                    stringResource(if (preset) R.string.tstate_preset else stateLabel(state)),
                ).filter { it.isNotEmpty() }.joinToString("  ·  ")
                Text(meta, style = MaterialTheme.typography.labelMedium, fontWeight = if (!available) FontWeight.SemiBold else null)
                if (!available) {
                    Spacer(Modifier.height(Space.s))
                    Key(stringResource(R.string.action_get_access), onClick = onOpenAccess)
                }
                error?.let { ErrorLine(stringResource(R.string.tweak_failed, it)) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Key(
                    text = stringResource(
                        when {
                            busy -> R.string.state_working
                            on -> R.string.action_undo
                            else -> R.string.action_apply
                        },
                    ),
                    onClick = { onToggle(on) },
                    enabled = available && !anyBusy && state != TweakState.NotApplicable && !preset,
                )
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** One journal entry in words: every kind of change the app makes reads as what it did, not as an internal id. */
@Composable
private fun journalLine(e: app.booxultimatum.core.JournalEntry): String = when (e.action) {
    "undo" -> stringResource(R.string.journal_undo, e.subject)
    "apply" -> stringResource(R.string.journal_apply, e.subject)
    "statusbar" -> if (e.subject == "restore") stringResource(R.string.journal_sb_restore) else stringResource(
        if (e.detail == "hidden") R.string.journal_sb_hidden else R.string.journal_sb_shown,
        app.booxultimatum.core.StatusBar.label(e.subject)?.let { stringResource(it) } ?: e.subject,
    )
    "font" -> stringResource(R.string.journal_font, e.subject)
    "font-remove" -> stringResource(R.string.journal_font_remove, e.subject)
    "launcher" -> stringResource(R.string.journal_launcher, e.subject)
    "system-font" -> stringResource(R.string.journal_system_font, e.subject)
    else -> e.subject
}

fun tierNeed(t: Tier) = when (t) {
    Tier.T0 -> R.string.need_t0
    Tier.T1 -> R.string.need_t1
    Tier.T2 -> R.string.need_t2
    Tier.T3 -> R.string.need_t3
}

private fun stateLabel(s: TweakState) = when (s) {
    TweakState.On -> R.string.tstate_on
    TweakState.Off -> R.string.tstate_off
    TweakState.Unknown -> R.string.tstate_unknown
    TweakState.NotApplicable -> R.string.tstate_na
}
