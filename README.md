System Utility: Tactical Deaddrop Suite
Overview

<img width="279" height="492" alt="image" src="https://github.com/user-attachments/assets/d17e45af-d856-4931-a322-0fc0fbdcac32" />
<img width="243" height="420" alt="image" src="https://github.com/user-attachments/assets/6b1a2b80-972f-44cf-8119-367711730e91" />

A high-stakes, "offline-first" exfiltration tool designed for rapid data dispersal in high-risk environments. This application prioritizes speed and stealth, allowing for immediate GPS alerting and background media capture with zero user confirmation required once triggered.
Key Features

    Zero-Click Exfiltration: Immediate programmatic SMS dispatch of GPS coordinates to all armed contacts upon trigger.
    Stealth Background Capture: Dual-stream recording of high-definition audio and video using a background service (No camera viewfinder/preview).
    Kinetic Trigger: Integrated accelerometer support for Shake-to-Fire capability, allowing activation without looking at the screen.
    Multi-Vector Parallel Dispatch:
        SMS: Background GPS/Map link delivery.
        Cloud: Parallel HTTP POST of media files to a pre-configured Hub.
        Email: Automated "Share Sheet" population for tactical attachments.
    Self-Seeding Deployment: Built-in ability to clone and share its own APK binary for field distribution without an app store.
    Security: Dual-PIN authentication with a functional Duress Mode that triggers a decoy news feed.

Deployment & Setup
1. Permissions Required

To function in a high-risk capacity, the following permissions must be granted manually in the Android App Settings:
    SMS: To send background alerts.
    Location: For precise GPS link generation.
    Camera & Microphone: For stealth recording.
    Display over other apps: (Optional) To ensure the decoy mode covers the UI.

2. Configuration

Access the SYSTEM CONFIG menu (via the Gear/QR icon) to set:
    Cloud Hub IP: The destination for parallel media uploads.
    Tactical Emails: Comma-separated list for payload dispersal.
    PIN Rotation: Update Real and Duress codes frequently.

3. Usage
    Arm: Add contacts via the "ADD CONTACTS" button. Use the multi-select picker to arm multiple targets at once.
    Trigger: Shake the device or tap the central trigger button.
    Haptic Confirmation: The device will provide a distinct vibration pulse once the exfiltration sequence begins.

Technical Stack
    Language: Kotlin 1.9+
    Minimum SDK: Android 26 (8.0 Oreo)
    Hardware APIs: Camera2, SensorManager, LocationManager, Telephony (SmsManager)
