# Home Assistant on the TV

Use Flick's side panel to control a few lights and scenes with the remote. Add the optional
Flick integration when you also want notifications and launcher management from Home Assistant.

![Sample Home Assistant controls in Flick](screenshots/home-assistant.png)

## Choose controls for the remote

1. Create a dedicated **non-admin** Home Assistant user for the TV.
2. Sign in as that user and create a long-lived access token under **Profile → Security**.
3. On the TV, open **Flick Settings → Home Assistant** and enter the address and token.
4. Open **Choose controls** and select up to sixteen entities.

Lights, switches and toggle helpers support on/off. Scenes and scripts run when selected.
Sensors and binary sensors show their reported state. For example:

| Example entity | What appears on the TV |
| --- | --- |
| `light.living_room_lamp` | Lamp switch and reported brightness |
| `scene.movie_time` | A scene the remote can run |
| `input_boolean.quiet_evening` | A toggle helper |
| `sensor.living_room_temperature` | A read-only reading |

Create a Movie time scene in Home Assistant's scene editor using the lights you want to dim,
then select that scene in Flick. These ordinary controls do not need the custom integration.
Flick excludes locks, alarms and gate controls.

## Pair the optional TV connection

Install [Flick TV for Home Assistant](https://github.com/alphasixtyfive/flick-tv-home-assistant)
using its setup instructions. Its current documented requirements are Home Assistant 2026.11
or later and Flick 0.2.2 or later.

On the TV, open **Home Assistant → TV connection → Pair TV**. In an administrator's
Home Assistant session, add **Flick** under **Settings → Devices & services** and enter
the six-digit code. Keep the TV connected with its non-admin account.

Enable notifications in Flick. To show cards over another app, grant Android's
**Display over other apps** permission. Some protected screens can suppress overlays.
Use **Test notification** on the paired device page before building an automation.

All names and IDs below are examples. Copy your actual entities from the paired device page.
For `flick` actions, use the action editor's **TV** device selector to choose a device.
Replace `YOUR_FLICK_DEVICE_ID` before running an example.

## Send a short message

In **Developer tools → Actions**, choose YAML mode:

```yaml
action: notify.send_message
target:
  entity_id: notify.living_room_tv_notification
data:
  title: Laundry
  message: The washing is finished.
```

This brief card leaves remote input with the current app. Notifications do not require
Launcher management. The TV must be connected and notifications enabled.

For an automation, create a timer helper called Laundry, then paste this into a new
automation's YAML editor, adjusting the timer and TV entity IDs:

```yaml
alias: Tell the TV when the laundry timer finishes
triggers:
  - trigger: event
    event_type: timer.finished
    event_data:
      entity_id: timer.laundry
actions:
  - action: notify.send_message
    target:
      entity_id: notify.living_room_tv_notification
    data:
      title: Laundry
      message: The washing is finished.
mode: single
```

## Show a camera preview

Choose an existing camera. Home Assistant supplies a short-lived image link; the TV
does not receive the camera's credentials.

```yaml
action: flick_tv.notify
data:
  device_id: YOUR_FLICK_DEVICE_ID
  id: front-door-preview
  title: Front door
  message: Someone is at the door.
  camera_entity_id: camera.front_door
  timeout: 15
  actions:
    - id: flick_tv_view_camera
      label: View camera
```

**View camera** opens the silent live feed in an unlocked adult profile. Remove the
`actions` block for a photo-only alert.

For an existing direct image URL, use `image: https://example.com/doorbell.jpg` instead
of `camera_entity_id`. Use one image source. Images must be reachable by the TV without
a login prompt. Do not put passwords in URLs.

## Keep a reminder until it is dismissed

```yaml
action: flick_tv.notify
data:
  device_id: YOUR_FLICK_DEVICE_ID
  id: tea-ready
  title: Tea is ready
  message: Come through when you are ready.
  persistent: true
```

A persistent card takes remote focus and offers **Dismiss**; Back dismisses it too.
It is held in memory and does not survive Flick's process restarting. Switching profiles
clears queued cards. Reusing an ID updates the existing card.

## Add a notification button

Custom buttons return named events to Home Assistant. They do not carry arbitrary service calls
or open URLs. Up to two buttons are supported, and they appear only in an unlocked adult
session. Child profiles receive the message without action buttons.

```yaml
action: flick_tv.notify
data:
  device_id: YOUR_FLICK_DEVICE_ID
  id: tea-ready
  title: Tea is ready
  message: Let us know when you have seen this.
  persistent: true
  actions:
    - id: acknowledged
      label: Got it
```

Create a **Tea reminder** toggle helper, then use this automation to clear it when the
button is selected:

```yaml
alias: Acknowledge the tea reminder from the TV
triggers:
  - trigger: event
    event_type: flick_tv_notification_action
    event_data:
      notification_id: tea-ready
      action_id: acknowledged
actions:
  - action: input_boolean.turn_off
    target:
      entity_id: input_boolean.tea_reminder
mode: single
```

If more than one TV can send this event, also filter `event_data.device_id`. The event
contains Flick's device ID, which may differ from Home Assistant's device-registry ID;
copy it from a test event in **Developer tools → Events**. Only buttons belonging to a
notification sent to that TV are accepted.

## Add a small dashboard card

Paste this into a manual card, replacing the example entity IDs with those on the device page:

```yaml
type: entities
title: Living room TV
show_header_toggle: false
entities:
  - entity: binary_sensor.living_room_tv_connection
    name: Connected
  - entity: sensor.living_room_tv_current_profile
    name: Selected profile
  - entity: sensor.living_room_tv_version
    name: Flick version
  - entity: button.living_room_tv_test_notification
    name: Test notification
  - entity: button.living_room_tv_next_wallpaper
    name: Next wallpaper
```

The reported profile is the selected or resume profile, including while the startup
chooser is locked. It does not indicate an unlocked session.

## Change the wallpaper or selected profile

Enable **Launcher management** on the TV and unlock an adult profile first. Management
requests require a Home Assistant administrator or system automation context as well.
They are rejected at the startup picker and while a child profile is active; these
actions cannot be used as an unattended child-profile switch.

Advance the slideshow once:

```yaml
action: flick_tv.next_wallpaper
data:
  device_id: YOUR_FLICK_DEVICE_ID
```

Change slideshow preferences:

```yaml
action: flick_tv.set_wallpaper_options
data:
  device_id: YOUR_FLICK_DEVICE_ID
  interval_seconds: 300
  shuffle: true
  dim_amount: 0.2
```

A fixed profile wallpaper takes precedence over the shared slideshow. To select a profile,
use `flick_tv.list_profiles` to find its ID, then call:

```yaml
action: flick_tv.select_profile
data:
  device_id: YOUR_FLICK_DEVICE_ID
  profile_id: kids
```

Replace `kids` with the returned ID. This selects a Flick profile, not an account inside
a streaming app. Set parent PINs only through the masked device-options form, never in
automation YAML.

## If nothing appears

Check the paired device's connection, Flick's notification switch and Android's overlay
permission. Open Flick after a force-stop or vendor shutdown. For failed management actions,
check Launcher management and the current adult session. Use **Test notification** to
separate a connection problem from an automation trigger problem.

The integration's [README](https://github.com/alphasixtyfive/flick-tv-home-assistant),
[Flick setup guide](../SETUP.md#home-assistant) and [protocol reference](home-assistant-protocol.md)
cover connection details. For YAML syntax, see Home Assistant's
[notification action](https://www.home-assistant.io/actions/notify.send_message/) and
[automation triggers](https://www.home-assistant.io/docs/automation/trigger/).
