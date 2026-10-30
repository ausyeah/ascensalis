package com.mistakebook.ui

import com.mistakebook.data.prefs.OcrProfile
import com.mistakebook.data.prefs.OcrType
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.math.MathWrap
import com.mistakebook.ui.chat.ChatThinking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.0.81 核心功能与关键修复的契约守护。
 *
 * 1. LaTeX 矩阵渲染防切断与环境深度跟踪、公式错误详情展示
 * 2. KaTeX 模板右侧内边距安全留白 (28px) 防止右括号裁切
 * 3. 聊天消息复制剥离 <think>，思考框内独立复制思考，代码块顶部栏一键复制
 * 4. 会话内图片点击全屏无损放大预览 (FullScreenImageDialog)
 * 5. 错题详情页删除无效的“重新裁切”按钮
 * 6. OCR 自定义配置体系 (OcrProfile)、讯飞 MaaS/OpenAI 视觉模型智能端点适配与测试连通
 * 7. OCR 重试防锁死机制与超时熔断保护
 * 8. 系统提示词 LaTeX 三大硬约束规则注入
 */
class Version81FeaturesContractTest {

    @Test
    fun `MathWrap 矩阵移出多行环境且矩阵内部换行符不被切断`() {
        val matrixLatex = """\begin{vmatrix} 1 & 2 \\ 3 & 4 \end{vmatrix}=0"""
        val slices = MathWrap.splitForDisplay(matrixLatex, 60)
        assertEquals("矩阵与其收尾紧跟的等号不得被强制断行腰斩", 1, slices.size)
        assertEquals(matrixLatex, slices[0])

        val pmatrixLatex = """\begin{pmatrix} a & b \\ c & d \end{pmatrix}"""
        val pSlices = MathWrap.splitForDisplay(pmatrixLatex, 60)
        assertEquals("pmatrix 移出多行环境后不得被拆成多行", 1, pSlices.size)
        assertEquals(pmatrixLatex, pSlices[0])
    }

    @Test
    fun `math_html 模板增大右侧 padding 至 28px 并且回传 errMsg 错误信息`() {
        val src = readSource("app/src/main/assets/katex/math.html")
        assertTrue("必须设置右侧 padding 28px 安全留白防右括号裁切", src.contains("16px 28px 18px 16px"))
        assertTrue("必须提供 getRenderErrorMessage 捕获 KaTeX 错误", src.contains("function getRenderErrorMessage("))
        assertTrue("批处理结果必须携带 errMsg", src.contains("errMsg:"))
    }

    @Test
    fun `RichText 支持代码块复制与公式解析失败详情卡片`() {
        val src = readSource("app/src/main/java/com/mistakebook/ui/common/RichText.kt")
        assertTrue("RichText 必须包含 CodeBlock 组件并带复制按钮", src.contains("private fun CodeBlock("))
        assertTrue("CodeBlock 头部栏必须包含语言标签与复制图标", src.contains("Icons.Default.ContentCopy"))
        assertTrue("公式失败时必须展示公式解析失败及错误信息与可选中源码", src.contains("公式解析失败"))
    }

