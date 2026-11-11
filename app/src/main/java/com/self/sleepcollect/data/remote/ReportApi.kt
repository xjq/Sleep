package com.self.sleepcollect.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface ReportApi {
    /**
     * [url] 为用户填写的完整端点，覆盖 Retrofit 占位 baseUrl。
     */
    @POST
    suspend fun postReport(@Url url: String, @Body body: CollectReport): Response<ResponseBody>
}
