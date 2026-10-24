package com.mistakebook.math

import android.content.Context
import androidx.annotation.ColorInt
import androidx.collection.LruCache
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * LaTeX 公式渲染：WebView + KaTeX（资源打在本机 assets，无第三方 SDK）。
 * 渲染结果按「latex + 是否独立行」缓存，供页面展示与 PDF 导出共用。
 *
 * 踩过的坑（每个都会导致「公式位置一片空白」而不是回退到源码）：
 * 1. 必须 `setLayerType(LAYER_TYPE_SOFTWARE)`，否则内容在硬件 surface 里，
 *    `view.draw(canvas)` 抓到的是全透明位图。
 * 2. 必须给 WebView 真实布局尺寸，0×0 视口下页面内容根本不会光栅化。
 * 3. **JS 量到的 getBoundingClientRect() 是 CSS 像素，view.draw 画的是设备像素**。
 *    高分屏上两者差一个 devicePixelRatio（约 3 倍），必须让 JS 乘 dpr 再返回，
 *    否则抠出来的是公式左上角一小块甚至全空。
 * 4. WebView 重排/光栅化是异步的，JS 返回后要等几帧再抓，否则抓到上一帧。
 * 5. 抓到的位图可能只有背景色（不透明），单纯查 alpha 判不出空白，要按「主色占比」判断。
 */
/**
 * 一次渲染的产物：位图 + 该位图实际使用的字号（设备像素）。
 * fontPx 是排版的关键：调用方必须按「目标字号 / fontPx」等比缩放，
 * 这样公式里的字母数字才和正文中文**同号**。若改成按包围盒高度缩放，
 * 公式之间会大小失衡（单字母被撑得巨大、长分式被压扁），观感很不协调。
 */
data class RenderedMath(
    val bitmap: Bitmap,
    val fontPx: Float
)

