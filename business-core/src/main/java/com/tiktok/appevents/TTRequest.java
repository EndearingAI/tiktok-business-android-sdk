/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import static com.tiktok.util.TTConst.TTSDK_EXCEPTION_SDK_CATCH;

import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import com.tiktok.BuildConfig;
import com.tiktok.TikTokBusinessSdk;
import com.tiktok.iap.TTInAppPurchaseWrapper;
import com.tiktok.util.HttpRequestUtil;
import com.tiktok.util.JSON;
import com.tiktok.util.LastSessionUtil;
import com.tiktok.util.SystemInfoUtil;
import com.tiktok.util.TTConst;
import com.tiktok.util.TTLogger;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;
import com.tiktok.util.TimeUtil;
import com.tiktok.util.UrlConst;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class TTRequest {
    private static final String TAG = "TTRequest";
    private static final TTLogger logger = new TTLogger(TAG, TikTokBusinessSdk.getLogLevel());

    private static final int MAX_EVENT_SIZE = 50;

    // stats for the current batch
    private static int toBeSentRequests = 0;
    private static int failedRequests = 0;
    private static int successfulRequests = 0;

    // stats for the whole lifecycle
    private static final TreeSet<Long> allRequestIds = new TreeSet<>();
    private static final List<TTAppEvent> successfullySentRequests = new CopyOnWriteArrayList<>();

    private static final Map<String, String> headParamMap = new ConcurrentHashMap<>();
    private static final Map<String, String> getHeadParamMap = new ConcurrentHashMap<>();

    static {
        // these fields wont change, so cache it locally to enhance performance
        headParamMap.put("Content-Type", "application/json");
        headParamMap.put("Connection", "Keep-Alive");
        String ua =
                String.format(
                        "tiktok-business-android-sdk/%s/%s",
                        BuildConfig.VERSION_NAME, TikTokBusinessSdk.getApiAvailableVersion());
        headParamMap.put("User-Agent", ua);

        // no content-type application/json for get requests
        getHeadParamMap.put("Connection", "Keep-Alive");
        getHeadParamMap.put("User-Agent", ua);
        getHeadParamMap.put("Content-Type", "application/json");
    }

    public static JSONObject getBusinessSDKConfig() {
        if (!TTTrackingConsent.isGranted()) return null;
        if (TextUtils.isEmpty(TikTokBusinessSdk.getTTAppId())
                || TextUtils.isEmpty(TikTokBusinessSdk.getAppId())) {
            JSONObject result = JSON.build();
            JSON.putBoolean(result, "enable_sdk", false);
            logger.debug("config request return by appid or ttAppId");
            return result;
        }

        JSONObject paramsJson = buildConfigParams();
        String url = UrlConst.getConfigUrl();

        // handle cache query
        try {
            final String ttAppId = TikTokBusinessSdk.getTTAppId();
            url =
                    Uri.parse(url)
                            .buildUpon()
                            .appendQueryParameter("tiktok_app_id", ttAppId == null ? "" : ttAppId)
                            .appendQueryParameter("sdk_version", SystemInfoUtil.getSDKVersion())
                            .appendQueryParameter("platform", "Android")
                            .appendQueryParameter("model", Build.MODEL)
                            .appendQueryParameter("app_version", SystemInfoUtil.getAppVersionName())
                            .appendQueryParameter("os_version", SystemInfoUtil.getAndroidVersion())
                            .appendQueryParameter("locale", SystemInfoUtil.getBcp47Language())
                            .appendQueryParameter("namespace", SystemInfoUtil.getPackageName())
                            .build()
                            .toString();
        } catch (Throwable ignore) {
        }

        HttpRequestUtil.HttpResponse result =
                HttpRequestUtil.doPost(url, getHeadParamMap, paramsJson.toString(), false);
        if (result != null) {
            logger.debug(result.url);
            logger.debug("code=" + String.valueOf(result.code));
            logger.debug("rsp=" + String.valueOf(result.body));
        }
        if (result != null && result.isOK()) {
            return JSON.getJsonObject(result.body, "data");
        }

        // might be api returning something wrong
        return null;
    }

    public static JSONObject getDebugModeConfig() {
        if (!TTTrackingConsent.isGranted()) return null;
        try {
            JSONObject paramsJson = buildConfigParams();
            String url = UrlConst.getDebugModeUrl();
            HttpRequestUtil.HttpResponse response =
                    HttpRequestUtil.doPost(url, getHeadParamMap, paramsJson.toString(), false);
            if (response != null && response.isOK()) {
                return JSON.getJsonObject(response.body, "data");
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static JSONObject buildConfigParams() {
        if (!TTTrackingConsent.isGranted()) return JSON.build();
        JSONObject jsonObject = JSON.build();
        try {
            JSONObject app = getAppInfo();
            JSON.putObject(jsonObject, "app", app);

            JSONObject device = JSON.build();
            JSON.putObject(device, "platform", "Android");
            JSON.putObject(device, "version", SystemInfoUtil.getAndroidVersion());
            if (TikTokBusinessSdk.isGaidCollectionEnabled()) {
                try {
                    TTIdentifierFactory.AdIdInfo adIdInfo =
                            TTIdentifierFactory.getGoogleAdIdInfo(
                                    TikTokBusinessSdk.getApplicationContext());
                    JSON.putObject(device, "gaid", adIdInfo.getAdId());
                } catch (Throwable ignore) {
                }
            }

            JSON.putObject(jsonObject, "device", device);
            if (TikTokBusinessSdk.isInSdkDebugMode()) {
                JSON.putObject(jsonObject, "debug", "true");
            }

            JSONObject library = JSON.build();
            JSON.putObject(library, "name", "tiktok/" + SystemInfoUtil.getLibraryName());
            JSON.putObject(library, "version", SystemInfoUtil.getSDKVersion());
            JSON.putBoolean(library, "smart_sdk_client_flag", TikTokBusinessSdk.isEdpEnable());
            JSON.putInt(library, "auto_iap_track_config", TTInAppPurchaseWrapper.devAutoTrack);

            JSON.putObject(jsonObject, "library", library);
        } catch (Throwable ignore) {
        }

        return jsonObject;
    }

    // for debugging purpose
    public static synchronized List<TTAppEvent> getSuccessfullySentRequests() {
        return successfullySentRequests;
    }

    /**
     * Try to send events to api with MTU set to 1000 app events, If there are more than 1000
     * events, they will be split into several chunks and then be sent separately, Any failed events
     * will be accumulated and finally returned.
     *
     * @param appEventList event list
     * @return the accumulation of all failed events
     */
    public static synchronized List<TTAppEvent> reportAppEvent(
            JSONObject basePayload, List<TTAppEvent> appEventList, boolean isEdp) {
        TTUtil.checkThread(TAG);
        if (!TTTrackingConsent.isGranted()) return new ArrayList<>();
        if (appEventList == null || appEventList.isEmpty()) {
            return new ArrayList<>();
        }

        toBeSentRequests = appEventList.size();
        for (TTAppEvent event : appEventList) {
            allRequestIds.add(event.getUniqueId());
        }
        failedRequests = 0;
        successfulRequests = 0;
        notifyChange();
        //  dynamic req domain and version
        String url = UrlConst.getBatchUrl();

        List<TTAppEvent> failedEventsToBeSaved = new ArrayList<>();
        int failedEventsToBeDiscardedSize = 0;

        List<List<TTAppEvent>> chunks = averageAssign(appEventList, MAX_EVENT_SIZE);

        for (List<TTAppEvent> currentBatch : chunks) {
            if (!TTTrackingConsent.isGranted()) return new ArrayList<>();
            JSONArray batch = JSON.buildArr();
            for (TTAppEvent event : currentBatch) {
                JSONObject propertiesJson = transferJson(event);
                if (propertiesJson == null) {
                    continue;
                }
                JSON.putArr(batch, propertiesJson);
            }

            if (batch.length() == 0) {
                continue;
            }

            // remove
            JSON.putObject(basePayload, "batch", null);

            try {
                JSON.putObject(basePayload, "batch", batch);
            } catch (Throwable e) {
                if (!isEdp) {
                    failedEventsToBeSaved.addAll(currentBatch);
                }
                TTCrashHandler.handleCrash(TAG, e, TTSDK_EXCEPTION_SDK_CATCH);
                continue;
            }
            HttpRequestUtil.HttpResponse result =
                    HttpRequestUtil.doPost(url, headParamMap, basePayload.toString());
            if (!TTTrackingConsent.isGranted()) return new ArrayList<>();
            if (isEdp) {
                return null;
            }

            if (result == null || !result.isOK()) {
                failedEventsToBeSaved.addAll(currentBatch);
                failedRequests += currentBatch.size();
            } else {
                try {
                    logger.debug("url=" + String.valueOf(url));
                    logger.debug("rsp=" + String.valueOf(result.body));

                    int code = result.code;
                    if (TikTokBusinessSdk.isInSdkDebugMode()
                            || code == TTConst.ApiErrorCodes.API_ERROR.code
                            || code == TTConst.ApiErrorCodes.PARTIAL_SUCCESS.code) {
                        failedEventsToBeDiscardedSize += currentBatch.size();
                        failedRequests += currentBatch.size();
                    } else if (code != 0) {
                        failedEventsToBeSaved.addAll(currentBatch);
                        failedRequests += currentBatch.size();
                    } else {
                        successfulRequests += currentBatch.size();
                        TTTrackingConsent.runIfGranted(
                                () -> successfullySentRequests.addAll(currentBatch));
                    }
                } catch (Throwable e) {
                    failedRequests += currentBatch.size();
                    failedEventsToBeSaved.addAll(currentBatch);
                    TTCrashHandler.handleCrash(TAG, e, TTSDK_EXCEPTION_SDK_CATCH);
                }
            }

            notifyChange();
        }
        logger.debug("Flushed %d events successfully", successfulRequests);

        // might be due to network disconnection
        if (!failedEventsToBeSaved.isEmpty()) {
            logger.debug("Failed to flush %d events, will save them to disk");
        }
        // api returns some unrecoverable error
        int discardedEventCount = failedEventsToBeDiscardedSize;
        if (discardedEventCount != 0) {
            logger.debug("Failed to flush " + discardedEventCount + " events, will discard them");
            TTAppEventLogger.totalDumped += discardedEventCount;
            if (TikTokBusinessSdk.diskListener != null) {
                TikTokBusinessSdk.diskListener.onDumped(TTAppEventLogger.totalDumped);
            }
        }
        logger.debug("Failed to flush %d events in total", failedRequests);

        toBeSentRequests = 0;
        failedRequests = 0;
        successfulRequests = 0;
        notifyChange();
        return failedEventsToBeSaved;
    }

    private static void notifyChange() {
        if (!TTTrackingConsent.isGranted()) return;
        if (TikTokBusinessSdk.networkListener != null) {
            TikTokBusinessSdk.networkListener.onNetworkChange(
                    toBeSentRequests,
                    successfulRequests,
                    failedRequests,
                    allRequestIds.size() + TTAppEventsQueue.size(),
                    successfullySentRequests.size());
        }
    }

    private static JSONObject transferJson(TTAppEvent event) {
        if (event == null) {
            return null;
        }
        try {
            JSONObject propertiesJson = JSON.build();

            JSON.putObject(propertiesJson, "event_id", event.getTransportEventId());
            JSON.putObject(
                    propertiesJson,
                    "tt_event_id",
                    TextUtils.isEmpty(event.getEventId()) ? "" : event.getEventId());
            JSON.putObject(propertiesJson, "type", event.getType());
            if (event.getEventName() != null) {
                JSON.putObject(propertiesJson, "event", event.getEventName());
            }
            JSON.putObject(
                    propertiesJson,
                    "timestamp",
                    TimeUtil.getISO8601Timestamp(event.getTimeStamp()));
            if (TikTokBusinessSdk.isInSdkLDUMode()) {
                JSON.putBoolean(propertiesJson, "limited_data_use", true);
            }

            final JSONObject properties = JSON.build(event.getPropertiesJson());
            if (properties != null && properties.length() > 0) {
                JSON.putObject(propertiesJson, "properties", properties);
            }

            JSON.putObject(propertiesJson, "context", TTRequestBuilder.getContextForApi(event));

            final ReferrerInfo refer = SystemInfoUtil.getInstallReferrer();
            if (refer != null) {
                JSON.putLong(
                        propertiesJson, "gp_referrer_install_ts", refer.getGpReferrerInstallTs());
                JSON.putLong(propertiesJson, "gp_referrer_click_ts", refer.getGpReferrerClickTs());
            }

            final String screenShot = event.getScreenShot();
            if (!TextUtils.isEmpty(screenShot)) {
                JSON.putObject(propertiesJson, "screenshot", screenShot);
            }

            return propertiesJson;
        } catch (Throwable e) {
            TTCrashHandler.handleCrash(TAG, e, TTSDK_EXCEPTION_SDK_CATCH);
            return null;
        }
    }

    /** split event list */
    public static <T> List<List<T>> averageAssign(List<T> sourceList, int splitNum) {
        List<List<T>> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) {
            return result;
        }

        try {
            List<T> splitList = new ArrayList<>();
            int count = sourceList.size();
            for (int i = 0; i < count; i++) {
                try {
                    splitList.add(sourceList.get(i));

                    if (splitList.size() >= splitNum || i == count - 1) {
                        result.add(splitList);
                        splitList = new ArrayList<>();
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
            result.add(sourceList);
        }

        return result;
    }

    public static HttpRequestUtil.HttpResponse reportMonitorEvent(JSONObject stat) {
        String url = UrlConst.getMonitorUrl();
        return HttpRequestUtil.doPost(url, headParamMap, stat.toString());
    }

    public static HttpRequestUtil.HttpResponse fetchDeferredDeeplinkWithCompletion() {
        JSONObject stat = TTRequestBuilder.ddlJson();
        String url = UrlConst.getDDLUrl();
        return HttpRequestUtil.doPost(url, headParamMap, stat.toString(), false);
    }

    static void clearTrackingData() {
        successfullySentRequests.clear();
        allRequestIds.clear();
        headParamMap.remove("X-TT-Signature");
        getHeadParamMap.remove("X-TT-Signature");
        toBeSentRequests = failedRequests = successfulRequests = 0;
    }

    public static JSONObject getAppInfo() {
        if (!TTTrackingConsent.isGranted()) return JSON.build();
        JSONObject app = JSON.build();
        try {
            if (TikTokBusinessSdk.bothIdsProvided()) {
                JSON.putObject(app, "id", TikTokBusinessSdk.getAppId());
            }
            JSON.putObject(app, "name", SystemInfoUtil.getAppName());
            JSON.putObject(app, "namespace", SystemInfoUtil.getPackageName());
            JSON.putObject(app, "version", SystemInfoUtil.getAppVersionName());
            JSON.putObject(app, "build", SystemInfoUtil.getAppVersionCode() + "");
            JSON.putObject(app, "tiktok_app_id", TikTokBusinessSdk.getTTAppId());
            JSON.putObject(app, "app_session_id", SystemInfoUtil.getAppSessionId());

            LastSessionUtil.inject2RequestParam(app);
        } catch (Throwable ignore) {
        }
        return app;
    }
}
