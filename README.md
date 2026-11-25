# OBD2 Pro Link — Native Android Background Logger (Galaxy S25)

Screen-off, pocket-in-your-jeans OBD2 background logger for the **Vgate iCar Pro BLE (`IOS-Vlink`)**.
Automatically wakes up when your **Samsung Galaxy S25** connects to your car's Bluetooth stereo, identifies the vehicle via Auto-VIN (`0902`), records live Mode 01 telemetry + DTCs (`03`/`07`/`0A`) + multi-ECU `0100` discovery for **20 minutes**, buffers everything in local SQLite, syncs live to **Google Drive (`ODB2 FDD / 02 Logs`)**, and cleanly disconnects at 20:00.

## 1. Zero-Android-Studio Cloud Build (GitHub Actions)
1. Create a new GitHub repository named `obd2-pro-link-android` under `dsolovyo-debug`.
2. Unzip `obd2-pro-link-android.zip` into your local GitHub folder (or upload via GitHub Desktop) and push to `main`.
3. Open the **Actions** tab on GitHub — within ~2 minutes, **Build Android APK** finishes and publishes `app-debug.apk` under **Releases → Latest**.
4. On your Galaxy S25, open the **Releases** page in Chrome, tap `app-debug.apk`, and install it (allow Chrome to install unknown apps once).

## 2. One-Time Samsung Galaxy S25 (One UI 7) Setup
1. Open **OBD2 Pro Link** and tap **Grant BT/Notif** (allow Nearby Devices + Notifications).
2. Tap **Unrestrict Battery** (or go to **Settings → Apps → OBD2 Pro Link → Battery → Unrestricted**).
3. In **Settings → Battery → Background usage limits → Never auto sleeping apps**, add **OBD2 Pro Link** so One UI never puts the background Bluetooth listener to sleep.
4. Check the **Car Bluetooth trigger keywords** field — it comes pre-loaded with `ELANTRA,HYUNDAI,SUBARU,LEGACY,MB BLUETOOTH,MERCEDES,HANDSFREELINK,HONDA,IOS-VLINK`. Add your exact car stereo Bluetooth name if it differs, then tap **Save Settings**.

## 3. How Auto-Capture Works
- **Start car →** Galaxy S25 connects to car stereo Bluetooth → `BluetoothTriggerReceiver` starts `ObdCaptureService` (`connectedDevice` foreground service, silent notification).
- **Connects to `IOS-Vlink` →** initializes ELM327 (`ATZ` + `ATSP6`), reads VIN (`0902`), scans DTCs (`03`/`07`/`0A`), and logs Mode 01 telemetry every ~0.4s.
- **SQLite + Google Drive Sync →** every 10 seconds, batches are written to local SQLite first and POSTed to your `OBD2 Drive Logger` Apps Script (`/exec`), only clearing from SQLite once Drive returns `{"status":"ok"}`.
- **At 20:00 →** flushes final batch, sends `ATPC`, disconnects `IOS-Vlink`, and shuts down cleanly until the next drive.
