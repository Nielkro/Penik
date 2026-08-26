package niel.kro.penik.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.database.DatabaseEncryption
import niel.kro.penik.data.local.database.PenikDatabase
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.repository.AttachmentManager
import niel.kro.penik.data.repository.SecureTokenStorage
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.crypto.GroupCrypto
import niel.kro.penik.BuildConfig

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DB_NAME = "penik_database"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        tokenStorage: SecureTokenStorage,
    ): PenikDatabase {
        val passphrase = tokenStorage.getOrCreateDatabasePassphrase().toByteArray(Charsets.UTF_8)

        // Convert any pre-existing plaintext database before Room opens it, so
        // group keys and cached messages are no longer readable on-disk.
        DatabaseEncryption.migratePlaintextIfNeeded(context, DB_NAME, passphrase)

        return Room.databaseBuilder(
            context,
            PenikDatabase::class.java,
            DB_NAME
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .fallbackToDestructiveMigrationOnDowngrade()
            .fallbackToDestructiveMigration()
            .addMigrations(
                PenikDatabase.MIGRATION_1_2,
                PenikDatabase.MIGRATION_2_3,
                PenikDatabase.MIGRATION_3_4,
                PenikDatabase.MIGRATION_4_5,
                PenikDatabase.MIGRATION_5_6,
                PenikDatabase.MIGRATION_6_7,
                PenikDatabase.MIGRATION_7_8,
                PenikDatabase.MIGRATION_8_9,
            )
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("UPDATE messages SET timestamp = timestamp * 1000 WHERE timestamp < 10000000000")
                    db.execSQL("UPDATE chats SET lastMessageTimestamp = lastMessageTimestamp * 1000 WHERE lastMessageTimestamp > 0 AND lastMessageTimestamp < 10000000000")
                }
            })
            .build()
    }

    @Provides
    fun provideMessageDao(db: PenikDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChatDao(db: PenikDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideGroupDao(db: PenikDatabase): GroupDao = db.groupDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val BASE_URL = niel.kro.penik.data.network.api.ApiConfig.BASE_URL

    /**
     * Resolves the OkHttp logging verbosity from the build type.
     *
     * Full BODY logging captures the Authorization bearer token, passwords,
     * encrypted key backups and message metadata, so it must never be active
     * in a release build. Only debug builds emit request/response bodies.
     */
    internal fun httpLogLevel(isDebug: Boolean): HttpLoggingInterceptor.Level =
        if (isDebug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenStorage: SecureTokenStorage,
        webSocketManager: WebSocketManager
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request()
                val host = niel.kro.penik.data.network.api.ApiConfig.HOST
                val port = niel.kro.penik.data.network.api.ApiConfig.PORT
                val scheme = niel.kro.penik.data.network.api.ApiConfig.SCHEME

                val newUrl = orig.url.newBuilder()
                    .scheme(scheme)
                    .host(host)
                    .port(port)
                    .build()

                val request = orig.newBuilder().url(newUrl)
                val token = tokenStorage.getToken()
                if (token != null) {
                    request.header("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = httpLogLevel(BuildConfig.DEBUG)
            })
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful) {
                    webSocketManager.notifyRestSuccess()
                } else if (response.code == 401) {
                    webSocketManager.notifyUnauthorized()
                }
                response
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(client: OkHttpClient, json: Json): ApiService {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideE2EECrypto(): E2EECrypto {
        return E2EECrypto()
    }

    @Provides
    @Singleton
    fun provideGroupCrypto(e2eeCrypto: E2EECrypto): GroupCrypto {
        return GroupCrypto(e2eeCrypto)
    }

    @Provides
    @Singleton
    fun provideAttachmentManager(
        apiService: ApiService,
        e2eeCrypto: E2EECrypto
    ): AttachmentManager {
        return AttachmentManager(apiService, e2eeCrypto)
    }
}
