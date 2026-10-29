package com.mistakebook.ui.crop

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CropSession] 的序列化回归测试。
 *
 * ## 为什么值得单测
 *
 * v0.0.55 反馈给裁剪会话加了 `enhanceStrength` 字段（对比度增强档位）。
 * 这是一份**已经写在用户设备上**的持久化数据：老版本存下的会话 JSON 里
 * 没有这个字段。若 `ignoreUnknownKeys` / 默认值的配置有任何差池，
 * 用户升级后打开裁剪页会直接解码失败——表现是「裁剪框、涂鸦、旋转
 * 全部丢回默认值」，而且不报错，最难排查的那类 bug。
 *
 * 两个方向都要钉住：
 * 1. 新字段能原样往返（否则用户调好的档位下次进来自行变回去）；
 * 2. 缺字段的旧 JSON 能解出来且回退到标准档（2）。
 */
class CropSessionEnhanceStrengthTest {

    // 与 CropSessionStore 里同一份配置：忽略未知键 + 连默认值一起编码
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `增强档位随会话原样往返`() {
        val session = CropSession(
            left = 0.1f,
            top = 0.2f,
            right = 0.8f,
            bottom = 0.9f,
            rotationQuarter = 1,
            strokes = emptyList(),
            enhanceStrength = 3
        )
        val encoded = json.encodeToString(CropSession.serializer(), session)
        val decoded = json.decodeFromString(CropSession.serializer(), encoded)
        assertEquals(3, decoded.enhanceStrength)
    }

    @Test
    fun `档位零也照样往返_不能被当成缺省值吞掉`() {
        val session = CropSession(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
            rotationQuarter = 0,
            strokes = emptyList(),
            enhanceStrength = 0
        )
        val decoded = json.decodeFromString(
            CropSession.serializer(),
            json.encodeToString(CropSession.serializer(), session)
        )
        assertEquals(0, decoded.enhanceStrength)
    }

    @Test
    fun `旧会话没有档位字段时回退到标准档`() {
        // v0.0.55 之前存下的会话 JSON：只有 rect / rotation / strokes
        val legacy =
            """{"left":0.1,"top":0.2,"right":0.8,"bottom":0.9,"rotationQuarter":0,"strokes":[]}"""
        val decoded = json.decodeFromString(CropSession.serializer(), legacy)
        assertEquals(2, decoded.enhanceStrength)
        // 其余字段也要能正常解出，证明不是「整体回退到默认会话」
        assertEquals(0.1f, decoded.left, 1e-6f)
        assertEquals(0, decoded.rotationQuarter)
        assertEquals(emptyList<List<StrokePoint>>(), decoded.strokes)
    }
}
