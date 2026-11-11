package com.mistakebook.math

/**
 * 常见物理单位换算（精确有理数）。
 *
 * 单位换算是弱模型的另一个高频翻车点——`36 km/h` 换 `m/s` 写成 3.6
 * 或 360 都见过。因子表里全部是**精确分数**（`km/h → m/s` 乘 5/18），
 * 换算不可能出错；能整除的给整数，否则给分数 + 小数参考。
 */
object UnitConvert {

    /** 类别 → 单位 → 换算到该类别基准单位的因子（精确）。 */
    private val categories: Map<String, Map<String, Rational>> = mapOf(
        "length" to mapOf(
            "mm" to Rational.parse("0.001"),
            "cm" to Rational.parse("0.01"),
            "m" to Rational.ONE,
            "km" to Rational.of(1000L),
            "in" to Rational.parse("0.0254"),
            "ft" to Rational.parse("0.3048"),
            "mile" to Rational.parse("1609.344") // 精确定义
        ),
        "mass" to mapOf(
            "mg" to Rational.parse("0.000001"),
            "g" to Rational.parse("0.001"),
            "kg" to Rational.ONE,
            "t" to Rational.of(1000L)
        ),
        "time" to mapOf(
            "ms" to Rational.parse("0.001"),
            "s" to Rational.ONE,
            "min" to Rational.of(60L),
            "h" to Rational.of(3600L)
        ),
        // km/h = 1000m / 3600s = 5/18 (m/s)
        "speed" to mapOf(
            "m/s" to Rational.ONE,
            "km/h" to Rational.of(5L, 18L),
            "km/min" to Rational.of(50L, 3L),
            "mile/h" to Rational.parse("0.44704") // 精确定义
        ),
        "area" to mapOf(
            "cm²" to Rational.parse("0.0001"),
            "m²" to Rational.ONE,
            "km²" to Rational.of(1000000L),
            "公顷" to Rational.of(10000L),
            "亩" to Rational.of(2000L, 3L)
        ),
        "volume" to mapOf(
            "mL" to Rational.parse("0.000001"),
            "L" to Rational.parse("0.001"),
            "m³" to Rational.ONE
        ),
        "energy" to mapOf(
            "J" to Rational.ONE,
            "kJ" to Rational.of(1000L),
            "Wh" to Rational.of(3600L),
            "kWh" to Rational.of(3600000L)
        ),
        "pressure" to mapOf(
            "Pa" to Rational.ONE,
            "kPa" to Rational.of(1000L),
            "MPa" to Rational.of(1000000L),
            "bar" to Rational.of(100000L),
            "atm" to Rational.of(101325L)
        )
    )

    /** 用户可能写的别名 → 表内规范名。 */
    private val aliases: Map<String, String> = mapOf(
        "米" to "m", "千米" to "km", "厘米" to "cm", "毫米" to "mm",
        "千克" to "kg", "克" to "g", "吨" to "t",
        "秒" to "s", "分钟" to "min", "小时" to "h",
        "米/秒" to "m/s", "千米/小时" to "km/h", "千米每小时" to "km/h",
        "升" to "L", "毫升" to "mL",
        "千焦" to "kJ", "焦" to "J", "千瓦时" to "kWh", "度" to "kWh",
        "米2" to "m²", "平方米" to "m²", "平方千米" to "km²"
    )

    fun convert(valueText: String, from: String, to: String): String {
        val value = try {
            Rational.parse(valueText)
        } catch (error: IllegalArgumentException) {
            return "数值「$valueText」解析失败：${error.message}"
        }
        val fromUnit = canonical(from)
            ?: return "不认识的单位「$from」。支持的类别与单位见工具描述。"
        val toUnit = canonical(to)
            ?: return "不认识的单位「$to」。支持的类别与单位见工具描述。"

        val category = categories.entries.firstOrNull { fromUnit in it.value && toUnit in it.value }
            ?: return "「$from」和「$to」不属于同一类单位，不能互换。"

        val result = value * category.value[fromUnit]!!.div(category.value[toUnit]!!)
        val categoryName = when (category.key) {
            "length" -> "长度"; "mass" -> "质量"; "time" -> "时间"
            "speed" -> "速度"; "area" -> "面积"; "volume" -> "体积"
            "energy" -> "能量"; "pressure" -> "压强"
            else -> category.key
        }
        return "$valueText $from = ${result.displayWithDecimal()} $to（$categoryName）"
    }

    private fun canonical(name: String): String? {
        val key = name.trim()
        if (categories.values.any { key in it }) return key
        return aliases[key]
    }
}
