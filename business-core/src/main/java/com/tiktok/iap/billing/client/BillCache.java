package com.tiktok.iap.billing.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.tiktok.TikTokBusinessSdk;
import com.tiktok.util.TTTrackingConsent;

public final class BillCache {
    private static final long DEF_LAST = 1735660800000L;
    private static final String F_NAME = "com.tiktok.sdk.pay";
    private static final String K_INAPP_LAST = "inapp_last";
    private static final String K_SUBS_LAST = "subs_last";

    private static volatile BillCache sInstance;
    private SharedPreferences mSP = null;

    public static BillCache getInstance() {
        if (sInstance == null) {
            synchronized (BillCache.class) {
                if (sInstance == null) {
                    sInstance = new BillCache();
                }
            }
        }
        return sInstance;
    }

    private BillCache() {}

    public long getINAPPLast() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return DEF_LAST;

            final SharedPreferences sp = getSP();
            if (sp != null) {
                return sp.getLong(K_INAPP_LAST, DEF_LAST);
            }
            return DEF_LAST;
        }
    }

    public long getSUBSLast() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return DEF_LAST;

            final SharedPreferences sp = getSP();
            if (sp != null) {
                return sp.getLong(K_SUBS_LAST, DEF_LAST);
            }
            return DEF_LAST;
        }
    }

    public void saveINAPPLast(long last) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                if (last > 0) {
                    final SharedPreferences sp = getSP();
                    if (sp != null) {
                        sp.edit().putLong(K_INAPP_LAST, last).apply();
                    }
                }
            } catch (Throwable ignore) {
            }
        }
    }

    public void saveSUBSLast(long last) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            try {
                if (last > 0) {
                    final SharedPreferences sp = getSP();
                    if (sp != null) {
                        sp.edit().putLong(K_SUBS_LAST, last).apply();
                    }
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private SharedPreferences getSP() {
        try {
            if (mSP == null) {
                final Context ctx = TikTokBusinessSdk.getApplicationContext();
                if (ctx != null) {
                    mSP = ctx.getSharedPreferences(F_NAME, Context.MODE_PRIVATE);
                }
            }
        } catch (Throwable ignore) {
        }
        return mSP;
    }
}
