# DrishtiVoice (offline Android MVP)

Offline obstacle narration + voice control + text reading + GPS/compass guidance for blind users, English and Hindi.
Nothing in the app uses the internet.

## Easiest: build the APK online with GitHub (no Android Studio)
1. Create a free GitHub account and a new repository.
2. Upload everything in this folder, including the hidden `.github` folder (or use `git push`).
3. Open the repo's **Actions** tab, then the "Build APK" run (use **Run workflow** if it didn't start).
4. When it turns green (about 10 minutes), open the run and download **DrishtiVoice-debug-apk** at the bottom. Unzip it and install `app-debug.apk` on your phone.
The workflow downloads the three model files below for you. If the run fails, open the failed step and send me its log.

## Or build locally: add 3 things to `app/src/main/assets/`

1. `detector.tflite` - EfficientDet-Lite0 **with metadata** (COCO labels). Search "EfficientDet-Lite0 detection metadata tflite" on Kaggle Models / TF Hub and rename it.
2. `model-en/` - unzip `vosk-model-small-en-in-0.4` from https://alphacephei.com/vosk/models and put its *contents* in this folder (so `model-en/am`, `model-en/conf`, `model-en/graph`, ...).
3. `model-hi/` - same, from `vosk-model-small-hi-0.22`.

Then open the folder in Android Studio, let Gradle sync, and run on a real phone (Android 8+).

## On the phone
- Install the offline voices: Settings > System > Languages > Text-to-speech output (English India, Hindi).
- Allow camera, microphone, location, physical activity.
- Wear/hold the phone upright at chest height with the camera facing forward.

## How it behaves
- Walking: speaks only hazards in your walking path, with vibration. Never queues old messages.
- Stationary: silent, unless something moves toward you, or you ask.
- Always listening. Say the wake word first ("hello vision" / "दृष्टि"), e.g. "hello vision scan now". Right after the app speaks you can skip the wake word for 6 seconds.
- Volume up = scan now. Volume down = stop speaking.

## Voice commands
scan now / what is in front, read text, repeat, help, pause, resume, quiet mode, normal mode, detailed mode, hindi,
save location as home|clinic|pharmacy|hospital|shop|college|bus stop|park|station|gate|market, navigate to <saved place>, stop navigation.
Hindi: सामने क्या है, पढ़ो, फिर से बोलो, मदद, रुको, शुरू करो, शांत/सामान्य/विस्तार मोड, अंग्रेजी, यहाँ <जगह> सेव करो, <जगह> चलो, रास्ता बंद करो.

## Known limits (be honest with testers)
- Not compiled or tested on a device yet; expect small fixes on first build.
- Distance is an estimate from object size, not a measurement. Stairs, drop-offs, glass and poles are NOT detected. Keep your cane/guide dog.
- Navigation is straight-line GPS + compass. It does not know roads. Never use it to cross roads.
- Works while the screen is on and the app is in the foreground (no background service yet).
- Check the wake words exist in each Vosk model's `graph/words.txt`; change `Commands.wake` if not.
- Tune `AlwaysOnVoice.CONF_MIN` from real walking tests.
