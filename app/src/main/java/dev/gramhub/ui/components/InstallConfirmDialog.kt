package dev.gramhub.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.RemoteClient
import dev.gramhub.remote.Verdict

/**
 * Confirms an install before anything is downloaded.
 *
 * A dangerous verdict is called out in the dialog rather than merely shown as a
 * badge, because installing is the one action in this app that can damage the
 * device, and the warning has to be seen before the download starts.
 */
@Composable
fun InstallConfirmDialog(
    entry: RemoteClient,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val verdict = entry.scan?.verdict ?: Verdict.UNKNOWN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_confirm_title, entry.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.install_confirm_message))
                entry.apk?.formattedSize()?.takeIf { it.isNotEmpty() }?.let { size ->
                    Text(
                        text = stringResource(R.string.install_size, size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (verdict == Verdict.DANGEROUS) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.install_dangerous_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.install),
                    color = if (verdict == Verdict.DANGEROUS) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.install_cancel))
            }
        },
    )
}
