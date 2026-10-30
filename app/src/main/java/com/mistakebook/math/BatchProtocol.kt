package com.mistakebook.math

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `math.html` 与 Kotlin 之间的**长卷协议**，集中定义在这一个文件里。
 *
 * ## 为什么必须抽出来
 *
 * 这个协议之前是「JS 推字段、Kotlin 读字段」，两边各写各的，没有共享定义，
 * 也没有任何测试。结果 `fs` 被读成 `f`，取到默认值 0，一直没人发现——
 * 因为：
 *
 * - 编译通过（JSON 键名是字符串，运行期才发现）
 * - 渲染**看起来是正常的**（公式确实画出来了，只是大小不对）
 * - 屏幕上是「稍微有点大」，PDF 里被 `USABLE_WIDTH` 截成整页宽
 *
 * 一条协议如果只能靠肉眼发现对错，那它一定会在某个时刻对错。
 * 所以：**字段名集中定义 + 用测试钉住**，两边都从这里取。
 *
 * ## 为什么用 kotlinx.serialization 而不是 org.json
 *
 * `org.json` 在 Android 单元测试里是**空壳**，方法直接抛
 * 「not mocked」——也就是说这段解析**根本没法测**，
 * 也就等于没有测试保护。改用项目里已经在用的 kotlinx.serialization 之后，
 * 它是纯 Kotlin/JVM 实现，协议解析才真正可测。
 *
 * ## 单位：全部是**设备像素**
 *
 * JS 侧一律乘 `dpr` 后再上报（见 math.html）。
 * 漏乘或错乘都会让切片位置整体偏移——那种错误同样不会报错，
 * 只会表现为「公式显示不全」或「位置不对」。
 */
internal object BatchProtocol {

    /**
     * 剥掉 `evaluateJavascript` 对**字符串**返回值多加的那一层 JSON 编码。
     *
     * `math.html` 里 `render()` / `renderBatch()` / `toMathML()` 都用 `JSON.stringify(...)`
     * 返回（一个 JS 字符串），而 `evaluateJavascript` 会把它再 JSON 编码一次，
     * 回调收到的是形如 `"{\"w\":2016,...}"` 的**字符串字面量**。
     * 不剥这层直接 `parseToJsonElement` 得到的是 `JsonPrimitive`，取 `.jsonObject` 必失败。
     *
     * 已经包成字符串的才剥；本来就是对象的（测试直接喂内部 JSON）原样返回。
     */
    fun unwrap(raw: String): String {
        val text = raw.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return runCatching { json.parseToJsonElement(text).jsonPrimitive.content }
                .getOrDefault(text)
        }
        return text
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 字段名常量。**只在这里定义一次**，JS 侧改字段名时必须同步改这里，
     * 而 [BatchProtocolTest] 会强制两边保持一致。
     */
    object Key {
        const val SHEET_WIDTH = "w"
        const val SHEET_HEIGHT = "h"
        const val ITEMS = "items"

        const val ITEM_KEY = "k"
        const val ITEM_X = "x"
        const val ITEM_Y = "y"
        const val ITEM_WIDTH = "w"
        const val ITEM_HEIGHT = "h"
        /** 渲染时实际使用的字号（设备像素）。**曾被读成 "f"，务必注意。 */
        const val ITEM_FONT_PX = "fs"
        /**
         * 渲染失败标记。**math.html 推的是 `err`，曾被读成 `e`**——
         * 于是批量路径的失败标记一直取不到默认值 0，
         * 界面公式的「渲染失败回退源码」从来没生效过。
         * 和 `fs`→`f` 是同一类错误，同一次协议自检里一起抓出来。
         */
        const val ITEM_ERR = "err"
        const val ITEM_ERR_MSG = "errMsg"
    }

    /**
     * 字号缺失/非法时的兜底。
     *
     * 不能退化成「等于目标字号」——那会让缩放倍数变成 1，
     * 等于把设备像素当版面单位，公式按原生分辨率画（见 KDoc）。
     * 退到一个**已知的渲染字号**才是有意义的兜底。
     */
    const val FALLBACK_FONT_PX = 112f

