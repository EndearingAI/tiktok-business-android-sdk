/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.util;

import static com.tiktok.util.TTConst.TTSDK_APP_ANONYMOUS_ID;
import static com.tiktok.util.TTConst.TTSDK_APP_SENSIG_LIST;
import static com.tiktok.util.TTConst.TTSDK_APP_SENSIG_VERSION;
import static com.tiktok.util.TTConst.TTSDK_EXCEPTION_SDK_CATCH;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.tiktok.appevents.TTCrashHandler;
import com.tiktok.appevents.edp.Sensig;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.UUID;

public class TTUtil {

    /**
     * All internal operations should be pushed to the internal {@link
     * com.tiktok.appevents.TTAppEventLogger#eventLoop} and run in a non-main thread
     *
     * @param tag tag name
     */
    public static void checkThread(String tag) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            TTCrashHandler.handleCrash(
                    tag,
                    new IllegalStateException(
                            "Current method should be called in a non-main thread"),
                    TTSDK_EXCEPTION_SDK_CATCH);
        }
    }

    public static String getOrGenAnoId(Context context, boolean forceGenerate) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return null;
            try {
                TTKeyValueStore store = new TTKeyValueStore(context);
                String anoId = store.get(TTSDK_APP_ANONYMOUS_ID);
                if (TextUtils.isEmpty(anoId) || forceGenerate) {
                    anoId = UUID.randomUUID().toString();
                    store.set(TTSDK_APP_ANONYMOUS_ID, anoId);
                }
                return anoId;
            } catch (Throwable ignore) {
            }
            return "";
        }
    }

    public static Sensig getSensigInfo(Context context) {
        try {
            TTKeyValueStore store = new TTKeyValueStore(context);
            int version = store.getInt(TTSDK_APP_SENSIG_VERSION);
            String sensigList = store.get(TTSDK_APP_SENSIG_LIST);
            return new Sensig(version, sensigList);
        } catch (Throwable ignore) {
        }

        return null;
    }

    public static void setSensigInfo(Context context, Sensig sensig) {
        if (sensig == null) {
            return;
        }
        try {
            TTKeyValueStore store = new TTKeyValueStore(context);
            store.set(TTSDK_APP_SENSIG_VERSION, sensig.version);
            store.set(TTSDK_APP_SENSIG_LIST, sensig.regexList);
        } catch (Throwable ignore) {
        }
    }

    public static JSONObject getMetaWithTS(@Nullable Long ts) {
        if (ts == null) {
            ts = System.currentTimeMillis();
        }

        final JSONObject json = JSON.build();
        JSON.putLong(json, "ts", ts);
        return json;
    }

    public static JSONObject getMonitorException(
            @Nullable Throwable ex, @Nullable Long ts, int type) {
        JSONObject monitor = JSON.build();
        try {
            JSON.putObject(monitor, "type", "exception");
            JSON.putObject(monitor, "name", "exception");
            JSON.putObject(monitor, "meta", getMetaException(ex, ts, type));
            JSON.putObject(monitor, "extra", null);
        } catch (Throwable ignored) {
        }
        return monitor;
    }

    public static JSONObject getMetaException(@Nullable Throwable ex, @Nullable Long ts, int type) {
        JSONObject meta = getMetaWithTS(ts);
        try {
            JSON.putObject(meta, "ex_sdk_version", SystemInfoUtil.getSDKVersion());
            if (ex != null) {
                Throwable rootCause = ex;
                while (rootCause.getCause() != null && rootCause.getCause() != rootCause)
                    rootCause = rootCause.getCause();
                JSON.putObject(meta, "ex_class", rootCause.getStackTrace()[0].getClassName());
                JSON.putObject(meta, "ex_method", rootCause.getStackTrace()[0].getMethodName());
                String argMsg =
                        rootCause.getStackTrace()[0].getFileName()
                                + " "
                                + rootCause.getStackTrace()[0].getLineNumber();
                JSON.putObject(meta, "ex_args", argMsg);
                JSON.putObject(meta, "ex_msg", rootCause.getMessage());
                JSON.putInt(meta, "ex_type", type);
                final int stackLimit = 15;
                String[] st = new String[stackLimit];
                for (int i = 0; i < stackLimit; i++) {
                    if (rootCause.getStackTrace()[i] != null)
                        st[i] = rootCause.getStackTrace()[i].toString();
                }
                JSON.putObject(meta, "ex_stack", Arrays.toString(st));
                JSON.putBoolean(meta, "success", false);
            } else {
                JSON.putBoolean(meta, "success", true);
            }
        } catch (Throwable ignored) {
        }
        return meta;
    }
}
