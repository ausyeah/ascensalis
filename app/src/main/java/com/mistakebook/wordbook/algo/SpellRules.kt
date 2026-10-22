package com.mistakebook.wordbook.algo

/**
 * 拼写模式的判定规则。
 *
 * 抽成纯函数有两个原因：
 * 1. 判定逻辑要有单测覆盖——拼写判定的边界（大小写、首尾空格、
 *    连字符词）错一位，用户就会觉得「我明明拼对了却说错」；
 * 2. ViewModel 里只留「调规则 → 走通用打分路径」两行，
 *    不掺和字符串处理。
 */
object SpellRules {

    /**
     * 归一化：去首尾空格 + 转小写。
     *
     * 只去首尾空格、不删中间的：手输 "air conditioning"（漏连字符）
     * 与 "air-conditioning" 是两个不同的词，宽松匹配会让拼写练习
     * 失去意义——所以中间的连字符必须打。
     */
    fun normalize(input: String): String = input.trim().lowercase()

    /**
     * 判断用户输入是否拼写正确。
     *
     * 大小写不敏感（手机上输入法首字母常自动大写）、忽略首尾空格，
     * 其余严格比对。空答案永远判错。
     */
    fun isCorrect(input: String, answer: String): Boolean {
        if (answer.isBlank()) return false
        return normalize(input) == normalize(answer)
    }
}
