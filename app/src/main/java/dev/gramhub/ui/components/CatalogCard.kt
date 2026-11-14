package dev.gramhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.RemoteClient
import dev.gramhub.remote.Verdict
import dev.gramhub.ui.InstallState
import dev.gramhub.ui.ScanNotice
import kotlin.math.roundToInt

/**
 * One catalog entry: a client that is not installed.
 *
 * There is no app icon here, because nothing is installed and therefore nothing
 * has one. A neutral placeholder keeps the layout aligned with the installed
 * tab without pretending to be the real icon.
 */
@Composable
fun CatalogCard(
    entry: RemoteClient,
    installState: InstallState?,
    isScanning: Boolean,
    scanNotice: ScanNotice?,
    onClick: () -> Unit,
    onInstall: () -> Unit,
    onScan: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenReleasePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val verdict = entry.scan?.verdict ?: Verdict.UNKNOWN
    val busy = installState != null &&
        installState.phase in setOf(
            InstallState.Phase.DOWNLOADING,
            InstallState.Phase.VERIFYING,
            InstallState.Phase.INSTALLING,
        )
    val failed = installState?.phase == InstallState.Phase.FAILED

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(AppIconSize)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.repo + (entry.latestVersion?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onOpenRepository) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.catalog_repository),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entry.latestReleaseUrl != null) {
                    IconButton(onClick = onOpenReleasePage) {
                        Icon(
                            imageVector = Icons.Rounded.NewReleases,
                            contentDescription = stringResource(R.string.catalog_release_page),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VerdictChip(verdict)

                if (entry.stars > 0) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.catalog_stars, entry.stars),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                entry.apk?.formattedSize()?.takeIf { it.isNotEmpty() }?.let { size ->
                    Text(
                        text = size,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(modifier = Modifier.weight(1f))

                when {
                    isScanning -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.verdict_checking),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    entry.apk != null && !busy -> IconButton(onClick = onScan) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(
                                if (entry.scan == null) R.string.verdict_check else R.string.verdict_recheck,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            InstallRow(
                entry = entry,
                verdict = verdict,
                busy = busy,
                failed = failed,
                installState = installState,
                onInstall = onInstall,
                onScan = onScan,
            )

            scanNotice?.let { notice ->
                ScanNoticeRow(
                    message = notice.message,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun InstallRow(
    entry: RemoteClient,
    verdict: Verdict,
    busy: Boolean,
    failed: Boolean,
    installState: InstallState?,
    onInstall: () -> Unit,
    onScan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            busy -> {
                if (installState?.phase == InstallState.Phase.DOWNLOADING) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = downloadProgressText(installState),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val fraction = installState.fraction
                        if (fraction != null) {
                            WavyLinearProgressIndicator(progress = { fraction })
                        } else {
                            WavyLinearProgressIndicator()
                        }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(
                            when (installState?.phase) {
                                InstallState.Phase.VERIFYING -> R.string.installing_verifying
                                else -> R.string.installing_installing
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            installState?.phase == InstallState.Phase.DONE -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.installing_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onInstall) {
                    Text(stringResource(R.string.install_retry))
                }
            }

            failed -> {
                Text(
                    text = installMessage(installState?.message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onInstall) {
                    Text(stringResource(R.string.install_retry))
                }
            }

            entry.apk == null -> {
                Text(
                    text = stringResource(R.string.install_no_apk),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            verdict == Verdict.DANGEROUS -> {
                Text(
                    text = stringResource(R.string.verdict_dangerous_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onInstall) {
                    Text(stringResource(R.string.install))
                }
            }

            entry.scan == null -> {
                Text(
                    text = stringResource(R.string.verdict_unknown_summary),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FilledTonalButton(onClick = onScan) {
                    Text(stringResource(R.string.verdict_check))
                }
            }

            else -> {
                Text(
                    text = stringResource(
                        if (verdict == Verdict.TRUSTED) {
                            R.string.verdict_trusted_summary
                        } else {
                            R.string.verdict_caution_summary
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = onInstall) {
                    Text(stringResource(R.string.install))
                }
            }
        }
    }
}

/** Maps a machine-readable install failure to a localized message. */
@Composable
private fun installMessage(code: String?): String = when (code) {
    "digest_mismatch" -> stringResource(R.string.install_digest_mismatch)
    "no_apk" -> stringResource(R.string.install_no_apk)
    else -> stringResource(R.string.install_failed)
}

/**
 * The download readout: a percentage with "12,4 МБ из 44,0 МБ" when the total is
 * known, or just the running byte count when the server did not report a size.
 */
@Composable
private fun downloadProgressText(state: InstallState): String {
    val fraction = state.fraction
    return if (fraction != null) {
        stringResource(
            R.string.installing_progress,
            (fraction * 100).roundToInt(),
            formatBytes(state.downloadedBytes),
            formatBytes(state.totalBytes),
        )
    } else {
        stringResource(R.string.installing_progress_unknown, formatBytes(state.downloadedBytes))
    }
}

/** Formats a byte count for people: 1,4 МБ, 512 КБ, 800 Б. */
@Composable
private fun formatBytes(bytes: Long): String {
    val mb = 1024.0 * 1024.0
    val kb = 1024.0
    return when {
        bytes >= mb -> "%.1f %s".format(bytes / mb, stringResource(R.string.unit_mb))
        bytes >= kb -> "%.0f %s".format(bytes / kb, stringResource(R.string.unit_kb))
        else -> "%d %s".format(bytes, stringResource(R.string.unit_bytes))
    }
}