class MathRenderer(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val cache = object : android.util.LruCache<String, RenderedMath>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: RenderedMath): Int = value.bitmap.byteCount
    }

    private var webView: WebView? = null
    private var pageLoaded: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * 公式前景色（CSS 串）。
     *
     * 抠出来的位图是**透明背景**的，前景色必须跟当前 App 主题走：
     * 深色主题下还用浅色的墨黑 #1B1F26，公式贴到近黑背景上就是
     * 「一片深色叠一片深色」——用户看到的是空白（v0.0.12 实测反馈）。
     */
    @Volatile
    private var fgCss: String = FG_CSS_LIGHT

    /**
     * 切换主题时由 UI 层调用。颜色变了就**整表作废缓存**——
     * 缓存 key 里没有颜色，旧位图全是上一个主题的墨水，留着只会显示错色。
     */
    fun setForegroundColor(@ColorInt color: Int) {
        val css = String.format("#%06X", 0xFFFFFF and color)
        if (css == fgCss) return
        fgCss = css
        cache.evictAll()
        Log.i(TAG, "公式前景色切换为 $css，缓存已作废")
    }

    /** 把当前前景色推进页面。同一 WebView 的 JS 队列保序，放在 render 脚本前调用即先生效。 */
    private fun pushForegroundColor(view: WebView) {
        view.evaluateJavascript("setFgColor('$fgCss')", null)
    }

    /** 渲染结果为透明背景位图 + 实际字号；失败返回 null（调用方回退为源码文本）。 */
    suspend fun render(latex: String, displayMode: Boolean): RenderedMath? {
        // 顺序要紧：先清理语义命令，再修复控制字符。
        // 反过来的话，清理过程可能新造出控制字符而没人收拾。
        // mathOnly = false：这里的 latex 是 RichText 切出来的**公式内部内容**，
        //  + "" + $ 分隔符早已被剥掉（RichText: rest.substring(2, end)），
        // 若按「数学区内」判定，这个修复一次都不会触发——而且不报错，只是静默失效。
        // **顺序：先修复，后清理。**
        //
        // LatexSanitizer.balanceDelimiters 会数 \left / \right 的个数，
        // 少了就在末尾补 \right.。若先把损坏文本送去清理，它看到的仍是
        // ight]（\right 已被 v0.0.3 删掉），于是补一个；
        // 等修复再把 \right] 补回来，就变成 1 个 \left 配 2 个 \right——
        // 反而更不平衡，KaTeX 直接报错、整条公式退化成红色源码。
        //
        // 真机踩过：修复生效了，但因为多出的那个 \right. 而渲染失败。
        val repaired = com.mistakebook.pipeline.LatexEscapes.repairForDisplay(
            latex,
            mathOnly = false
        )
        val trimmed = com.mistakebook.pipeline.LatexSanitizer.clean(repaired)
        if (trimmed.isEmpty()) return null
        val key = (if (displayMode) "d" else "i") + ":" + trimmed
        cache.get(key)?.let { return it }
        Log.i(TAG, "render 请求: '$trimmed' display=$displayMode")
        return withContext(Dispatchers.Main) {
            mutex.withLock {
                try {
                    val view = ensureWebView()
                    // 页面没加载完就发 JS，回调永远不回来；必须加超时，
                    // 否则 WebView 起不来会把 Mutex 永久占住，之后所有公式渲染一起卡死。
                    if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                        Log.w(TAG, "KaTeX 页面加载超时: $trimmed")
                        return@withLock null
                    }
                    // 先把主题前景色推上去，再发 render：JS 队列保序，颜色必然先生效。
                    pushForegroundColor(view)
                    // 留 32px 余量给 #out 的 padding，避免抠图时切到最右侧
                    val script =
                        "render(${jsonString(trimmed)}, $displayMode, ${MAX_BITMAP_EDGE - 32})"
                    // 光栅化有竞态：抓到空白就重来一次
                    repeat(2) { attempt ->
                        val raw = awaitJavascript(view, script)
                        if (raw == null) {
                            Log.w(TAG, "evaluateJavascript 无返回: $trimmed")
                            return@withLock null
                        }
                        val size = parseSize(raw)
                        if (size == null) {
                            Log.w(TAG, "尺寸解析失败, 原始返回: $raw")
                            return@withLock null
                        }
                        val width = size.width.coerceIn(1, MAX_BITMAP_EDGE)
                        val height = size.height.coerceIn(1, MAX_BITMAP_EDGE)
                        if (width < 2 || height < 2) return@withLock null

                        // **JS 侧已判定公式渲染失败，直接回退成源码。**
                        // err=1 源文本已被 JSON 转义破坏；err=2 KaTeX 解析失败。
                        // 不处理的话，用户看到的是 KaTeX 画出来的**红色错误文本**
                        // （那正是「一坨红色的字」的来源）——比显示源码糟糕得多：
                        // 源码至少还能看出题目原本是什么。
                        if (size.err != 0) {
                            Log.w(TAG, "公式渲染失败 err=${size.err}，回退源码: $trimmed")
                            return@withLock null
                        }

                        relayout(view)
                        view.scrollTo(0, 0)
                        val bitmap = capture(view, width, height)
                        if (bitmap != null) {
                            val result = RenderedMath(bitmap, size.fontPx)
                            cache.put(key, result)
                            return@withLock result
                        }
                        Log.w(TAG, "第 ${attempt + 1} 次抓图为空白，重试: $trimmed")
                    }
                    null
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Log.w(TAG, "render latex failed: $error")
                    null
                }
            }
        }
    }

    /** 每次渲染前重新走一遍 measure/layout，确保页面内容已在该尺寸下光栅化。 */
    private fun relayout(view: WebView) {
        view.measure(exactly(VIEWPORT_WIDTH), exactly(VIEWPORT_HEIGHT))
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    }

    /**
     * 批量长卷视口。
     *
     * 视口必须够高，否则 WebView 会对内容做滚动/裁剪，`view.draw` 画不到
     * 视口外的内容，量出的 sheetRect 也会被截断。
     * 一张长卷最多 12 个公式，按每个 ~200 设备像素估，留 4096 足够。
     */
    private fun relayoutForBatch(view: WebView, neededHeight: Int) {
        val h = neededHeight.coerceIn(VIEWPORT_HEIGHT, BATCH_VIEWPORT_HEIGHT)
        view.measure(exactly(VIEWPORT_WIDTH), exactly(h))
        view.layout(0, 0, VIEWPORT_WIDTH, h)
    }

    private suspend fun waitFrames(count: Int) {
        repeat(count) { awaitFrame() }
    }

    /**
     * 「逼帧」用的临时画布，**复用一张**。
     *
     * 未挂载到窗口的 WebView 不会自己产出合成帧，必须先往一张画布上画一次，
     * 后面 draw 才有内容——这一步本身省不掉。但原先每次都
     * `createBitmap(3200×1600)` 再 `recycle()`：那是 20MB 的分配紧接着释放，
     * 列表里滚过几十条公式就是几百 MB 的抖动，GC 一跑整个界面就顿一下。
     * 它只是个一次性耗材（画完就丢），所以按最大尺寸复用一张即可。
     */
    private var scratchBitmap: Bitmap? = null

    private fun scratchFor(view: WebView): Bitmap {
        val w = maxOf(view.width, 1)
        val h = maxOf(view.height, 1)
        val existing = scratchBitmap
        if (existing != null && existing.width >= w && existing.height >= h) return existing
        existing?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratchBitmap = it }
    }

    /**
     * 抓取页面左上角 width×height 的设备像素区域。
     *
     * 返回的位图已裁掉四周透明边（见 [trimTransparent]）——
     * math.html 的安全 padding 只是抓图阶段的保险，不带进排版。
     */
    private suspend fun capture(view: WebView, width: Int, height: Int): Bitmap? {
        waitFrames(2)
        // 从未挂载到窗口的 WebView 不会自己产出合成帧，AwContents 里是空的，
        // 直接 draw 出来就是全透明。先往一张临时画布上画一次，逼它把帧做出来。
        val scratch = scratchFor(view)
        val scratchCanvas = Canvas(scratch)
        scratch.eraseColor(Color.MAGENTA)
        view.draw(scratchCanvas)
        // 不 recycle：这张是复用的，见 [scratchFor]

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        // 判定必须看 alpha：「颜色数 > 1」是假指标，全透明区域照样能有几十种
        // 透明度抖动出来的颜色，一样等于什么都没画出来。
        // 判「有没有画出来」用采样，不用全像素统计。
        // 全像素统计每次要几百万次 JNI 调用，而这里只需要一个布尔结论。
        if (isBlankBySampling(bitmap.width, bitmap.height) { x, y ->
                bitmap.getPixel(x, y) ushr 24
            }
        ) {
            bitmap.recycle()
            return null
        }
        // 安全 padding 带来一圈透明边；行内公式的位图高度受 MathLayout 上限约束，
        // 透明边会把公式整体压小，所以裁掉再返回。理由详见 [trimTransparent]。
        return trimTransparent(bitmap)
    }

    /**
     * 公式渲染自检：把整条链路的真实值逐项打出来，供真机定位。
     * 「空白」这个症状对应至少五种完全不同的故障，光看现象无法区分，必须看数据。
     * 只做探测，不写缓存。
     */
    /**
     * LaTeX -> **MathML** 字符串。用于 HTML 导出。
     *
     * 为什么不直接用字体渲染：内嵌位图会让公式变成选不中的图像，
     * 文件大几十倍，还会破坏复制搜索。MathML 是文字，浏览器自带渲染能力。
     *
     * 失败返回 null，由调用方回退到位图方案——每个公式独立回退，
     * 而不是让整份文档导出失败。
     */
    suspend fun toMathMl(latex: String, displayMode: Boolean): String? =
        withContext(Dispatchers.Main) {
            mutex.withLock {
                val trimmed = com.mistakebook.pipeline.LatexSanitizer.clean(
                    com.mistakebook.pipeline.LatexEscapes.repairForDisplay(latex, mathOnly = false)
                )
                if (trimmed.isEmpty()) return@withLock null
                mathMlCache.get(trimmed)?.let { cached ->
                    return@withLock cached.takeIf { it.isNotEmpty() }
                }
                try {
                    val view = ensureWebView()
                    if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                        Log.w(TAG, "KaTeX 页面加载超时(MathML): $trimmed")
                        return@withLock null
                    }
                    val script = "toMathML(${jsonString(trimmed)}, $displayMode)"
                    val raw = awaitJavascript(view, script) ?: return@withLock null
                    // evaluateJavascript 对字符串返回值会再 JSON 编码一层，必须剥掉
                    val text = BatchProtocol.unwrap(raw)
                    if (text.isEmpty()) return@withLock null
                    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: return@withLock null
                    val ml = obj[MathMlProtocol.MATHML]?.jsonPrimitive?.content.orEmpty()
                    if (ml.isBlank()) {
                        Log.w(TAG, "MathML 为空: $trimmed")
                        return@withLock null
                    }
                    mathMlCache.put(trimmed, ml)
                    ml
                } catch (t: Throwable) {
                    Log.w(TAG, "MathML 生成失败: $trimmed", t)
                    null
                }
            }
        }

    /** MathML 缓存。不限制数量：字符串比位图小很多，而导出只跑一遍。 */
    private val mathMlCache = LruCache<String, String>(MATHML_CACHE_ENTRIES)


    suspend fun diagnose(): String = withContext(Dispatchers.Main) {
        mutex.withLock {
            val sb = StringBuilder()
            try {
                val view = ensureWebView()
                sb.appendLine("view: ${view.width}x${view.height}  layerType=${view.layerType} (SOFTWARE=${View.LAYER_TYPE_SOFTWARE})")
                sb.appendLine("pageLoaded 已完成=${pageLoaded.isCompleted} 已取消=${pageLoaded.isCancelled}")

                val start = System.currentTimeMillis()
                val loaded = withTimeoutOrNull(20_000L) { pageLoaded.await() }
                sb.appendLine(
                    "等页面加载: ${if (loaded != null) "完成" else "超时"} 耗时=${System.currentTimeMillis() - start}ms"
                )

                val probe = awaitJavascript(
                    view,
                    "JSON.stringify({katex:typeof katex!=='undefined',dpr:window.devicePixelRatio," +
                        "iw:window.innerWidth,ih:window.innerHeight," +
                        "fonts:(document.fonts?document.fonts.status:'n/a')})"
                )
                sb.appendLine("环境探针: ${probe ?: "<evaluateJavascript 没返回>"}")

                val sample = "x^2+\\frac{1}{2}"
                val raw = awaitJavascript(
                    view,
                    "render(${jsonString(sample)}, false, ${MAX_BITMAP_EDGE - 32})"
                )
                sb.appendLine("render() 返回: ${raw ?: "<null>"}")
                val size = raw?.let { parseSize(it) }
                sb.appendLine("解析尺寸: ${size?.let { "${it.width}x${it.height} 字号=${it.fontPx}px" } ?: "<解析失败>"}")

                val htmlLen = awaitJavascript(
                    view,
                    "(document.getElementById('out').innerHTML||'').length"
                )
                sb.appendLine("KaTeX 产出的 HTML 长度: ${htmlLen ?: "<null>"}")

                relayout(view)
                waitFrames(2)
                val w = (size?.width ?: 300).coerceIn(1, MAX_BITMAP_EDGE)
                val h = (size?.height ?: 120).coerceIn(1, MAX_BITMAP_EDGE)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                val (colors, dominant) = colorStats(bmp)
                val alphaInfo = alphaStats(bmp)
                sb.appendLine("直接抓图 ${w}x$h: 颜色数=$colors 主色=${hexOf(dominant)}")
                sb.appendLine("  像素: 总数=${alphaInfo.total} 不透明(alpha=255)=${alphaInfo.opaque} 可见(alpha>0)=${alphaInfo.visible} 最大alpha=${alphaInfo.maxAlpha}")
                sb.appendLine("  非透明像素包围盒: ${alphaInfo.box}")
                // 导出 PNG，adb pull 出来肉眼看，别再靠猜
                runCatching {
                    val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                    val file = java.io.File(dir, "math_probe.png")
                    java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    sb.appendLine("  PNG 已导出: ${file.absolutePath}")
                }.onFailure { sb.appendLine("  PNG 导出失败: ${it.message}") }
                bmp.recycle()
            } catch (error: Throwable) {
                sb.appendLine("自检异常: $error")
            }
            sb.toString()
        }
    }

    private fun hexOf(color: Int): String = String.format("#%08X", color)

    private data class AlphaInfo(
        val total: Int,
        val opaque: Int,
        val visible: Int,
        val maxAlpha: Int,
        val box: String
    )

    /**
     * 逐像素统计 alpha。**只供 [diagnose] 排障用。**
     *
     * ## 为什么常规渲染路径不能用它
     *
     * 它对整张位图逐点 `getPixel`——每次一个 JNI 调用。长卷最坏
     * `2048 × 4096` = **838 万次**，而且发生在主线程上。
     * 而它产出的六个字段里，常规路径只用到 `visible == 0` 一个布尔。
     *
     * 也就是**为 1 个 bit 的结论，付了几百万次跨语言调用的钱**。
     * 常规路径改用 [isBlankBySampling]；这里保留完整统计，
     * 是因为排障时恰恰需要这些数字（「空白」这个症状对应至少五种故障，
     * 光看现象无法区分，见 [diagnose] 的注释）。
     *
     * 颜色数多不代表有内容，全是透明度抖动一样是空白——所以判据是 alpha。
     */
    private fun alphaStats(bitmap: Bitmap): AlphaInfo {
        val w = bitmap.width
        val h = bitmap.height
        var total = 0
        var opaque = 0
        var visible = 0
        var maxAlpha = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                total++
                val a = pixels[idx++] ushr 24
                if (a > maxAlpha) maxAlpha = a
                if (a == 255) opaque++
                if (a > 0) {
                    visible++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        val box = if (maxX < 0) "空" else "($minX,$minY)-($maxX,$maxY)"
        return AlphaInfo(total, opaque, visible, maxAlpha, box)
    }

/** 采样统计：不同颜色数、主色（出现最多的颜色）。 */
    private fun colorStats(bitmap: Bitmap): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return 1 to bitmap.getPixel(0, 0)
        val stepX = (w / 64).coerceAtLeast(1)
        val stepY = (h / 64).coerceAtLeast(1)
        val counts = HashMap<Int, Int>(128)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                counts[c] = (counts[c] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        return counts.size to counts.maxBy { it.value }.key
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(appContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        // 关键：不设 SOFTWARE 层，WebView 内容在硬件 surface 里，draw 到软件画布必然全空
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // 字体就绪桥：math.html 的 initFonts() 等全部 KaTeX 字体加载完后经它放行
        // pageLoaded 门。必须在 loadUrl 之前注入，否则页面脚本首次执行时桥还不存在。
        view.addJavascriptInterface(FontsReadyBridge(pageLoaded), "AndroidMath")

        val loaded = pageLoaded
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "KaTeX 页面加载完成: $url")
                view?.let { relayout(it) }
                // 兜底：字体桥 3 秒内没回调也放行（字体加载失败时降级继续，
                // 与 v0.0.30 及之前的行为一致，绝不让渲染链路卡死）。
                // 正常路径由 FontsReadyBridge 先完成，这里重复 complete 是幂等的。
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!loaded.isCompleted) {
                        Log.w(TAG, "字体就绪桥超时（3s），降级放行")
                        loaded.complete(Unit)
                    }
                }, FONTS_BRIDGE_TIMEOUT_MS)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                Log.e(TAG, "KaTeX 页面加载失败: ${error?.description}")
                loaded.complete(Unit)
            }
        }
        relayout(view)
        view.loadUrl(ASSET_URL)
        webView = view
        return view
    }

    /**
     * math.html → Kotlin 的字体就绪桥。
     *
     * v0.0.31 事故的修复核心：render()/renderBatch() 曾是 async 函数，
     * 而 evaluateJavascript 不等 Promise，Kotlin 只拿到 "{}"，全量渲染失败。
     * 现在「等字体」只在页面加载阶段做一次，完成后经本桥放行 [pageLoaded]，
     * 之后的渲染调用全部同步返回，与 evaluateJavascript 语义精确匹配。
     */
    private class FontsReadyBridge(
        private val pageLoaded: CompletableDeferred<Unit>
    ) {
        @android.webkit.JavascriptInterface
        fun onFontsReady() {
            if (!pageLoaded.isCompleted) {
                Log.i("MathRenderer", "KaTeX 字体全部就绪（AndroidMath 桥）")
                pageLoaded.complete(Unit)
            }
        }
    }

    private suspend fun awaitJavascript(view: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) {
                    continuation.resume(value?.takeIf { it != "null" })
                }
            }
        }

    /** 等下一帧，保证 WebView 完成一次光栅化。 */
    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        Choreographer.getInstance().postFrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    /**
     * 解析 `render()` 返回的 `{"w":..,"h":..,"fs":..}`（单位已是设备像素）。
     */
    private data class Size(val width: Int, val height: Int, val fontPx: Float, val err: Int = 0)

    private fun parseSize(raw: String): Size? {
        val text = BatchProtocol.unwrap(raw)
        if (text.isEmpty()) return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val w = obj[SingleProtocol.WIDTH]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: return null
        val h = obj[SingleProtocol.HEIGHT]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt() ?: return null
        val fs = obj[SingleProtocol.FONT_PX]?.jsonPrimitive?.content?.toFloatOrNull() ?: DEFAULT_FONT_PX
        val err = obj[SingleProtocol.ERR]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return Size(w, h, fs, err)
    }

    /**
     * 判断是否「没画出东西」。
     * 不能只看 alpha：WebView 可能画了一层不透明背景，那样整张 alpha 都是 255。
     * 取采样像素里出现最多的颜色当背景，只要存在明显不同的像素就算有内容。
     */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return true
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        val samples = ArrayList<Int>(1024)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                samples.add(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        val counts = HashMap<Int, Int>(64)
        for (c in samples) counts[c] = (counts[c] ?: 0) + 1
        val bg = counts.maxBy { it.value }.key
        val bgR = Color.red(bg)
        val bgG = Color.green(bg)
        val bgB = Color.blue(bg)
        for (c in samples) {
            if (abs(Color.red(c) - bgR) > TOLERANCE ||
                abs(Color.green(c) - bgG) > TOLERANCE ||
                abs(Color.blue(c) - bgB) > TOLERANCE
            ) {
                return false
            }
        }
        return true
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun jsonString(text: String): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(text)
        )

    /**
     * **批量渲染**：一次 WebView 往返出多个公式。
     *
     * ## 为什么必须批量
     * 单条路径每渲染一个公式的代价是：2 次 Choreographer 帧等待 +
     * 2 次全画布 `draw` + 1 张全尺寸临时位图。
     * 一道题的解析里有 8~12 个公式，就是 16~24 次帧等待（每帧 16ms）、
     * 以及 N 张位图的分配与 GC。这正是「打开详情页很卡」的全部来源。
     *
     * 批量路径把 N 个公式**一次性塞进同一条长卷纵向排列**，
     * 只等 2 帧、只 draw 一次，然后按各行偏移切图。
     * 帧等待从 O(N) 降到 O(1)，这是唯一量级上的改进。
     *
     * ## 分批在缓存过滤**之后**
     *
     * 原来先按 `items` 分批、再让每批各自过滤缓存，于是「10 条里 8 条命中」
     * 也会被摊成 2 批、跑 2 次 WebView 往返。改成先滤后分：
     * 命中项不进 `pending`，批大小是**真正要画的条数**。
     *
     * @param items `(key, latex, displayMode)`。key 是调用方的缓存键，
     *   与 latex 分开是必要的：行内 `r:` 前缀的公式在 JS 侧要包 `\begin{aligned}`，
     *   但缓存键必须用**原始** key，否则命中不了单条路径写入的缓存。
     * @return key -> 渲染结果；某个失败不影响其他。
     */
    /** [cachedOf] 的实例版本，供界面直接调用。 */
    fun cachedOf(
        items: List<Triple<String, String, Boolean>>
    ): Map<String, RenderedMath> = cachedOf(cache::get, items)

    suspend fun renderAll(
        items: List<Triple<String, String, Boolean>>
    ): Map<String, RenderedMath?> = withContext(Dispatchers.Main) {
        if (items.isEmpty()) return@withContext emptyMap()

        // 命中缓存的直接给值，不再进 WebView
        val results = linkedMapOf<String, RenderedMath?>()
        val pending = mutableListOf<Triple<String, String, Boolean>>()
        items.forEach { (key, latex, display) ->
            val hit = cache.get(key)
            if (hit != null) {
                results[key] = hit
            } else {
                pending.add(Triple(key, latex, display))
            }
        }
        if (pending.isEmpty()) return@withContext results

        // 超量分批：一张长卷的位图开销随公式数线性增长，
        // 一次画 40 个公式会比画 4 批 10 个更容易触发 GC 抖动。
        //
        // 分批放在**缓存过滤之后**：命中项已从 pending 里剔掉了，
        // 所以这里的批大小是「真正要画的条数」，不会因为缓存命中而被摊薄。
        splitPending(pending.map { it.first }, BATCH_LIMIT).forEach { batchKeys ->
            val subset = pending.filter { it.first in batchKeys }
            results.putAll(renderPending(subset))
        }
        return@withContext results
    }

    /** 只渲染给定的（已剔除缓存命中的）一批。 */
    private suspend fun renderPending(
        pending: List<Triple<String, String, Boolean>>
    ): Map<String, RenderedMath?> {
        val results = linkedMapOf<String, RenderedMath?>()
        if (pending.isEmpty()) return results

        mutex.withLock {
            try {
                val view = ensureWebView()
                if (withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { pageLoaded.await() } == null) {
                    Log.w(TAG, "KaTeX 页面加载超时，批量渲染放弃")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                // 批量路径同样要先推主题色：长卷里每个公式都继承 #sheet 的 color。
                pushForegroundColor(view)
                val script = buildBatchScript(pending)
                val raw = awaitJavascript(view, script)
                if (raw == null) {
                    Log.w(TAG, "批量渲染 evaluateJavascript 无返回")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                val layout = BatchProtocol.parse(raw) ?: run {
                    Log.w(TAG, "批量布局解析失败, 原始返回(截断): ${raw.take(200)}")
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                // 视口要先撑到长卷高度，view.draw 才画得到全部内容。
                // 顺序反了只会拿到第一屏，量出的 sheetRect 也是截断的。
                relayoutForBatch(view, layout.sheetHeight)
                val sheet = captureSheet(view, layout.sheetWidth, layout.sheetHeight)
                if (sheet == null) {
                    pending.forEach { (key, _) -> results[key] = null }
                    return@withLock
                }
                layout.entries.forEach { entry ->
                    val key = entry.key
                    // 渲染失败（含被 JSON 转义破坏）一律回退成源码，
                    // 绝不能把 KaTeX 的红色错误当成公式结果返回——
                    // 打印时那会变成纸上一片红字。
                    val value = if (entry.err != 0) {
                        Log.w(TAG, "批量渲染失败 err=${entry.err}，回退源码: $key")
                        null
                    } else {
                        slice(sheet, entry.x, entry.y, entry.width, entry.height)
                            ?.let { RenderedMath(it, entry.fontPx) }
                    }
                    if (value != null) cache.put(key, value)
                    results[key] = value
                }
                sheet.recycle()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.w(TAG, "批量渲染失败: $error")
                pending.forEach { (key, _) -> results[key] = null }
            }
        }
        return results
    }

    /** 生成「把 N 个公式竖排进一条长卷」的 JS。 */
    private fun buildBatchScript(items: List<Triple<String, String, Boolean>>): String {
        val payload = items.joinToString(",") { (key, latex, display) ->
            // 批量路径绕过了 render()，清洗与控制字符修复必须在这里也做一次。
            // restoreLatexControlChars 不能省：批量路径直接拿草稿里的原始字符串，
            // 若此前解析层漏了保护，红色错误会被原样切进位图、印到纸上。
            // 同 render()：批量路径拿到的也是剥掉分隔符的公式内部内容。
            // 打印走这条路，漏了修复就会把损坏公式原样印到纸上。
            // 顺序理由同 render()：先修复再清理，否则 balanceDelimiters
            // 会基于损坏文本误判，把多余 \right. 印到纸上。
            val repaired = com.mistakebook.pipeline.LatexEscapes.repairForDisplay(
                latex,
                mathOnly = false
            )
            val cleaned = com.mistakebook.pipeline.LatexSanitizer.clean(repaired)
            "{k:${jsonString(key)},t:${jsonString(cleaned)},d:$display}"
        }
        return "renderBatch([$payload],${MAX_BITMAP_EDGE - 32})"
    }

    /** 抓整张「公式长卷」，一次 draw 拿到所有公式。 */
    private suspend fun captureSheet(view: WebView, width: Int, height: Int): Bitmap? {
        waitFrames(BATCH_FRAME_WAIT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        // 同样是采样判定，理由见 [capture] 里的注释
        if (isBlankBySampling(bitmap.width, bitmap.height) { x, y ->
                bitmap.getPixel(x, y) ushr 24
            }
        ) {
            bitmap.recycle()
            Log.w(TAG, "批量长卷为空白 ${width}x$height")
            return null
        }
        return bitmap
    }

    /**
     * 从长卷里切出一条公式。
     *
     * ## 为什么「切不全就返回 null」而不是返回残缺图
     *
     * 原来这里是：
     * ```
     * val safeW = width.coerceAtMost(sheet.width - safeX)
     * val safeH = height.coerceAtMost(sheet.height - safeY)
     * Bitmap.createBitmap(sheet, safeX, safeY, safeW, safeH)   // 残缺也照给
     * ```
     * 公式超出长卷时被**切掉一块照样当完整结果返回**，没有任何日志——
     * 用户看到的就是「大量公式显示不全」，而且完全无从排查。
     *
     * 长卷高度受视口上限约束（见 [BATCH_SAFE_SHEET_HEIGHT]），
     * 一批公式高到超限时必然溢出。现在改成：**切不全就不给**，
     * 上层回退成显示源码。源码至少还能看出题目原样，
     * 截断的公式则既看不懂、也不知道发生了什么。
     */
    private fun slice(
        sheet: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val safeX = x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val safeW = width.coerceAtMost(sheet.width - safeX)
        val safeH = height.coerceAtMost(sheet.height - safeY)
        if (safeW <= 0 || safeH <= 0) return null
        if (safeW < width || safeH < height) {
            Log.w(
                TAG,
                "公式超出长卷边界，已截断 ${width}x$height -> ${safeW}x$safeH" +
                    "（长卷 ${sheet.width}x${sheet.height} @ ${safeX},$safeY）。改为回退源码"
            )
            return null
        }
        val sliced = runCatching { Bitmap.createBitmap(sheet, safeX, safeY, safeW, safeH) }.getOrNull()
            ?: return null
        // 同 [capture]：把安全 padding 留下的透明边裁掉再返回，
        // 位图紧贴墨水，Compose 侧按字号换算才不受透明边干扰。
        return trimTransparent(sliced)
    }

    /**
     * 裁掉位图四周的透明边，只留「墨水 + [INK_MARGIN_PX] 余量」。
     *
     * ## 为什么抓完图还要再裁一刀
     *
     * math.html 的 `#out` / `.body` 都带了四边安全 padding——
     * getBoundingClientRect() 量的是排版盒，而斜体修正、分式横线、
     * 分式分子/分母、根号钩的墨水会溢出盒外，不留边就会被切
     * （v0.0.30 真机事故：`\{x_n\}` 右花括号整个消失）。
     * 所以抓图阶段必须留边。
     *
     * 但带着透明边去排版有两个问题：
     * 1. 行内公式高度受 `INLINE_MATH_MAX_HEIGHT_RATIO` 上限约束，
     *    透明边撑高位图，会把公式整体压小（竖向 padding 尤其明显）；
     * 2. 左侧透明边把公式推离正文，行内基线对不齐。
     *
     * 因此分工是：**JS 侧负责「抓图不被切」，Kotlin 侧负责「排版不胖」**。
     * 这里按不透明像素包围盒把透明边裁掉，裁完的位图紧贴墨水。
     * [MathLayout.fit] 按「目标字号 / fontPx」换算，与位图实际尺寸无关，
     * 所以裁剪对字号、对 PDF 打印都没有影响。
     *
     * 全透明时原样返回——调用方（capture / captureSheet）已经判过空白，
     * 这里是双保险，不重复回收。
     */
    private fun trimTransparent(src: Bitmap, marginPx: Int = INK_MARGIN_PX): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val box = inkBoxOf(pixels, w, h) ?: return src
        val left = (box.left - marginPx).coerceAtLeast(0)
        val top = (box.top - marginPx).coerceAtLeast(0)
        val right = (box.right + marginPx).coerceAtMost(w - 1)
        val bottom = (box.bottom + marginPx).coerceAtMost(h - 1)
        val newW = right - left + 1
        val newH = bottom - top + 1
        // 已经紧贴墨水（只剩余量）就不裁了，省一次位图分配
        if (newW == w && newH == h) return src
        return runCatching { Bitmap.createBitmap(src, left, top, newW, newH) }
            .getOrNull()
            ?.also { src.recycle() }
            ?: src
    }

    companion object {
        private const val TAG = "MathRenderer"
        private const val ASSET_URL = "file:///android_asset/katex/math.html"
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_BITMAP_EDGE = 2048
        private const val PAGE_LOAD_TIMEOUT_MS = 8_000L
        /**
         * 字体就绪桥的兜底超时（onPageFinished 之后多久没桥回调也放行）。
         * 本地 file:// 字体加载通常 <100ms，3 秒足够；超时只意味着降级到
         * 旧行为（可能量到回退字体度量），不会卡死渲染链路。
         */
        private const val FONTS_BRIDGE_TIMEOUT_MS = 3_000L
        private const val TOLERANCE = 12
        private const val DEFAULT_FONT_PX = 112f
    private const val MATHML_CACHE_ENTRIES = 400
        // 单位是设备像素；换算成 CSS 视口约 730px @3x，足够放下常规公式
        // 视口要足够宽：aligned 公式块可能很长，宽度不够就会触发字号缩小甚至裁切。
        // 高度给足，一块多行公式才不会被切掉。
        private const val VIEWPORT_WIDTH = 3200
        private const val VIEWPORT_HEIGHT = 1600

        /**
         * 批量渲染后等待的帧数。
         * 2 帧足够 KaTeX 完成排版与光栅化；再多只是白等。
         */
        private const val BATCH_FRAME_WAIT = 2

        /**
         * 批量渲染单次最多处理多少个公式。
         * 超出就分批：一张长卷位图按 ARGB_8888 算是 width×height×4 字节，
         * 公式多了长卷会高到吃内存并触发 GC，反而比多次小渲染更慢。
         */
        /**
     * 一批渲染多少条公式。
     *
     * 原值 12，配套注释假设「每条约 200 设备像素，4096 够用」。
     * 实际带分式、嵌套指数的公式能到 400~500 设备像素，
     * 12 条就是 4800~6000，**超出视口上限**——后面几条被裁掉，
     * 而旧代码把残缺位图当完整结果返回（用户报「大量公式显示不全」）。
     *
     * 降到 6：最坏情况 6x500 = 3000，留出余量不再溢出。
     * 代价是 WebView 往返次数翻倍，但批量本来就是为性能做的，
     * 而「少渲染一点」比「渲染出一堆错的」好得多。
     */
    private const val BATCH_LIMIT = 6

        /** 批量长卷视口上限。12 个公式 × ~200 设备像素 + 余量，4096 绰绰有余。 */
        private const val BATCH_VIEWPORT_HEIGHT = 4096

        /**
         * 裁透明边后保留的余量（设备像素）。
         *
         * 2 设备像素约 0.7 CSS px：刚好盖住抗锯齿的半透明边缘，
         * 又不至于把安全 padding 的收益全吃掉。
         * 调大 → 位图边缘留白变多；调小 → 可能剩一圈毛边。
         */
        private const val INK_MARGIN_PX = 2

        /**
         * 浅色主题的公式前景色（墨黑），与 math.html 里 CSS 默认值一致。
         * 保持原值不动：浅色模式下公式一直是这个色，不能因改造而变色。
         */
        const val FG_LIGHT = 0xFF1B1F26.toInt()

        /**
         * 深色主题的公式前景色，取设计系统的 PaperTextPrimaryDark（柔和月白）。
         * 抠图是透明背景，深色模式下必须用亮色，否则深字贴深底 = 用户看到空白。
         */
        const val FG_DARK = 0xFFECE9E2.toInt()

        private const val FG_CSS_LIGHT = "#1B1F26"
    }
}

    /**
 * 取出「能立刻显示」的那部分渲染结果。
 *
 * ## 为什么要单独提供
 *
 * 界面上「公式出图」和「公式算完」是两件事，但原来只有后者：
 * `renderAll` 要等**全部**条目都处理完才返回，调用方才能拿到 map。
 * 于是「10 条里 8 条早就命中缓存」的场景下，
 * 用户要陪那 8 条一起等剩下 2 条走完 WebView 往返。
 *
 * 这个函数只做**纯内存查表**：不渲染、不进 WebView、**不抢互斥锁**，
 * 所以调用方可以先 `value = cachedOf(...)` 把命中部分立刻画出来，
 * 未命中的部分走占位，等 `renderAll` 回来再补。
 *
 * ## 只收非空
 *
 * `null` 是「这条渲染失败」的结论，属于**要重试**的状态。
 * 提前把 null 钉给界面，会让本可重试成功的公式永远停在源码。
 * 所以未命中与失败的条目一律**不出现**在结果里，由调用方按缺省处理。
 *
 * @param items 与 [MathRenderer.renderAll] 同一形状；只用 key，另两项忽略
 * @return key -> 已渲染好的结果，按传入顺序；未命中的 key 不出现
 */
fun <T : Any> cachedOf(
    cache: (String) -> T?,
    items: List<Triple<String, String, Boolean>>
): Map<String, T> {
    val out = linkedMapOf<String, T>()
    items.forEach { (key, _, _) ->
        // 缓存里存 null 与「没这个 key」语义不同，但对调用方都是「拿不到可用结果」，
        // 所以一律跳过：null 是「渲染失败、要重试」的结论，
        // 提前钉给界面会让本可重试成功的公式永远停在源码。
        cache(key)?.let { out[key] = it }
    }
    return out
}

/**
 * 把「还没渲染的」那批 key 切成若干批。
 *
 * ## 为什么切批放在缓存过滤之后
 *
 * 原来 `renderAll` 是**先切批、再让每批各自过滤缓存**。
 * 于是「10 条里 8 条命中缓存」也被摊成 `6 + 4` 两批，
 * 两批都要抢一次互斥锁、进一次 WebView——哪怕后一批里全是命中项，
 * 也要跑一趟才发现「都不用画」。
 *
 * 改成先滤后切：命中项不进 `pending`，批大小就是**真正要画的条数**。
 *
 * 抽成纯函数是为了能测（见 `CachedOfTest`）。
 *
 * @return 每批非空；空输入返回空列表
 */
fun splitPending(keys: List<String>, batchLimit: Int): List<List<String>> {
    if (keys.isEmpty()) return emptyList()
    val limit = batchLimit.coerceAtLeast(1)
    val out = ArrayList<List<String>>()
    var i = 0
    while (i < keys.size) {
        out.add(keys.subList(i, minOf(i + limit, keys.size)))
        i += limit
    }
    return out
}

/**
 * 采样判断「这张位图到底画没画出东西」。
 *
 * ## 为什么是采样而不是逐点
 *
 * 这个判据要回答的只有一个问题：**有没有画出来**。
 * 而原来的 [alphaStats] 为了回答它，把整张图逐点 `getPixel` 了一遍——
 * 长卷最坏 838 万次 JNI 调用，在主线程上，为一个布尔结论。
 *
 * 采样把这一项从「与面积成正比」降到「与网格数成正比」，
 * 后者最多 `48 × 48 = 2304` 次。**量级上降三个数量级，且不随公式变大而恶化。**
 *
 * ## 为什么不会漏判
 *
 * 公式框的尺寸是 JS 侧量出来的真实元素尺寸，内容（字形）占框内相当比例，
 * 不是一条 1px 的细线。48×48 的网格对这类内容几乎不可能整个落空。
 *
 * 但**这确实是拿一点理论精度换性能**，不能当成无损替换：
 * 若真机出现「公式被判空白而不显示」，正确做法是**把网格调密**，
 * 而不是把全像素统计放回常规路径——那会把两个数量级的收益吐回去。
 *
 * @param alphaAt 取某点的 alpha 值（0..255）。抽成参数是因为
 *   `Bitmap` 在 JVM 单测里是空壳，没法在测试里造出来，
 *   而**测试里复制一份实现就等于没有测试**。
 */
fun isBlankBySampling(
    width: Int,
    height: Int,
    alphaAt: (x: Int, y: Int) -> Int
): Boolean {
    if (width <= 0 || height <= 0) return true
    val stepX = (width / BLANK_SAMPLE_GRID).coerceAtLeast(1)
    val stepY = (height / BLANK_SAMPLE_GRID).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (alphaAt(x, y) > 0) return false
            x += stepX
        }
        y += stepY
    }
    return true
}

