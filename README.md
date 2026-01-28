Deaddrop Tactical Suite

A high-stakes, "offline-first" exfiltration tool designed for rapid data dispersal in high-risk environments. This application prioritizes speed and stealth, allowing for immediate GPS alerting and background media capture with zero user confirmation required once triggered.

Overview

<img width="279" height="492" alt="image" src="https://github.com/user-attachments/assets/d17e45af-d856-4931-a322-0fc0fbdcac32" />
<img width="243" height="420" alt="image" src="https://github.com/user-attachments/assets/6b1a2b80-972f-44cf-8119-367711730e91" />


## Key Features
    - Zero-Click Exfiltration: Immediate programmatic SMS dispatch of GPS coordinates via Google Maps link to all armed contacts upon trigger.
    - Stealth Background Capture: Dual-stream recording of high-definition audio and video using a background service (No camera viewfinder/preview).
    - Kinetic Trigger: Integrated accelerometer support for Shake-to-Fire capability, allowing activation without looking at the screen.
    - Multi-Vector Parallel Dispatch:
    - SMS: Background GPS/Map link delivery.
    - Cloud: Parallel HTTP POST of media files to a pre-configured Hub.
    - Email: Automated payload population for tactical attachments.
    - Self-Seeding Deployment: Built-in ability to clone and share its own APK binary (Utility_Installer.apk) for field distribution without an app store.
    - Security: Dual-PIN authentication with a functional Duress Mode that triggers a decoy news feed to mask active exfiltration.

## Deployment and Setup
	- Permissions Required For the system to function in a high-risk/automated capacity, the following permissions must be granted manually in the Android App Settings (Set to "Allow all the time"):
	- SMS: To send background alerts without user intervention.
	- Location: For precise GPS link generation in the panic message.
	- Camera and Microphone: For stealth recording (must allow background access).
	- Display over other apps: Required for the Duress Mode to effectively mask the UI.
	- Configuration Access the SYSTEM CONFIG menu (via the Gear/QR icon) to set your parameters:
	- Cloud Hub IP: The destination URL for parallel media uploads (e.g., http://your-server-ip:5000/upload).
	- Tactical Emails: A comma-separated list of recipients for media payloads.
	- PIN Rotation: Update your Real PIN (Access) and Duress PIN (Decoy) regularly.
	- Usage Flow
	- Add Contacts: Tap ADD CONTACTS. Use the multi-select picker to arm your entire team at once.
	- Trigger: Shake the device or tap the central START DEADDROP button.
	- Haptic Confirmation: The device will provide a 500ms vibration pulse to confirm the sequence has begun while in a pocket.
	- Exfiltrate: Tap the button again to stop recording and trigger the parallel Cloud and Email dispatch.

## Technical Specifications
	- Binary Name: System Utility
	- Package: com.example.deaddrop
	- Language: Kotlin (Target SDK 36)
	- Communication: SMS (Telephony API), HTTP/S (Parallel Streams), SMTP (Intent-based)
