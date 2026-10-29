package com.mistakebook.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.data.local.entities.ChatSession
import com.mistakebook.data.local.entities.Question
import com.mistakebook.data.repos.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 会话列表的一行。会话与题目的关联要一次查出来，不能在 Composable 里逐条查库。 */
data class ChatListItemUi(
    val session: ChatSession,
    val questionTitle: String?
)

data class ChatListUiState(
    val loading: Boolean = true,
    val items: List<ChatListItemUi> = emptyList()
)

class ChatListViewModel(
    private val repository: ChatRepository,
    private val questionDao: com.mistakebook.data.local.QuestionDao
) : ViewModel() {

    private val _state = MutableStateFlow(ChatListUiState())
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSessions().combine(questionDao.observeTitles()) { sessions, rows ->
                val titles = rows.associate { it.id to it.title }
                ChatListUiState(
                    loading = false,
                    items = sessions.map { session ->
                        ChatListItemUi(
                            session = session,
                            questionTitle = session.questionId?.let { titles[it] }
                        )
                    }
                )
            }.collect { _state.value = it }
        }
    }

    /**
     * 首页/列表页进入「新对话」。
     *
     * **在这里就把会话建出来**，然后带着它的 id 进聊天页。
     *
     * 早先是 `onReady(null)`——不建会话，靠聊天页的
     * `sessionForQuestion(null)` 现场建。问题在于那条查询会复用**已有的空会话**，
     * 于是点「新对话」可能落进你半小时前开了没说话的那条；
     * 而且聊天页拿到的 id 事后才知道，没法用来做 ViewModel 的 key。
     *
     * 建好再带 id 过去，两件事都解决了：`sessionForQuestion` 只在没给 id 时才兜底创建。
     */
    fun startFreeSession(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.findEmptyFreeSession()?.id ?: repository.createFreeSession()
            onReady(id)
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repository.renameSession(id, title) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.softDeleteSession(id) }
    }

    /** 撤销删除（v0.0.66）：软删不再删附件，恢复即完整还原。 */
    fun restore(id: Long) {
        viewModelScope.launch { repository.restoreSession(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.softDeleteAllSessions() }
    }
}
