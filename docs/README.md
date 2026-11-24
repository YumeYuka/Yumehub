**English** | [简体中文](README_ZH_HANS.md)

# Yumehub

> **Yumehub** takes **Yume** from **@YumeYuka** and **hub** from **GitHub**.

Yumehub is an open-source third-party client for GitHub Mobile on Android, built to address the
official app's shortcomings. It supports all GitHub Mobile features and aims to replace the
official client. Beyond the core client features, we may add extensibility features in
the future.

## Background

For a long time, the official GitHub Android app has offered a terrible experience, and the
maintainers seem to have no intention of fixing it. To promote Copilot, they even replaced
navigation-bar features with Copilot Chat. This disruption to the existing navigation can be seen
throughout the app.

The UI regressions are only part of the problem. Performance is even harder to accept: whenever you
open a repository or pull request, a loading spinner appears before the content does. Rather than
wait for the official app to be fixed and optimized, we decided to rewrite it from the ground up.

## Notifications

Unread notifications have a dot below the event icon. GitHub Actions notifications show the
check result and open the workflow run when GitHub provides a run link.
