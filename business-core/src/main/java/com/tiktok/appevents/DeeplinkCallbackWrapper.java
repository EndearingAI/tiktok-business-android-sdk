package com.tiktok.appevents;

import android.os.SystemClock;
import android.text.TextUtils;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.JSON;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.json.JSONObject;

public class DeeplinkCallbackWrapper implements TikTokBusinessSdk.FetchDeferredDeeplinkCompletion {
    private final TikTokBusinessSdk.FetchDeferredDeeplinkCompletion callback;
    private final long consentGeneration = TTTrackingConsent.capture();

    private long initTime = 0;
    private long threadTime = 0;
    private long requestTime = 0;
    private long endTime = 0;

    public DeeplinkCallbackWrapper(TikTokBusinessSdk.FetchDeferredDeeplinkCompletion callback) {
        this.callback = callback;
    }

    @Override
    public void completion(String deepLinkUrl, ErrorData errorData) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isCurrent(consentGeneration)) return;
            sendResultLog(deepLinkUrl, errorData);
            if (callback != null) callback.completion(deepLinkUrl, errorData);
        }
    }

    private void sendResultLog(String deepLinkUrl, ErrorData errorData) {
        try {
            JSONObject meta = TTUtil.getMetaWithTS(null);
            JSON.putLong(meta, "duration", endTime - initTime);
            JSON.putLong(meta, "thread_duration", threadTime - initTime);
            JSON.putLong(meta, "req_duration", requestTime - threadTime);

            boolean failed = TextUtils.isEmpty(deepLinkUrl) || errorData != null;
            JSON.putInt(meta, "result", failed ? 1 : 0);
            if (failed) {
                int code = -1;
                String msg = "unknown";
                if (errorData != null) {
                    code = errorData.getCode();
                    msg = errorData.getMsg();
                }

                JSON.putInt(meta, "err_code", code);
                JSON.putObject(meta, "err_msg", msg);
            }

            TikTokBusinessSdk.getAppEventLogger().monitorMetric("dplink_req", meta, null);
        } catch (Throwable ignore) {
        }
    }

    public void markInit() {
        initTime = SystemClock.elapsedRealtime();
    }

    public void markThread() {
        threadTime = SystemClock.elapsedRealtime();
    }

    public void markRequest() {
        requestTime = SystemClock.elapsedRealtime();
    }

    public void markEnd() {
        endTime = SystemClock.elapsedRealtime();
    }
}
