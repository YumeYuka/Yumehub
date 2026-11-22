package moe.yumeyuka.markdown.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import moe.yumeyuka.markdown.core.HtmlElement

/** 表格共享一次列宽计算和网格绘制；跨行、跨列单元格不产生穿过内容的分隔线。 */
@Composable
internal fun NativeHtmlTable(
    element: HtmlElement,
    style: HtmlStyles,
    bodyTextStyle: TextStyle = style.bodyStyle(),
    modifier: Modifier = Modifier,
    elementModifier: @Composable (HtmlElement) -> Modifier = { Modifier.testTag(tableNodeTag(it)) },
    onIssue: (String) -> Unit = {},
    renderCell: @Composable (HtmlElement, TextStyle, Modifier) -> Unit,
) {
    val grid = remember(element) { htmlTableGrid(element) }
    val latestOnIssue = rememberUpdatedState(onIssue)
    val reportedIssues = remember(element.key) { mutableSetOf<String>() }
    val geometry = remember(element.key) { HtmlTableGeometryHolder() }
    Column(modifier.fillMaxWidth()) {
        element.children
            .filterIsInstance<HtmlElement>()
            .filter { it.tag == "caption" }
            .forEach { caption ->
                key(caption.key) {
                    renderCell(
                        caption,
                        bodyTextStyle.copy(textAlign = TextAlign.Center),
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        if (grid.cells.isEmpty()) return@Column
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewportWidth =
                constraints.maxWidth.takeUnless { it == Constraints.Infinity }
                    ?: constraints.minWidth
            val scrollState = rememberScrollState()
            Layout(
                modifier =
                    Modifier.horizontalScroll(scrollState)
                        .semantics {
                            collectionInfo = CollectionInfo(grid.rows.size, grid.columnCount)
                        }
                        .drawBehind {
                            val measured = geometry.value ?: return@drawBehind
                            val rtl = layoutDirection == LayoutDirection.Rtl
                            grid.rows.forEachIndexed { index, row ->
                                val color =
                                    when {
                                        row.header -> style.palette.tableHeaderBackground
                                        row.alternate -> style.palette.tableAlternateBackground
                                        else -> style.palette.background
                                    }
                                drawRect(
                                    color = color,
                                    topLeft = Offset(0f, measured.rowOffsets[index].toFloat()),
                                    size =
                                        Size(
                                            measured.width.toFloat(),
                                            measured.rowHeights[index].toFloat(),
                                        ),
                                )
                            }
                            val stroke = style.spacing.borderWidth.toPx()
                            if (stroke > 0f) {
                                measured.edges.forEach { edge ->
                                    if (edge.horizontal) {
                                        val y =
                                            edge.fixed
                                                .toFloat()
                                                .coerceIn(
                                                    stroke / 2,
                                                    max(stroke / 2, measured.height - stroke / 2),
                                                )
                                        val left =
                                            if (rtl) measured.width - edge.end else edge.start
                                        val right =
                                            if (rtl) measured.width - edge.start else edge.end
                                        drawLine(
                                            style.palette.tableBorder,
                                            Offset(left.toFloat(), y),
                                            Offset(right.toFloat(), y),
                                            strokeWidth = stroke,
                                        )
                                    } else {
                                        val physicalX =
                                            if (rtl) measured.width - edge.fixed else edge.fixed
                                        val x =
                                            physicalX
                                                .toFloat()
                                                .coerceIn(
                                                    stroke / 2,
                                                    max(stroke / 2, measured.width - stroke / 2),
                                                )
                                        drawLine(
                                            style.palette.tableBorder,
                                            Offset(x, edge.start.toFloat()),
                                            Offset(x, edge.end.toFloat()),
                                            strokeWidth = stroke,
                                        )
                                    }
                                }
                            }
                        },
                content = {
                    grid.cells.forEach { cell ->
                        key(cell.element.key) {
                            val textStyle =
                                bodyTextStyle.copy(
                                    fontWeight =
                                        if (cell.element.tag == "th") FontWeight.SemiBold
                                        else FontWeight.Normal,
                                    textAlign =
                                        tableTextAlign(
                                            cell.element,
                                            grid.rows[cell.row].element,
                                            grid.columns.getOrNull(cell.column),
                                            element,
                                        ),
                                )
                            Box(
                                elementModifier(cell.element)
                                    .semantics {
                                        collectionItemInfo =
                                            CollectionItemInfo(
                                                cell.row,
                                                cell.rowSpan,
                                                cell.column,
                                                cell.columnSpan,
                                            )
                                    }
                                    .padding(
                                        horizontal =
                                            style.spacing.tableCellHorizontal +
                                                style.spacing.borderWidth / 2,
                                        vertical =
                                            style.spacing.tableCellVertical +
                                                style.spacing.borderWidth / 2,
                                    ),
                            ) {
                                renderCell(cell.element, textStyle, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    grid.rows.forEach { row ->
                        key("row:${row.element.key}") { Box(elementModifier(row.element)) }
                    }
                },
            ) { measurables, tableConstraints ->
                val minWidths = IntArray(grid.columnCount)
                val maxWidths = IntArray(grid.columnCount)
                grid.columns.forEachIndexed { index, column ->
                    val requested = tableRequestedWidth(column, viewportWidth, density)
                    maxWidths[index] = requested
                }
                val fallbackWidth = max(1, viewportWidth / max(1, grid.columnCount))
                fun intrinsic(index: Int, minimum: Boolean): Int {
                    val measurable = measurables[index]
                    return try {
                        val result =
                            if (minimum) measurable.minIntrinsicWidth(Constraints.Infinity)
                            else measurable.maxIntrinsicWidth(Constraints.Infinity)
                        if (result == Constraints.Infinity) {
                            throw IllegalStateException("Unbounded table-cell intrinsic width")
                        }
                        result.coerceAtLeast(0)
                    } catch (_: IllegalStateException) {
                        val issue = "table_intrinsics_unavailable:${grid.cells[index].element.key}"
                        if (reportedIssues.add(issue)) latestOnIssue.value(issue)
                        fallbackWidth * grid.cells[index].columnSpan
                    } catch (_: IllegalArgumentException) {
                        val issue = "table_intrinsics_unavailable:${grid.cells[index].element.key}"
                        if (reportedIssues.add(issue)) latestOnIssue.value(issue)
                        fallbackWidth * grid.cells[index].columnSpan
                    }
                }
                // 先确定单列需求，再补齐跨列单元格，避免 DOM 顺序改变相同表格的列宽。
                val cellOrder = grid.cells.indices.sortedBy { grid.cells[it].columnSpan }
                cellOrder.forEach { index ->
                    val cell = grid.cells[index]
                    val requested = tableRequestedWidth(cell.element, viewportWidth, density)
                    val minimum = intrinsic(index, minimum = true)
                    val maximum = max(minimum, max(intrinsic(index, minimum = false), requested))
                    growTableSpan(minWidths, cell.column, cell.columnSpan, minimum)
                    growTableSpan(maxWidths, cell.column, cell.columnSpan, maximum)
                }
                maxWidths.indices.forEach { maxWidths[it] = max(maxWidths[it], minWidths[it]) }
                val available = max(viewportWidth, tableConstraints.minWidth)
                val widths = tableColumnWidths(minWidths, maxWidths, available)
                val columnOffsets = tableOffsets(widths)
                val placeables =
                    measurables.take(grid.cells.size).mapIndexed { index, measurable ->
                        val cell = grid.cells[index]
                        val width =
                            columnOffsets[cell.column + cell.columnSpan] -
                                columnOffsets[cell.column]
                        // 每个真实子项只测量一次；intrinsic 不会触发图片槽位的再次组合。
                        measurable.measure(Constraints.fixedWidth(width))
                    }
                val rowHeights = IntArray(grid.rows.size)
                grid.rows.forEachIndexed { index, row ->
                    rowHeights[index] = tableRequestedHeight(row.element, density)
                }
                grid.cells.indices
                    .sortedBy { grid.cells[it].rowSpan }
                    .forEach { index ->
                        val cell = grid.cells[index]
                        val height =
                            max(
                                placeables[index].height,
                                tableRequestedHeight(cell.element, density),
                            )
                        growTableSpan(rowHeights, cell.row, cell.rowSpan, height)
                    }
                val rowOffsets = tableOffsets(rowHeights)
                val width = columnOffsets.last()
                val height = rowOffsets.last()
                // 行盒承载真实行边界及锚点；单元格仍统一测量，允许 rowspan 跨越行盒。
                val rowPlaceables =
                    measurables.drop(grid.cells.size).mapIndexed { index, measurable ->
                        measurable.measure(Constraints.fixed(width, rowHeights[index]))
                    }
                geometry.value =
                    HtmlTableGeometry(
                        width,
                        height,
                        rowHeights,
                        rowOffsets,
                        tableEdges(grid.cells, columnOffsets, rowOffsets),
                    )
                layout(width, height) {
                    placeables.forEachIndexed { index, placeable ->
                        val cell = grid.cells[index]
                        val availableHeight =
                            rowOffsets[cell.row + cell.rowSpan] - rowOffsets[cell.row]
                        val extra = max(0, availableHeight - placeable.height)
                        val dy =
                            when (tableVerticalAlign(cell.element)) {
                                "top",
                                "baseline" -> 0
                                "bottom" -> extra
                                else -> extra / 2
                            }
                        placeable.placeRelative(
                            columnOffsets[cell.column],
                            rowOffsets[cell.row] + dy,
                        )
                    }
                    rowPlaceables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(0, rowOffsets[index])
                    }
                }
            }
        }
    }
}

private data class HtmlTableRow(
    val element: HtmlElement,
    val groupEnd: Int,
    val alternate: Boolean,
    val header: Boolean,
)

private data class HtmlTableCell(
    val element: HtmlElement,
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
)

private data class HtmlTableGrid(
    val rows: List<HtmlTableRow>,
    val cells: List<HtmlTableCell>,
    val columnCount: Int,
    val columns: List<HtmlElement>,
)

private fun htmlTableGrid(table: HtmlElement): HtmlTableGrid {
    val groups = mutableListOf<Pair<String, List<HtmlElement>>>()
    val declaredColumns = mutableListOf<HtmlElement>()
    val directRows = mutableListOf<HtmlElement>()
    fun addColumn(column: HtmlElement) {
        repeat(column.attr("span")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1) {
            declaredColumns += column
        }
    }
    fun flushDirectRows() {
        if (directRows.isNotEmpty()) groups += "tbody" to directRows.toList()
        directRows.clear()
    }
    table.children.filterIsInstance<HtmlElement>().forEach { element ->
        when (element.tag) {
            "col" -> addColumn(element)
            "colgroup" -> {
                val columns =
                    element.children.filterIsInstance<HtmlElement>().filter { it.tag == "col" }
                if (columns.isEmpty()) addColumn(element) else columns.forEach(::addColumn)
            }
            "tr" -> directRows += element
            "thead",
            "tbody",
            "tfoot" -> {
                flushDirectRows()
                val rows =
                    element.children.filterIsInstance<HtmlElement>().filter { it.tag == "tr" }
                if (rows.isNotEmpty()) groups += element.tag to rows
            }
        }
    }
    flushDirectRows()
    val rows = mutableListOf<HtmlTableRow>()
    groups
        .sortedBy { (tag, _) -> if (tag == "thead") 0 else if (tag == "tfoot") 2 else 1 }
        .forEach { (_, group) ->
            val end = rows.size + group.size
            group.forEachIndexed { index, row ->
                val cells =
                    row.children.filterIsInstance<HtmlElement>().filter { it.tag in CellTags }
                rows +=
                    HtmlTableRow(
                        row,
                        end,
                        alternate = index % 2 == 1,
                        header = cells.isNotEmpty() && cells.all { it.tag == "th" },
                    )
            }
        }
    val cells = mutableListOf<HtmlTableCell>()
    val occupiedUntil = mutableMapOf<Int, Int>()
    var columns = 0
    rows.forEachIndexed { rowIndex, row ->
        var column = 0
        row.element.children
            .filterIsInstance<HtmlElement>()
            .filter { it.tag in CellTags }
            .forEach { element ->
                // HTML 的 colSpan 上限是 1000；rowSpan=0 延伸至当前行组末尾。
                val columnSpan = element.attr("colspan")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                val remainingRows = row.groupEnd - rowIndex
                val requestedRows = element.attr("rowspan")?.toIntOrNull() ?: 1
                val rowSpan =
                    if (requestedRows == 0) remainingRows
                    else requestedRows.coerceIn(1, remainingRows)
                while (
                    (column until column + columnSpan).any { (occupiedUntil[it] ?: 0) > rowIndex }
                ) {
                    column++
                }
                cells += HtmlTableCell(element, rowIndex, column, rowSpan, columnSpan)
                repeat(columnSpan) { occupiedUntil[column + it] = rowIndex + rowSpan }
                column += columnSpan
                columns = max(columns, column)
            }
    }
    return HtmlTableGrid(rows, cells, max(columns, declaredColumns.size), declaredColumns)
}

/** 在最小宽度和 max-content 之间分配空间，只有最小需求已超出视口时才横向溢出。 */
private fun tableColumnWidths(minimum: IntArray, preferred: IntArray, viewport: Int): IntArray {
    val minTotal = minimum.sumOf(Int::toLong)
    val preferredTotal = preferred.sumOf(Int::toLong)
    val target = max(viewport.toLong(), minTotal).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val result = minimum.copyOf()
    val extra = target.toLong() - minTotal
    if (extra <= 0) return result
    val flexibility = preferred.indices.map { max(0L, preferred[it].toLong() - minimum[it]) }
    val budget = min(extra, max(0L, preferredTotal - minTotal))
    distributeTableExtra(result, flexibility, budget.toInt())
    val afterPreferred = target.toLong() - result.sumOf(Int::toLong)
    if (afterPreferred > 0) {
        distributeTableExtra(result, preferred.map { max(1L, it.toLong()) }, afterPreferred.toInt())
    }
    return result
}

private fun growTableSpan(widths: IntArray, start: Int, span: Int, requested: Int) {
    val current = (start until start + span).sumOf { widths[it].toLong() }
    val difference = requested.toLong() - current
    if (difference <= 0) return
    val each = difference / span
    val remainder = difference % span
    repeat(span) { index ->
        widths[start + index] += (each + if (index < remainder) 1 else 0).toInt()
    }
}

private fun distributeTableExtra(widths: IntArray, weights: List<Long>, extra: Int) {
    val total = weights.sum()
    if (extra <= 0 || total <= 0) return
    var consumed = 0
    var cumulativeWeight = 0L
    widths.indices.forEach { index ->
        cumulativeWeight += weights[index]
        val cumulative = (extra.toDouble() * cumulativeWeight / total).toInt()
        widths[index] += cumulative - consumed
        consumed = cumulative
    }
}

private fun tableOffsets(sizes: IntArray): IntArray {
    val result = IntArray(sizes.size + 1)
    sizes.forEachIndexed { index, size -> result[index + 1] = Math.addExact(result[index], size) }
    return result
}

private fun tableRequestedWidth(element: HtmlElement, viewport: Int, density: Float): Int {
    val value = tableCssProperty(element, "width") ?: element.attr("width") ?: return 0
    val pixels =
        if (value.endsWith('%')) value.dropLast(1).toFloatOrNull()?.times(viewport / 100f)
        else value.removeSuffix("px").toFloatOrNull()?.times(density)
    return pixels?.takeIf { it.isFinite() && it > 0f }?.roundToInt() ?: 0
}

private fun tableRequestedHeight(element: HtmlElement, density: Float): Int {
    val value = tableCssProperty(element, "height") ?: element.attr("height") ?: return 0
    return value
        .removeSuffix("px")
        .toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }
        ?.times(density)
        ?.roundToInt() ?: 0
}

private fun tableCssProperty(element: HtmlElement, property: String): String? =
    element
        .attr("style")
        ?.split(';')
        ?.mapNotNull { declaration ->
            val parts = declaration.split(':', limit = 2)
            parts
                .takeIf { it.size == 2 && it[0].trim().equals(property, ignoreCase = true) }
                ?.get(1)
                ?.trim()
                ?.lowercase()
        }
        ?.lastOrNull()

private fun tableTextAlign(
    element: HtmlElement,
    row: HtmlElement,
    column: HtmlElement?,
    table: HtmlElement,
): TextAlign =
    when (
        listOfNotNull(element, row, column, table)
            .firstNotNullOfOrNull { tableCssProperty(it, "text-align") ?: it.attr("align") }
            ?.lowercase()
    ) {
        "left" -> TextAlign.Left
        "right" -> TextAlign.Right
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        "end" -> TextAlign.End
        "start" -> TextAlign.Start
        else -> if (element.tag == "th") TextAlign.Center else TextAlign.Start
    }

private fun tableVerticalAlign(element: HtmlElement): String? =
    (tableCssProperty(element, "vertical-align") ?: element.attr("valign"))?.lowercase()

private data class HtmlTableEdge(
    val horizontal: Boolean,
    val fixed: Int,
    val start: Int,
    val end: Int,
)

private fun tableEdges(
    cells: List<HtmlTableCell>,
    columnOffsets: IntArray,
    rowOffsets: IntArray,
): List<HtmlTableEdge> {
    val edges = mutableListOf<HtmlTableEdge>()
    cells.forEach { cell ->
        val left = columnOffsets[cell.column]
        val right = columnOffsets[cell.column + cell.columnSpan]
        val top = rowOffsets[cell.row]
        val bottom = rowOffsets[cell.row + cell.rowSpan]
        edges += HtmlTableEdge(true, top, left, right)
        edges += HtmlTableEdge(true, bottom, left, right)
        edges += HtmlTableEdge(false, left, top, bottom)
        edges += HtmlTableEdge(false, right, top, bottom)
    }
    return edges
        .groupBy { it.horizontal to it.fixed }
        .values
        .flatMap { aligned ->
            val sorted = aligned.sortedBy(HtmlTableEdge::start)
            if (sorted.isEmpty()) return@flatMap emptyList()
            val merged = mutableListOf<HtmlTableEdge>()
            var current = sorted.first()
            sorted.drop(1).forEach { next ->
                if (next.start <= current.end)
                    current = current.copy(end = max(current.end, next.end))
                else {
                    merged += current
                    current = next
                }
            }
            merged += current
            merged
        }
}

private data class HtmlTableGeometry(
    val width: Int,
    val height: Int,
    val rowHeights: IntArray,
    val rowOffsets: IntArray,
    val edges: List<HtmlTableEdge>,
)

private class HtmlTableGeometryHolder {
    var value: HtmlTableGeometry? = null
}

private val CellTags = setOf("th", "td")

private fun tableNodeTag(element: HtmlElement): String {
    val identity =
        element.attr("data-fixture-id")?.takeIf(String::isNotBlank)
            ?: element.attr("id")?.takeIf(String::isNotBlank)
            ?: element.key
    return "html:$identity"
}