    @Test
    fun `ChatThinking 提取回答剥离思考标签且聊天页支持复制思考与回答`() {
        val mixedContent = "<think>\n这里是详细推理过程\n</think>\n这是纯净的助手回答"
        assertEquals("这是纯净的助手回答", ChatThinking.answerOf(mixedContent))
        assertEquals("这里是详细推理过程", ChatThinking.thinkingOf(mixedContent))

        val chatSrc = readSource("app/src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue("复制助手消息时必须剥离 think 标签", chatSrc.contains("ChatThinking.answerOf(message.content)"))
        assertTrue("ThinkingBlock 必须提供独立的复制思考按钮", chatSrc.contains("Icons.Default.ContentCopy") && chatSrc.contains("contentDescription = \"复制思考\""))
    }

    @Test
    fun `会话中图片支持应用内全屏放大预览`() {
        val chatSrc = readSource("app/src/main/java/com/mistakebook/ui/chat/ChatScreen.kt")
        assertTrue("ChatScreen 必须声明 previewImagePath 预览状态", chatSrc.contains("var previewImagePath by remember { mutableStateOf<String?>(null) }"))
        assertTrue("ChatScreen 必须引入 FullScreenImageDialog 全屏放大弹窗", chatSrc.contains("FullScreenImageDialog("))
        assertTrue("点击图片气泡必须触发预览", chatSrc.contains("previewImagePath = it"))
    }

    @Test
    fun `错题详情页删除无效的重新裁切按钮`() {
        val detailSrc = readSource("app/src/main/java/com/mistakebook/ui/detail/DetailScreen.kt")
        assertFalse("详情页题图下方不得保留无效的重新裁切按钮与参数", detailSrc.contains("onRecrop"))
        assertFalse("详情页不得引入 action_recrop 文本", detailSrc.contains("stringResource(R.string.action_recrop)"))
    }

    @Test
    fun `OcrProfile 智能适配端点且 SettingsSnapshot 兼容 mineruConfigured`() {
        val xfProfile = OcrProfile(
            name = "讯飞星辰",
            type = OcrType.OPENAI_VISION,
            baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions",
            model = "xopdeepseekocr"
        )
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions", xfProfile.endpointUrl())

        val responsesProfile = OcrProfile(
            name = "讯飞 Responses 端点",
            type = OcrType.OPENAI_VISION,
            baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/v1/responses",
            model = "xopdeepseekocr"
        )
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v1/responses", responsesProfile.endpointUrl())

        val bareProfile = OcrProfile(
            name = "裸域名端点",
            type = OcrType.OPENAI_VISION,
            baseUrl = "https://api.openai.com",
            model = "gpt-4o"
        )
        assertEquals("https://api.openai.com/v1/chat/completions", bareProfile.endpointUrl())

        val snapshot = SettingsSnapshot(
            ocrProfiles = listOf(
                OcrProfile(
                    id = "test_deepseek",
                    name = "DeepSeek OCR",
                    type = OcrType.OPENAI_VISION,
                    apiKey = "sk-test",
                    model = "xopdeepseekocr"
                )
            ),
            activeOcrProfileId = "test_deepseek",
            mineruKey = "" // 即使老 MinerU key 为空
        )
        assertTrue("只要当前激活的 OCR profile 已配置 key，ocrConfigured 必须为 true", snapshot.ocrConfigured)
        assertTrue("mineruConfigured 属性必须委托给 ocrConfigured 防拦截", snapshot.mineruConfigured)
    }

    @Test
    fun `OCR 识别失败不拦截二次重试且图片特征包含哈希`() {
        val imageOcrSrc = readSource("app/src/main/java/com/mistakebook/data/chat/ChatImageOcr.kt")
        assertTrue("识别失败时不得将图片记录到 tried 表防重试死锁", imageOcrSrc.contains("失败不记入 tried"))
        assertTrue("keyOf 生成必须包含图片头部字节哈希防换图同名拦截", imageOcrSrc.contains("headBytes"))

        val ocrEngineSrc = readSource("app/src/main/java/com/mistakebook/net/ocr/OcrEngine.kt")
        assertTrue("OcrEngine 必须设置 callTimeout 严格超时", ocrEngineSrc.contains(".callTimeout("))
        assertTrue("OcrEngine 必须设置 40 秒协程超时熔断", ocrEngineSrc.contains("withTimeoutOrNull(40_000L)"))
    }

    @Test
    fun `系统提示词注入 LaTeX 矩阵收尾与多公式硬约束`() {
        val chatSrc = readSource("app/src/main/java/com/mistakebook/data/chat/ChatPrompts.kt")
        assertTrue("聊天提示词必须包含独立公式块中的环境收尾规则", chatSrc.contains("独立公式块中的环境收尾规则"))
        assertTrue("聊天提示词必须包含多公式并列规则", chatSrc.contains("多公式并列规则"))
        assertTrue("聊天提示词必须包含公式块纯净规则", chatSrc.contains("公式块纯净规则"))

        val pipelineSrc = readSource("app/src/main/java/com/mistakebook/pipeline/PromptTemplates.kt")
        assertTrue("错题流水线提示词必须包含独立公式块中的环境收尾规则", pipelineSrc.contains("独立公式块中的环境收尾规则"))
        assertTrue("错题流水线提示词必须包含多公式并列规则", pipelineSrc.contains("多公式并列规则"))
        assertTrue("错题流水线提示词必须包含公式块纯净规则", pipelineSrc.contains("公式块纯净规则"))
    }

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}
