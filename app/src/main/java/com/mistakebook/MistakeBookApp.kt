package com.mistakebook

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.mistakebook.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Application 入口：通知渠道、依赖容器、冷启动时的残留任务清理与学科预置。
 */
class MistakeBookApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        createReviewChannel()
        createWordbookChannel()
        container.appScope.launch {
            container.captureTaskRepository.failUnfinished(RecognitionEngineInterruptedMessage)
        }
        // T10 徽章判定：启动时跑一轮轻量判定，把新解锁徽章持久化
        container.appScope.launch {
            runCatching { container.badgeRepository.evaluateAndStore() }
        }
    }

    private fun createReviewChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_REVIEW,
                getString(R.string.channel_review_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_review_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 单词学习目标提醒渠道。
     *
     * 独立渠道而不是复用错题复习渠道：两条提醒的触发条件完全不同
     * （「有到期错题」vs「今天没达标」），混在一个渠道里用户没法
     * 单独关掉其中一条。
     */
    private fun createWordbookChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_WORDBOOK,
                getString(R.string.channel_wordbook_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_wordbook_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_REVIEW = "review"
        const val CHANNEL_WORDBOOK = "wordbook"
        const val RecognitionEngineInterruptedMessage = "任务被中断"

        @Volatile
        private var instance: MistakeBookApp? = null

        /** 进程内单例访问（小组件刷新等无 Context 场景用）；未初始化返回 null。 */
        fun instanceOrNull(): MistakeBookApp? = instance

        fun from(context: Context): MistakeBookApp =
            instance ?: (context.applicationContext as MistakeBookApp)
    }
}
