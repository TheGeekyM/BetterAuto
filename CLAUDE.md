# BetterAuto — notes for working on this project

BetterAuto is the **phone side of the Android Auto protocol (AAP)**: a replacement for the Android Auto
app. The head unit (car) is unchanged; the phone renders its own launcher on a virtual display, encodes it
as H.264 and streams it, plus audio, over USB or wirelessly (a wireless head unit or dongle). Kotlin, Jetpack Compose (foundation
only, no Material), minSdk 29. User-facing docs: `README.md`; build, bench and debugging details:
`docs/DEVELOPMENT.md` (read it before touching the protocol or the bench).

This repository is **public**: never commit credentials, IP addresses, device serials, personal data or
anything copied from closed-source apps.

## Working agreements

- **Language**: code, comments, logs and commit messages in English. UI strings in English
  (`res/values`) with an Italian translation (`res/values-it`) for every string; the phone's locale picks.
  The user talks in Italian.
- **Comments**: short, saying *why*, in the tone of the surrounding code. KDoc on classes and non-obvious
  functions. Match the existing naming and idioms; don't add frameworks or libraries casually.
- **Line endings**: files are a mix of CRLF and LF; keep each file's own. Edit with tools that preserve
  them (a Python script reading/writing with `newline=""` is safe; `sed -i` in Git Bash is not).
- **Every batch ends with**: build, unit tests, and a check on the real phone + head unit
  (`scripts\deploy.cmd -Tests`, then a session and a look at the car screen / log).
- **Ask before** big designs or user-visible behaviour changes; small bug fixes can just be done and
  reported. Commit/push only when asked; stage files **by name**, never `git add -A`.
- **Certificates**: `app/src/main/assets/` holds the phone's TLS identity, found by content. Only our own
  self-signed development pair (`phone_cert.pem`, `phone_key.pem`) is public. Anything else put there
  (e.g. a certificate to try a factory head unit) is ignored by `.gitignore` and must never be committed;
  neither must the deletion of our pair. Check `git status` on that folder before every commit. Both may sit
  there: a build takes yours, `-Pbetterauto.certs=dev` ours (public releases).
- **Releases**: `scripts\release.cmd [-DryRun]` (version in `app/build.gradle.kts`, notes in `CHANGELOG.md`
  EN + IT, under "Unreleased" until released; `docs/DEVELOPMENT.md`, "Releases"). Every user-visible change
  gets a line in "Unreleased".
- **Exlap credentials** (VW group vehicle data) are built into `ExlapSettings`: they are publicly known,
  so the user keeps them in the code on purpose. Leave them there, but never echo them in logs or docs.

## Map of the code (`app/src/main/java/com/simoneluconi/betterauto/`)

