# Public screenshots

[About](about.png) and [Settings focus](settings-focus.png) were captured from the
Flick TV 0.2.36 app running on Android at 1920 × 1080 with a fresh profile and its
built-in wallpaper. The home row shows the original artwork of installed apps.

The earlier screenshots render Flick 0.2.18's production Compose screens with fictional profiles,
illustrated avatars and sample app and Home Assistant data. The UI source is
[`c0c99c1`](https://github.com/alphasixtyfive/flick-tv/commit/c0c99c1e5c41e8f3d382ee4e84389948b9cc115d).

The isolated Paparazzi test setup uses a 1920 × 1080 TV layout at density 320. Captures
come from the settled final render frame, saved losslessly as PNG at that resolution.
They use Flick's included wallpapers and its regular theme, controls and typography.
Names, avatars, weather and entity readings are sample content. App artwork identifies
the apps shown; those apps remain separate projects and are not bundled with Flick.

Screenshot-only test dependencies and fixtures are kept outside the published app.
Real household configuration, portraits, account data and diagnostic screens are excluded.

When replacing these images, use an isolated test environment, keep the set at the
same size and theme, wait for images and animations to settle, and review both the
original PNG and GitHub's gallery size. Preserve sharp text and use the same files
in the README and feature guides.
