# MPU ERP – Android App

Android app for the **Mind Power University (MPU) ERP**, built with **Kotlin** and **Jetpack Compose**. It wraps the ERP web portal in a WebView and adds native features on top: auto-login, session persistence, pull-to-refresh and offline handling.

## Features

- ERP portal inside a native Compose WebView
- Auto-login and session persistence (admin and user logins)
- Pull-to-refresh with correct scroll-state handling
- Offline detection and handling
- Keyboard/focus handling (no IME popping up after login or navigation)
- Smooth splash/loading flow without login page flicker

## Tech Stack

- Kotlin
- Jetpack Compose
- Android WebView (with JS bridge)
- Gradle (Kotlin DSL)

## Getting Started

### Requirements

- Android Studio (latest stable)
- JDK 17+
- Android SDK (API 24+)

### Run Locally

```bash
git clone https://github.com/sachin-cyber904/mpu-erp2-full-working-app.git
cd mpu-erp2-full-working-app
```

1. Open the project in Android Studio.
2. Let Gradle sync finish.
3. Connect a device or start an emulator.
4. Click **Run ▶**.

### Build Release APK / AAB

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

Output:

- APK: `app/build/outputs/apk/release/`
- AAB: `app/build/outputs/bundle/release/`

Note: add your own keystore details in `keystore.properties` or `app/build.gradle.kts` for signing.

## Project Structure

```
app/
 └── src/main/
      ├── java/.../        # Activities, Compose UI, WebView setup
      ├── res/             # Icons, themes, strings
      └── AndroidManifest.xml
build.gradle.kts
settings.gradle.kts
```

## Configuration

Set the ERP base URL in the WebView/config file of the app:

```kotlin
const val BASE_URL = "https://your-erp-url.com"
```

## Known Fixes Included

- Admin login page no longer flashes before the auto-login redirect
- Pull-to-refresh/scroll state resets correctly after logout
- Keyboard no longer pops up on every touch after login

## Author

**Sachin**
- GitHub: [sachin-cyber904](https://github.com/sachin-cyber904)
- LinkedIn: [Sachin Bhatt](https://www.linkedin.com/in/sachin-bhatt-004a3b34a)
- Email: sachinbhatt865@gmail.com

## License

This project is for Mind Power University. All rights reserved.
