# Call and map console

Open **Open call & map console** from DuoPlus Simulator. The phone pane controls real Android Telecom calls. The map follows the loaded synthetic scenario and uses the same playback session as the simulator and Android Auto view.

In portrait, the compact phone pane shows the caller, call duration, main controls, and status. Tap **Keypad** to expand the number entry, phone-app chooser, secondary call controls, and notification settings. Expanded content scrolls within the phone pane. Landscape shows the call pane beside the map.

## Select the phone app

1. Expand **Keypad** and tap **Use as phone app**.
2. Review Android's default-phone-app chooser and select this app if you want it to manage your calls.
3. Grant calling permission when requested. A permission grant does not place a call; press **Place real call** separately when ready.

The app requests `ROLE_DIALER` only from that button. Android may not offer the role on a device without supported Telecom calling. The map remains usable when calling is unavailable. You can change the default phone app again in Android settings.

Opening a `tel:` link or an `ACTION_DIAL` intent only fills the number field. Opening the console, loading a scenario, starting playback, and connecting an Android Auto host do not dial a number or select a default phone app.

## Phone controls

- **Place real call:** submits the entered number to Android Telecom after an explicit tap. It requires the selected phone role and `CALL_PHONE` permission.
- **Answer / Decline:** answers an incoming call as audio or rejects it.
- **Hang up:** disconnects the selected call.
- **Mute / Speaker:** changes actual call audio. Unsupported routes remain unavailable; Android controls the final route.
- **Keypad:** sends short DTMF tones during an active call. Before a call, the keys edit the number. Automated pause/wait dial strings are not supported.
- **Hold / Resume call:** appears in the expanded controls when a call exists; availability depends on Telecom's call capabilities.
- **Calls / Choose SIM:** selects which call to control or a calling account when Telecom requests one.

The call timer uses elapsed time. Caller information comes from Telecom and respects restricted caller presentation. The call pane does not record calls or access microphone samples.

## Call notifications

Enable **Call notification settings** to keep call actions accessible outside the console. Android 13 and later can request notification permission; a disabled app or call-notification channel is reported in the pane.

Android 12 and later use the platform CallStyle notification; Android 10–11 use standard call notifications with explicit actions. Incoming notifications can open the call console full screen when Android allows that permission. Otherwise, open the notification or console normally. Lock-screen public content omits caller details. Android Telecom owns ringing and call hardware; the call UI adds no wake lock or playback foreground service.

These behaviors follow Android's [default dialer and InCallService contract](https://developer.android.com/reference/android/telecom/InCallService) and [CallStyle notification guidance](https://developer.android.com/develop/ui/compose/notifications/call-style).

## Map and playback

Use **Demo** to prepare the included scenario, then **Start**, **Pause**, **Resume**, or **Stop**. **Details** opens the simulator's import, channel inspection, and explicit report export tools. Starting playback may request notification permission because the background simulation uses a visible notification with controls.

The marker, camera, and route come from scenario data. They do not change Android location providers or feed synthetic location to other apps. Basemap loading can fail independently of playback; the route overlay remains available. See [map rendering](MAP.md) and [scenario format](SCENARIOS.md).

The Android Auto integration projects the simulated route and playback controls through the car app templates. Real phone calls remain managed by Android Telecom and the host's phone experience.

## Validation limits

Real incoming/outgoing calls, phone-role selection, carrier behavior, Bluetooth call routing, and a physical Android Auto head unit have **not been tested**. No real call was placed and no default phone role was changed during development checks. See [console validation](CONSOLE-VALIDATION.md) for the current build and functional-check evidence.
