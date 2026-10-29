/*
 * Copyright (c) 2021. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 */

package com.tiktok.appevents;

import static com.tiktok.util.TTConst.TTSDK_EXCEPTION_SDK_CATCH;

import android.content.Context;

import com.tiktok.util.DecryptUtil;
import com.tiktok.util.JSON;
import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.json.JSONObject;

import java.io.Serializable;

public class TTUserInfo implements Cloneable, Serializable {
    static final String TAG = "TTUserInfo";
    String anonymousId;
    String externalId;
    String externalUserName;
    String phoneNumber;
    String email;
    transient boolean isIdentified = false;

    public static final TTUserInfo sharedInstance = new TTUserInfo();

    // clear the previous userInfo, useful when logging out
    public static void reset(Context context, boolean forceGenerateAnoId) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;
            sharedInstance.anonymousId = TTUtil.getOrGenAnoId(context, forceGenerateAnoId);
            sharedInstance.externalId = null;
            sharedInstance.externalUserName = null;
            sharedInstance.phoneNumber = null;
            sharedInstance.email = null;
            sharedInstance.isIdentified = false;
        }
    }

    public String getAnonymousId() {
        return TTTrackingConsent.isGranted() ? anonymousId : null;
    }

    public static void clear() {
        sharedInstance.anonymousId = null;
        sharedInstance.externalId = null;
        sharedInstance.externalUserName = null;
        sharedInstance.phoneNumber = null;
        sharedInstance.email = null;
        sharedInstance.isIdentified = false;
    }

    public boolean isIdentified() {
        return this.isIdentified;
    }

    public void setIdentified() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            this.isIdentified = true;
        }
    }

    public void setExternalId(String externalId) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (DecryptUtil.isSHA256(externalId)) {
                this.externalId = externalId;
            } else {
                this.externalId = DecryptUtil.toSha256(externalId);
            }
        }
    }

    public void setExternalUserName(String externalUserName) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (DecryptUtil.isSHA256(externalUserName)) {
                this.externalUserName = externalUserName;
            } else {
                this.externalUserName = DecryptUtil.toSha256(externalUserName);
            }
        }
    }

    public void setPhoneNumber(String phoneNumber) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (DecryptUtil.isSHA256(phoneNumber)) {
                this.phoneNumber = phoneNumber;
            } else {
                this.phoneNumber = DecryptUtil.toSha256(phoneNumber);
            }
        }
    }

    public void setEmail(String email) {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return;

            if (DecryptUtil.isSHA256(email)) {
                this.email = email;
            } else {
                this.email = DecryptUtil.toSha256(email);
            }
        }
    }

    public JSONObject toJsonObject() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return JSON.build();

            JSONObject jsonObject = JSON.build();
            try {
                if (externalId != null) {
                    JSON.putObject(jsonObject, "external_id", externalId);
                }
                if (externalUserName != null) {
                    JSON.putObject(jsonObject, "external_username", externalUserName);
                }
                if (phoneNumber != null) {
                    JSON.putObject(jsonObject, "phone_number", phoneNumber);
                }
                if (email != null) {
                    JSON.putObject(jsonObject, "email", email);
                }
            } catch (Throwable e) {
                TTCrashHandler.handleCrash(TAG, e, TTSDK_EXCEPTION_SDK_CATCH);
            }
            return jsonObject;
        }
    }

    // Since there are no composition in this class, should be fairly safe
    // to do the default cloning
    public TTUserInfo clone() {
        synchronized (TTTrackingConsent.LOCK) {
            if (!TTTrackingConsent.isGranted()) return new TTUserInfo();

            try {
                return (TTUserInfo) super.clone();
            } catch (Throwable ignore) {
                return new TTUserInfo();
            }
        }
    }
}
