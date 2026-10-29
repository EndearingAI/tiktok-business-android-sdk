/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import static com.tiktok.appevents.ErrorData.TT_DDL_CODE_HTTP_ERROR;
import static com.tiktok.appevents.edp.EDPConfig.ConfigConst.EDP_NATIVE_SDK_CONFIG;
import static com.tiktok.appevents.edp.TTEDPEventTrack.trackFirstAppLaunch;
import static com.tiktok.util.TTConst.TTSDK_EXCEPTION_SDK_CATCH;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.appevents.edp.EDPConfig;
import com.tiktok.appevents.edp.TTEDPEventTrack;
import com.tiktok.iap.TTInAppPurchaseWrapper;
import com.tiktok.unity.TTUnityBridge;
import com.tiktok.util.HttpRequestUtil;
import com.tiktok.util.JSON;
import com.tiktok.util.LastSessionUtil;
import com.tiktok.util.NetworkTimeout;
import com.tiktok.util.SystemInfoUtil;
import com.tiktok.util.TTConst;
import com.tiktok.util.TTHandlerUtil;
import com.tiktok.util.TTLogger;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TTAppEventLogger {
    static final String SKIP_FLUSHING_BECAUSE_GLOBAL_SWITCH_IS_TURNED_OFF =
            "Skip flushing because global switch is turned off";
    static final String SKIP_FLUSHING_BECAUSE_GLOBAL_CONFIG_IS_NOT_FETCHED =
            "Skip flushing because global config is not fetched";
    static final String TAG = "TTAppEventLogger";

    // every TIME_BUFFER seconds, a flush task will be pushed to the execution queue
    private static int TIME_BUFFER;
    int counter;
    // once THRESHOLD events got accumulated in the memory, a flush task will be pushed to the
    // execution queue
    static final int THRESHOLD = 100;
    public static final String NETWORK_IS_TURNED_OFF =
            "SDK can't send tracking events to server, it will be cached locally, and will be sent"
                    + " in batches only after startTracking";

    static int totalDumped = 0;

    // whether to trigger automatic events in the lifeCycle callbacks provided by Android
    final boolean lifecycleTrackEnable;
    // custom auto event disable, events will be disabled when disabledEvents.contains(event)
    final List<TTConst.AutoEvents> disabledEvents;

    /** Logger util */
    TTLogger logger;

    /** Lifecycle */
    Lifecycle lifecycle;

    // for internal debug purpose
    int flushId = 0;
    public static volatile boolean autoTrackRetentionEnable = true;

    // similar to what javascript has, so that all the internal tasks are executed in a waterfall
    // fashion, avoiding race conditions
    static final ScheduledThreadPoolExecutor eventLoop =
            new ScheduledThreadPoolExecutor(1, new TTThreadFactory());
    ScheduledFuture<?> future = null;

    // used by internal monitor
    static final ScheduledExecutorService timerService =
            Executors.newSingleThreadScheduledExecutor(new TTThreadFactory());
    ScheduledFuture<?> timeFuture = null;
    private final Runnable batchFlush = () -> flush(FlushReason.TIMER);

    final TTAutoEventsManager autoEventsManager;

    static boolean metricsEnabled = true;
    private final TTLifecycleListener mLifecycleListener = new TTLifecycleListener();
    private final long consentGeneration = TTTrackingConsent.capture();
    private volatile boolean destroyed;
    private TTActivityLifecycleCallbacksListener lifecycleObserver;
    final Handler uiThreadHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (!isActive()) return;
                    try {
                        startHeart();
                        if (TTActivityLifecycleCallbacksListener.isBackground()) {
                            return;
                        }
                        if (!TikTokBusinessSdk.isGlobalConfigFetched()) {
                            return;
                        }
                        if (!TikTokBusinessSdk.isSystemActivated()) {
                            return;
                        }
                        JSONObject meta = TTUtil.getMetaWithTS(System.currentTimeMillis());
                        TikTokBusinessSdk.getAppEventLogger()
                                .monitorMetric("session_activity", meta, null);
                    } catch (Throwable e) {

                    }
                }
            };

    public static List<TTAppEvent> getSuccessfulEvents() {
        return TTRequest.getSuccessfullySentRequests();
    }

    public TTAppEventLogger(
            boolean lifecycleTrackEnable,
            List<TTConst.AutoEvents> disabledEvents,
            int flushTime,
            boolean monitorDisable) {
        logger = new TTLogger(TAG, TikTokBusinessSdk.getLogLevel());
        this.lifecycleTrackEnable = lifecycleTrackEnable;
        this.disabledEvents = disabledEvents;
        TIME_BUFFER = flushTime;
        counter = flushTime;
        lifecycle = ProcessLifecycleOwner.get().getLifecycle();
        metricsEnabled = !monitorDisable;

        /* ActivityLifecycleCallbacks & LifecycleObserver */
        final Runnable lifeRun =
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!isActive()) return;
                            lifecycleObserver =
                                    new TTActivityLifecycleCallbacksListener(TTAppEventLogger.this);
                            TTAppEventLogger.this.lifecycle.addObserver(lifecycleObserver);
                            TikTokBusinessSdk.getApplicationContext()
                                    .registerActivityLifecycleCallbacks(mLifecycleListener);
                        } catch (Throwable ignore) {
                        }
                    }
                };
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                lifeRun.run();
            } else {
                uiThreadHandler.post(TTTrackingConsent.wrap(consentGeneration, lifeRun));
            }
        } catch (Throwable ignore) {
        }

        autoEventsManager = new TTAutoEventsManager(this);
    }

    public void initConfig(
            long initTimeMS,
            final TikTokBusinessSdk.TTInitCallback callback,
            AtomicBoolean sdkInitialized) {
        addToQ(
                () -> {
                    try {
                        startHeart();

                        LastSessionUtil.getLastSessionID();
                        SystemInfoUtil.initAppSessionId();
                        SystemInfoUtil.initInstallReferrer();
                        SystemInfoUtil.updateSensigInfo();
                        SystemInfoUtil.initUserAgent();

                        if (!isActive()) return;

                        TTAppEventsQueue.clearAll();
                        TTEdpAppEventsQueue.clearAll();

                        TTTrackingConsent.runIfGranted(
                                () -> {
                                    sdkInitialized.set(true);
                                    if (callback != null) callback.success();
                                });

                        JSONObject meta = TTUtil.getMetaWithTS(initTimeMS);
                        JSON.putLong(meta, "duration", (System.currentTimeMillis() - initTimeMS));
                        monitorMetric("init_end_callback", TTUtil.getMetaWithTS(initTimeMS), null);
                    } catch (Throwable e) {
                        logger.error(e, "init error");
                        TTTrackingConsent.runIfGranted(
                                () -> {
                                    if (callback != null)
                                        callback.fail(
                                                -1,
                                                "TikTok SDK initialization failed; retry"
                                                        + " initialization after granting tracking"
                                                        + " consent");
                                });
                    }
                });

        fetchGlobalConfig(0);
        monitorMetric("init_start", TTUtil.getMetaWithTS(initTimeMS), null);
    }

    /** persist events to the disk */
    void persistEvents() {
        addToQ(() -> TTAppEventStorage.persist(null));
    }

    public void trackPurchase(List<TTPurchaseInfo> purchaseInfos) {
        trackPurchase(false, purchaseInfos);
    }

    public void trackPurchase(boolean isHistory, List<TTPurchaseInfo> purchaseInfos) {
        if (!TikTokBusinessSdk.isSystemActivated()) {
            logger.info("Global switch is off, ignore track purchase");
            return;
        }
        addToQ(
                () -> {
                    if (purchaseInfos.isEmpty()) {
                        return;
                    }

                    for (TTPurchaseInfo purchaseInfo : purchaseInfos) {
                        JSONObject property = TTInAppPurchaseManager.getPurchaseProps(purchaseInfo);
                        if (property != null) {
                            track(
                                    isHistory ? "__purchase_history" : "Purchase",
                                    property,
                                    purchaseInfo.getEventId());
                        }
                    }
                });
    }

    public void startHeart() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!isActive()) return;
            try {
                TTHandlerUtil.getInstance().removeCallbacks(heartRunnable);
                TTHandlerUtil.getInstance().postDelayed(heartRunnable, 60000);
            } catch (Throwable ignore) {
            }
        }
    }

    public void closeHeart() {
        TTHandlerUtil.getInstance().removeCallbacks(heartRunnable);
    }

    void startScheduler() {
        if (TIME_BUFFER != 0) {
            doStartScheduler(TIME_BUFFER, false);
        }
    }

    void restartScheduler() {
        if (TIME_BUFFER != 0) {
            doStartScheduler(TIME_BUFFER, true);
        }
    }

    /**
     * Try to flush to network every {@link TTAppEventLogger#TIME_BUFFER} seconds Like
     * setTimeInterval in js
     */
    private void doStartScheduler(int interval, boolean immediate) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!isActive()) return;
            try {
                if (future == null) {
                    future =
                            eventLoop.scheduleWithFixedDelay(
                                    TTTrackingConsent.wrap(consentGeneration, batchFlush),
                                    immediate ? 0 : interval,
                                    interval,
                                    TimeUnit.SECONDS);
                }
                if (timeFuture == null && TikTokBusinessSdk.nextTimeFlushListener != null) {
                    counter = interval;
                    timeFuture =
                            timerService.scheduleWithFixedDelay(
                                    () -> {
                                        TikTokBusinessSdk.nextTimeFlushListener.timeLeft(counter);
                                        if (counter == 0) {
                                            counter = interval;
                                        }
                                        counter--;
                                    },
                                    0,
                                    1,
                                    TimeUnit.SECONDS);
                }
            } catch (Throwable e) {
                TikTokBusinessSdk.setSdkGlobalSwitch(false);
            }
        }
    }

    /** Stop the recurrent task when the user interface is no longer interactive */
    void stopScheduler() {
        try {
            if (future != null) {
                future.cancel(false);
                future = null;
            }
            if (timeFuture != null) {
                timeFuture.cancel(false);
                timeFuture = null;
            }
        } catch (Throwable e) {
            logger.error(e, "stop scheduler exception");
        }
    }

    public boolean identify(
            String externalId,
            @Nullable String externalUserName,
            @Nullable String phoneNumber,
            @Nullable String email) {
        if (!isActive()) return false;
        synchronized (TTTrackingConsent.LOCK) {
            if (!isActive()) return false;
            try {
                TTUserInfo sharedInstance = TTUserInfo.sharedInstance;
                if (sharedInstance.isIdentified()) {
                    logger.warn(
                            "SDK is already identified, if you want to switch to anotheruser"
                                    + " account, plz call TiktokBusinessSDK.logout() first and then"
                                    + " identify");
                    return false;
                }
                sharedInstance.setIdentified();
                if (!TextUtils.isEmpty(externalId)) {
                    sharedInstance.setExternalId(externalId);
                }
                if (!TextUtils.isEmpty(externalUserName)) {
                    sharedInstance.setExternalUserName(externalUserName);
                }
                if (!TextUtils.isEmpty(phoneNumber)) {
                    sharedInstance.setPhoneNumber(phoneNumber);
                }
                if (!TextUtils.isEmpty(email)) {
                    sharedInstance.setEmail(email);
                }
                trackEvent(TTAppEvent.TTAppEventType.identify, null, null, null, false);
                flushWithReason(FlushReason.IDENTIFY);
                return true;
            } catch (Throwable ignore) {
                return false;
            }
        }
    }

    public void logout() {
        if (!isActive()) return;
        TTUserInfo.reset(TikTokBusinessSdk.getApplicationContext(), true);
        flushWithReason(TTAppEventLogger.FlushReason.LOGOUT);
    }

    /**
     * interface exposed to {@link TikTokBusinessSdk}
     *
     * @param event
     * @param props
     */
    public void track(String event, @Nullable JSONObject props) {
        trackEvent(TTAppEvent.TTAppEventType.track, event, props, null, false);
    }

    public void track(String event, @Nullable JSONObject props, String eventId) {
        trackEvent(TTAppEvent.TTAppEventType.track, event, props, eventId, false);
    }

    public boolean track(
            String event, @Nullable JSONObject props, String eventId, long occurredAtMillis) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!isActive() || !TikTokBusinessSdk.isSystemActivated()) return false;
            TTAppEvent appEvent =
                    new TTAppEvent(
                            TTAppEvent.TTAppEventType.track,
                            event,
                            new java.util.Date(occurredAtMillis),
                            props == null ? "{}" : props.toString(),
                            eventId,
                            TikTokBusinessSdk.getTTAppIds());
            return addToTask(appEvent, null, null, null, null, false);
        }
    }

    public void trackEdp(String event, @Nullable JSONObject props, String eventId) {
        if (!TikTokBusinessSdk.isEdpEnable()) return;
        trackEvent(TTAppEvent.TTAppEventType.track, event, props, eventId, true);
    }

    private void trackEvent(
            TTAppEvent.TTAppEventType type,
            String event,
            @Nullable JSONObject props,
            String eventId,
            boolean edp) {
        if (!isActive()
                || !TikTokBusinessSdk.isSystemActivated()
                || TextUtils.isEmpty(TikTokBusinessSdk.getAppId())) {
            return;
        }
        try {
            final String mt = JSON.getString(props, "monitor_type", "");
            if ("enhanced_data_postback".equals(mt)) {
                TTEDPEventTrack.trackUnityEvent(event, props);
                return;
            }
        } catch (Throwable ignore) {
        }

        final JSONObject finalProps = props != null ? props : JSON.build();
        if (DebugModeHelper.enableScreenshot()) {
            uiThreadHandler.post(
                    TTTrackingConsent.wrap(
                            consentGeneration,
                            new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (edp) {
                                            JSON.putObject(finalProps, "track_source", "edp");
                                        }
                                        TTAppEvent ttAppEvent =
                                                new TTAppEvent(
                                                        type,
                                                        event,
                                                        finalProps.toString(),
                                                        eventId,
                                                        TikTokBusinessSdk.getTTAppIds());
                                        ttAppEvent.setScreenShot();
                                        addToTask(ttAppEvent, null, null, null, null, edp);
                                    } catch (Throwable ignore) {
                                    }
                                }
                            }));
        } else {
            addToTask(null, type, event, finalProps, eventId, edp);
        }
    }

    private boolean addToTask(
            TTAppEvent appEvent,
            TTAppEvent.TTAppEventType type,
            String event,
            @Nullable JSONObject props,
            String eventId,
            boolean edp) {
        Runnable task =
                () -> {
                    try {
                        TTAppEvent ttAppEvent = appEvent;
                        if (ttAppEvent == null) {
                            if (edp) {
                                JSON.putObject(props, "track_source", "edp");
                            }
                            ttAppEvent =
                                    new TTAppEvent(
                                            type,
                                            event,
                                            props == null ? "" : props.toString(),
                                            eventId,
                                            TikTokBusinessSdk.getTTAppIds());
                        }
                        if (edp) {
                            TTEdpAppEventsQueue.addEvent(ttAppEvent);
                        } else {
                            TTAppEventsQueue.addEvent(ttAppEvent);
                        }

                        if (TTAppEventsQueue.size() + TTEdpAppEventsQueue.size() > THRESHOLD) {
                            flush(FlushReason.THRESHOLD);
                        }

                    } catch (Throwable ignore) {
                    }
                };
        return tryAddToQ(task);
    }

    public void forceFlush() {
        flushWithReason(FlushReason.FORCE_FLUSH);
    }

    public void flushWithReason(FlushReason reason) {
        logger.debug(reason.name() + " triggered flush");
        addToQ(() -> flush(reason));
    }

    // only when this method is called will the whole sdk be activated
    private void activateSdk() {
        autoEventsManager.trackOnAppOpenEvents();
        startScheduler();
        flush(FlushReason.START_UP);
        trackFirstAppLaunch();
    }

    void flush(FlushReason reason) {
        long initTimeMS = System.currentTimeMillis();
        TTUtil.checkThread(TAG);

        // if global config is not fetched, we can track events and put in into memory
        // but they should not be sent to the network
        if (!TikTokBusinessSdk.isGlobalConfigFetched()) {
            logger.info(SKIP_FLUSHING_BECAUSE_GLOBAL_CONFIG_IS_NOT_FETCHED);
            return;
        }
        // global switch is turned off, dump all events
        if (!TikTokBusinessSdk.isSystemActivated()) {
            logger.info(SKIP_FLUSHING_BECAUSE_GLOBAL_SWITCH_IS_TURNED_OFF);
            return;
        }

        int flushSize = 0;

        try {
            if (TikTokBusinessSdk.getNetworkSwitch()) {
                logger.debug("Start flush, version %d reason is %s", flushId, reason.name());

                TTAppEventPersist appEventPersist = TTAppEventStorage.readFromDisk();

                appEventPersist.addEvents(TTAppEventsQueue.exportAllEvents());

                flushSize = appEventPersist.getAppEvents().size() + TTEdpAppEventsQueue.size();

                List<TTAppEvent> failedEvents =
                        TTRequest.reportAppEvent(
                                TTRequestBuilder.getBasePayloadWithTs(),
                                appEventPersist.getAppEvents(),
                                false);
                TTRequest.reportAppEvent(
                        TTRequestBuilder.getBasePayloadWithTs(),
                        TTEdpAppEventsQueue.exportAllEvents(),
                        true);

                if (failedEvents != null
                        && !failedEvents.isEmpty()) { // flush failed, persist events
                    logger.debug(
                            "Failed to send %d events, will save to disk", failedEvents.size());
                    TTAppEventStorage.persist(failedEvents);
                }
                logger.debug("END flush, version %d reason is %s", flushId, reason.name());

                flushId++;
            } else {
                logger.info(NETWORK_IS_TURNED_OFF);
                TTAppEventStorage.persist(null);
            }
        } catch (Throwable e) {
            try {
                TTEdpAppEventsQueue.clearAll();
                TTCrashHandler.handleCrash(TAG, e, TTSDK_EXCEPTION_SDK_CATCH);
            } catch (Throwable ignore) {
            }
        }

        if (flushSize != 0) {
            try {
                long endTimeMS = System.currentTimeMillis();
                JSONObject meta = TTUtil.getMetaWithTS(initTimeMS);
                JSON.putLong(meta, "latency", endTimeMS - initTimeMS);
                JSON.putObject(meta, "type", reason.name());
                JSON.putInt(meta, "interval", TIME_BUFFER);
                JSON.putInt(meta, "size", flushSize);
                monitorMetric("flush", meta, null);
            } catch (Throwable ignored) {
            }
        }

        addToQ(TTCrashHandler::initCrashReporter);
    }

    public void destroy() {
        destroyed = true;
        closeHeart();
        uiThreadHandler.removeCallbacksAndMessages(null);
        TikTokBusinessSdk.getApplicationContext()
                .unregisterActivityLifecycleCallbacks(mLifecycleListener);
        TTLifecycleListener.clear();
        if (lifecycleObserver != null) {
            TTActivityLifecycleCallbacksListener observer = lifecycleObserver;
            uiThreadHandler.post(() -> lifecycle.removeObserver(observer));
            lifecycleObserver = null;
        }
        TTAppEventsQueue.clearAll();
        TTEdpAppEventsQueue.clearAll();
        stopScheduler();
        eventLoop.getQueue().clear();
    }

    boolean isActive() {
        return !destroyed && TTTrackingConsent.isCurrent(consentGeneration);
    }

    /** Purges SDK-owned tracking state while preserving install-once bookkeeping. */
    public static void clearTrackingData(android.content.Context context) {
        IllegalStateException failure =
                new IllegalStateException(
                        "Unable to erase TikTok tracking data; keep tracking disabled and retry"
                                + " cleanup");
        attemptCleanup(TTAppEventsQueue::clearAll, failure);
        attemptCleanup(TTEdpAppEventsQueue::clearAll, failure);
        attemptCleanup(TTUserInfo::clear, failure);
        attemptCleanup(() -> TTIdentifierFactory.clear(context), failure);
        attemptCleanup(SystemInfoUtil::clearTrackingData, failure);
        attemptCleanup(LastSessionUtil::clear, failure);
        attemptCleanup(TTRequest::clearTrackingData, failure);
        attemptCleanup(TTRequestBuilder::clearTrackingData, failure);
        attemptCleanup(() -> TTCrashHandler.clear(context), failure);
        attemptCleanup(DebugModeHelper::clear, failure);
        attemptCleanup(TTEDPEventTrack::clear, failure);
        attemptCleanup(EDPConfig::clear, failure);
        attemptCleanup(com.tiktok.appevents.edp.proxy.TouchProxyHelper::clear, failure);
        attemptCleanup(
                () -> {
                    if (com.tiktok.appevents.edp.TTHierarchyHelper.mHandler != null) {
                        com.tiktok.appevents.edp.TTHierarchyHelper.mHandler
                                .removeCallbacksAndMessages(null);
                    }
                },
                failure);
        attemptCleanup(() -> TTInAppPurchaseWrapper.clearTrackingData(context), failure);
        if (context != null) {
            attemptCleanup(() -> eraseFile(context, "events_cache"), failure);
            attemptCleanup(() -> eraseFile(context, "tt_crash_log"), failure);
            attemptCleanup(
                    () -> {
                        android.content.SharedPreferences preferences =
                                context.getSharedPreferences(
                                        TTConst.TTSDK_KEY_VALUE_STORE,
                                        android.content.Context.MODE_PRIVATE);
                        android.content.SharedPreferences.Editor editor = preferences.edit();
                        for (String key : preferences.getAll().keySet()) {
                            if (!key.equals(TTConst.TTSDK_APP_FIRST_INSTALL)
                                    && !key.equals(TTConst.TTSDK_APP_2DR_TIME)) {
                                editor.remove(key);
                            }
                        }
                        if (!editor.commit())
                            throw new IllegalStateException(
                                    "Unable to erase TikTok identity preferences");
                    },
                    failure);
        }
        if (failure.getSuppressed().length != 0) throw failure;
    }

    private static void eraseFile(android.content.Context context, String name) {
        if (context.getFileStreamPath(name).exists() && !context.deleteFile(name)) {
            throw new IllegalStateException("Unable to erase TikTok tracking file " + name);
        }
    }

    private static void attemptCleanup(Runnable cleanup, IllegalStateException failure) {
        try {
            cleanup.run();
        } catch (RuntimeException error) {
            failure.addSuppressed(error);
        }
    }

    /** flush reasons */
    public enum FlushReason {
        THRESHOLD, // when reaching the threshold of the event queue
        TIMER, // triggered every 15 seconds
        START_UP, // when app is started, flush all the accumulated events
        FORCE_FLUSH, // when developer calls flush from app
        IDENTIFY, // when calling identify
        LOGOUT, // when logging out
    }

    public void addToQ(Runnable task) {
        tryAddToQ(task);
    }

    private boolean tryAddToQ(Runnable task) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!isActive()) return false;
            Runnable guarded = TTTrackingConsent.wrap(consentGeneration, task);
            // http://www.javabyexamples.com/handling-exceptions-from-executorservice-tasks
            try {
                eventLoop.execute(guarded);
                return true;
            } catch (Throwable e) {
                logger.error(e, "SDK event queue rejected work");
                return false;
            }
        }
    }

    private void onExecuteFailed(Runnable runnable, Throwable e) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runnable.run();
        } else {
            logger.error(e, "Runnable execute error");
        }
    }

    // Do not remove, for the ease of local test
    private void addToLater(Runnable task, int seconds) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (!isActive()) return;
            Runnable guarded = TTTrackingConsent.wrap(consentGeneration, task);
            // http://www.javabyexamples.com/handling-exceptions-from-executorservice-tasks
            try {
                eventLoop.schedule(guarded, seconds, TimeUnit.SECONDS);
            } catch (Throwable e) {
                onExecuteFailed(guarded, e);
            }
        }
    }

    public void clearAll() {
        addToQ(this::clearAllImmediately);
    }

    private void clearAllImmediately() {
        TTAppEventsQueue.clearAll();
        TTEdpAppEventsQueue.clearAll();
        TTAppEventStorage.clearAll();
    }

    /**
     * set remote switch and api available version if the remote config is not fetched, the events
     * can only be saved in memory if the config is fetched and config.globalSwitch is true, events
     * can be saved in memory or on the disk. if the config is fetched and config.globalSwitch is
     * false, the events can neither be saved in memory nor on the disk any events in the memory
     * will be gone when the app is closed.
     */
    public void fetchGlobalConfig(int delaySeconds) {
        addToLater(
                () -> {
                    boolean enableSDK = false;
                    try {
                        logger.info("Fetching global config....");

                        JSONObject requestResult = TTRequest.getBusinessSDKConfig();
                        if (!isActive()) return;

                        if (requestResult == null) {
                            logger.info("config is null, api returns error");

                            // retry immediately and only once
                            requestResult = TTRequest.getBusinessSDKConfig();
                            if (!isActive()) return;
                            if (requestResult == null) {
                                logger.info("config is null, api returns error2");
                                return;
                            }
                        }

                        synchronized (TTTrackingConsent.LOCK) {
                            if (!isActive()) return;
                            JSONObject businessSdkConfig =
                                    JSON.getJsonObject(requestResult, "business_sdk_config");
                            enableSDK = JSON.getBoolean(businessSdkConfig, "enable_sdk", false);
                            String availableVersion =
                                    JSON.getString(businessSdkConfig, "available_version");
                            String trackEventDomain = JSON.getString(businessSdkConfig, "domain");

                            NetworkTimeout.updateConfig(businessSdkConfig);

                            TikTokBusinessSdk.setSdkGlobalSwitch(enableSDK);
                            logger.debug("enable_sdk=" + enableSDK);
                            // if sdk is shutdown, stop all the timers
                            if (!enableSDK) {
                                logger.info(
                                        "Clear all events and stop timers because global switch is"
                                                + " not turned on");
                                clearAllImmediately();
                            }

                            TikTokBusinessSdk.setApiAvailableVersion(availableVersion);
                            TikTokBusinessSdk.setApiTrackDomain(trackEventDomain);
                            logger.debug("available_version=" + availableVersion);
                            TikTokBusinessSdk.setGlobalConfigFetched();
                            autoTrackRetentionEnable =
                                    JSON.getBoolean(
                                            businessSdkConfig, "auto_track_Retention_enable");
                            TTInAppPurchaseWrapper.updateConfig(businessSdkConfig);
                            TTUnityBridge.setConfigCallback(requestResult);
                            EDPConfig.optConfig(
                                    JSON.getJsonObject(businessSdkConfig, EDP_NATIVE_SDK_CONFIG));

                            // when edp switch is closed, clear all edp events
                            clearEDPWhenClosed();
                        }
                    } catch (Throwable e) {
                        logger.error(e, "Errors occurred during initGlobalConfig");
                    } finally {
                        boolean[] activate = {false};
                        TTTrackingConsent.runIfGranted(
                                () -> {
                                    if (isActive()
                                            && TikTokBusinessSdk.isSystemActivated()
                                            && !TikTokBusinessSdk.isActivatedLogicRun) {
                                        TikTokBusinessSdk.isActivatedLogicRun = true;
                                        activate[0] = true;
                                    }
                                });
                        if (activate[0]) activateSdk();

                        if (isActive() && enableSDK && !DebugModeHelper.isSuccess()) {
                            addToQ(DebugModeHelper::tryRequestConfig);
                        }
                    }
                },
                delaySeconds);
    }

    private void clearEDPWhenClosed() {
        addToQ(
                () -> {
                    try {
                        if (!EDPConfig.enable_sdk) {
                            TTEdpAppEventsQueue.clearAll();
                        }
                    } catch (Throwable ignore) {
                    }
                });
    }

    public void monitorMetric(
            @NonNull String name, @Nullable JSONObject meta, @Nullable JSONObject extra) {
        if (!metricsEnabled) return;
        addToQ(
                () -> {
                    if (!metricsEnabled) return;
                    JSONObject stat;
                    try {
                        stat = TTRequestBuilder.getHealthMonitorBase();
                    } catch (Throwable ignored) {
                        stat = JSON.build();
                    }

                    JSONObject monitor = JSON.build();
                    JSON.putObject(monitor, "type", "metric");
                    JSON.putObject(monitor, "name", name);
                    if (meta != null) {
                        JSON.putObject(monitor, "meta", meta);
                    }
                    if (extra != null) {
                        JSON.putObject(monitor, "extra", extra);
                    }

                    JSON.putObject(stat, "monitor", monitor);

                    TTCrashHandler.retryLater(stat);
                });
    }

    public void fetchDeferredDeeplinkWithCompletion(
            TikTokBusinessSdk.FetchDeferredDeeplinkCompletion callback) {
        final DeeplinkCallbackWrapper wrapper = new DeeplinkCallbackWrapper(callback);
        wrapper.markInit();

        addToQ(
                () -> {
                    wrapper.markThread();

                    String ddlData = "";
                    ErrorData errorData = null;

                    try {
                        HttpRequestUtil.HttpResponse response =
                                TTRequest.fetchDeferredDeeplinkWithCompletion();
                        ddlData = JSON.getString(JSON.getJsonObject(response.body, "data"), "ddl");
                        wrapper.markRequest();

                        if (!response.isOK() || TextUtils.isEmpty(ddlData)) {
                            ddlData = "";
                            errorData = new ErrorData(response.getErrCode(), response.getErrMsg());
                        }
                    } catch (Throwable e) {
                        ddlData = "";
                        errorData = new ErrorData(TT_DDL_CODE_HTTP_ERROR, e.getMessage());
                    }

                    wrapper.markEnd();
                    if (isActive()) wrapper.completion(ddlData, errorData);
                });
    }

    void persistMonitor() {
        addToQ(TTCrashHandler::persistToFile);
    }
}
