package com.mistakebook.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条长期记忆（v0.0.108）。
 *
 * ## 为什么是「一条条短句」而不是一整篇文档
 * 记忆的用途只有两个：注入系统提示词、让用户增删。整篇文档在这两件事上都更差——
 * 注入时无法按需裁剪（一篇越长，模型越容易在无关话题上被带偏），
 * 删除时只能整篇重写。一条条短句可以单独删、可以限量注入，
 * 也天然去重（同一条记忆不会因为总结两次就出现两遍）。
 *
 * @param fromModel 由模型整理出来的（界面标一个淡标签），还是用户自己填的。
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val fromModel: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)