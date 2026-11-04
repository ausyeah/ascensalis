package com.mistakebook.data.local

/**
 * 一行「说过的话」（v0.0.108），给记忆整理用。
 *
 * 只带角色和正文：整理记忆不需要时间、附件、状态，
 * 多查一列只会让这段查询更贵。
 */
data class RecentSpoken(
    val role: String,
    val content: String
)