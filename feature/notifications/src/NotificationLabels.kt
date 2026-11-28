package moe.yumeyuka.yumehub.feature.notifications

import moe.yumeyuka.yumehub.locale.NotificationsStrings

internal fun NotificationFilter.label(strings: NotificationsStrings): String =
    when (this) {
        NotificationFilter.Unread -> strings.Unread
        NotificationFilter.Inbox -> strings.Inbox
        NotificationFilter.Done -> strings.Done
    }

internal fun NotificationReasonFilter.label(strings: NotificationsStrings): String =
    when (this) {
        NotificationReasonFilter.All -> strings.AllReasons
        NotificationReasonFilter.Mention -> strings.ReasonMention
        NotificationReasonFilter.TeamMention -> strings.ReasonTeamMention
        NotificationReasonFilter.ReviewRequested -> strings.ReasonReview
        NotificationReasonFilter.Assigned -> strings.ReasonAssigned
        NotificationReasonFilter.Comment -> strings.ReasonComment
        NotificationReasonFilter.Author -> strings.ReasonAuthor
        NotificationReasonFilter.Subscribed -> strings.ReasonSubscribed
        NotificationReasonFilter.StateChange -> strings.ReasonStateChange
        NotificationReasonFilter.CiActivity -> strings.ReasonCi
        NotificationReasonFilter.ApprovalRequested -> strings.ReasonApproval
        NotificationReasonFilter.SecurityAlert -> strings.ReasonSecurity
        NotificationReasonFilter.Invitation -> strings.ReasonInvitation
        NotificationReasonFilter.Manual -> strings.ReasonManual
    }
