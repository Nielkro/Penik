package niel.kro.penik.data.network.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
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

    @POST("pairing/sessions/claim")
    suspend fun claimPairingSession(@Body body: PairingClaimRequest): Response<PairingClaimResponse>
    @GET("pairing/sessions/{id}") suspend fun getPairingSession(@Path("id") id: String): Response<PairingStateResponse>

    /* ── Groups ── */

    @POST("groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): Response<GroupResponse>

    @GET("groups")
    suspend fun listGroups(): Response<GroupListResponse>

    @GET("groups/{groupId}")
    suspend fun getGroup(@Path("groupId") groupId: Long): Response<GroupResponse>

    @PATCH("groups/{groupId}")
    suspend fun renameGroup(@Path("groupId") groupId: Long, @Body body: RenameGroupRequest): Response<Unit>

    @DELETE("groups/{groupId}")
    suspend fun deleteGroup(@Path("groupId") groupId: Long): Response<Unit>

    @GET("groups/{groupId}/members")
    suspend fun listGroupMembers(@Path("groupId") groupId: Long): Response<GroupMembersResponse>

    @POST("groups/{groupId}/members")
    suspend fun inviteGroupMember(@Path("groupId") groupId: Long, @Body body: InviteMemberRequest): Response<Unit>

    @DELETE("groups/{groupId}/members/{userId}")
    suspend fun removeGroupMember(@Path("groupId") groupId: Long, @Path("userId") userId: Long): Response<Unit>

    @PATCH("groups/{groupId}/members/{userId}")
    suspend fun changeGroupMemberRole(@Path("groupId") groupId: Long, @Path("userId") userId: Long, @Body body: ChangeRoleRequest): Response<Unit>

    @POST("groups/{groupId}/accept")
    suspend fun acceptGroupInvitation(@Path("groupId") groupId: Long): Response<Unit>

    @POST("groups/{groupId}/decline")
    suspend fun declineGroupInvitation(@Path("groupId") groupId: Long): Response<Unit>

    @GET("groups/{groupId}/keys")
    suspend fun listGroupKeyVersions(@Path("groupId") groupId: Long): Response<GroupKeyVersionsResponse>

    @GET("groups/{groupId}/keys/{version}")
    suspend fun getGroupEnvelope(@Path("groupId") groupId: Long, @Path("version") version: Long): Response<GroupEnvelopeResponse>

    @POST("groups/{groupId}/keys/{version}/envelopes")
    suspend fun uploadGroupEnvelopes(@Path("groupId") groupId: Long, @Path("version") version: Long, @Body body: UploadEnvelopesRequest): Response<Unit>

    @POST("groups/{groupId}/keys/rotate")
    suspend fun rotateGroupKey(@Path("groupId") groupId: Long): Response<RotateKeyResponse>

    @GET("groups/{groupId}/messages/history")
    suspend fun getGroupHistory(@Path("groupId") groupId: Long, @Query("limit") limit: Int = 100, @Query("before_id") beforeId: Long? = null): Response<GroupHistoryResponse>
}
