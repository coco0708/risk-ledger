# Risk Ledger

A small Android app for logging crypto trades with a 4-step take-profit plan, sizing each
position from your risk %, and showing a simple dashboard (balance, next-trade risk,
drawdown brake, win rate, expectancy, TP funnel, compound vs flat).

- `web/app.html` - the app itself (edit this)
- `build_web.py` - copies the app into the Android project (`python build_web.py`)
- `android/` - the Android project (a WebView that saves trades to a private file on the phone)
- `.github/workflows/build-apk.yml` - builds the APK on GitHub and posts it under Releases

## Get the APK with GitHub (nothing to install except Git)

1. On github.com create an empty repository named `risk-ledger` (no README).
2. In PowerShell:

```powershell
Expand-Archive "$HOME\Downloads\risk-ledger.zip" -DestinationPath "$HOME\Projects" -Force
Set-Location "$HOME\Projects\risk-ledger"
git init -b main
git add .
git commit -m "Risk Ledger app"
git remote add origin https://github.com/YOUR-USERNAME/risk-ledger.git
git push -u origin main
```

3. Open the repository's **Actions** tab and wait for "Build APK" to finish (about 3-5 minutes).
4. On your phone open `https://github.com/YOUR-USERNAME/risk-ledger/releases`, download
   `RiskLedger.apk`, and open it. Allow "Install unknown apps" for your browser when asked.

## Or build it in Android Studio

Open the `android` folder in Android Studio, then **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
Or in PowerShell (Android Studio installed):

```powershell
Set-Location "$HOME\Projects\risk-ledger\android"
.\gradlew.bat assembleRelease
# APK: app\build\outputs\apk\release\app-release.apk
```

## Notes

- The app is signed with the key in `android/app/risk-ledger.jks`, so new builds install as
  updates and keep your trades. Keep that file.
- Trades stay on the phone. Use Settings > Back up to send yourself a copy.
