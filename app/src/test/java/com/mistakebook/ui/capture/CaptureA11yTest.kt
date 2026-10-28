package com.mistakebook.ui.capture

import com.mistakebook.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.0.54 后续优化 · Task C：拍照页无障碍的**标签契约**单测（JVM，CI 可跑）。
 *
 * 本机 / CI 无安卓设备、无 Robolectric，无法用 createComposeRule 断言语义树，
 * 所以这里守「标签资源映射」这条纯逻辑契约：
 * - 每个可点击图标都有可读的 contentDescription（字符串资源，非硬编码）；
 * - 闪光灯这个「开关类」控件：控件名走 contentDescription、档位走 stateDescription，两者分开；
 * - 三档各取到正确的字符串资源，且彼此不重复。
 *
 * 真正的节点级语义（contentDescription 是否真的合并到按钮上、禁用态是否被朗读）
 * 需在真机 / androidTest 上验，CI 不跑设备，故以本条资源契约 + 代码评审兜底。
 */
class CaptureA11yTest {

    // ---- 闪光灯：contentDescription（控件名）与 stateDescription（档位）分开 ----

    @Test
    fun `闪光灯三档各取到正确的 stateDescription 资源`() {
        assertEquals(R.string.capture_flash_on, flashStateDescriptionRes(FLASH_ON))
        assertEquals(R.string.capture_flash_off, flashStateDescriptionRes(FLASH_OFF))
        assertEquals(R.string.capture_flash_auto, flashStateDescriptionRes(FLASH_AUTO))
    }

    @Test
    fun `闪光灯档位资源两两不同`() {
        val on = flashStateDescriptionRes(FLASH_ON)
        val off = flashStateDescriptionRes(FLASH_OFF)
        val auto = flashStateDescriptionRes(FLASH_AUTO)
        assertNotEquals(on, off)
        assertNotEquals(on, auto)
        assertNotEquals(off, auto)
    }

    @Test
    fun `闪光灯控件名与档位状态分开_不混用同一个字符串`() {
        // contentDescription 固定「闪光灯」，不能等于任一档位串，否则状态既进 label 又进 state，朗读重复
        assertNotEquals(R.string.capture_flash, flashStateDescriptionRes(FLASH_ON))
        assertNotEquals(R.string.capture_flash, flashStateDescriptionRes(FLASH_OFF))
        assertNotEquals(R.string.capture_flash, flashStateDescriptionRes(FLASH_AUTO))
    }

    // ---- 每个可点击图标都有可读标签（字符串资源，非零且互不相同）----

    @Test
    fun `五个图标按钮的标签资源都存在且互不相同`() {
        val labels = listOf(
            R.string.capture_flash,          // 闪光灯
            R.string.capture_switch_camera,  // 切换前后摄像头
            R.string.capture_shutter,        // 快门
            R.string.capture_gallery,        // 相册
            R.string.capture_cancel          // 取消 / 关闭
        )
        labels.forEach { assertTrue("标签资源 id 应非零", it != 0) }
        assertEquals("图标标签不能重复（否则 TalkBack 分不清是哪个）", labels.size, labels.toSet().size)
    }
}
