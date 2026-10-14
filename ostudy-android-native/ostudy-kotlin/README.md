# Ostudy Academy – native Android app (Kotlin + Jetpack Compose)

## What it does now
Login / register / forgot password · Courses and Tutors search with your Course Search Pro filters · Course page, curriculum, lessons (video + text), mark complete · My learning · Checkout with offline payment + receipt upload · My orders · Profile, language (Arabic / French / English, RTL) · Tutor tools: overview, approve/reject payments, payment methods list/delete · Messages, private sessions and a few tutor pages open the website inside the app (already logged in).

Not native yet: Messages, private sessions, adding payment methods, push notifications.

NOT compiled yet (written without Android Studio). The first build may show a few small red errors – send me a screenshot or the text and I fix them.

## Step 1 – WordPress (once)
1. Plugins > Add New > Upload > `ostudy-mobile-api.zip` > Activate.
2. Open `https://YOUR-SITE/wp-json/ostudy/v1/config` in a browser – you must see text starting with `{`.
3. Your site must use **https**, and Application Passwords must not be blocked by a security plugin.

## Step 2 – Windows PC (once)
1. Install **Android Studio** (https://developer.android.com/studio). Accept all defaults; it installs the SDK.

## Step 3 – Open the project
1. Unzip `ostudy-android-native.zip` (right click > Extract All).
2. Android Studio > **Open** > choose the folder `ostudy-kotlin` (the one that contains `settings.gradle.kts`).
3. Wait for "Gradle sync" at the bottom to finish (first time 5–15 min, needs internet). Press **Trust project** if asked.
4. Site address: open `app/build.gradle.kts`, find `val siteUrl = "https://dev-ostudy.pantheonsite.io"`. Change it only when you go live.

## Step 4 – Test on your phone
1. Phone: Settings > About phone > tap "Build number" 7 times > Developer options > turn on **USB debugging**.
2. Plug in the phone, accept the popup on it.
3. In Android Studio pick your phone at the top and press the green ▶ button.

## Step 5 – Make the APK to send to people
Build > **Generate Signed App Bundle / APK** > **APK** > Create new keystore (keep the file and passwords forever – needed for every update) > release > Finish. Click "locate" to find `app-release.apk`.

## If something fails
- Red text after Gradle sync: copy it and send it to me.
- Login says "Application passwords are disabled": site is not https or a security plugin blocks them.
- Empty course list: open `/wp-json/ostudy/v1/diagnostics` as admin and send me the result.