    /** err 取值：0 正常；1 源文本被 JSON 转义破坏；2 KaTeX 解析失败。 */
    object Err {
        const val NONE = 0
        const val CORRUPTED_SOURCE = 1
        const val KATEX_PARSE_FAILED = 2
    }

    data class Entry(
        val key: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val fontPx: Float,
        val err: Int = Err.NONE,
        val errMsg: String = ""
    )

    data class Layout(val sheetWidth: Int, val sheetHeight: Int, val entries: List<Entry>)

    private fun kotlinx.serialization.json.JsonElement.intOr(key: String): Int =
        runCatching { this.jsonObject[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0

    private fun kotlinx.serialization.json.JsonElement.floatOr(key: String): Float =
        runCatching { this.jsonObject[key]?.jsonPrimitive?.floatOrNull }.getOrNull() ?: FALLBACK_FONT_PX

    /**
     * 解析 `renderBatch` 的返回值。
     *
     * 解析不出来返回 null，让调用方回退源码——**绝不返回半个布局**，
     * 那会让后续切片按错误的坐标走，产出各种看不懂的残缺图。
     */
    fun parse(raw: String): Layout? {
        if (raw.isBlank()) return null
        // **必须在 parse 内部解包**，不能指望调用方记得调 [unwrap]。
        //
        // 这个坑踩过两次：
        // 1. 第一次——单条路径 parseSize 处理了 evaluateJavascript 多加的那层 JSON 编码，
        //    新加的批量路径忘了，于是所有公式被判为解析失败、退回 LaTeX 源码，
        //    界面上「公式完全渲染不出来」。
        // 2. 修法是「抽一个 unwrapJavascriptJson 出来共用」——但那等于把「记得调」
        //    交给每个调用方。后来把 `parseBatchLayout` 换成 `BatchProtocol.parse` 时，
        //    解包又漏了，从 v0.0.5 起界面上每一个公式都在走回退路径。
        //
        // 抽公共函数 ≠ 共用：**只有「漏不掉」才算真的共用**。所以解包放进 parse 里。
        val text = unwrap(raw)
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val w = root.intOr(Key.SHEET_WIDTH)
        val h = root.intOr(Key.SHEET_HEIGHT)
        if (w <= 0 || h <= 0) return null
        val items = runCatching { root[Key.ITEMS]?.jsonArray }.getOrNull() ?: return null

        val entries = ArrayList<Entry>(items.size)
        for (element in items) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val width = item.intOr(Key.ITEM_WIDTH)
            val height = item.intOr(Key.ITEM_HEIGHT)
            // 宽高为 0 的条目无法切片，直接剔除，不进列表
            if (width <= 0 || height <= 0) continue
            val fontPx = item.floatOr(Key.ITEM_FONT_PX)
            entries += Entry(
                key = item[Key.ITEM_KEY]?.jsonPrimitive?.content.orEmpty(),
                x = item.intOr(Key.ITEM_X),
                y = item.intOr(Key.ITEM_Y),
                width = width,
                height = height,
                // 非正字号（JS 异常）同样兜底，绝不能让它流到缩放里
                fontPx = if (fontPx > 0f) fontPx else FALLBACK_FONT_PX,
                err = item.intOr(Key.ITEM_ERR),
                errMsg = item[Key.ITEM_ERR_MSG]?.jsonPrimitive?.content.orEmpty()
            )
        }
        return Layout(w, h, entries)
    }
}

/** 与 math.html 的 `render()` 单条协议字段名，单列出来便于对照。 */
internal object SingleProtocol {
    const val RESOURCE_PATH = "katex/math.html"

    const val WIDTH = "w"
    const val HEIGHT = "h"
    const val FONT_PX = "fs"
    const val ERR = "err"
    const val ERR_MSG = "errMsg"
}

/**
 * JS ↔ Kotlin 的 MathML 协议。
 *
 * 与 [BatchProtocol] 同理：字段名**集中定义并用单测钉住**。
 * 这套代码里已经因为字段名漂移静默失效两次（`fs` 被读成 `f`、
 * `err` 被读成 `e`）——两次都不报错，只是特定功能一直不生效。
 */
internal object MathMlProtocol {
    /** JS 返回对象里 MathML 字符串的键。 */
    const val MATHML = "ml"
}
