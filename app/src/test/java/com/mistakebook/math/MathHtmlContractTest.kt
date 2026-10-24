package com.mistakebook.math

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * math.html 与 Kotlin 渲染链路的契约守护。
 *
 * ## 背景（v0.0.31~32 全量渲染失败事故）
 *
 * v0.0.31 为修「公式最右端被切」把 math.html 的 render()/renderBatch()
 * 改成了 async 函数（内部 await fontsReady()）。但 Android 的
 * `WebView.evaluateJavascript` **不会 await Promise**：它把求值结果
 * JSON.stringify 一下，Promise 序列化成 `{}`，Kotlin 侧 parseSize 拿不到
 * w/h，[MathRenderer.render] / renderPending 全部返回 null，用户看到所有
 * 公式退化成 LaTeX 源码。
 *
 * Playwright 的 page.evaluate 会自动 await Promise，所以当时的"终验全过"
 * 是假象——这个测试补的正是那个盲区：**在 JVM 侧直接审 math.html 的源码**，
 * 把"render 必须是同步函数"固化成断言，杜绝再有人改回 async。
 *
 * 修复方案（v0.0.33）：字体等待改为页面加载阶段做一次
 * （initFonts() → AndroidMath 桥 → Kotlin pageLoaded 门），
 * render()/renderBatch() 恢复同步返回。
 */
class MathHtmlContractTest {

    private val mathHtml: String by lazy {
        // 相对模块根目录定位 assets（JVM 单测的工作目录是 app/）
        val file = File("src/main/assets/katex/math.html")
        assertTrue("math.html 不存在: ${file.absolutePath}", file.isFile)
        file.readText()
    }

    @Test
    fun `render 必须是同步函数`() {
        // 事故签名：async function render —— 出现即回归
        assertFalse(
            "render() 不允许是 async：evaluateJavascript 不等 Promise，会拿到 \"{}\"",
            mathHtml.contains(Regex("""async\s+function\s+render\s*\("""))
        )
    }

    @Test
    fun `renderBatch 必须是同步函数`() {
        assertFalse(
            "renderBatch() 不允许是 async：evaluateJavascript 不等 Promise，会拿到 \"{}\"",
            mathHtml.contains(Regex("""async\s+function\s+renderBatch\s*\("""))
        )
    }

    @Test
    fun `render 内部不允许 await`() {
        // 直接抓 await 更稳：任何 await 都意味着函数体进入了异步语义。
        // 注释里的 "await"（事故历史说明）先剥掉再查。
        val code = mathHtml.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
        assertFalse(
            "math.html 不允许再出现 await（字体等待已上格为页面加载阶段）",
            code.contains("await ")
        )
    }

    @Test
    fun `字体桥契约存在`() {
        // JS 侧通知 + Kotlin 侧接收，两头都必须在
        assertTrue(
            "math.html 必须调用 AndroidMath.onFontsReady()",
            mathHtml.contains("AndroidMath.onFontsReady")
        )
        assertTrue(
            "math.html 必须定义 initFonts() 并调用它",
            mathHtml.contains("function initFonts()") && mathHtml.contains("initFonts();")
        )
        val renderer = File("src/main/java/com/mistakebook/math/MathRenderer.kt").readText()
        assertTrue(
            "MathRenderer 必须注册 AndroidMath 桥",
            renderer.contains("addJavascriptInterface") && renderer.contains("\"AndroidMath\"")
        )
        assertTrue(
            "MathRenderer 必须提供 @JavascriptInterface onFontsReady",
            renderer.contains("fun onFontsReady()")
        )
    }
}
