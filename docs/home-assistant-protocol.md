# Home Assistant TV connection

Protocol 1 uses Home Assistant's authenticated WebSocket API. The Android connection is optional
and separate from the direct light-control client.

## Pairing

Flick generates a stable device ID, a random 32-byte device secret and a six-digit code. The secret
is encrypted in Android Keystore storage and bound to the canonical Home Assistant origin. It is
never shown in the UI. The code lasts five minutes and is not saved as a launcher preference.

After authenticating as a dedicated non-admin user, the TV subscribes using `flick_tv/connect`:

| Field | Format |
| --- | --- |
| `device_id` | Stable ID, at most 64 characters |
| `name` | Device name, at most 80 characters |
| `protocol` | `1` |
| `device_secret` | 64 lowercase hex characters |
| `pairing_code` | Six digits; supplied only while pairing |

The component holds unapproved subscriptions in memory. An administrator confirms the TV's code
in its configuration flow. Paired reconnects require the saved secret and the same active user.
Authorization also binds results and events to the current WebSocket transport.

## Commands

The subscription receives an event containing `type: command`, `protocol`, `request_id`, `command`
and an object named `data`. Only the following operations are accepted:

| Command | Data |
| --- | --- |
| `get_status`, `list_apps`, `list_profiles` | Empty object |
| `set_profile` | `id`, optional `name`, `child`, `allowed_apps`, `avatar`, ephemeral `pin` |
| `delete_profile`, `select_profile` | `id` |
| `next_wallpaper` | Empty object |
| `set_wallpaper_options` | Optional `interval_seconds`, `shuffle`, `dim_amount`; at least one |
| `set_wallpapers` | `photos`: up to 500 HTTP(S) image URLs; `replace` defaults to true |
| `open_camera` | `camera_entity_id` and optional `title` |
| `launch_app` | `package_name` of an installed Android TV app |
| `notify` | `id`, optional `title`, `message`, `image`, `actions`, `persistent`, `timeout` |

Launcher management requires explicit adult approval on the TV. Home Assistant must restrict
management actions to administrators or system automations. A non-admin TV account
cannot switch itself into an adult profile through a Home Assistant select entity. PIN updates
belong only in a masked admin options flow, never a domain service or an automation trace.
Parent PINs contain exactly four digits, including leading zeroes.

App launches require Flick TV Home to be visible, the screen to be awake, and
an unlocked adult profile with launcher management enabled.

The TV replies through `flick_tv/result`, including `device_id`, `device_secret`, `request_id`,
`success`, `data` and an optional short safe error.

Proactive `flick_tv/status` messages use flat fields `version`, `profile`, `profiles`, optional
`current_app`, and `capabilities`. Profile summaries contain only `id`, `name` and `child`.
Flick reconnects if a status update is rejected or remains unacknowledged for ten seconds;
WebSocket ping alone cannot detect a missing logical TV session after an integration reload.
The explicit `list_profiles` response may also contain `allowed_apps` and online avatar URLs.
Neither response includes PINs, verifiers, salts or document grants.

The reported profile is the selected/resume account, including while the startup chooser is locked.
It is not an unlocked indicator. Protocol 1 has no session-authorization field; the TV independently
requires a current adult session for launcher management. Notifications may still arrive while locked,
but notification action buttons require an adult session.

## Home screen text

Home screen text uses the optional `flick_tv/home_text` subscription
on the existing authenticated socket. It has a separate WebSocket message ID, with
`device_id`, `device_secret`, `profile_id` and a fresh positive integer `profile_generation`.
The server acknowledges it with the standard success result and sends exactly these fields:

| Field | Format |
| --- | --- |
| `profile_id`, `profile_generation` | Requested profile context |
| `revision` | Positive integer, increasing within this subscription |
| `text` | Literal single-line message, at most 120 Unicode codepoints; empty hides it |
| `show_for_children` | Boolean, false unless explicitly enabled by an administrator |

Flick centres the message in the header. Long text is ellipsized to fit between the controls
and clock; if no space is available, the message is hidden. Text changes do not move other
Home screen elements. The message does not receive remote focus.

The TV does not evaluate templates or interpret HTML. It clears text on profile/authority changes,
backgrounding, disconnect or disable, and rejects old subscription IDs, generations and revisions.
Home Assistant independently checks the current profile and child visibility. The template has
one tracker and independent error handling; changes coalesce into at most one update per second. The TV
unsubscribes with `unsubscribe_events`. An unsupported subscription's `unknown_command` response affects
only this feature; the main control/notification subscription continues.

Configure Message in Home Assistant. See the
[integration setup](https://github.com/alphasixtyfive/flick-tv-home-assistant#home-screen-text).

## Notifications and bounds

Notifications allow a 100-character title, 2048-character message and up to two action records
(`id`, `label`, at most 32 characters). At least one text field must be nonblank. A timeout is
0–120 seconds; zero uses the default eight seconds unless `persistent` is true. Persistent cards
wait for Dismiss/Back while the process remains alive. Actions return through
`flick_tv/notification_action` using the original notice/action IDs. The component validates them
against the notification it sent before emitting `flick_tv_notification_action`.
The TV acknowledges a queued notification while its photo loads. Replacing or dismissing that
notification cancels any outstanding photo work for it.

Frames are limited to 512 KiB with bounded nesting, fields, collections and queues. Unknown commands, arbitrary intents,
shell commands and arbitrary service/event names are rejected. Old-session replies cannot satisfy
a new connection. Queued commands have a nine-second budget from receipt, including execution;
expired commands are rejected before dispatch. That local budget cannot compensate for arbitrary
network latency. Timeouts do not trigger automatic retries of a scene or wallpaper advance.
