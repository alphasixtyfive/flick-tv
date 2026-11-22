# Architecture

Flick TV has one Android application module. Source lives under `app/src/main/java/app/flick/tv/`,
with feature tests under `app/src/test/java/app/flick/tv/`.

## Code layout

| Package/file | Responsibility |
| --- | --- |
| `home/` | Home layout, cards, clock/weather, panel routing and screen state/actions |
| `profiles/` | Profile editing, avatars, PIN keypad and verification |
| `settings/` | Grouped settings UI, versioned preferences and persistence |
| `backup/` | ZIP backup validation, media staging and restore recovery |
| `apps/` | Installed apps, launch intents and Android Watch Next access |
| `wallpaper/` | Photo selection, document-folder enumeration and slideshow presentation |
| `weather/` | Location search and current conditions |
| `homeassistant/` | Credentials, selected controls, transport and reported device state |
| `integration/` | Optional paired WebSocket connection and allowlisted launcher commands |
| `notifications/` | Notice queue, native overlays and foreground connection service |
| `ui/` | Shared theme, focus surfaces, rows, dialogs and remote gestures |
| `platform/` | Android adapters for sounds and network cleanup |
| `FlickRuntime` | Process-owned stores and integration clients |
| `LauncherViewModel` | Screen work, profile authority and feature coordination |
| `LauncherCommands` | Home Assistant command dispatch and management checks |
| `MainActivity` | Lifecycle, Android permission screens, document pickers and activity results |

Keep models with their feature. Add a module when it provides a useful dependency or build boundary.

## State and persistence

Compose collects lifecycle-aware view-model state. Repositories own I/O and expose typed state;
network services load independently of the home screen. Process-owned clients survive Activity
recreation, while screen work such as weather refresh belongs to the view model.

Protected operations recheck the current profile before execution. PINs use salted PBKDF2
verifiers and persisted attempt limits. Unlock input stays in memory and is cleared after submission.

Settings and wallpaper edits merge changed fields into the latest stored value, preserving unrelated
changes from an older screen or Home Assistant. A local commit and its StateFlow publication finish
together. ZIP restore validates settings and stages media before writing. A private journal records the
previous state of both preference stores so an interrupted restore can be rolled back on startup.

## UI and input

Compose for TV supplies focus and accessibility. Shared components provide the same focus colours,
rows and choice indicators throughout the app. Panels own their structural focus keys; device-state
updates do not reset selection. Read-only status rows remain focusable for scrolling without exposing
a click action. Dialogs use separate windows and return focus to their caller.

A remote-gesture state machine opens long-press menus while OK is held. It retires that gesture
before the popup opens and ignores orphan repeats/releases. Focus loss, window changes and lifecycle
suspension cancel pending holds. Text editing starts from an explicit edit row; profile unlocking
uses its own numeric keypad.

## Photos and networking

Wallpaper settings, folder grants and the current photo belong to `WallpaperRepository`. Folder
scans run off the UI thread and read one level with bounded work. Rotation pauses when the home
screen is inactive. Coil prepares the next frame before a short crossfade; failed images leave the
last successful frame visible.

Home Assistant keeps reported state, pending requests and errors separate. A successful service
response is not proof that a device reached the requested state. Only selected, supported entities
are exposed. The companion client uses Home Assistant's existing WebSocket endpoint; there is no
listening HTTP server on Android. Its device secret is bound to the configured Home Assistant origin.
Request IDs are retained before execution, and non-idempotent commands are not retried automatically.
See the [protocol](docs/home-assistant-protocol.md) for messages and limits.

Native notification overlays live outside the Activity. Passive cards leave app input alone;
interactive cards offer Dismiss. Android overlay permission is separate from launcher management.
The user-enabled foreground connection can recover a saved pairing after process loss.

HTTP clients bound requests and reject redirects. Cancellation, TLS socket disposal and connection-pool
cleanup run away from the main thread and do not depend on a cancelled screen coroutine.
Credentials are encrypted with Android Keystore keys. Preferences stay in private app storage.
