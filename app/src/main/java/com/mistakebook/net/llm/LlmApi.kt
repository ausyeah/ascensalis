package com.mistakebook.net.llm

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * 大模型（OpenAI 兼容）接口。Base URL 由用户在设置里配置，因此这里全部走 @Url。
 */
interface LlmApi {

    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>

    /** 测试连接用：HTTP 200 即 Key 有效。 */
    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<ModelsResponse>

    /** 向量嵌入（资料库语义检索用，v0.0.113）：OpenAI 兼容 /v1/embeddings。 */
    @POST
    suspend fun embeddings(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: EmbeddingRequest
    ): Response<EmbeddingResponse>
}
