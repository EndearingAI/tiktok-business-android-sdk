/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.util;

import android.annotation.SuppressLint;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TimeUtil {
    @SuppressLint("ConstantLocale")
    private static final DateFormat sFormate =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault());

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    /** return ISO8601 time format */
    public static synchronized String getISO8601Timestamp(Date date) {
        try {
            TimeZone tz = TimeZone.getTimeZone("UTC");
            sFormate.setTimeZone(tz);
            return sFormate.format(date);
        } catch (Throwable ignore) {
        }
        return "";
    }

    public static String getISO8601Timestamp() {
        try {
            return getISO8601Timestamp(new Date());
        } catch (Throwable ignore) {
        }
        return "";
    }

    public static String dateStr(int dayDifference) {
        // now
        try {
            Calendar c1 = Calendar.getInstance();
            if (dayDifference != 0) {
                c1.add(Calendar.DATE, dayDifference);
            }
            return sdf.format(c1.getTime());
        } catch (Throwable ignore) {
        }
        return "";
    }

    public static boolean isNowAfter(String referenceStr, int days) {
        try {
            String yesterdayStr = dateStr(-days);
            return yesterdayStr.equals(referenceStr);
        } catch (Throwable ignore) {
        }
        return false;
    }
}
