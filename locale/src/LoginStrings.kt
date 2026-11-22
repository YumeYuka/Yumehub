package moe.yumeyuka.yumehub.locale

public data class GithubStrings_Login(
    val Title: String,
    val SignIn: String,
    val AccountHint: String,
    val Terms: String,
    val LegalSeparator: String,
    val Privacy: String,
)

internal val ZhHansLoginStrings: GithubStrings_Login =
    GithubStrings_Login(
        Title = "欢迎使用 Yumehub",
        SignIn = "继续",
        AccountHint = "使用 GitHub 账号登录",
        Terms = "服务条款",
        LegalSeparator = "  ·  ",
        Privacy = "隐私声明",
    )

internal val EnLoginStrings: GithubStrings_Login =
    GithubStrings_Login(
        Title = "Welcome to Yumehub",
        SignIn = "Continue",
        AccountHint = "Sign in with GitHub",
        Terms = "Terms of Service",
        LegalSeparator = "  ·  ",
        Privacy = "Privacy Statement",
    )
