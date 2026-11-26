package moe.yumeyuka.yumehub.ui.component.placeholders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList

@Composable
fun CommitAuthorPlaceholder() {
    GlasmioGroupedList {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarPlaceholder(32.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextPlaceholder(height = 14.dp, fraction = 0.45f)
                    TextPlaceholder(height = 10.dp, fraction = 0.3f)
                }
            }
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.92f)
            TextLinePlaceholder(GlasmioTheme.textStyles.body, fraction = 0.58f)
        }
    }
}
