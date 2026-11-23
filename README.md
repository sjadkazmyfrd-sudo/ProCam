# ProCam — Android CameraX + Windows Network Camera

## Camera features
- Photo and MP4 video capture, saved to `Pictures/ProCam` and `Movies/ProCam`
- Pinch-to-zoom, tap-to-focus, long-press AE/AF lock, and exposure compensation
- Flash / torch, 3- and 10-second timer, composition grid
- Camera extensions when supported by the phone: HDR, Night, Auto, Portrait/Bokeh, Face Retouch
- Video quality selection up to the highest quality supported by the device
- Pause/resume video recording, last-capture thumbnail, and volume-button shutter
- Front/rear camera switching

## Use the phone as a Wi-Fi network camera (Windows)
The Windows-camera function uses ONVIF discovery, an ONVIF Device/Media service, an H.264 encoder, and an RTSP/RTP unicast stream.

1. Turn on the Android phone's **Wi-Fi hotspot** in Android Settings.
2. Connect the Windows PC to that phone hotspot. Both devices must be on the same local subnet.
3. Open ProCam and allow the camera permission. Tap the **PC** button in the top bar. It changes to `ON` when the network services start.
4. On Windows 11, make sure the phone hotspot network is set to **Private** in **Settings → Network & internet → Wi-Fi → (connected hotspot) → Network profile type**, if Windows discovery is blocked on a Public profile.
5. Open **Settings → Bluetooth & devices → Cameras** and click **Search for cameras**.
6. Select **ProCam Wireless Camera**. Windows should then list it as a connected camera.
7. Open the Windows **Camera** app and use its camera-switch button to select ProCam.
8. To stop streaming, tap `ON` in ProCam.

The PC does not need Internet access or a router; the phone hotspot is the local network. Keep ProCam visible in the foreground while using the network camera. The app stops the network service when it leaves the foreground.

### Windows 10
Windows 10 also supports ONVIF network-camera streaming, but it does not have the same Camera settings page as Windows 11. Pairing is done through **Settings → Devices → Bluetooth & other devices → Add Bluetooth or other device → Everything else**, or through a compatible ONVIF pairing utility. After pairing, the network camera can be used by camera applications.

### Stream details and limitations
- Video stream: H.264 over RTSP/RTP unicast, target 640×480 at up to about 15 fps.
- Audio is not streamed. Windows network-camera support currently does not expose network-camera audio.
- Some camera combinations on older phones cannot run Preview + Capture/Video + ImageAnalysis simultaneously. In that case ProCam reports that the network stream could not start and restores the regular camera session.
- While streaming, CameraX extensions such as HDR/Night/Portrait are temporarily bypassed; the selected setting is retained and is available again after stopping the PC stream.
- Some Android hotspot implementations block multicast or communication between connected devices. Windows ONVIF discovery requires multicast and local device-to-device traffic to be allowed. This is a hotspot/phone-firmware limitation, not an Internet requirement. If Windows cannot discover the camera, the limitation may be in the phone's hotspot implementation rather than the video stream service.
- This is an initial ONVIF-compatible implementation; it has not been certified by ONVIF and has not yet been validated on every Android hotspot / Windows combination.
- The ONVIF service currently uses no ONVIF username/password, and the RTSP stream is unencrypted. Use a strong hotspot password and connect only trusted devices. Anyone connected to the hotspot may be able to view the stream.

## Build with GitHub Actions
1. Upload the complete project folder to a GitHub repository (keep the `.github/workflows` folder at the repository root).
2. Push to the repository or open **Actions → Build APK → Run workflow**.
3. Open the completed workflow run and download the `ProCam-Windows-Camera` artifact.
4. Extract the artifact and install `app-debug.apk` on the Android phone.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Build locally
Open the project in Android Studio with JDK 17 and a compatible Android SDK, sync Gradle, then select **Build → Build APK(s)**.

- Minimum Android version: 7.0 (API 24)
- Target SDK is kept at 36 for now. Android 17 requires apps targeting API 37+ to request `ACCESS_LOCAL_NETWORK` at runtime; add that permission flow before raising the target SDK.
- Application ID: `com.example.procam`
