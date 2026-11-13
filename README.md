# Expense Tracker (Android, Kotlin + Jetpack Compose)

Open this folder in **Android Studio (Ladybug or newer)** → let Gradle sync → Run.

## Features
Add / edit / delete (swipe left, with undo) · amount, date & time, category, subcategory, tags ·
All-expenses list with Week / Month / Year / Custom period and category / tag filters ·
Statistics by category, subcategory, tags, month, any period · Light / Dark / System with 3 styles each ·
6 accent colours · 4 fonts · Excel (.xlsx) sync to Google Drive and/or GitHub, automatic and manual.

## Cloud setup
**GitHub:** Settings → Sync → enter `owner/repo`, branch, file path, and a fine-grained personal
access token (repo permission *Contents: Read and write*). Tap *Sync now*.

**Google Drive:** needs a one-time Google Cloud setup (free):
1. console.cloud.google.com → new project → enable **Google Drive API**.
2. OAuth consent screen → add yourself as a test user; add scope `.../auth/drive.file`.
3. Credentials → Create **OAuth client ID** → type **Android** → package `com.personal.expensetracker`
   and the SHA-1 of your debug key (`./gradlew signingReport`).
4. In the app: Settings → Sync → Google Drive → Connect.
The app only sees the `expenses.xlsx` file it creates itself (scope `drive.file`).

## Notes
- Sync writes one `expenses.xlsx` (sheet "Expenses": ID, Date, Time, Amount, Category, Subcategory, Tags).
- "Restore" / "Import" merge a file back in by ID (rows with a blank ID get a new one), so you can edit in Excel.
- Auto sync runs ~10 s after a change (WorkManager, needs network); schedule = Off / Daily / Weekly.
- Fonts use Android system families. To use Inter/Lora etc., add TTFs to `res/font` and extend `Fonts` in `Theme.kt`.
- The GitHub token is stored in app-private SharedPreferences (not encrypted).
