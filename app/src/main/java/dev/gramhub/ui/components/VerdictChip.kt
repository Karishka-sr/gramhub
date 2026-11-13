package dev.gramhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gramhub.R
import dev.gramhub.remote.Verdict

/**
 * The security verdict as a compact badge.
 *
 * The icon, the label and the colour all change together, so the state is
 * readable without relying on colour alone.
 */
@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier) {
    val palette = verdictPalette(verdict)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = palette.icon,
            contentDescription = null,
            tint = palette.content,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(palette.label),
            style = MaterialTheme.typography.labelMedium,
            color = palette.content,
        )
    }
}

data class VerdictPalette(
    val label: Int,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

@Composable
fun verdictPalette(verdict: Verdict): VerdictPalette = when (verdict) {
    Verdict.TRUSTED -> VerdictPalette(
        label = R.string.verdict_trusted,
        icon = Icons.Rounded.CheckCircle,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    Verdict.CAUTION -> VerdictPalette(
        label = R.string.verdict_caution,
        icon = Icons.Rounded.Warning,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    )

    Verdict.DANGEROUS -> VerdictPalette(
        label = R.string.verdict_dangerous,
        icon = Icons.Rounded.Error,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )

    Verdict.UNKNOWN -> VerdictPalette(
        label = R.string.verdict_unknown,
        icon = Icons.AutoMirrored.Rounded.HelpOutline,
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
