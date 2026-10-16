# Galaxy TV Apple — Build Without Mac

## Solution: GitHub Actions Remote Build (Free Mac Server)

You do NOT need a Mac.

1. Push this repo to GitHub (or create repo and push):
   git remote add origin https://github.com/YOUR_USERNAME/galaxy.git
   git push -u origin main

2. Go to Actions tab → "Build Galaxy TV Apple" → Run.

3. GitHub uses `macos-latest` (real Mac server) to run Xcode.

4. Download `.app` / `.ipa` artifacts after build completes.

## Already Done in This Project
- Apple source code: `Apple/Shared/` (full SwiftUI + AVKit + API + Encryption)
- Xcode project: `Apple/GalaxyTV.xcodeproj/`
- Endpoint: `localhost:8080` (match Docker server if running)
- No duplicate API classes; MAC = "" (Apple limitation handled)
- Assets note added to SplashScreen (add `logo_video.mp4` + `splash_logo.png` to Assets)
- Info.plist ATS exception set

## Manual Build Commands (if you get a Mac later)
cd Apple
xcodebuild -project GalaxyTV.xcodeproj -scheme GalaxyTV-iOS -destination 'platform=iOS Simulator,name=iPhone 15' build
xcodebuild -project GalaxyTV.xcodeproj -scheme GalaxyTV-tvOS -destination 'platform=tvOS Simulator,name=Apple TV' build
