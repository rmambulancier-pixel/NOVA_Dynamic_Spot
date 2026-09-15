# NOVA Dynamic Spot V3

Build reliability first. Android 17 / API 37 / Pixel.

V3 removes the Compose/UI dependency stack and uses only Android platform UI + native media/notification APIs. This deliberately minimizes resource merging and dependency surface after V2 exposed an AAPT2 resource compilation failure.

Features:
- Dynamic Spot overlay
- Media metadata, album art and live progress
- Tap play/pause
- Swipe next/previous
- Notification event queue
- Battery/charging/low-battery events
- Local switches stored in SharedPreferences
- No account, server, ads, analytics, Firebase or Sentry

Build: JDK 17 + Gradle 9.6.1 + AGP 9.4.0. The project uses AGP 9 built-in Kotlin and therefore does not apply `org.jetbrains.kotlin.android`.
