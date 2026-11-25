# Market Watch

25 Nov 2026 se Market Watch fir se Sahayak ke andar hai (23 Nov ko alag kiya tha, wapas jod diya).

- Code: `app/src/main/java/com/sahayak/app` (Sahayak me). Alag Market Watch app wahi files `market/build.gradle.kts` ke `sharedCode` se copy karke banti hai, isliye ek hi jagah badlo, dono me aa jata hai.
- Tests: `app/src/test/java` (GitHub Actions par har push me chalte hain).
- APK: `sahayak.apk` (Market Watch button ke saath), `market-watch.apk` (Hinglish) aur `market-watch-marathi.apk` (Marathi). Link: Releases → latest.
- Data aur khel (Nifty/Bank Nifty andaza, nakli option trade): `research/` ki Python files, GitHub Actions par.
