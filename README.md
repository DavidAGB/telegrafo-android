# Telégrafo

A pocket telegraph for Android: translates text to Morse code and back, and sends the message
as sound, light or vibration.

Written in Kotlin with the Android SDK only, no external libraries.

## What it does

- **Text to Morse and Morse to text**, translating as you type. It accepts dots and dashes
  pasted from other apps, and handles Spanish text, including the letter Ñ.
- **Telegraph key:** tap the message out by hand with a straight key, or with separate dot
  and dash buttons, and read the text as it is decoded.
- **Playback** of any message as a tone, with the camera flash or with vibration.
- **Paper tape view:** each letter is drawn as dots and dashes with its character underneath.
- **Adjustable speed** in words per minute.

## How it is built

| | |
|---|---|
| Language | Kotlin 2.0 |
| Android | minimum 8 (API 26), built for Android 15 (API 35) |
| Sound | a continuous `AudioTrack` tone with a gate, so the key responds without delay |
| Light | `CameraManager.setTorchMode`, which needs no camera permission |
| UI | native views with an XML layout, plus a custom view for the tape |
| Dependencies | none beyond the Android SDK |
| Size | about 1,150 lines in 4 files |

Timing follows the standard: a dot is one unit, a dash three, one unit between signs, three
between letters and seven between words. The tone uses 5 ms ramps to avoid clicks.

The code lives in `app/src/main/java/com/davidagb/telegrafo/`:

| File | What it holds |
|---|---|
| `Morse.kt` | The code table, translation both ways and timing |
| `Signals.kt` | Tone, flash and the player that walks the timeline |
| `TapeView.kt` | The paper tape |
| `MainActivity.kt` | The screen: message, translation, key and playback |

## Permissions

Only vibration. The flash is used without the camera permission.

## Build

With the Android SDK and Java 17:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Author

**David Albarrán** · [@DavidAGB](https://github.com/DavidAGB)
