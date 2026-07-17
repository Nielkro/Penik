package niel.kro.penik.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.MessageDao
import niel.kro.penik.data.local.database.PenikDatabase
import niel.kro.penik.data.network.api.ApiService
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
import niel.kro.penik.data.crypto.PreKeyManager


@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PenikDatabase {
        return Room.databaseBuilder(
            context,
            PenikDatabase::class.java,
            "penik_database"
        )
            .addMigrations(PenikDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideMessageDao(db: PenikDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChatDao(db: PenikDatabase): ChatDao = db.chatDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://penik.dev.slavchat.ru/api/v1/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStorage: SecureTokenStorage): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                val token = tokenStorage.getToken()
                if (token != null) {
                    request.header("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
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
    fun providePreKeyManager(
        apiService: ApiService,
        tokenStorage: SecureTokenStorage,
        e2eeCrypto: E2EECrypto
    ): PreKeyManager {
        return PreKeyManager(apiService, tokenStorage, e2eeCrypto)
    }
}
