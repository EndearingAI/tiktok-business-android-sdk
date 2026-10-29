/*******************************************************************************
 * Copyright (c) 2024. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/
package com.tiktok.appevents.edp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.appevents.edp.proxy.ITouchListener;
import com.tiktok.appevents.edp.proxy.TouchProxyHelper;
import com.tiktok.util.JSON;
import com.tiktok.util.RegexUtil;
import com.tiktok.util.TTTrackingConsent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

public class TTHierarchyHelper {
    public static volatile Handler mHandler;

    public static JSONObject getViewHierarchy(WeakReference<View> rootView, int hierarchy) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return JSON.build();
            JSONObject jsonObject = JSON.build();
            try {
                if (hierarchy <= 0) {
                    return jsonObject;
                }

                View view = rootView.get();
                if (view == null) {
                    return jsonObject;
                }

                JSON.putObject(jsonObject, "class_name", view.getClass().getCanonicalName());
                if (view instanceof TextView) {
                    String text = "";
                    if (((TextView) view).getText() != null) {
                        text = ((TextView) view).getText().toString();
                    }
                    if (!TextUtils.isEmpty(text)) {
                        text =
                                RegexUtil.replaceAllToHash(
                                        EDPConfig.sensig_filtering_regex_list, text);
                    }
                    JSON.putObject(jsonObject, "text", text);
                    JSON.putDouble(jsonObject, "font_size", ((TextView) view).getTextSize());
                }
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                JSON.putInt(jsonObject, "left", location[0]);
                JSON.putInt(jsonObject, "top", location[1]);
                JSON.putInt(jsonObject, "width", view.getMeasuredWidth());
                JSON.putInt(jsonObject, "height", view.getMeasuredHeight());
                JSON.putInt(jsonObject, "scroll_x", view.getScrollX());
                JSON.putInt(jsonObject, "scroll_y", view.getScrollY());
                if (view instanceof ViewGroup) {
                    JSONArray jsonArray = JSON.buildArr();
                    ViewGroup vp = (ViewGroup) view;
                    int count = vp.getChildCount();
                    for (int i = 0; i < count; i++) {
                        JSONObject jsonItem =
                                getViewHierarchy(
                                        (new WeakReference<>(vp.getChildAt(i))), hierarchy - 1);
                        JSON.putArr(jsonArray, jsonItem);
                    }
                    JSON.putObject(jsonObject, "child_views", jsonArray);
                }
            } catch (Throwable ignore) {
            }
            return jsonObject;
        }
    }

    public static void proxyOnTouch(
            WeakReference<View> rootView, WeakReference<Activity> activity) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;
            try {
                if (!EDPConfig.enable_click_track) {
                    return;
                }
                if (rootView == null) {
                    return;
                }
                final View view = rootView.get();
                if (view == null) {
                    return;
                }
                final Activity act = activity.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                TouchProxyHelper.proxy(
                        rootView,
                        new ITouchListener() {
                            long touchDown = 0;

                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                try {
                                    if (!EDPConfig.enable_click_track) {
                                        return false;
                                    }
                                    if (act == null || act.isFinishing() || act.isDestroyed()) {
                                        return false;
                                    }
                                    if (view == null) {
                                        return false;
                                    }
                                    if (v == null) {
                                        return false;
                                    }

                                    switch (event.getAction()) {
                                        case MotionEvent.ACTION_DOWN:
                                            touchDown = System.currentTimeMillis();
                                            break;
                                        case MotionEvent.ACTION_UP:
                                            if (act == null
                                                    || act.isFinishing()
                                                    || act.isDestroyed()) {
                                                return false;
                                            }
                                            if (EDPConfig.button_black_list.contains(
                                                    v.getClass().getCanonicalName())) {
                                                return false;
                                            }
                                            if (!TTEDPEventTrack.checkUpload()
                                                    || TTEDPEventTrack.isSending) {
                                                return false;
                                            }
                                            if (System.currentTimeMillis()
                                                            - TTEDPEventTrack.LAST_CLICK_TS
                                                    <= EDPConfig.time_diff_frequency_control
                                                            * 1000) {
                                                return false;
                                            }
                                            TTEDPEventTrack.isSending = true;
                                            final String className =
                                                    v.getClass().getCanonicalName();
                                            final float rawX = event.getRawX();
                                            final float rawY = event.getRawY();
                                            final View decorView = act.getWindow().getDecorView();
                                            final int width = v.getMeasuredWidth();
                                            final int height = v.getMeasuredHeight();
                                            String tip = "";
                                            try {
                                                tip =
                                                        v instanceof TextView
                                                                ? ((TextView) (v))
                                                                        .getText()
                                                                        .toString()
                                                                : "";
                                            } catch (Throwable ignore) {
                                            }
                                            final String tipFinal = tip;

                                            TikTokBusinessSdk.getAppEventLogger()
                                                    .addToQ(
                                                            new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    try {
                                                                        if (!EDPConfig
                                                                                .enable_click_track) {
                                                                            return;
                                                                        }
                                                                        if (act == null
                                                                                || act.isFinishing()
                                                                                || act
                                                                                        .isDestroyed()) {
                                                                            return;
                                                                        }
                                                                        if (view == null
                                                                                || v == null) {
                                                                            return;
                                                                        }

                                                                        TTEDPEventTrack.trackClick(
                                                                                className,
                                                                                rawX,
                                                                                rawY,
                                                                                width,
                                                                                height,
                                                                                tipFinal,
                                                                                act.getClass()
                                                                                        .getSimpleName(),
                                                                                TTHierarchyHelper
                                                                                        .getViewHierarchy(
                                                                                                new WeakReference<>(
                                                                                                        decorView),
                                                                                                EDPConfig
                                                                                                        .page_detail_upload_deep_count),
                                                                                getViewHierarchyCount(
                                                                                        new WeakReference<>(
                                                                                                decorView)),
                                                                                System
                                                                                                .currentTimeMillis()
                                                                                        - touchDown);
                                                                    } catch (Throwable ignore) {
                                                                    }

                                                                    TTEDPEventTrack.isSending =
                                                                            false;
                                                                }
                                                            });
                                            break;
                                    }
                                } catch (Throwable ignore) {
                                }
                                return false;
                            }
                        });
            } catch (Throwable ignore) {
            }
        }
    }

    public static int getViewHierarchyCount(WeakReference<View> rootView) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return 0;

            try {
                if (rootView == null) {
                    return 0;
                }

                View view = rootView.get();
                if (view == null) {
                    return 0;
                }

                if (view instanceof ViewGroup) {
                    ViewGroup vp = (ViewGroup) view;
                    int count = vp.getChildCount();
                    int viewHierarchyCount = 1;
                    for (int i = 0; i < count; i++) {
                        viewHierarchyCount =
                                Math.max(
                                        getViewHierarchyCount(new WeakReference<>(vp.getChildAt(i)))
                                                + 1,
                                        viewHierarchyCount);
                    }
                    return viewHierarchyCount;
                } else {
                    return 1;
                }
            } catch (Throwable ignore) {
                return 0;
            }
        }
    }

    public static Handler getHandler() {
        if (mHandler == null) {
            synchronized (TTHierarchyHelper.class) {
                if (mHandler == null) {
                    mHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return mHandler;
    }

    public static int getViewHierarchyCountAndRegisterOnTouch(
            WeakReference<View> rootView, WeakReference<Activity> activity) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return 0;
            final long generation = TTTrackingConsent.capture();

            try {
                Activity act = activity.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    return 0;
                }

                View view = rootView.get();
                if (view == null) {
                    return 0;
                }

                if (EDPConfig.enable_click_track) {
                    getHandler()
                            .post(
                                    TTTrackingConsent.wrap(
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        proxyOnTouch(rootView, activity);
                                                    } catch (Throwable ignore) {
                                                    }
                                                }
                                            }));
                }

                if (EDPConfig.enable_webview_request_track && view instanceof WebView) {
                    getHandler()
                            .post(
                                    TTTrackingConsent.wrap(
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        if (EDPConfig
                                                                .enable_webview_request_track) {
                                                            WebView web = (WebView) view;
                                                            String url = web.getOriginalUrl();
                                                            if (!TextUtils.isEmpty(url)) {
                                                                TTEDPEventTrack.trackWebviewRequest(
                                                                        url);
                                                            }
                                                        }
                                                    } catch (Throwable ignore) {
                                                    }
                                                }
                                            }));
                }

                if (view instanceof ViewGroup) {
                    ViewGroup vp = (ViewGroup) view;
                    int count = vp.getChildCount();
                    int viewHierarchyCount = 1;
                    for (int i = 0; i < count; i++) {
                        viewHierarchyCount =
                                Math.max(
                                        getViewHierarchyCountAndRegisterOnTouch(
                                                        new WeakReference<>(vp.getChildAt(i)),
                                                        activity)
                                                + 1,
                                        viewHierarchyCount);
                    }
                    vp.setOnHierarchyChangeListener(
                            new ViewGroup.OnHierarchyChangeListener() {

                                @Override
                                public void onChildViewAdded(View parent, View child) {
                                    if (!TTTrackingConsent.isCurrent(generation)) return;
                                    try {
                                        getViewHierarchyCountAndRegisterOnTouch(
                                                new WeakReference<>(child), activity);
                                    } catch (Throwable ignore) {
                                    }
                                }

                                @Override
                                public void onChildViewRemoved(View parent, View child) {}
                            });
                    return viewHierarchyCount;
                } else {
                    return 1;
                }
            } catch (Throwable ignore) {
                return 0;
            }
        }
    }
}
