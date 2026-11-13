package dev.gramhub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.RemoteScan
import dev.gramhub.remote.Severity

/**
 * Shows what the server's security scan found.
 *
 * The full list of findings is shown rather than a summary, because the point of
 * the scan is to let the user judge for themselves; a one-line "safe/unsafe"
 * would hide the reasoning behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySheet(
    title: String,
    scan: RemoteScan?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (scan == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.verdict_unknown_summary),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            DigestLine(scan)
            if (scan.debugSigned) {
                NoteLine(stringResource(R.string.verdict_debug_signed), Severity.HIGH)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.verdict_findings_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            scan.findings.forEach { finding ->
                FindingRow(
                    icon = severityIcon(finding.severity),
                    tint = severityColor(finding.severity),
                    summary = finding.summary,
                    detail = finding.detail,
                )
            }

            if (scan.unexpectedPermissions.isNotEmpty()) {
                Text(
                    text = scan.unexpectedPermissions.joinToString(", ") { it.substringAfterLast('.') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DigestLine(scan: RemoteScan) {
    val (label, icon, severity) = when (scan.digestVerified) {
        true -> Triple(R.string.verdict_digest_ok, Icons.Rounded.CheckCircle, Severity.INFO)
        false -> Triple(R.string.verdict_digest_bad, Icons.Rounded.Error, Severity.HIGH)
        null -> Triple(R.string.verdict_digest_absent, Icons.Rounded.Info, Severity.INFO)
    }
    FindingRow(
        icon = icon,
        tint = severityColor(severity),
        summary = stringResource(label),
        detail = scan.signers.firstOrNull()?.let { "SHA-256: ${it.take(16)}…" } ?: "",
    )
}

@Composable
private fun FindingRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, summary: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = summary, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun NoteLine(text: String, severity: Severity) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = severityIcon(severity),
            contentDescription = null,
            tint = severityColor(severity),
            modifier = Modifier.size(18.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun severityIcon(severity: Severity): ImageVector = when (severity) {
    Severity.INFO -> Icons.Rounded.CheckCircle
    Severity.LOW -> Icons.Rounded.Info
    Severity.MEDIUM -> Icons.Rounded.Warning
    Severity.HIGH -> Icons.Rounded.Error
}

@Composable
private fun severityColor(severity: Severity) = when (severity) {
    Severity.INFO -> MaterialTheme.colorScheme.primary
    Severity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Severity.HIGH -> MaterialTheme.colorScheme.error
}
