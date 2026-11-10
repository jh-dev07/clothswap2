# ClothSwap 2.0

Android client and Python 3.11 service for clothing try-on. The server runs the
original CoverSwap YOLO pose, SAM2 and CatVTON pipeline. The Android app uploads
the selected photos and saves the returned PNG.

## Run the Python service on Windows

1. Install **64-bit Python 3.11**. Extract the project ZIP and the Windows wheel ZIP.
2. From the project's `server` directory in CMD, create a virtual environment and
   install the wheels. Replace `C:\ClothSwap-wheels` with the extracted wheel directory.

   ```bat
   py -3.11 -m venv .venv
   .venv\Scripts\python.exe -m pip install --no-index --find-links "C:\ClothSwap-wheels\wheels" --require-hashes -r "C:\ClothSwap-wheels\requirements-win311-cpu.lock"
   ```

3. Copy your existing model files to `server\backend\weights`, preserving their
   directory structure. If any clothing models are missing, run this on a connected machine.

   ```bat
   .venv\Scripts\python.exe download_models.py
   ```

   The pinned CatVTON program files are included in the source ZIP. The downloader
   selects clothing weights and skips files it recognizes as present. For offline
   operation transfer the completed weights directory to the offline machine.

4. Create a user. The command prompts for a password of at least 12 characters.

   ```bat
   .venv\Scripts\python.exe auth.py tester
   ```

5. Start the server. Bind to all interfaces to reach it from a phone or LDPlayer
   on your test network. The default bind is loopback if `--host` is omitted.

   ```bat
   .venv\Scripts\python.exe start_server.py --host 0.0.0.0 --port 8000
   ```

6. Open `http://127.0.0.1:8000/health` on the PC. Check for `models_ready: true`
   before testing a swap. This check verifies file presence and minimum sizes.
   Model initialization occurs on the first inference request.

The wheel bundle uses **CPU PyTorch 2.7.1**. CPU generation can take substantially
longer than GPU generation. To use an NVIDIA GPU, install a compatible CUDA build
of Torch 2.7.1 and torchvision 0.22.1 in a separate Python 3.11 environment, then
install the service requirements. The service selects CUDA when available.
Set `DEVICE=cpu` in CMD to force CPU.

## Sign in and test the Android app

1. Install the supplied debug APK on Android 7 or newer. If Android reports a
   signature mismatch with the previous build, uninstall the old debug app first.
2. In the app enter the PC's reachable server URL, for example
   `http://192.168.1.20:8000`. Enable the HTTP option for your local test network
   or use an HTTPS endpoint. Enter the account from step 4.
3. Choose the person and garment photos. Tap **Detect people**, select a person
   and garment category, and tap **Swap clothes**. Save the result as PNG.

On LDPlayer, first check the server's LAN URL plus `/health` in its browser.
If the LAN address is blocked, an established ADB connection can forward the port.

```bat
adb -s 127.0.0.1:5555 reverse tcp:8000 tcp:8000
```

With that forwarding active, enter `http://127.0.0.1:8000` in the app. Substitute
your LDPlayer serial from `adb devices` if it differs.

## Authentication and API

The operator provisions accounts with `auth.py`. The service stores salted
scrypt password hashes and hashes of session tokens in SQLite
(`server/auth.sqlite3` by default). Set `CLOTHSWAP_AUTH_DB` to relocate it.

- `POST /auth/login` accepts JSON `username` and `password`.
- `GET /auth/me` returns the signed-in account.
- `POST /auth/logout` revokes the current token.
- `POST /detect` and `POST /swap` require a bearer token.
- `GET /health` is public. OpenAPI documentation is at `/docs`.

Tokens expire after eight hours and the app keeps its token in memory. If a
request returns HTTP 401, the app returns to sign-in. Failed sign-in attempts
are limited per username. Use an HTTPS reverse proxy for deployment across
untrusted networks. The app's HTTP opt-in is for local testing.

The `/detect` body is `{"photo":"BASE64"}`. The `/swap` body also takes
`garment`, `person`, `category`, `steps`, `seed`, `guidance` and
`high_resolution`. The `image` field in its JSON response is base64 PNG.

## Build Android

Use JDK 17, Android SDK Platform 34, and Build Tools 34.0.0. Point Android Studio
to that SDK or set `sdk.dir` in `android/local.properties`. From `android` run:

```bat
gradlew.bat assembleDebug
```

The wrapper is **Gradle 8.2-all**. The APK is at
`app/build/outputs/apk/debug/app-debug.apk`. The version catalog pins AGP 8.2.1,
AppCompat 1.6.1, Material 1.11.0, Activity 1.8.0, ConstraintLayout 2.1.1, and Glide
4.15.1. It also pins ONNX Runtime 1.30.0 and OpenCV 5.0.0.1 as available aliases.
The APK uses server-side inference, so the two native runtimes are catalog
entries only.

The Gradle and Android ZIPs include instructions for an isolated offline build.
They require a separately installed Windows JDK and Android SDK.

## Upstream source

CoverSwap revision `99c43fd773a567a56212b2efdbc934b158215d48` supplies the
inference pipeline. CatVTON revision `7818397f25613beedb3d861a34769f607cfcf3b1`
supplies the try-on program files and its included license. Weights remain in a
separate local directory and retain their upstream terms.
