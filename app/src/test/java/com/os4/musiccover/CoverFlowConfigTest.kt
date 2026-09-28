package com.os4.musiccover

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowConfigTest {
    @Test fun oldConfigurationRemainsOff() {
        assertFalse(CoverFlowConfig.fromJson(null).enabled)
        assertFalse(CoverFlowConfig.fromJson("{}").enabled)
        assertEquals("{\"enabled\":false}", CoverFlowConfig.normalizedJson(null))
    }

    @Test fun oldPresetAndSliderValuesOnlyKeepTheSwitch() {
        val migrated = CoverFlowConfig.normalizedJson(
            """{"enabled":true,"preset":2,"warp":0.75,"speed":2.2,"blur":7}""")
        assertEquals("{\"enabled\":true}", migrated)
        assertTrue(CoverFlowConfig.fromJson(migrated).enabled)
    }

    @Test fun backupImportPreservesExistingChoice() {
        assertNull(CoverFlowConfig.backupValue(JSONObject("""{"coverStyle":1}""")))
        val backup = JSONObject().put(CoverFlowConfig.BACKUP_KEY,
            JSONObject("""{"enabled":true,"preset":1,"warp":0.45}"""))
        assertEquals("{\"enabled\":true}", CoverFlowConfig.backupValue(backup))
    }
}