/**
 * 空白判定的采样网格上限。
 *
 * 48 是权衡值：公式字形在框内占比高，再密收益已经很小；
 * 再疏则开始有漏判风险。**真机出现漏判时先调这里。**
 */
const val BLANK_SAMPLE_GRID = 48

/**
 * 位图里「有墨水」的像素包围盒，四个边界都是闭区间。
 *
 * alpha > 0 即算墨水：抗锯齿边缘、半透明字形都要算进来，
 * 否则裁完还会剩一圈可见的毛边。
 */
data class InkBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 在像素数组里求 alpha > 0 的包围盒。全透明（或数组装不下这张图）返回 null。
 *
 * ## 为什么是纯函数
 *
 * 和 [isBlankBySampling] 同一动机：`Bitmap` 在 JVM 单测里是空壳，
 * 造不出来。所以这里只收 `IntArray`（`getPixels` 的产物），
 * 测试直接造数组驱动——**而不是在测试里复制一份裁剪实现**，
 * 那等于没有测试。
 *
 * ## 扫描顺序
 *
 * 先逐行定上下边界（找到即停），再在已定行范围内逐列定左右。
 * 最坏 2×w×h 次比较；公式位图通常几十万像素，且整条渲染链路里
 * 每个公式只跑一次，不进绘制循环。
 *
 * @param pixels ARGB_8888 的 `getPixels` 结果，长度必须 ≥ width × height
 */
