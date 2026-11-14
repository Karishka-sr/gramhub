package dev.gramhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.CheckLevel
import dev.gramhub.remote.FullCheckReport
import dev.gramhub.remote.RemoteClient
import dev.gramhub.remote.Verdict
import dev.gramhub.ui.FullCheckState
import dev.gramhub.ui.InstallState
import dev.gramhub.data.TelegramClient
import kotlin.math.roundToInt

/**
 * Everything GramHub can do with one catalog entry, in a single sheet.
 *
 * The sheet is opened by tapping the entry, and it is deliberately the only place
 * the three checks are offered, so "Проверить" (the server's verdict) and
 * "Проверить полностью" (a check of the actual file on this device) cannot be
 * confused with each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDetailSheet(
    entry: RemoteClient,
    installed: TelegramClient?,
    hasDownload: Boolean,
    installState: InstallState?,
    fullCheckState: FullCheckState?,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onFullCheck: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    val busy = installState != null &&
        installState.phase in setOf(
            InstallState.Phase.DOWNLOADING,
            InstallState.Phase.VERIFYING,
            InstallState.Phase.INSTALLING,
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(entry = entry, installed = installed, hasDownload = hasDownload)

            VerdictLine(entry = entry, isScanning = isScanning)

            Text(
                text = stringResource(R.string.client_description_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.description.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.client_no_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            MetaBlock(entry = entry, installed = installed)

            entry.scan?.let { scan ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SecurityContent(scan = scan)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Actions(
                entry = entry,
                installed = installed,
                hasDownload = hasDownload,
                busy = busy,
                isScanning = isScanning,
                onInstall = onInstall,
                onCheck = onCheck,
                onFullCheck = onFullCheck,
                onDelete = onDelete,
                onOpen = onOpen,
                onOpenRepository = onOpenRepository,
                onOpenRelease = onOpenRelease,
            )

            fullCheckState?.let { state ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                FullCheckBlock(state = state)
            }
        }
    }
}

@Composable
private fun Header(entry: RemoteClient, installed: TelegramClient?, hasDownload: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (installed != null) {
            AppIcon(drawable = installed.icon, size = 56.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.repo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (installed != null) {
                Text(
                    text = stringResource(R.string.installed_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (hasDownload) {
                Text(
                    text = stringResource(R.string.client_downloaded_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun VerdictLine(entry: RemoteClient, isScanning: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VerdictChip(entry.scan?.verdict ?: Verdict.UNKNOWN)
        if (isScanning) {
            Text(
                text = stringResource(R.string.verdict_checking),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(
                    when (entry.scan?.verdict ?: Verdict.UNKNOWN) {
                        Verdict.TRUSTED -> R.string.verdict_trusted_summary
                        Verdict.CAUTION -> R.string.verdict_caution_summary
                        Verdict.DANGEROUS -> R.string.verdict_dangerous_summary
                        Verdict.UNKNOWN -> R.string.verdict_unknown_summary
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaBlock(entry: RemoteClient, installed: TelegramClient?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetaLine(R.string.client_repository, entry.repo)
        entry.latestVersion?.let { MetaLine(R.string.client_version, it) }
        entry.apk?.formattedSize()?.takeIf { it.isNotEmpty() }?.let { MetaLine(R.string.client_size, it) }
        installed?.versionName?.let { MetaLine(R.string.client_installed_version, it) }
    }
}

@Composable
private fun MetaLine(labelRes: Int, value: String) {
    Text(
        text = stringResource(labelRes, value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Actions(
    entry: RemoteClient,
    installed: TelegramClient?,
    hasDownload: Boolean,
    busy: Boolean,
    isScanning: Boolean,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onFullCheck: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    val checking = busy || isScanning

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (installed != null) {
            ActionButton(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                label = stringResource(R.string.client_open),
                onClick = onOpen,
                primary = true,
            )
        } else {
            ActionButton(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.install),
                onClick = onInstall,
                enabled = entry.apk != null && !busy,
                primary = true,
            )
        }

        ActionButton(
            icon = Icons.Rounded.Refresh,
            label = stringResource(R.string.client_check),
            onClick = onCheck,
            enabled = entry.apk != null && !checking,
        )

        ActionButton(
            icon = Icons.AutoMirrored.Rounded.FactCheck,
            label = stringResource(R.string.client_full_check),
            onClick = onFullCheck,
            enabled = entry.apk != null && !checking,
        )

        if (hasDownload) {
            ActionButton(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.client_delete),
                onClick = onDelete,
                enabled = !busy,
                danger = true,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenRepository, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.client_open_repo), maxLines = 1)
            }
            if (entry.latestReleaseUrl != null) {
                OutlinedButton(onClick = onOpenRelease, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.client_open_release), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }

    when {
        primary -> Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            content()
        }

        else -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (danger) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = tint,
                )
                Text(text = label, modifier = Modifier.padding(start = 8.dp), color = tint)
            } else {
                content()
            }
        }
    }
}

/**
 * The full-check report.
 *
 * Every observation is listed rather than reduced to a single verdict, because the
 * value of this check is that the user can see what was actually examined on the
 * file sitting on their phone.
 */
@Composable
private fun FullCheckBlock(state: FullCheckState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.fullcheck_title),
            style = MaterialTheme.typography.titleMedium,
        )

        when (state.phase) {
            FullCheckState.Phase.DOWNLOADING -> {
                Text(
                    text = stringResource(R.string.installing_downloading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FullCheckState.Phase.RUNNING -> {
                Text(
                    text = stringResource(R.string.fullcheck_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.step.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.fullcheck_progress,
                            (state.fraction * 100).roundToInt(),
                            state.step,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FullCheckState.Phase.FAILED -> NoteRow(
                icon = Icons.Rounded.Error,
                tint = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.fullcheck_failed),
                detail = state.error.orEmpty(),
            )

            FullCheckState.Phase.DONE -> state.report?.let { ReportBody(it) }
        }
    }
}

@Composable
private fun ReportBody(report: FullCheckReport) {
    val summary = when (report.level) {
        CheckLevel.DANGER -> stringResource(R.string.fullcheck_summary_bad)
        CheckLevel.WARNING -> stringResource(R.string.fullcheck_summary_caution)
        CheckLevel.INFO -> stringResource(R.string.fullcheck_summary_ok)
    }
    val (icon, tint) = when (report.level) {
        CheckLevel.DANGER -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
        CheckLevel.WARNING -> Icons.Rounded.Warning to MaterialTheme.colorScheme.tertiary
        CheckLevel.INFO -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteRow(icon = icon, tint = tint, title = summary)

        Text(
            text = stringResource(
                R.string.fullcheck_entries,
                report.entriesTotal,
                report.entriesVerified,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (report.damagedEntries == 0) {
                stringResource(R.string.fullcheck_integrity_ok)
            } else {
                stringResource(R.string.fullcheck_integrity_bad, report.damagedEntries)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (report.damagedEntries == 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = if (report.signed) {
                stringResource(R.string.fullcheck_signature_ok)
            } else {
                stringResource(R.string.fullcheck_signature_missing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (report.signed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = stringResource(R.string.fullcheck_dex, report.dexClasses),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.fullcheck_native, report.nativeLibs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "SHA-256: ${report.digest.take(32)}…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        report.notes.filter { it.level != CheckLevel.INFO }.forEach { note ->
            NoteRow(
                icon = if (note.level == CheckLevel.DANGER) Icons.Rounded.Error else Icons.Rounded.Info,
                tint = if (note.level == CheckLevel.DANGER) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                title = note.title,
                detail = note.detail,
            )
        }
    }
}

@Composable
private fun NoteRow(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String = "",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
