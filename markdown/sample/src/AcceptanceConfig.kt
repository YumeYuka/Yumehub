package moe.yumeyuka.markdown.sample

import android.content.Intent

/** 验收场景全部由 Intent 或仪器测试指定，不把调试控件加入产品页面。 */
data class AcceptanceConfig(
    val caseName: String = "basic",
    val mode: RenderMode = RenderMode.Native,
    val dark: Boolean = false,
    val widthDp: Int = 360,
    val heightDp: Int = 740,
    val fontScale: Float = 1f,
    val preview: Boolean = true,
    val callbacks: Boolean = true,
    val revision: Long = 1,
    val scrollToAnchor: String? = null,
) {
    init {
        require(caseName.matches(Regex("[a-z0-9][a-z0-9_-]{0,95}")))
        require(widthDp in 240..1200 && heightDp in 200..2400)
        require(fontScale in 0.5f..3f)
    }

    val identity: String
        get() {
            val anchorIdentity = scrollToAnchor?.let { "${it.length}:$it" } ?: "-"
            return "$caseName:${mode.name}:$dark:$widthDp:$heightDp:$fontScale:$preview:$revision:$callbacks:$anchorIdentity"
        }

    companion object {
        fun fromIntent(intent: Intent): AcceptanceConfig =
            AcceptanceConfig(
                caseName = intent.getStringExtra("case") ?: "basic",
                mode =
                    if (intent.getStringExtra("mode") == "reference") RenderMode.Reference
                    else RenderMode.Native,
                dark = intent.getBooleanExtra("dark", false),
                widthDp = intent.getIntExtra("width_dp", 360),
                heightDp = intent.getIntExtra("height_dp", 740),
                fontScale = intent.getFloatExtra("font_scale", 1f),
                preview = intent.getBooleanExtra("preview", true),
                callbacks = intent.getBooleanExtra("callbacks", true),
                revision = intent.getLongExtra("revision", 1),
                scrollToAnchor = intent.getStringExtra("anchor"),
            )
    }
}

enum class RenderMode {
    Reference,
    Native,
}
