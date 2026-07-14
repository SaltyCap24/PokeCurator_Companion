# PokéCurator Companion (Android overlay)

A tiny native Android app that floats your PokéCurator **cleanup plan** above
Pokémon GO / PGSharp, so you never have to app-switch while transferring.

- A draggable 🧹 **bubble** sits on top of the game.
- Tap it to expand a **panel** showing the current species: which copies to
  favorite first, the two in-game searches (`squirtle` and `squirtle&!favorite`)
  with **Copy** buttons, and Prev / Skip / **Done ▶** navigation.
- Progress is saved per species, so you can stop and resume anytime.

It talks to your existing PokéCurator server using the **same sync URL/token**
you already use for PGSharp — no separate login.

## Build & install

You need **Android Studio** (Giraffe or newer) and an Android phone (API 26+ /
Android 8.0+).

1. Open this folder (`PokeCurator Android`) in Android Studio.
   - The Gradle wrapper jar isn't committed (it's a binary). Android Studio will
     offer to generate it automatically. If it doesn't, run once in a terminal:
     `gradle wrapper --gradle-version 8.7` (needs a system Gradle), or use
     Android Studio's **File → Sync Project with Gradle Files**.
2. Plug in your phone with **USB debugging** on, pick it as the run target.
3. Press **Run** (▶). The app installs as **PokéCurator Companion**.

## Use it

1. Open the app. **Paste your sync URL** (from PokéCurator → Settings →
   Auto-sync with PGSharp → Copy) and tap **Save URL**.
2. Tap **Grant "Display over other apps"** and enable the permission.
3. Tap **Start overlay**, then open Pokémon GO.
4. The 🧹 bubble floats on top. Tap it to open the panel and work one species at
   a time: favorite the keepers it lists, copy `species&!favorite`, select-all →
   Transfer, then **Done ▶** to advance.

## Notes / safety
- The app is **read-only** against your box — it only *shows* the plan and copies
  search text. It never automates taps or touches your Pokémon GO account.
- The plan is fetched from `GET /api/export/transfer-session?token=…` (token from
  your sync URL). Change the favorites mode by editing `Prefs.mode` (auto /
  respect / reconcile) — defaults to `auto`.
- If the plan looks stale, sync in PokéCurator first, then tap ↻ in the panel.

## Project layout
- `app/src/main/java/com/pokecurator/companion/`
  - `MainActivity.kt` — setup screen (URL, permission, start/stop).
  - `OverlayService.kt` — foreground service; the bubble + panel via WindowManager.
  - `Api.kt` — fetches the plan (plain HttpURLConnection, no HTTP dependency).
  - `Models.kt` — Plan/Step/Specimen parsing.
  - `Prefs.kt` — stores the sync URL + progress; derives the plan URL from it.
- `app/src/main/res/layout/` — `activity_main`, `overlay_bubble`, `overlay_panel`.
