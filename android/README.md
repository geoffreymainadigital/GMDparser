# Android Application Module (`android/`)

This directory will contain the native Android application (Kotlin / Gradle).

## Planned Components:
- **SMS Receiver**: Broadcast receiver listening for incoming M-PESA messages (`android.provider.Telephony.SMS_RECEIVED`).
- **Parser Engine**: Deterministic regex/pattern matching module extracting code, amount, party, date, time, and transfer details.
- **Review UI**: Compose/Views-based card requiring explicit user confirmation prior to network transmission.
- **Network Client**: Retrofit / OkHttp client communicating strictly with `https://gmdarser.vercel.app`.
- **Invariants**:
  - `isAutoSync() = false`
  - No direct Google Sheets access.
  - Zero Google API secrets in client.
