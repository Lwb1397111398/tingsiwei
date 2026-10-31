package com.tingsiwei.app.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 进程级 OkHttp 底座：LLM 对话 / 模型下载 / 在线朗读三处客户端都由 [base] newBuilder 派生，
 * 连接池与线程池共用一份（newBuilder 不复制池，只覆盖超时等配置），不再各自 build 三套。
 */
object Http {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
}
