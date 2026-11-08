package com.mistakebook.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动压缩阈值收敛（v0.0.135，用户：「压缩上下文可以自己设置多大的时候压缩」）。
 *
 * 与 [DailyNewLimitNormalizeTest] 同一思路：脏值（null / 越界 / 历史遗留）
 * 一律回落默认档 500k，**不钳到边界**——钳到 100k 等于把老用户的会话
 * 突然开始频繁压缩，那比显示错更糟。
 */
class AutoCompressTokensTest {

    @Test
    fun `合法档位原样保留`() {
        SettingsSnapshot.AUTO_COMPRESS_TOKEN_OPTIONS.forEach { tokens ->
            assertEquals(tokens, normalizeAutoCompressTokens(tokens))
        }
    }

    @Test
    fun `空值回落默认档`() {
        assertEquals(
            SettingsSnapshot.DEFAULT_AUTO_COMPRESS_TOKENS,
            normalizeAutoCompressTokens(null)
        )
    }

    @Test
    fun `越界与脏值回落默认档而不是钳到边界`() {
        // 钳到 100k 会突然频繁压缩；钳到 800k 会突然不压缩——都该回落默认
        listOf(-1, 0, 1, 499_999, 1_000_000, Int.MAX_VALUE).forEach { dirty ->
            assertEquals(
                "脏值 $dirty 应回落默认档",
                SettingsSnapshot.DEFAULT_AUTO_COMPRESS_TOKENS,
                normalizeAutoCompressTokens(dirty)
            )
        }
    }

    @Test
    fun `默认档就是档位表里的一项`() {
        assertEquals(500_000, SettingsSnapshot.DEFAULT_AUTO_COMPRESS_TOKENS)
        assertEquals(
            true,
            SettingsSnapshot.AUTO_COMPRESS_TOKEN_OPTIONS.contains(
                SettingsSnapshot.DEFAULT_AUTO_COMPRESS_TOKENS
            )
        )
    }
}
