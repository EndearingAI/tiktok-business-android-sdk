package com.tiktok.appevents;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import androidx.lifecycle.ProcessLifecycleOwner;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.HttpRequestUtil;
import com.tiktok.util.SystemInfoUtil;
import com.tiktok.util.TTConst;
import com.tiktok.util.TTTrackingConsent;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

/** Runs the real SDK, persistence, and HTTP serialization against a local request recorder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TrackingConsentTest {
    public static class DeletionFailureApplication extends Application {
        boolean rejectDeletion;

        @Override
        public boolean deleteFile(String name) {
            if (rejectDeletion && "events_cache".equals(name)) return false;
            return super.deleteFile(name);
        }
    }

    private static final String LOOPBACK_PROPERTY = "com.tiktok.consent.test.loopback";
    private MockWebServer server;
    private Application application;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        TikTokBusinessSdk.destroy();
        server = new MockWebServer();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody(
                                        "{\"code\":0,\"data\":{\"business_sdk_config\":{\"enable_sdk\":true,\"available_version\":\"v1.3\",\"domain\":\"analytics.us.tiktok.com\"}}}");
                    }
                });
        server.start();
        System.setProperty(LOOPBACK_PROPERTY, server.url("/").toString());
        try {
            URL.setURLStreamHandlerFactory(
                    protocol ->
                            "https".equals(protocol)
                                    ? new URLStreamHandler() {
                                        @Override
                                        protected URLConnection openConnection(URL url)
                                                throws java.io.IOException {
                                            return new LocalHttpsConnection(url);
                                        }
                                    }
                                    : null);
        } catch (Error alreadyInstalled) {
            // The factory is process-wide; Robolectric can reuse this test JVM.
            if (!alreadyInstalled.getMessage().contains("factory already defined"))
                throw alreadyInstalled;
        }
    }

    @After
    public void tearDown() throws Exception {
        TikTokBusinessSdk.destroy();
        drain();
        server.shutdown();
    }

    @Test
    public void deniedStartupDoesNotStartConfigMonitorOrIdentifierCollection() throws Exception {
        TikTokBusinessSdk.initializeSdk(config());
        TikTokBusinessSdk.trackEvent("DeniedEvent");
        TikTokBusinessSdk.identify("denied-user", null, null, null);
        TikTokBusinessSdk.fetchDeferredDeeplinkWithCompletion((url, error) -> {});
        TikTokBusinessSdk.flush();
        TTCrashHandler.handleCrash("test", new IllegalStateException("denied crash"), 1);
        TTCrashHandler.initCrashReporter();
        TTRequest.reportMonitorEvent(new JSONObject());
        HttpRequestUtil.doGet(
                "https://example.invalid/consent-probe",
                new java.util.HashMap<>(),
                new HttpRequestUtil.HttpRequestOptions());
        drain();

        assertEquals(
                "No SDK transport may start before explicit consent", 0, server.getRequestCount());
        assertNull(
                application
                        .getSharedPreferences(TTConst.TTSDK_KEY_VALUE_STORE, Context.MODE_PRIVATE)
                        .getString(TTConst.TTSDK_APP_ANONYMOUS_ID, null));
        assertEquals(0, TTAppEventsQueue.size());
        assertFalse(application.getFileStreamPath("tt_crash_log").exists());
    }

    @Test
    public void deniedIdentifierReadsDoNotCreateSessionOrAnonymousIdentity() {
        assertNull(TikTokBusinessSdk.getAnonymousID());
        assertNull(SystemInfoUtil.getAppSessionId());
        assertNull(com.tiktok.util.TTUtil.getOrGenAnoId(application, false));
        assertEquals("", TTIdentifierFactory.getGoogleAdIdInfo(application).getAdId());
    }

    @Test
    public void grantThenWithdrawAndForegroundCannotRestartRequests() throws Exception {
        grantAndInitialize();
        TTActivityLifecycleCallbacksListener observer =
                new TTActivityLifecycleCallbacksListener(logger());
        assertNotNull(TikTokBusinessSdk.getAnonymousID());
        assertTrue(server.getRequestCount() > 0);
        TikTokBusinessSdk.trackEvent("BeforeWithdrawal");
        drain();

        TikTokBusinessSdk.setTrackingEnabled(false);
        int sentBeforeDeniedPeriod = server.getRequestCount();
        TikTokBusinessSdk.setSdkGlobalSwitch(true);
        TikTokBusinessSdk.trackEvent("DeniedAfterWithdrawal");
        TikTokBusinessSdk.identify("denied-user", null, null, null);
        TikTokBusinessSdk.flush();
        observer.onPause(ProcessLifecycleOwner.get());
        observer.onResume(ProcessLifecycleOwner.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.MINUTES);
        drain();

        assertEquals(sentBeforeDeniedPeriod, server.getRequestCount());
        assertNull(TikTokBusinessSdk.getAnonymousID());
        assertEquals(0, TTAppEventsQueue.size());
        assertNull(TTUserInfo.sharedInstance.externalId);
        assertFalse(application.getFileStreamPath("events_cache").exists());
        assertFalse(application.getFileStreamPath("tt_crash_log").exists());
    }

    @Test
    public void regrantUsesNewIdentityWithoutReplayingWithdrawnEventsOrInstall() throws Exception {
        grantAndInitialize();
        String originalId = TikTokBusinessSdk.getAnonymousID();
        String installMarker = preferences().getString(TTConst.TTSDK_APP_FIRST_INSTALL, null);
        assertNotNull(installMarker);
        drainRecordedRequests();

        TikTokBusinessSdk.trackEvent("QueuedBeforeWithdrawal");
        drain();
        TikTokBusinessSdk.setTrackingEnabled(false);
        TikTokBusinessSdk.trackEvent("DeniedPeriodEvent");
        TikTokBusinessSdk.setTrackingEnabled(true);
        drain();
        assertNotNull(TikTokBusinessSdk.getAnonymousID());
        assertNotEquals(originalId, TikTokBusinessSdk.getAnonymousID());
        assertEquals(installMarker, preferences().getString(TTConst.TTSDK_APP_FIRST_INSTALL, null));
        TikTokBusinessSdk.trackEvent("AfterRegrant");
        TikTokBusinessSdk.flush();
        drain();

        String bodies = drainRecordedRequests();
        assertTrue(bodies, bodies.contains("AfterRegrant"));
        assertFalse(bodies, bodies.contains("QueuedBeforeWithdrawal"));
        assertFalse(bodies, bodies.contains("DeniedPeriodEvent"));
        assertFalse(bodies, bodies.contains("InstallApp"));
        assertFalse(bodies, bodies.contains(originalId));
    }

    @Test
    public void configAlreadyInFlightCannotActivateOrCollectAfterWithdrawal() throws Exception {
        CountDownLatch configStarted = new CountDownLatch(1);
        CountDownLatch releaseConfig = new CountDownLatch(1);
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request)
                            throws InterruptedException {
                        configStarted.countDown();
                        assertTrue(releaseConfig.await(5, TimeUnit.SECONDS));
                        return new MockResponse()
                                .setBody(
                                        "{\"code\":0,\"data\":{\"business_sdk_config\":{\"enable_sdk\":true}}}");
                    }
                });
        TikTokBusinessSdk.setTrackingEnabled(true);
        TikTokBusinessSdk.initializeSdk(config());
        assertTrue(configStarted.await(10, TimeUnit.SECONDS));
        TikTokBusinessSdk.setTrackingEnabled(false);
        releaseConfig.countDown();
        drain();

        assertFalse(TikTokBusinessSdk.isInitialized());
        assertFalse(TikTokBusinessSdk.isSystemActivated());
        assertFalse(TikTokBusinessSdk.isGlobalConfigFetched());
        assertNull(TikTokBusinessSdk.getAnonymousID());
        assertNull(preferences().getString(TTConst.TTSDK_APP_ANONYMOUS_ID, null));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void alreadyQueuedWorkCannotRunAfterRegrant() throws Exception {
        grantAndInitialize();
        TTAppEventLogger oldLogger = logger();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> barrier =
                TTAppEventLogger.eventLoop.submit(
                        () -> {
                            blocked.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        TikTokBusinessSdk.trackEvent("StaleQueuedEvent");
        TikTokBusinessSdk.setTrackingEnabled(false);
        TikTokBusinessSdk.setTrackingEnabled(true);
        oldLogger.track("OldObserverEvent", null);
        release.countDown();
        barrier.get(5, TimeUnit.SECONDS);
        drain();
        TikTokBusinessSdk.flush();
        drain();
        String bodies = drainRecordedRequests();
        assertFalse(bodies, bodies.contains("StaleQueuedEvent"));
        assertFalse(bodies, bodies.contains("OldObserverEvent"));
    }

    @Test
    public void offlineEventsAndHistoricalCrashesAreDeletedOnWithdrawal() throws Exception {
        grantAndInitialize();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse().setResponseCode(503).setBody("{}");
                    }
                });
        TikTokBusinessSdk.trackEvent("OfflineEvent");
        TikTokBusinessSdk.flush();
        drain();
        assertTrue(application.getFileStreamPath("events_cache").exists());
        TTCrashHandler.handleCrash("test", new IllegalStateException("old crash"), 1);
        assertTrue(application.getFileStreamPath("tt_crash_log").exists());

        TikTokBusinessSdk.setTrackingEnabled(false);
        assertFalse(application.getFileStreamPath("events_cache").exists());
        assertFalse(application.getFileStreamPath("tt_crash_log").exists());
        assertNull(preferences().getString(TTConst.TTSDK_APP_ANONYMOUS_ID, null));
        assertTrue(TTCrashHandler.crashReport.reports.isEmpty());
    }

    @Test
    public void staleConsentScopeCannotStartHttpOrRestoreIdentityAfterRegrant() throws Exception {
        grantAndInitialize();
        long oldGeneration = TTTrackingConsent.capture();
        TikTokBusinessSdk.setTrackingEnabled(false);
        TikTokBusinessSdk.setTrackingEnabled(true);
        drain();
        String freshId = TikTokBusinessSdk.getAnonymousID();
        int before = server.getRequestCount();

        TTTrackingConsent.wrap(
                        oldGeneration,
                        () -> {
                            com.tiktok.util.TTUtil.getOrGenAnoId(application, true);
                            TTCrashHandler.retryLater(new JSONObject());
                            HttpRequestUtil.doPost(
                                    "https://example.invalid/stale",
                                    new java.util.HashMap<>(),
                                    "{}",
                                    false);
                        })
                .run();
        assertEquals(before, server.getRequestCount());
        assertEquals(freshId, TikTokBusinessSdk.getAnonymousID());
    }

    @Test
    public void deniedViewHierarchyDoesNotReadScreenText() {
        android.widget.TextView view = new android.widget.TextView(application);
        view.setText("Private screen content");
        assertEquals(
                0,
                com.tiktok.appevents.edp.TTHierarchyHelper.getViewHierarchy(
                                new java.lang.ref.WeakReference<>(view), 2)
                        .length());
    }

    @Test
    public void oldTouchObserverCannotCollectAfterWithdrawalOrRegrant() {
        TikTokBusinessSdk.setTrackingEnabled(true);
        java.util.concurrent.atomic.AtomicInteger collected =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger deliveredToApp =
                new java.util.concurrent.atomic.AtomicInteger();
        com.tiktok.appevents.edp.proxy.ProxyOnTouchListener proxy =
                new com.tiktok.appevents.edp.proxy.ProxyOnTouchListener(
                        (view, event) -> {
                            collected.incrementAndGet();
                            return false;
                        },
                        (view, event) -> {
                            deliveredToApp.incrementAndGet();
                            return true;
                        });
        android.view.MotionEvent event = android.view.MotionEvent.obtain(0, 1, 0, 0, 0, 0);
        android.view.View view = new android.view.View(application);
        assertTrue(proxy.onTouch(view, event));
        assertEquals(1, collected.get());
        TikTokBusinessSdk.setTrackingEnabled(false);
        assertTrue(proxy.onTouch(view, event));
        TikTokBusinessSdk.setTrackingEnabled(true);
        assertTrue(proxy.onTouch(view, event));
        assertEquals(1, collected.get());
        assertEquals(3, deliveredToApp.get());
        event.recycle();
    }

    @Test
    public void callerEventIdIsStableAcrossAnOfflineRetry() throws Exception {
        grantAndInitialize();
        drainRecordedRequests();
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse().setResponseCode(503).setBody("{}");
                    }
                });
        TikTokBusinessSdk.trackEvent("RetryDelivery", new JSONObject(), "backend-event-42");
        TikTokBusinessSdk.flush();
        drain();
        JSONObject firstAttempt = findEvent(drainRecordedRequests(), "RetryDelivery");
        assertNotNull(firstAttempt);
        assertEquals("backend-event-42", firstAttempt.getString("event_id"));

        TikTokBusinessSdk.flush();
        drain();
        JSONObject secondAttempt = findEvent(drainRecordedRequests(), "RetryDelivery");
        assertNotNull(secondAttempt);
        assertEquals("backend-event-42", secondAttempt.getString("event_id"));
        assertEquals(firstAttempt.getString("timestamp"), secondAttempt.getString("timestamp"));
    }

    @Test
    public void durableDeliveryUsesOriginalOccurrenceTimeInProviderTimestamp() throws Exception {
        grantAndInitialize();
        drainRecordedRequests();
        assertTrue(
                TikTokBusinessSdk.trackEvent(
                        "OriginalConversion",
                        new JSONObject(),
                        "durable-conversion-1",
                        1700000000000L));
        TikTokBusinessSdk.flush();
        drain();
        JSONObject event = findEvent(drainRecordedRequests(), "OriginalConversion");
        assertNotNull(event);
        assertEquals("durable-conversion-1", event.getString("event_id"));
        assertEquals("2023-11-14T22:13:20Z", event.getString("timestamp"));
    }

    @Test
    public void startupWithdrawalPurgesPriorProcessIdentityWithoutInitializingSdk() {
        preferences()
                .edit()
                .putString(TTConst.TTSDK_APP_ANONYMOUS_ID, "prior-process-identity")
                .putString(TTConst.TTSDK_APP_FIRST_INSTALL, "2026-10-01 12:00:00")
                .commit();
        application
                .getSharedPreferences("com.tiktok.sdk.ids", Context.MODE_PRIVATE)
                .edit()
                .putString("gaid", "old-gaid")
                .commit();
        TikTokBusinessSdk.setTrackingEnabled(application, false);
        assertFalse(TikTokBusinessSdk.isInitialized());
        assertNull(preferences().getString(TTConst.TTSDK_APP_ANONYMOUS_ID, null));
        assertNull(
                application
                        .getSharedPreferences("com.tiktok.sdk.ids", Context.MODE_PRIVATE)
                        .getString("gaid", null));
        assertEquals(
                "2026-10-01 12:00:00",
                preferences().getString(TTConst.TTSDK_APP_FIRST_INSTALL, null));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void durableDeliveryRejectsDeniedOrOldLoggerQueue() throws Exception {
        assertFalse(
                TikTokBusinessSdk.trackEvent(
                        "DeniedConversion", new JSONObject(), "durable-1", 1700000000000L));
        grantAndInitialize();
        TTAppEventLogger oldLogger = logger();
        TikTokBusinessSdk.setTrackingEnabled(false);
        TikTokBusinessSdk.setTrackingEnabled(true);
        drain();
        assertFalse(
                oldLogger.track("StaleConversion", new JSONObject(), "durable-2", 1700000000000L));
    }

    @Test
    public void advertisingIdArrivingAfterWithdrawalCannotRestoreOldIdentity() throws Exception {
        grantAndInitialize();
        TTIdentifierFactory.clear(application);
        com.google.android.gms.ads.identifier.AdvertisingIdClient.value = "withdrawn-gaid";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        com.google.android.gms.ads.identifier.AdvertisingIdClient.entered = entered;
        com.google.android.gms.ads.identifier.AdvertisingIdClient.release = release;
        java.util.concurrent.ExecutorService identifiers =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<TTIdentifierFactory.AdIdInfo> oldRead =
                    identifiers.submit(() -> TTIdentifierFactory.getGoogleAdIdInfo(application));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            TikTokBusinessSdk.setTrackingEnabled(false);
            TikTokBusinessSdk.setTrackingEnabled(true);
            com.google.android.gms.ads.identifier.AdvertisingIdClient.value = "new-consented-gaid";
            release.countDown();
            assertEquals("", oldRead.get(5, TimeUnit.SECONDS).getAdId());
            drain();
            assertEquals(
                    "new-consented-gaid",
                    TTIdentifierFactory.getGoogleAdIdInfo(application).getAdId());
            assertEquals(
                    "new-consented-gaid",
                    application
                            .getSharedPreferences("com.tiktok.sdk.ids", Context.MODE_PRIVATE)
                            .getString("gaid", null));
        } finally {
            release.countDown();
            identifiers.shutdownNow();
            com.google.android.gms.ads.identifier.AdvertisingIdClient.entered = null;
            com.google.android.gms.ads.identifier.AdvertisingIdClient.release = null;
        }
    }

    @Test
    @Config(application = DeletionFailureApplication.class)
    public void failedDiskErasureKeepsTrackingDisabledUntilCleanupSucceeds() throws Exception {
        grantAndInitialize();
        try (OutputStream file = application.openFileOutput("events_cache", Context.MODE_PRIVATE)) {
            file.write(new byte[] {1});
        }
        DeletionFailureApplication failedDisk = (DeletionFailureApplication) application;
        failedDisk.rejectDeletion = true;
        try {
            assertThrows(
                    IllegalStateException.class, () -> TikTokBusinessSdk.setTrackingEnabled(false));
            assertFalse(TikTokBusinessSdk.isSystemActivated());
            assertThrows(
                    IllegalStateException.class, () -> TikTokBusinessSdk.setTrackingEnabled(true));
            assertNull(TikTokBusinessSdk.getAnonymousID());
        } finally {
            failedDisk.rejectDeletion = false;
        }
        TikTokBusinessSdk.setTrackingEnabled(true);
        drain();
        assertNotNull(TikTokBusinessSdk.getAnonymousID());
        assertFalse(application.getFileStreamPath("events_cache").exists());
    }

    @Test
    public void redirectFromAnOldRequestCannotStartAfterWithdrawalAndRegrant() throws Exception {
        grantAndInitialize();
        drainRecordedRequests();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request)
                            throws InterruptedException {
                        if (request.getPath().startsWith("/consent-redirect-source")) {
                            started.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return new MockResponse()
                                    .setResponseCode(302)
                                    .addHeader(
                                            "Location",
                                            "https://example.invalid/revoked-redirect-target");
                        }
                        return new MockResponse()
                                .setBody(
                                        "{\"code\":0,\"data\":{\"business_sdk_config\":{\"enable_sdk\":true}}}");
                    }
                });
        java.util.concurrent.ExecutorService requests =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<HttpRequestUtil.HttpResponse> oldRequest =
                    requests.submit(
                            () ->
                                    HttpRequestUtil.doGet(
                                            "https://example.invalid/consent-redirect-source",
                                            new java.util.HashMap<>(),
                                            new HttpRequestUtil.HttpRequestOptions()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            TikTokBusinessSdk.setTrackingEnabled(false);
            TikTokBusinessSdk.setTrackingEnabled(true);
            release.countDown();
            assertFalse(oldRequest.get(5, TimeUnit.SECONDS).isOK());
            drain();
            RecordedRequest request;
            while ((request = server.takeRequest(30, TimeUnit.MILLISECONDS)) != null) {
                assertFalse(
                        request.getPath(),
                        request.getPath().startsWith("/revoked-redirect-target"));
            }
        } finally {
            release.countDown();
            requests.shutdownNow();
        }
    }

    @Test
    public void explicitInitializeAfterRegrantCompletesItsCallbackOnce() throws Exception {
        grantAndInitialize();
        TikTokBusinessSdk.setTrackingEnabled(false);
        TikTokBusinessSdk.setTrackingEnabled(true);
        java.util.concurrent.atomic.AtomicInteger completed =
                new java.util.concurrent.atomic.AtomicInteger();
        TikTokBusinessSdk.initializeSdk(
                config(),
                new TikTokBusinessSdk.TTInitCallback() {
                    @Override
                    public void success() {
                        completed.incrementAndGet();
                    }

                    @Override
                    public void fail(int code, String message) {
                        throw new AssertionError(message);
                    }
                });
        drain();
        assertEquals(1, completed.get());
    }

    private JSONObject findEvent(String requests, String name) throws Exception {
        for (String request : requests.split("\\n")) {
            if (request.isEmpty()) continue;
            JSONArray batch = new JSONObject(request).optJSONArray("batch");
            if (batch == null) continue;
            for (int i = 0; i < batch.length(); i++) {
                JSONObject event = batch.getJSONObject(i);
                if (name.equals(event.optString("event"))) return event;
            }
        }
        return null;
    }

    private android.content.SharedPreferences preferences() {
        return application.getSharedPreferences(
                TTConst.TTSDK_KEY_VALUE_STORE, Context.MODE_PRIVATE);
    }

    private TTAppEventLogger logger() {
        return TikTokBusinessSdk.getAppEventLogger();
    }

    private void grantAndInitialize() throws Exception {
        TikTokBusinessSdk.setTrackingEnabled(true);
        TikTokBusinessSdk.initializeSdk(config());
        drain();
    }

    private String drainRecordedRequests() throws Exception {
        StringBuilder bodies = new StringBuilder();
        RecordedRequest request;
        while ((request = server.takeRequest(30, TimeUnit.MILLISECONDS)) != null) {
            byte[] bytes = request.getBody().readByteArray();
            if (bytes.length == 0) continue;
            InputStream input = new ByteArrayInputStream(bytes);
            if ("gzip".equals(request.getHeader("Content-Encoding")))
                input = new GZIPInputStream(input);
            bodies.append(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('\n');
        }
        return bodies.toString();
    }

    private TikTokBusinessSdk.TTConfig config() {
        return new TikTokBusinessSdk.TTConfig(application, "test-access-token")
                .setAppId("com.example.consent")
                .setTTAppId("1234567890123456789")
                .disableAdvertiserIDCollection()
                .disableAutoIapTrack()
                .disableAutoEnhancedDataPostbackEvent()
                .setFlushTimeInterval(0);
    }

    private void drain() throws Exception {
        for (int i = 0; i < 6; i++) {
            ShadowLooper.idleMainLooper();
            TTAppEventLogger.eventLoop.submit(() -> {}).get(10, TimeUnit.SECONDS);
        }
    }

    /** Rewrites HTTPS destinations only in the test JVM; actual requests use loopback sockets. */
    private static final class LocalHttpsConnection extends HttpsURLConnection {
        private final HttpURLConnection connection;

        LocalHttpsConnection(URL original) throws java.io.IOException {
            super(original);
            String path = original.getFile();
            connection =
                    (HttpURLConnection)
                            new URL(
                                            System.getProperty(LOOPBACK_PROPERTY)
                                                    + (path.startsWith("/")
                                                            ? path.substring(1)
                                                            : path))
                                    .openConnection();
        }

        @Override
        public void connect() throws java.io.IOException {
            connection.setRequestMethod(getRequestMethod());
            connection.setConnectTimeout(getConnectTimeout());
            connection.setReadTimeout(getReadTimeout());
            connection.setDoInput(getDoInput());
            connection.setDoOutput(getDoOutput());
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, List<String>> header : getRequestProperties().entrySet()) {
                for (String value : header.getValue())
                    connection.addRequestProperty(header.getKey(), value);
            }
            connection.connect();
        }

        @Override
        public OutputStream getOutputStream() throws java.io.IOException {
            return connection.getOutputStream();
        }

        @Override
        public InputStream getInputStream() throws java.io.IOException {
            return connection.getInputStream();
        }

        @Override
        public int getResponseCode() throws java.io.IOException {
            return connection.getResponseCode();
        }

        @Override
        public String getHeaderField(String name) {
            return connection.getHeaderField(name);
        }

        @Override
        public void disconnect() {
            connection.disconnect();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public String getCipherSuite() {
            return "test-loopback";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }

        @Override
        public Principal getPeerPrincipal() {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }
    }
}
