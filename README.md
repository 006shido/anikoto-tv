# Anikoto TV

Unofficial Android TV app for anikoto.cz. Native poster rows, D-pad focus, title preview, search, episode selection, and separate SUB/DUB server choices. The selected website player renders video inside Android WebView; the app provides native remote controls.

## Library

- Scrollable spotlight banner: Left/Right changes the featured title; OK opens it.
- Separate Latest Episodes, Top Anime, Upcoming Anime, New Releases, Newly Added, and Just Completed rows, including SUB/DUB counts and format metadata.
- Latest Episodes filters: All, Sub, Dub, Trending, and Random. Top Anime filters: Day, Week, and Month.
- Browse contains Genres, Types, Updated, Added, Popular, Upcoming, Ongoing, and Completed. Category lists support previous/next pages. Popular remains inside Browse, not in the home top bar.
- Schedule shows a seven-day selector, previous/next week, local timezone, airing times, and episode links. Today's schedule also appears at the bottom of Home. Schedule times are estimates supplied by the site.

## Install

The built debug APK is `Anikoto-TV.apk` in the project root. Android TV 6.0 or newer is required. Install with `adb install -r Anikoto-TV.apk`. Launch Anikoto TV from the TV apps screen.

## Remote

- Browse posters with arrows and open with OK.
- Search opens an inline field above the library, not a dialog. Results below update 450 ms after typing pauses. New results preserve input focus; Down or the keyboard's Search action moves into the results. Home or Back leaves search.
- Choose an episode and a SUB or DUB server before playback.
- The player shows the title at top left and Speed, Episodes, Server, and Subtitles at top right.
- The bottom seek bar shows elapsed time on the left and total duration on the right. Focus it and use left/right to seek by ten seconds. Compact rewind, Play/Pause, and forward controls sit beneath it.
- There is no Back button in the player overlay; use the remote's Back key to return to browsing.
- Native Skip Intro/Skip Outro buttons appear just above the seek bar on the right when supplied by the host. From the seek bar press Up to focus the available skip button, then OK. The embedded skip buttons stay invisible.
- Speed offers 0.5x, 0.75x, 1x, 1.25x, 1.5x, 1.75x, and 2x. The chosen speed is saved across app restarts and applied to subsequent videos.
- The dock hides after five seconds of inactivity once video is available. OK or an arrow brings it back. Left/right while hidden also seeks ten seconds.
- Menu opens the dock. Hardware Play/Pause keys control playback directly. Back returns to browsing.
- The original player controls are hidden to avoid duplicate seek bars. The Subtitles menu uses JW Player's caption list and selection API when available, with an HTML subtitle/caption track fallback. Metadata tracks are excluded.

## Build

Use JDK 17 and Android SDK Platform 35, with `ANDROID_HOME` pointing at the SDK. On Windows run `.\gradlew.bat assembleDebug`; on macOS/Linux run `./gradlew assembleDebug`. The included wrapper downloads Gradle 8.11.1. Gradle outputs `app/build/outputs/apk/debug/app-debug.apk`.

Local tools downloaded for this workspace are ignored under `.build-tools/`. Dependencies: jsoup 1.18.3 and AndroidX WebKit 1.12.1. Update Android System WebView on the TV; native cross-frame playback controls require document-start script and web message listener support.

## Verification and limitations

Built and installed over wireless ADB on a Haier Matrix Android TV. Verified native catalog rendering, episode/server data loading, and a selected Vidstream-2 embedded video reaching ready state 4. Hardware Play/Pause changed the paused state. Native plus/minus ten-second buttons changed playback time by exactly ten seconds using D-pad/OK. Confirmed the original JW Player controls have display:none and the native dock disappears after inactivity.

Verified the Speed dialog using D-pad/OK: selecting 1.5x changed the embedded video's playbackRate to 1.5 and saved the preference on the TV.

Verified the new title/settings/seek layout on the TV, focus on native Skip Outro with the D-pad, and OK seeking to the host's outro endpoint (1430 seconds). Verified a native subtitle menu with Off, English, Indonesian, and Thai, and the cross-frame subtitle command changing JW Player's caption index from 1 to 0.

Verified inline live searches for Naruto and Bleach on the TV without submitting. Matching posters appeared below the field, and the input retained focus while results updated. Search requests use a separate executor from poster image downloads and ignore stale responses.

Verified the expanded home layout and Left/Right spotlight changes on the TV. Verified Browse/Genres and category results, and schedule dates/times/episode links in Asia/Kolkata. The live Sub filter and dated schedule AJAX endpoints returned success. Some category feeds, dates, and titles remain dependent on the website and are not exhaustively tested.

The app depends on the site's markup and video hosts. Not every title, server, subtitle format, or device is verified. Video uses the hosted web player. No protected streams are extracted. Top-level navigation outside HTTPS anikoto.cz is blocked. The script message listener receives playback status only; it exposes no native account, file, or navigation actions. WebView inspection is enabled for debug builds only.

Not affiliated with Anikoto. The website supplies content and playback.
