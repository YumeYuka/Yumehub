package moe.yumeyuka.markdown.compose

/** 正文可自行拥有选择容器，或参与宿主固定视口中的连续选择。 */
enum class NativeHtmlSelectionMode {
    /** 保留独立正文的有界布局和内部滚动；外层列表长文应使用 [Inherited]。 */
    Own,

    /** 宿主提供 SelectionContainer 与纵向滚动，正文按自然高度完整组合。 */
    Inherited,
}
