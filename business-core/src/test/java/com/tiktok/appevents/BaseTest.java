/*******************************************************************************
 * Copyright (c) 2020. Tiktok Inc.
 *
 * This source code is licensed under the MIT license found in the LICENSE file in the root directory of this source tree.
 ******************************************************************************/

package com.tiktok.appevents;

import static org.mockito.ArgumentMatchers.anyString;

import com.tiktok.util.TTTrackingConsent;
import com.tiktok.util.TTUtil;

import org.junit.Before;
import org.powermock.api.mockito.PowerMockito;

public class BaseTest {
    @Before
    public void setup() throws Exception {
        TTTrackingConsent.setEnabled(true);
        com.tiktok.TikTokBusinessSdk.memoryListener = null;
        com.tiktok.TikTokBusinessSdk.nextTimeFlushListener = null;
        PowerMockito.mockStatic(TTUtil.class);
        PowerMockito.doNothing().when(TTUtil.class, "checkThread", anyString());
        TTAppEventsQueue.clearAll();
        TTEdpAppEventsQueue.clearAll();
    }
}
