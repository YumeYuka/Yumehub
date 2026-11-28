package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.yumeyucca.lucide.Check
import io.github.yumeyucca.lucide.Lucide

/** 面板中的有限高度选择列表，只组合可见项；页面滚动仍由 AppPage 管理。 */
@Composable
fun SelectionList(
    values: List<String>,
    selectedValue: String,
    label: (String) -> String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlasmioGroupedList(modifier) {
        LazyColumn(Modifier.fillMaxWidth()) {
            items(values, key = { it }) { value ->
                val isSelected = value == selectedValue
                GlasmioListRow(
                    title = label(value),
                    modifier = Modifier.semantics { selected = isSelected },
                    systemName = null,
                    showDisclosure = false,
                    trailing =
                        if (isSelected)
                            ({ AppIcon(Lucide.Check, contentDescription = null, size = 18.dp) })
                        else null,
                    onClick = { onSelected(value) },
                )
            }
        }
    }
}
