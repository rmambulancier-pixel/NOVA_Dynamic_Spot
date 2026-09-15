# NOVA Dynamic Spot V6 — Android 17

Clean native Android build for Pixel / Android 17.

- AGP 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk 37 / targetSdk 37
- Java only: no Kotlin source, no Compose, no AndroidX UI dependencies
- No custom Android resources: platform theme + literal labels only
- GitHub Actions removes stale Kotlin/resource files from previous versions before building
- Debug APK artifact only

Features: notification listener, media sessions, album art, progress, play/pause, previous/next swipe, battery/charging events, local settings, overlay Dynamic Spot.
