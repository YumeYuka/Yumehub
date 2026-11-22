package moe.yumeyuka.markdown.sample

/** 文件名只含受校验的场景标识和数值，便于 adb 定点取出。 */
object AcceptanceArtifacts {
    fun name(config: AcceptanceConfig): String =
        "${config.caseName}_${config.mode.name.lowercase()}_${if (config.dark) "dark" else "light"}_" +
            "${config.widthDp}dp_f${config.fontScale}_r${config.revision}"
}
