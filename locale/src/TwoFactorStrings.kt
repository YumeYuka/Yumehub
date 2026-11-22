package moe.yumeyuka.yumehub.locale

public data class GithubStrings_TwoFactor(
    val NotificationChannel: String,
    val NotificationTitle: String,
    val NotificationBody: String,
    val LoginTitle: String,
    val DeviceTitle: String,
    val ResetTitle: String,
    val SudoTitle: String,
    val DigitsMessage: String,
    val DigitsPlaceholder: String,
    val ConfirmationMessage: String,
    val Approve: String,
    val Reject: String,
)

internal val ZhHansTwoFactorStrings: GithubStrings_TwoFactor =
    GithubStrings_TwoFactor(
        NotificationChannel = "登录验证",
        NotificationTitle = "GitHub 登录验证请求",
        NotificationBody = "点按打开应用，确认是否为本人操作",
        LoginTitle = "登录验证",
        DeviceTitle = "设备验证",
        ResetTitle = "重置密码验证",
        SudoTitle = "身份确认",
        DigitsMessage = "输入另一台设备上显示的两位数字",
        DigitsPlaceholder = "两位数字",
        ConfirmationMessage = "确认是本人操作吗",
        Approve = "批准",
        Reject = "拒绝",
    )

internal val EnTwoFactorStrings: GithubStrings_TwoFactor =
    GithubStrings_TwoFactor(
        NotificationChannel = "Login verification",
        NotificationTitle = "GitHub login verification request",
        NotificationBody = "Tap to open the app and confirm it is you",
        LoginTitle = "Login verification",
        DeviceTitle = "Device verification",
        ResetTitle = "Password reset verification",
        SudoTitle = "Identity confirmation",
        DigitsMessage = "Enter the two digits shown on your other device",
        DigitsPlaceholder = "Two digits",
        ConfirmationMessage = "Confirm it is you",
        Approve = "Approve",
        Reject = "Reject",
    )
