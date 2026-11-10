package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 失败气泡的原因显示（v0.0.148）单测。
 *
 * 此前失败原因只走 4 秒即逝的 Snackbar，气泡上永远只有一句
 * 「发送失败」——用户报障时说不清服务端回了什么。现在原因
 * 持久显示，这里钉住取舍：有原因就原样透出，空白不占行。
 */
class ChatFailedReasonTest {

    @Test
    fun `有原因就原样显示`() {
        assertEquals(
            "the API key ending in a823 is not an enabled magpie gateway key",
            chatFailedReason("the API key ending in a823 is not an enabled magpie gateway key")
        )
    }

    @Test
    fun `空白与空串不显示`() {
        assertNull(chatFailedReason(null))
        assertNull(chatFailedReason(""))
        assertNull(chatFailedReason("   "))
    }
}