| Package | What lives there |
|---|---|
| `transport/` | Byte pipe to the head unit: `Transport` interface, `UsbAccessoryTransport`, `TcpTransport`. `wireless/`: `WirelessConnector` (Bluetooth RFCOMM bootstrap `WirelessBootstrap`, WiFi join `WifiJoin` with its pure rules in `JoinPolicy` (specifier, timeouts, which failures may be retried: never Android's refusal or the driver's answer to its dialog), TCP; `WifiPairing`: the car's WiFi associated by BSSID through the companion device manager, so Android skips its dialog), auto-connect (`WirelessAutoConnect` + `WirelessCompanionService`, companion device manager; every device used is watched, whichever comes up connects). Retries and the setup timeout live in `AapService` |
| `aap/` | The protocol: `AapSession` (control channel: version, TLS, auth, service discovery, opens channels), `FrameCodec`, `AapTls`, one class per service channel (`VideoChannel`, `AudioSinkChannel`, `InputChannel`, `SensorChannel`, `MicrophoneChannel`, `MediaStatusChannel`, `PhoneStatusChannel` (calls and signal for the cluster), `NavigationStatusChannel` (turn-by-turn for the cluster/head-up display once the car sends START: old turn/distance events and new steps/position both, the arrow as a picture for IMAGE clusters), `VendorExtensionChannel`, `BluetoothChannel` (pairs the phone with the car for calls; confirmed through Shizuku when there)), `ConnectionProblem` (why a car refused us) |
| `AapService` | Foreground service owning the session: USB attach → session, teardown, notifications (including connection-problem explanations) |
| `video/` | `VideoEncoder` (MediaCodec H.264 from a Surface); a stall watchdog in `VideoChannel` rebuilds the pipeline |
| `projection/` | `Projection` (virtual display + `LauncherPresentation`), touch/key/rotary injection into the UI |
| `audio/` | Phone audio to the car: `CarAudio` picks the way. With Shizuku ready, `ShizukuCapture` + `AudioRouterService` (an AIDL user service running as the shell user, audio policy loopback mix, system APIs by reflection) *move* it; otherwise `AudioCapture` *copies* it (MediaProjection) and `PhoneSpeakerMute` mutes the phone (to its lowest step, never zero: YouTube mutes its own player at zero, and the copy, taken after each app's own volume, went silent; `CopyWatch` gives the speaker back to a player the copy does not hear, and keeps it with one that goes quiet whenever muted). Navigation prompts: with Shizuku a second route feeds the car's guidance stream (`AudioSinkChannel` for both streams, `CarAudioFocus` shares the one audio focus); capture cannot take them, so otherwise `NavPrompts` notices them playing on the phone, lifts its speaker and ducks the music in the car. `PcmQuantizer` (float → dithered PCM, gain ramp) is shared. Also the car microphone |
| `media/` | `NowPlaying` (follows the active media session, never our radio's or a call's (`CALL_SESSIONS`); the radio tuning in pauses the player, a player starting stops the radio), media apps, library browsing |
| `phone/` | Calls (InCallService companion), contacts/call log, phone status (signal, battery; `PhoneSignal` shares the telephony listeners) |
| `notifications/` | Notifications shown in the car (`CarNotifications`, with each chat's RemoteInput reply action); `MessageText` (pure): who wrote, what is read aloud, the driver's own messages, verification codes |
| `voice/` | Voice commands: on-device speech recognition, `CommandParser`, `VoiceAssistant`. `cloud/`: the online assistant (build keys `betterauto.geminiKey`/`anthropicKey` in local.properties): `Brain` (online or not, the fast local path), `Gemini` (speech to text, streamed speech), `ClaudeAgent` (streamed, strict tools in `CloudTools` mapped onto `VoiceCommand`), `CloudSpeech`, `Persona`, `Conversation`. `MessageReader` (new messages read aloud during a session, waiting for calls, the assistant and navigation prompts; the reply dictated through `VoiceAssistant.ask`, a question of the assistant's own), `CallAnnouncer` (the caller said by name, answered or declined by voice). `wake/`: "hi batata" hands-free, `WakeListener` (during a car session: Vosk's small English model from `assets/vosk-model` (unpacked to `files/`) with a grammar of look-alikes, on the phone's microphone, then `VoiceAssistant.start`; lets the microphone go through `releaseMic()` before the assistant opens one; our phone-played sounds call `mute(ms)`), `WakeGate` (pure: release for calls, the assistant, a message being read; mute for our sounds and navigation prompts, 2 s after), `WakeMatcher` + `WakeDetector` (pure: phonetic score, sensitivity threshold) |
| `home/` | Data behind the home widgets: `Agenda` + `CalendarEvents` (calendar), `FavoriteContacts`, `WeatherWidgetHost` + `WeatherProviders` (the phone's weather app widget, hosted like a launcher). The widgets themselves and their registry (`HomeWidgets`, one `HomeWidgetKind` per `WidgetType`, with the `Section` its double tap opens) are in `ui/widgets/` (also `HomeGeometry`, `HomeEditor`; `WidgetArea` draws the home); the arrangement (rows or columns of places, one per kind of car screen; `mainPlace` the biggest, `promoted` the swap a tap makes) in `settings/HomeArrangement` |
| `prayer/` | Prayer times, offline: `PrayerTimes` (pure: the PrayTimes.org-style sun math, `PrayerMethod` (Egyptian by default), `AsrJuristic`, the angle-based rule at high latitudes, nothing when the sun never rises or sets), `PrayerPlace` (the map's last fix, else the phone's last known location, else `PrayerCity` from the settings), `Azan` (held by `LauncherRoot` for the session: at each time a chime and the spoken notice into the car under a `CarVoice` owner of its own, music paused and resumed; never during a call, never late). The widget is `ui/widgets/PrayerWidget` |
| `assist/` | The driving assistant, spoken at the right moment (each feature a setting): `DrivingAssist` (follows the car session through `VehicleData.connected`, started from `BetterAutoApp`; holds `MapLocation` and `RoadInfo` for the whole session; the timers), `AssistRules` (pure: `BriefRule` (no brief on a reconnection within 10 min), `RestTimer` (2 h, then every 45 min, reset by a 15-min stop), `SpeedAlert` (> 5 km/h over a known limit for 3 s, once a minute; `DrivingAssist.speeding` pulses `SpeedLimitSign`), `FuelAlert` (`FuelReserve`), `BriefText`, `LikelyDestination` (habit of the time of day from the trips set, else work/home by name)), `ArrivalBrief` (greeting, the car's outside temperature (the weather widget has no values to read), next appointment, ETA through `Navigation.eta`), `FuelStop` (nearest station by `PlaceSearch`, then `VoiceAssistant.ask` yes/no adds it to the `Trip`), `AssistVoice` (through `MessageReader.say`, dropped while the driver talks or calls; the speed chime), `AssistStore` (prefs: last session end, rest timer, trips set, parked car: on the phone only, never logged), `ParkedCarViews` (the quiet notification and the phone app's card, walking link) |
| `car/` | Data from the car: `VehicleData`/`CarState`, `FaultCodes`, `Units` (metric/imperial, °C/°F), `Viewport`. Vehicle values are canonical `VehicleField`s published by `VehicleSource`s (AA sensors, Exlap, demo) and merged by `VehicleMerger`; the UI never reads a protocol directly. `CarGpsGate`: whether the car's GPS can be believed (shared by the map and the arrival buttons) |
| `map/` | The map's data: `MapLocation` (car GPS, else the phone's for the whole car session (held by `LauncherRoot`), also while a map or the guidance runs: its GPS provider once a second, not the fused one (it handed over network guesses hundreds of metres off), the network only while the GPS is quiet; a phone fix delivered more than 5 s late or vaguer than 100 m is not drawn, a GPS quiet for 10 s is asked again, every gap is logged with provider, accuracy, age, screen and app importance; a position kept from before is labelled as the last known one, never taken as live; heading from the GPS course, never the phone's compass; with no GPS an estimate from the car's speed, compass or checked gyroscope; `MapSource` says which; `CarMap` shows a jump over 250 m at once), `MapStyle` (`res/raw/map_style.json`: our style on OpenFreeMap's vector tiles, colors as "@name" placeholders filled from `MapColors` in `CarTheme`, recolored in place on the day/night fade). Drawn by MapLibre in `ui/map/` (`CarMap` + `CarMapState` with the zoom with speed, `MapScreen`, `MapWidget`, `RoadPanel`: speed limit sign and road name). `roads/`: the road under the car, `RoadInfo` (only while a map is on screen) over `RoadTileStore` (Overture's transportation PMTiles read by HTTP range, newest monthly archive looked up, 48 MB disk cache), `PmTiles` + `VectorTile` (minimal readers), `OvertureRoads` (segments, limits by part/direction, names, numbers), `RoadMatcher` (nearest road weighed by heading, sticky); limits only where the data states them. `nav/`: guidance. Routes come from Google's Routes API (`GoogleRoutes`, with traffic, rechecked every 5 min) when the build has a Google key; otherwise from Valhalla (the driver's own server, or the public demo one): `Trip` (stops then destination, one list changed by the car, the voice and the phone page; kept 12 h, resumed at the next connection without the places within 300 m), `Navigation` (follows the trip while a car screen is up: route through every stop, reroute, stop reached, spoken prompts per the voice guidance setting, a turn announced once even across reroutes; `snapToRoute` puts the drawn car on the line within 10 m, `RoadInfo.snap` on the road within 8 m), `NavRoute` (Valhalla legs joined), `PlaceSearch` (Photon, the phone's geocoder, shared links), `Destinations` (recents). `DestinationActivity` is the phone page (search, share target, the trip to edit), `ui/map/TripViews` the trip list (drag, or knob: press to pick up, turn, press) and the stop-or-destination choice |
| `smarthome/` | Smart home, generic: `SmartHomeProvider` (the interface a service implements; its KDoc says how to add one), canonical `SmartDevice`/`DeviceKind`/`DeviceAction`/`DeviceState`/`SmartPlace`, `SmartHome` (registry, choices, talks to services only during a session), `Preselection`/`ArrivalCandidates`, `Geofence` (pure fence logic) + `ArrivalWatcher` (car GPS, else phone location), `SecretStore` (Keystore AES-GCM), `SmartHomeAuth` + `SignInActivity` (OAuth redirect `betterauto://auth/<provider>`). `homeassistant/`: `HomeAssistantProvider`, `HaAuth` (OAuth), `HaSocket` (WebSocket, OkHttp), `HaMapping`, `HaLinkCard` |
| `exlap/` | VW group Exlap (extra vehicle data, read only): `ExlapClient` (protocol), `ExlapSettings`, `ExlapData` (+ `ExlapVehicleSource`), `ExlapMapping` (object names to canonical fields), `ExlapRows`, `ExlapPhoneSettings` (phone-only settings card) |
| `settings/` | `LauncherSettings` (persisted, StateFlows), `SettingsCatalog` (one declaration shown both in the car and on the phone), `RailLayout` |
| `ui/` | The car launcher: `launcher/` (root, status bar, app rail, focus zones, settings screen), `widgets/` (music), `phone/`, `vehicle/`, `notifications/`, `voice/`, `smarthome/` (screen, tiles, arrival panel, group card, phone section), `theme/`, shared bits (`CarFocus`, `CarText`, `CarIcons`, `Scrollbar`, `AlphabetJump`) |
| `MainActivity`, `PhoneSettings` | The phone app: setup steps, log, settings (the catalog plus phone-only sections) |
| `src/debug/…/DebugInputReceiver` | Debug-only broadcasts to drive the car UI without a car (see its KDoc for the full list) |

## Car UI style

The car screen must look like a realistic, modern car UI: calm, big, legible at a glance, the same in every
section. The look is **glass**, after the "Square" music player the driver likes: one theme for every screen
(no page with its own palette), dark glass by night, white glass by day. Before adding UI, copy an existing
screen's patterns.

- **Colors**: only from `LocalCarColors` (`CarTheme`): `bg` (the opaque page), `surface` (glass: cards,
  translucent), `surface2` (chips, buttons and cards on a `surface`), `glass` (nearly opaque glass for what floats
  over content that moves or is busy: the app bar, the mini player and sections bar, map panels and buttons,
  banners, editors, the voice panel), `glassEdge` (the 1 dp edge of every pane), `line` (hairlines), `text`,
  `text2` (labels, secondary), `focus` (the knob's ring), `accent` (baby blue, the driver's main colour: what is selected or plays,
  progress, the play button) with `onAccent` (dark ink on it), `map` (the map's palette, with `route` and `puck`
  drawn by code; the route stays blue). Night (`NightColors`): near-black page, white-tinted glass, light text,
  baby blue #89CFF0, dark map. Day (`DayColors`): light page #EEF0F4, white glass (72%, floating 92%) with a grey edge,
  dark text, a deeper baby blue #3A9AD9 to read on white, light map. The whole palette, map included, fades with one
  progress (the car's night signal or the setting); text and map labels flip mid-fade. Never assume dark or light:
  no `Color.White`/`Black` except on fixed-color fills (call buttons, avatars) and scrims. Warnings use
  `WarningAmber` (orange, apart from the accent); call buttons `CallGreen`/`CallRed`. No hard-coded colors elsewhere. The surface tokens are translucent: never use them as an
  opaque cover or with `.copy(alpha = …)` (that replaces their alpha), and never over the map or a list (use `glass`).
- **Glass**: `Modifier.glass(shape)` (`ui/theme/CarTheme.kt`) draws a pane (fill + edge), `glass(shape) { it.glass }`
  a floating one; it doesn't clip, so put it after `carClickable` on buttons and add `.clip(shape)` for content.
  Floating panes get a soft `shadow` (with `clip = false` where a focus ring is drawn inside). Behind the whole
  launcher `Backdrop` (`ui/launcher/Backdrop.kt`) draws the page and the playing song's cover blurred, under the
  page's color (darkened by night, washed white by day; blurred once on a small copy off the main thread, its
  brightness capped by night and floored by day so `text2` stays near 4.5:1 or better, plus `Modifier.blur`
  on Android 12+ over a layer that doesn't change: meant to cost nothing per frame of the video, not measured yet),
  else a faint glow. Screens have no background of their own: they sit on it. Never blur large animated content.
- **Text**: always `CarLabel` (`ui/CarText.kt`): size in sp, `FontWeight.Bold/ExtraBold` for values and
  titles, `tabularNumbers` for anything that changes (times, speeds), `shrinkToFit` where a value may be
  long. Typical sizes: screen title 26 ExtraBold, card title 21 Bold, label 18 `text2`, value 19 Bold,
  big tile values 32–40 ExtraBold.
- **Shapes and spacing**: cards rounded 24–32 dp, glass on `surface`, padding 18 dp; cards inside them on
  `surface2`; chips and pill buttons fully rounded; round icon buttons 52 dp, glass; gaps 10–14 dp. The app bar is a
  floating pill (its `RAIL_WIDTH`/`RAIL_HEIGHT` include the margin), the current section in an inner pill of
  `surface2` with its icon in `accent`. Rows inside cards: label left (`text2`), value right (Bold), a 1 dp `line`
  between rows. Music and radio rows have no card: rounded artwork, title, grey line (`ui/widgets/MusicStyle.kt`).
- **Icons**: only `CarIcons` — our own 24 dp stroke icons (2 px lines, path strings). Add new ones there in
  the same style; no icon libraries.
- **Layout**: responsive. The frame can be landscape, portrait or very wide, and `Viewport` gives the part
  the car really shows (margins, insets). Use `BoxWithConstraints` and adapt (rows vs columns), never
  fixed screen sizes. Lists that scroll get a scrollbar (`LazyColumnWithBar`, `LazyVerticalGridWithBar`,
  `verticalScrollWithBar`, `Modifier.scrollbar(state)`).
- **Don't repeat the cluster**: main screens show only what the instrument cluster doesn't (no speed,
  no fuel gauge on the home screen); everything the car shares goes to the Vehicle section's "All data".
- **Status bar**: buttons that are always there (mic, bell) stay at the right, next to Wi-Fi/battery
  (which keep a steady width); buttons that come and go sit to their left, so nothing moves under the
  driver's finger.
- **Phone app**: same components and colors (it uses the car theme), cards per section; phone-only
  settings (e.g. Exlap) are separate sections after the shared catalog.

## Input: touch and the car's knob

Every screen must work fully with **touch** and with the **rotary knob / d-pad / keys** alone.

- Every interactive element uses `Modifier.carClickable(shape, label) { … }` (`ui/CarFocus.kt`): always
  focusable, activated by tap or center/enter, focus ring + slight zoom when focused, press feedback.
  `onDoubleClick` adds a double tap / double center press (the single one then waits for the double-tap time),
  `onKeyHold` a held center key that differs from a held finger.
- Home widgets: each whole widget is a `carClickable` card under its own controls (which keep their taps). A tap or
  center press makes it the main one (swapped into the biggest place, gliding there, saved); a double tap, double
  press or held center key opens its page (`HomeWidgetKind.section`); a held finger (or held center key on a widget
  without a page, or on one of its buttons) edits the home. Widgets take no gestures of their own on their surface
  (the home map has its gestures off, under a shield).
  Things that must react to touch but not take the knob (e.g. the notification banner) use a private
  tap-only modifier, like `carTapOnly` in `NotificationsWidget`.
- The knob turns = Tab / Shift+Tab through controls in order; the first knob move only *reveals* the
  focus (`LauncherFocus`). Nudges (the knob pushed sideways, d-pad keys) move between zones
  (`FocusZones`): the app bar, and on the screen every `Modifier.carZone()` (each home widget, the music
  player and its panel, the settings list and page); toward the bar from the last zone they land on the
  current section. A zone is entered back on the control the knob left there (nudged or turned out of it),
  and a nudge from the bar goes back to the zone the knob was in. Mark **one** main control per screen with
  `Modifier.contentFocus()` (where a nudge from the bar lands the first time). Overlays that wait for the
  driver (arrival panel) take the first knob move.
- Back (car key or Escape): closes the topmost thing (voice panel, detail, notifications go back to the
  previous section), otherwise goes home. Handle it in `onKeyEvent`/`onPreviewKeyEvent` like the
  existing screens.
- Steering-wheel media keys act on the player directly; the voice key (long press on VW) starts voice
  commands. Keys arriving from the car are logged as `Input: key …`.
- Overlays that wait for the driver (voice panel) take the focus on their main action (e.g. Cancel)
  and are dismissed by a tap outside.

## Settings

Declare a user setting once in `SettingsCatalog` (with a `LauncherSettings` StateFlow + setter) and it
appears both in the car's settings screen and in the phone app.

- Every setting has a `SettingsCategory` and a short **description** (what it does, and what Auto means),
  shown under its title everywhere, EN + IT: a setting without one is not finished. `more = true` puts it
  under the category's "More options".
- `ChoiceSetting` for chips; reuse `option_auto`, `option_on/off` etc. A boolean shows as a switch only
  with on/off or hide/show labels; two named choices stored as a boolean stay chips. Lists to tick are
  `ListSetting`s with a summary. A setting can refuse a value with a reason (e.g. hiding the last way to
  the notifications).
- Phone-only parts (sign-ins, system dialogs, typing) implement `PhoneSettingsSection` and are listed in
  `PhoneSettingsSections.all`; a `PhoneNote` in the catalog adds the car's "set up on the phone" line
  where the driver would look.
- The car settings (and editing the home or the bar) are available only while the car stands
  (`car/CarMotion`: P, parking brake or no speed; no data = available).
- No dead ends: every choice or setup keeps a visible way to change, redo or go back to Auto.

## Testing and debugging

- `scripts\deploy.cmd [-Tests] [-NoLaunch] [-Serial <id>]`: finds Java and adb, builds the debug APK,
  installs it, pre-approves audio capture, opens the app. `scripts\toggle-aa.cmd [-Show|-To BetterAuto|-To AndroidAuto]`
  switches between BetterAuto and the official Android Auto (both answer the head unit).
- Installing kills the running session: the head unit must reconnect (re-plug, or restart the bench head
  unit's core service; see `docs/DEVELOPMENT.md`).
- The log: `adb logcat -s BetterAuto` (also shown in the phone app). Logcat keeps only a few minutes: the same
  lines go to `files/log/events.txt` (1 MB files, 3 kept), read after a car trip with
  `adb shell run-as com.simoneluconi.betterauto cat files/log/events.txt` (debug builds). Car screen screenshot:
  `adb exec-out screencap -p -d <display id>` with the id of the "BetterAuto" display from
  `adb shell dumpsys SurfaceFlinger --display-id`.
- `DebugInputReceiver` (debug builds): `adb shell am broadcast -p com.simoneluconi.betterauto -a com.simoneluconi.betterauto.DEBUG_INPUT …`
  with extras for knob/keys, touches and drags in video pixels (`--es touch x,y` / `x1,y1,x2,y2`),
  section, theme, units, home widget slots, pretend calls/notifications/vehicle data/calendar/favorites, voice panel previews, connection
  problem notifications, video pipeline rebuild. Never drive the UI blindly: take a screenshot first.
- Map and car movement on the bench (debug builds): `--es mapDrive roads,lat,lon` drives a pretend car along the
  real roads (`tunnel`/`exit` take every GPS away); `TripRecorder` saves the car's movement data of every real
  session to `files/trips` (never commit those: they say where the car went) and `--es replay latest[,speed]`
  plays one again, or a GPX file (`/data/data/<package>/files/x.gpx`, copied there with `run-as`).
- Unit tests live in `app/src/test` (JVM; `android.util.Log` returns defaults). Protocol pieces are
  written so they can be tested without Android (e.g. `ExlapClient` against a scripted server).

## Known open points

- The H.264 encoder sometimes stops producing frames without an error (car screen frozen); the
  `VideoChannel` watchdog rebuilds the pipeline. Root cause not found yet.
- Factory head units verify the phone certificate against Google's; ours is self-signed.
- Shizuku started without root dies whenever the phone connects to the car by USB (the switch to
  accessory mode restarts adbd); BetterAuto then falls back to copying. Works with root, and will with
  wireless Android Auto.
- Video START goes out only once the car has given the screen (VIDEO_FOCUS PROJECTED), as Android Auto
  does: sent earlier, an Audi MMI (Harman MHI2) put up "observe the warnings on your phone" over the
  picture a few seconds in, depending on timing. A VIDEO_FOCUS_REQUEST goes out only if the car gives
  nothing within 1.5 s (Crankshaft waits for it). When the car takes the screen back (its own voice
  assistant, MEDIA key) the stream gets STOP, and a new START + SPS/PPS + keyframe when it returns: only
  resuming the frames brought the same warning. The car microphone opens only inside a
  VOICE_SESSION_NOTIFICATION START/END.
- Channels the cars offer that are not implemented yet: system audio,
  notifications, media browser, radio. Navigation status: the user's Audi offered it (cluster "enum") but never
  sent INSTRUMENT_CLUSTER_START; we now ask for the navigation focus (NAV_FOCUS_REQUEST PROJECTED) when a
  guidance starts, as navigation apps do. The Audi grants it (NAV_FOCUS_PROJECTED) but has sent no START yet; the
  cluster itself still to be looked at (the bench Crankshaft has no such channel).
- A car GPS may send a second fix a tenth of a second after the last: the map draws at most one every 0.5 s,
  or MapLibre's glide (timed on the last gap) lurched. Out of a garage the Audi sent the old position, 3 km
  off, for a minute with satellites in use: `CarGpsGate` waits for the position to change. The guidance (navigation prompts) stream is tried on the bench
  only; in the car it still has to be heard.
- Wireless with an AAWireless dongle: it often drops the first session while it sets up its USB link to
  the car (retried automatically), and sends an empty control message 0 right before it hangs up (not
  understood yet). On a bench without a car it may never switch its WiFi on.
- Wireless WiFi join: Android refuses a request at once (onUnavailable within ~30 ms) when the phone's WiFi is off
  or the app is not in the foreground; its approval dialog is skipped after an earlier approval Android still remembers, or
  with a companion association with the access point's BSSID (`WifiPairing`, the lasting way). After a join that must
  not be retried, auto-connect waits 10 minutes (`JoinPolicy.autoHoldUntil`). On HyperOS a head unit's WiFi saved by hand in the phone's WiFi settings is
  joined by the system as an ordinary network and dropped for having no internet (CMD_UNWANTED_NETWORK in
  `dumpsys wifi`): it must be forgotten there. A Geely head unit with Open Headunit closed the TCP connection 1–7 s
  into every wireless session seen so far, also on BetterAuto's own network: not understood yet.
