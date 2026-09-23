package com.devfahim.upscaler.di

import android.content.Context
import androidx.room.Room
import com.devfahim.upscaler.data.db.AppDatabase
import com.devfahim.upscaler.data.db.JobDao
import com.devfahim.upscaler.data.engine.NcnnInferenceEngine
import com.devfahim.upscaler.data.repository.JobsRepositoryImpl
import com.devfahim.upscaler.data.settings.SettingsDataStore
import com.devfahim.upscaler.data.storage.MediaIO
import com.devfahim.upscaler.data.storage.StorageManager
import com.devfahim.upscaler.domain.repository.InferenceEngine
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InferenceDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "upscaler.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideJobDao(db: AppDatabase): JobDao = db.jobDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Dedicated single-thread dispatcher for native inference. ncnn engines
     * are internally multi-threaded (CPU) / GPU-parallel, so one job at a
     * time per process keeps memory predictable and phones thermally sane.
     */
    @Provides
    @InferenceDispatcher
    fun provideInferenceDispatcher(): CoroutineDispatcher =
        kotlinx.coroutines.newSingleThreadContext("ncnn-inference")

    @Provides
    @Singleton
    fun provideStorageManager(@ApplicationContext context: Context): StorageManager =
        StorageManager(context)

    @Provides
    @Singleton
    fun provideMediaIO(@ApplicationContext context: Context): MediaIO = MediaIO(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsDataStore): SettingsRepository = impl

    @Provides
    @Singleton
    fun provideJobsRepository(impl: JobsRepositoryImpl): JobsRepository = impl

    @Provides
    @Singleton
    fun provideInferenceEngine(impl: NcnnInferenceEngine): InferenceEngine = impl

    // WdnInterpolator + ModelAssetManager are @Singleton classes with
    // @Inject constructors - Hilt builds them without module entries.
}
