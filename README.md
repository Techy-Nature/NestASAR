# NestASAR

An Android app for Electron **`.asar`** archives — open one, look around inside, preview files, pull files or folders out, or pack a folder back into a new `.asar`. No terminal, no root, no storage permissions (it uses Android's own file picker).

- **Browse** — folder tree with breadcrumbs, search across every file name, sizes and item counts, archive info (header size, file count, how many files carry SHA-256 hashes).
- **Preview** — text and code with line numbers, images (PNG, JPG, GIF, WebP, BMP, ICO), and a hex view for everything else.
- **Extract** — the whole archive, one folder, or a single file. Optionally checks each file against the SHA-256 hash stored in the archive.
- **Pack** — any folder into a `.asar` that Electron and `@electron/asar` read normally, with Electron's integrity hashes (on by default).
- **Open with / Share to** — send a `.asar` to NestASAR from a file manager.
- Always-dark interface.

Requires Android 8.0 (API 26) or newer.

---

## Getting the APK

Every push to `main` builds the app on GitHub.

1. Open the repo's **Actions** tab.
2. Tap the newest **Build APK** run with a green check.
3. Scroll to **Artifacts** and download **NestASAR-apk** (a zip).
4. Unzip it. Inside are:
   - `NestASAR.apk` — the normal app. Install this one.
   - `NestASAR-debug.apk` — a debug copy that installs *alongside* the normal one (handy for testing).
5. Open the APK on your device and allow "install unknown apps" for your file manager if asked.

To make a proper release with the APKs attached, push a tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Signing with your own key (so updates install over each other)

Without a key, each build is signed with a throwaway debug key, so Android will refuse to install a new build over an old one (uninstall first). To fix that, give the workflow your own `.jks` (NestSigner can make one):

1. Turn the keystore into text:
   ```bash
   base64 -w0 your-key.jks > key.txt
   ```
2. In the repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add:

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | contents of `key.txt` |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | the key alias |
   | `KEY_PASSWORD` | the key password |

3. Re-run the workflow (Actions → Build APK → **Run workflow**). The log line "Release APK will be signed with your key." confirms it worked.

---

## Using it

### Browse an archive
1. Tap **Browse an archive**.
2. Pick the `.asar` file.
3. Tap folders to go in; tap the breadcrumb names (or the back arrow) to go up.
4. Tap a file to preview it. The download arrow on any row saves that file, or extracts that folder.
5. The **ⓘ** button shows archive details; the box-with-arrow button extracts everything.

### Extract everything
1. Tap **Extract everything** and pick the `.asar`.
2. Pick the folder to extract into. NestASAR makes a new folder there named after the archive (`app.asar` → `app/`).
3. Wait for the progress bar, then read the summary. It lists anything skipped and any hash mismatches.

### Pack a folder
1. (Optional) Flip the switches under **Pack a folder**:
   - *Add integrity hashes* — keep this on unless you know you don't need it.
   - *Leave out hidden files* — skips names starting with a dot.
2. Tap **Pack a folder** and pick the folder. Its **contents** become the archive's root (pick `app/`, not the folder that contains `app/`).
3. Choose a name and location for the new `.asar` and tap **Save**.

### Things to know
- **"unpacked" files** (often native `.node` modules) are listed in the archive but their bytes live in the `app.asar.unpacked/` folder next to it. NestASAR shows them but can't extract them from the `.asar`; copy that folder separately.
- **Symlinks** inside an archive are shown but skipped on extract, because Android's shared storage can't hold symlinks.
- **Electron integrity checks:** if an app has ASAR integrity enforcement turned on (a hash of the header baked into the executable), *any* repacked archive will be rejected by that app, no matter which tool made it.
- Extracting a big archive (thousands of files) takes a while because Android creates each file through the storage system one at a time.

---

## Building it yourself

You need JDK 17 and the Android SDK (API 35).

```bash
git clone https://github.com/Techy-Nature/NestASAR
cd NestASAR
./gradlew testDebugUnitTest   # ASAR read/write tests
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

### Layout

```
app/src/main/java/com/techynature/nestasar/
├── asar/                 # plain-Java ASAR library (no Android code)
│   ├── AsarArchive.java  # header parsing, random-access reads
│   ├── AsarExtractor.java
│   ├── AsarPacker.java   # two-pass packer with Electron integrity hashes
│   ├── AsarEntry.java, Json.java, ByteSource.java, ...
├── Saf.kt                # Storage Access Framework input/output
├── MainViewModel.kt      # state, jobs, previews
├── MainActivity.kt
└── ui/                   # Jetpack Compose screens (Home, Viewer, dialogs, theme)
```

### ASAR format notes

```
offset  type    value
0       uint32  4                    size of the next field
4       uint32  H                    header pickle size
8       uint32  H - 4                header payload size
12      int32   L                    JSON length
16      L bytes JSON header, zero-padded to a multiple of 4
8 + H   ...     file data (each file's "offset" is relative to here)
```

Files in the JSON header look like `{"size": 6, "offset": "0", "integrity": {...}}`, folders like `{"files": {...}}`, links like `{"link": "a/b"}`. Integrity is a SHA-256 of the whole file plus one SHA-256 per 4 MiB block, matching `@electron/asar`.
