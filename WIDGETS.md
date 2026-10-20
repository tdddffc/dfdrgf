# Home-screen widgets

Add: long-press the home screen -> Widgets -> **Weather** (full) or **Weather alerts** (alerts only).
Both refresh every 30 minutes, when you open the app, and when you tap 🔄. Tapping elsewhere opens the app.

## Weather widget (resizable; stretch it taller for more)
From the top: location + update time, big emoji + temperature + feels-like + high/low,
**alerts directly under the temperature**, hourly strip, **next days as one row of tiles (icon + high/low)**,
**info tiles**, sunrise ☀️ / daylight / sunset 🌙.

| Height | What appears |
|---|---|
| small | header, temperature |
| 130dp+ | first alert (with "+N more") |
| 210dp+ | hourly strip (4/6/8 hours) |
| 250dp+ | sun and moon line |
| 300dp+ | next days row (3/5/7 tiles) |
| 340dp+ | 4 info tiles |
| 420dp+ | every active alert listed, one per row (up to 6) |
| 440dp+ | 8 info tiles |
| 540dp+ | 12 info tiles |
| 640dp+ (full screen) | 16 info tiles |

## Settings -> Widgets (collapsible group)
- **Look:** Weather colours / Dark / Light. Weather colours follow the worst weather in the next Now / 3 / 6 / 12 / 24 hours.
- **Show:** alerts, hourly strip, next days, info tiles, sunrise & sunset.
- **Amounts:** hours in strip (4/6/8), days in row (3/5/7).
- **Info tiles (up to 16, your order):** wind, gusts, wind from, feels like, coldest 24h, low 12h, frost hours, humidity, dew point, clouds, UV, snow today, snow 24h, rain today, rain 24h, rain chance, pressure, visibility, daylight.

## Settings screen
Now grouped into collapsible cards (one open at a time): Location, Custom Weather Alerts, Widgets, Notifications (quiet hours + test), Appearance & units.

Android widgets cannot animate, so widget emoji are still.
