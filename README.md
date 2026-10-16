# Galaxy Tv

A minimal Android application project for the Galaxy TV brand.

## Features
- Android app foundation with Kotlin
- Splash-style landing screen
- Simple TV-themed UI
- Ready for further screens and API integration

## Run locally

```bash
cd /workspaces/galaxy
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew assembleDebug
```

## Project structure
- `app/src/main/java/com/galaxytv/app/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/AndroidManifest.xml`

This project is currently configured to build against the installed Android SDK in `/usr/lib/android-sdk`.

## Server connection

Android and Apple clients use the Maghreb V6 API at `http://maghrebtv.xyz:2082/iptv/V6/API-V6.php`. Device resource detection selects the lite API when the device has 2 GB or less of RAM or no more than two processor cores; other devices use the normal package API. The server-provided `user_agent` is saved after activation and used for stream playback.

The configured failover endpoint is the same address as the main host, as requested; it does not provide independent redundancy if that host is unavailable. The API uses cleartext HTTP and a client-embedded XOR key; neither protects the activation code or API traffic from interception or reverse engineering. Use HTTPS and server-side secrets for production.
