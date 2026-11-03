package com.mistakebook.ui.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 连拍接力「第几张」的序号计算（v0.0.95）。
 *
 * 这个数字只在用户眼前一闪而过，错了也不会有崩溃日志——
 * 表现是「第 3 / 3 张」却裁到了第二张，只有当事人（而且得是细心的人）才发现。
 * 所以把推导抽成纯函数，在这里把边界全部钉住。
 */
class CropProgressTest {

    @Test
    fun `批次第一张是一`() {
        assertEquals(1, batchPosition(cropped = 0, total = 3))
    }

    @Test
    fun `每裁完一张序号加一`() {
        assertEquals(1, batchPosition(cropped = 0, total = 3))
        assertEquals(2, batchPosition(cropped = 1, total = 3))
        assertEquals(3, batchPosition(cropped = 2, total = 3))
    }

    @Test
    fun `单张与空批次都不显示序号`() {
        // total <= 1 时返回 null：单张照片上挂「第 1 / 1 张」是纯噪音
        assertNull(batchPosition(cropped = 0, total = 0))
        assertNull(batchPosition(cropped = 0, total = 1))
        assertNull(batchPosition(cropped = 1, total = 1))
    }

    @Test
    fun `成果数超出总张数时钳在最后一张`() {
        // 理论上不该发生（commitCropped 与 dropCurrent 成对推进），
        // 但进程被系统回收后重建、状态只恢复一半时可能构造出这种组合。
        // 宁可显示「第 3 / 3 张」，也不能显示「第 4 / 3 张」。
        assertEquals(3, batchPosition(cropped = 3, total = 3))
        assertEquals(3, batchPosition(cropped = 99, total = 3))
    }

    @Test
    fun `序号永远落在闭区间内且随完成数单调不减`() {
        val total = 5
        var previous = 0
        for (cropped in 0 until total) {
            val position = batchPosition(cropped, total)!!
            assertEquals(true, position in 1..total)
            assertEquals(true, position > previous)
            previous = position
        }
    }
}