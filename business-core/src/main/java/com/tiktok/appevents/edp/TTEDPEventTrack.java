/*******************************************************************************
 * Copyright (c) 2024. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/
package com.tiktok.appevents.edp;

import static com.tiktok.appevents.edp.EDPConfig.report_frequency_control;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_NAME_APP_LAUNCH;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_NAME_CLICK;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_NAME_PAGE_SHOW;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_NAME_PAY_SHOW;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_NAME_WEBVIEW_REQUEST;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLASS_NAME;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_BUTTON_TEXT;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_DURATION;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_POSITON_X;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_POSITON_Y;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_SIZE_H;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CLICK_SIZE_W;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_CURRENT_PAGE_NAME;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_FROM_BACKGROUND;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_INDEX;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_PAGE_COMPONENTS;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_PAGE_DEEP_COUNT;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_PAY_CODE;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_PAY_SKU_INFO;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_REFER;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_SOURCE_URL;
import static com.tiktok.appevents.edp.TTEDPEventConstants.EDP_EVENT_PROPERTY_WEBVIEW_REQUEST_URL;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.JSON;
import com.tiktok.util.TTTrackingConsent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

public class TTEDPEventTrack {
    private static TTAppLaunchEvent ttAppLaunchEvent;
    public static volatile long LAST_CLICK_TS = 0L;
    private static volatile boolean hasSendLaunch = false;
    public static volatile boolean isSending = false;
    public static volatile boolean pageShowIsSending = false;

    public static void clear() {
        ttAppLaunchEvent = null;
        hasSendLaunch = false;
        isSending = false;
        pageShowIsSending = false;
        LAST_CLICK_TS = 0;
    }

    public static void trackAppLaunch(String refer, String sourceUrl) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;
            try {
                JSONObject property = JSON.build();
                JSON.putObject(property, EDP_EVENT_PROPERTY_REFER, refer);
                JSON.putObject(property, EDP_EVENT_PROPERTY_SOURCE_URL, sourceUrl);

                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", property);
                if (TikTokBusinessSdk.isInitialized()) {
                    TikTokBusinessSdk.getAppEventLogger()
                            .trackEdp(EDP_EVENT_NAME_APP_LAUNCH, eventProp, null);
                } else {
                    if (ttAppLaunchEvent == null && !hasSendLaunch) {
                        ttAppLaunchEvent =
                                new TTAppLaunchEvent(property, System.currentTimeMillis());
                    }
                }
            } catch (Throwable ignore) {
            }
        }
    }

    public static void trackFirstAppLaunch() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                if (ttAppLaunchEvent != null) {
                    hasSendLaunch = true;
                    JSONObject eventProp = JSON.build();
                    JSON.putObject(eventProp, "meta", ttAppLaunchEvent.getProp());
                    TikTokBusinessSdk.getAppEventLogger()
                            .trackEdp(EDP_EVENT_NAME_APP_LAUNCH, eventProp, null);
                    ttAppLaunchEvent = null;
                }
            } catch (Exception ignore) {
            }
        }
    }

    public static void trackWebviewRequest(String url) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                JSONObject property = JSON.build();
                JSON.putObject(property, EDP_EVENT_PROPERTY_WEBVIEW_REQUEST_URL, url);

                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", property);
                TikTokBusinessSdk.getAppEventLogger()
                        .trackEdp(EDP_EVENT_NAME_WEBVIEW_REQUEST, eventProp, null);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void trackPageShow(
            String pageName,
            int index,
            boolean isFromBackground,
            JSONObject components,
            int pageCount) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                JSONObject property = JSON.build();
                JSON.putObject(property, EDP_EVENT_PROPERTY_CURRENT_PAGE_NAME, pageName);
                JSON.putInt(property, EDP_EVENT_PROPERTY_INDEX, index);
                JSON.putBoolean(property, EDP_EVENT_PROPERTY_FROM_BACKGROUND, isFromBackground);
                JSON.putObject(property, EDP_EVENT_PROPERTY_PAGE_COMPONENTS, components);
                JSON.putInt(property, EDP_EVENT_PROPERTY_PAGE_DEEP_COUNT, pageCount);

                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", property);
                TikTokBusinessSdk.getAppEventLogger()
                        .trackEdp(EDP_EVENT_NAME_PAGE_SHOW, eventProp, null);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void trackClick(
            String className,
            float x,
            float y,
            int width,
            int height,
            String text,
            String pageName,
            JSONObject components,
            int pageCount,
            long duration) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                LAST_CLICK_TS = System.currentTimeMillis();
                JSONObject property = JSON.build();
                JSON.putDouble(property, EDP_EVENT_PROPERTY_CLICK_POSITON_X, x);
                JSON.putDouble(property, EDP_EVENT_PROPERTY_CLICK_POSITON_Y, y);
                JSON.putInt(property, EDP_EVENT_PROPERTY_CLICK_SIZE_W, width);
                JSON.putInt(property, EDP_EVENT_PROPERTY_CLICK_SIZE_H, height);
                JSON.putObject(property, EDP_EVENT_PROPERTY_CLICK_BUTTON_TEXT, text);
                JSON.putObject(property, EDP_EVENT_PROPERTY_CURRENT_PAGE_NAME, pageName);
                JSON.putObject(property, EDP_EVENT_PROPERTY_PAGE_COMPONENTS, components);
                JSON.putInt(property, EDP_EVENT_PROPERTY_PAGE_DEEP_COUNT, pageCount);
                JSON.putLong(property, EDP_EVENT_PROPERTY_CLICK_DURATION, duration);
                JSON.putObject(property, EDP_EVENT_PROPERTY_CLASS_NAME, className);

                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", property);
                TikTokBusinessSdk.getAppEventLogger()
                        .trackEdp(EDP_EVENT_NAME_CLICK, eventProp, null);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void trackPayShow(int code, JSONArray skuInfo) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                JSONObject property = JSON.build();
                JSON.putInt(property, EDP_EVENT_PROPERTY_PAY_CODE, code);
                JSON.putObject(property, EDP_EVENT_PROPERTY_PAY_SKU_INFO, skuInfo);

                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", property);
                TikTokBusinessSdk.getAppEventLogger()
                        .trackEdp(EDP_EVENT_NAME_PAY_SHOW, eventProp, null);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void trackUnityEvent(String name, JSONObject meta) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                JSONObject eventProp = JSON.build();
                JSON.putObject(eventProp, "meta", meta);
                JSON.putObject(eventProp, "api_platform", meta.remove("api_platform"));
                TikTokBusinessSdk.getAppEventLogger().trackEdp(name, eventProp, null);
            } catch (Throwable ignore) {
            }
        }
    }

    private static final Random sRandom = new Random();

    public static boolean checkUpload() {
        return sRandom.nextDouble() <= report_frequency_control;
    }
}
