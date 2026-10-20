package com.voxelport.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuideTest {
    @Test fun `makers are recognised by manufacturer or brand`() {
        assertEquals(Oem.SAMSUNG, BatteryGuide.detect("samsung", "samsung"))
        assertEquals(Oem.XIAOMI, BatteryGuide.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, BatteryGuide.detect("Xiaomi", "POCO"))
        assertEquals(Oem.OPPO, BatteryGuide.detect("OPPO", "realme"))
        assertEquals(Oem.OPPO, BatteryGuide.detect("OnePlus", "OnePlus"))
        assertEquals(Oem.VIVO, BatteryGuide.detect("vivo", "iQOO"))
        assertEquals(Oem.HUAWEI, BatteryGuide.detect("HUAWEI", "HONOR"))
    }

    @Test fun `anything else gets the generic help`() {
        assertEquals(Oem.OTHER, BatteryGuide.detect("Google", "google"))
        assertEquals(Oem.OTHER, BatteryGuide.detect("Nothing", "Nothing"))
        assertEquals(Oem.OTHER, BatteryGuide.detect("", ""))
    }

    @Test fun `every maker with its own screen has a candidate to try, and the generic one has none`() {
        for (oem in Oem.values()) {
            if (oem == Oem.OTHER) assertTrue(BatteryGuide.screens(oem).isEmpty())
            else assertTrue("$oem", BatteryGuide.screens(oem).isNotEmpty())
        }
    }
}
