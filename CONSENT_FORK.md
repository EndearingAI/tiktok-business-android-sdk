# Endearing consent-controlled Android SDK fork

Based on TikTok Business Android SDK 1.7.1, upstream commit
`f43771da5b9019de784474561d1d3ee71f3f8ec0`. The upstream MIT license and notices
remain in this repository. The SDK still reports version 1.7.1.

## Application integration

Tracking starts disabled in every process. The application must restore its own
consent decision before initializing this SDK. Consent is never restored from an
SDK preference or granted by a remote configuration response.

```java
// Use the context overload at startup so a refusal can erase prior-process data.
TikTokBusinessSdk.setTrackingEnabled(applicationContext, consentGranted);
if (consentGranted) {
    TikTokBusinessSdk.initializeSdk(config, initCallback);
}

// Withdrawal immediately closes the consent gate, then erases SDK-owned data.
TikTokBusinessSdk.setTrackingEnabled(applicationContext, false);

// Regrant recreates the SDK with its saved configuration and fresh identities.
TikTokBusinessSdk.setTrackingEnabled(applicationContext, true);
```

`setTrackingEnabled(boolean)` remains available once the SDK has received a
context. When called before any context exists, withdrawal also records a cleanup
requirement that must complete before later initialization can collect data.
`startTrack()` now delegates to an explicit consent grant. `disableAutoStart()`
does not grant consent or provide an alternate privacy mechanism.

Initializing while denied saves only the supplied configuration/callback and
purges SDK tracking data. It creates no logger, identity, lifecycle observer,
timer, or request. Repeated initialization completes its callback when the
existing initialization is ready, including explicit initialization after regrant.

`getAnonymousID()` returns null while denied or before initialization. Withdrawal
clears anonymous, advertising, external-user and session IDs; referrer and cached
request context; event/EDP queues; persisted events and crash/monitor reports;
deferred work; purchase caches; and UI tracking callbacks. Install-once and
second-day-retention markers remain so regrant does not synthesize another install.
Applications that deliver verified revenue themselves should configure
`disableAutoIapTrack()` and disable EDP when that collection is not required.

The consent generation fences executor tasks, identifier results, configuration
responses, deferred links, HTTP redirects and retries. Every HTTP connection start
shares the withdrawal lock. A request already issued may finish; its old generation
cannot start another request, publish an identity, save data, or reactivate tracking.
Failed disk/preference erasure surfaces an exception and keeps tracking disabled;
regrant must complete cleanup successfully first.

## Durable verified conversions

```java
boolean accepted = TikTokBusinessSdk.trackEvent(
    providerEventName, verifiedProperties, backendEventId, originalOccurredAtMillis);
```

The overload returns whether the current consent generation accepted the event
into its queue. False means the bridge must not acknowledge queue acceptance.
True is local queue acceptance, not a server-delivery receipt. Withdrawal discards
pending events even if previously accepted.

The overload requires a nonempty event ID and a positive original epoch-millisecond
timestamp. It uses the SDK's existing provider wire fields: `event_id` and the
UTC ISO-8601 `timestamp` (second precision). `tt_event_id` remains populated for
legacy compatibility. IDs supplied through legacy APIs also become the stable
wire `event_id`; SDK-generated IDs are stored with the event. Offline retry no
longer generates a new `event_id` on every flush.

## Build, test, publication

The library builds with JDK 17, Gradle 8.14.3, AGP 8.10.1 and Android SDK 35.
The minimum SDK remains 21. Samples are excluded from the default library build;
`-PincludeSamples` opts into their upstream configuration.

```sh
./gradlew :business-core:testDebugUnitTest :business-core:assembleRelease
./gradlew :business-core:publishReleasePublicationToMavenLocal
```

`TrackingConsentTest` runs the real SDK and persistence under Robolectric and
records serialized HTTP traffic through loopback sockets with MockWebServer. It
covers denied startup, raw transport/monitor entry points, grant/withdrawal and
foreground activity, config responses in flight, offline events and crash erasure,
queued work, fresh identities and install-once behavior on regrant, late advertising
IDs, EDP screen/touch collection, redirect fencing, failed erasure, stable event-ID
retry and original occurrence timestamps. Existing upstream JVM tests also run.

JitPack's `jitpack.yml` publishes the library with the requested commit version.
After that commit is pushed and built by JitPack, pin the full commit:

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    implementation 'com.github.EndearingAI:tiktok-business-android-sdk:<full-commit>'
}
```

Publication coordinates can be exercised locally without changing the SDK's
reported version using `-PforkPublicationGroup=com.github.EndearingAI` and
`-PforkPublicationVersion=<commit>`. Generated AARs, JARs and build output must not
be committed.