fun inkBoxOf(pixels: IntArray, width: Int, height: Int): InkBox? {
    if (width <= 0 || height <= 0) return null
    if (pixels.size < width * height) return null

    fun rowHasInk(y: Int): Boolean {
        val start = y * width
        var x = 0
        while (x < width) {
            if (pixels[start + x] ushr 24 != 0) return true
            x++
        }
        return false
    }

    // 上边界：第一行有墨水的
    var top = -1
    var y = 0
    while (y < height) {
        if (rowHasInk(y)) {
            top = y
            break
        }
        y++
    }
    if (top < 0) return null // 整张图没有一个不透明像素

    // 下边界：最后一行有墨水的
    var bottom = -1
    y = height - 1
    while (y >= top) {
        if (rowHasInk(y)) {
            bottom = y
            break
        }
        y--
    }

    // 左右边界只需要在 [top, bottom] 行范围内扫
    var left = -1
    var x = 0
    while (x < width) {
        var yy = top
        while (yy <= bottom) {
            if (pixels[yy * width + x] ushr 24 != 0) {
                left = x
                break
            }
            yy++
        }
        if (left >= 0) break
        x++
    }

    var right = -1
    x = width - 1
    while (x >= left) {
        var yy = top
        while (yy <= bottom) {
            if (pixels[yy * width + x] ushr 24 != 0) {
                right = x
                break
            }
            yy++
        }
        if (right >= 0) break
        x--
    }
    return InkBox(left, top, right, bottom)
}
