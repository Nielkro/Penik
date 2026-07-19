package niel.kro.penik.data.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("register")
    suspend fun register(@Body body: RegisterRequestBody): Response<AuthResponseBody>

    @POST("login")
    suspend fun login(@Body body: LoginRequestBody): Response<AuthResponseBody>

    @GET("users/me")
    suspend fun getMe(): Response<UserSearchResult>

    @GET("users/{userId}")
    suspend fun getUserProfile(@Path("userId") userId: Long): Response<UserSearchResult>

    @GET("users/search")
    suspend fun searchUsers(@Query("q") query: String): Response<List<UserSearchResult>>

    @PATCH("users/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequestBody): Response<Unit>

    @GET("messages/history")
    suspend fun getMessageHistory(
        @Query("limit") limit: Int = 100
    ): Response<List<HistoryMessageResponse>>

    @GET("messages/{userId}/status")
    suspend fun getMessageStatuses(@Path("userId") userId: Long): Response<List<MessageStatusResponse>>

    @GET("keys/bundle/{userId}")
    suspend fun getKeyBundle(@Path("userId") userId: Long, @Query("skip_otk") skipOtk: Boolean = true): Response<KeyBundleResponse>

    @GET("keys/bundle/{userId}")
    suspend fun getKeyBundleSelf(@Path("userId") userId: Long, @Query("skip_otk") skipOtk: Boolean = true): Response<KeyBundleResponse>

    @POST("keys/prekeys")
    suspend fun uploadPreKeys(@Body body: PrekeysUploadRequest): Response<Unit>

    @GET("keys/prekeys/status")
    suspend fun getPreKeysStatus(): Response<PreKeysStatusResponse>
}
