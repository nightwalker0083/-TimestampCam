# 時間浮水印相機（TimestampCam）

Android App: after you tap "拍照" (take photo), it opens your phone's own camera. When the shot comes back, the app adds a watermark at the bottom-left and saves it to the album at **Pictures/TimeStamp**.

```
12:14 ┃ 2026.10.10
      ┃ 星期六 · 12:14:05
新北市板橋區○○路○號
25.012345, 121.456789  ±8m
```

- The time comes from when the photo was taken (taken from the photo's EXIF data).
- Location: GPS plus network positioning. The address is looked up automatically from coordinates (needs internet; if offline, only coordinates are shown).
- The photo's EXIF data keeps the shot time and GPS info, so Google Photos and similar apps can show it on a map.
- Burst mode: tick it and the camera reopens automatically after each save — good for events where you shoot one after another.
- Supports Android 7.0 and up.

---

## How to get the APK (pick one)

### Option A: Let GitHub build it for you (no software to install, recommended)

1. Log in to github.com → click **New repository** at top right → enter any name (e.g. `TimestampCam`) → Create.
2. On the repository page, click **uploading an existing file** and drag in **everything** from inside this folder (including the `.github` folder, `gradlew`, and `app` — upload the whole thing) → Commit.
   - If hidden folders (`.github`) can't be dragged in, use GitHub Desktop or the `git` command to upload instead.
3. Click the **Actions** tab at the top; it starts building automatically (about 3–5 minutes).
4. Once it shows a green ✅, click into that run, go to **Artifacts** at the bottom, and download `TimestampCam-apk` (it's a zip; the extracted file is `app-debug.apk`).

### Option B: Android Studio

1. Install Android Studio (free).
2. Choose **Open** and pick this `TimestampCam` folder; wait for Gradle Sync to finish (it downloads the needed files the first time).
3. Menu **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
4. The finished file is at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on the phone

1. Send `app-debug.apk` to your phone (LINE, Google Drive, or USB all work).
2. Tap the file to install; if it says "unknown source", allow installing from this source in the prompt.
3. On first launch, allow the **location permission** (pick "precise location" so the address is more accurate).

## FAQ

- **Watermark says "未取得定位" (no location)**: check that phone location is on. Indoors, the first fix can take 10–30 seconds — wait until the top of the screen shows an address before shooting.
- **Some phones have multiple camera apps**: the first time, it asks which one to use; picking the phone's built-in camera is most reliable.
