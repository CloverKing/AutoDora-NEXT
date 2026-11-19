package com.autodora.next.di

import android.content.Context
import com.autodora.next.data.SettingsRepository
import com.autodora.next.touch.AccessibilityTouchExecutor
import com.autodora.next.touch.TouchExecutor
import com.autodora.next.vision.BoardRecognizer
import com.autodora.next.vision.HybridBoardRecognizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository {
        return SettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideBoardRecognizer(@ApplicationContext context: Context): BoardRecognizer {
        return HybridBoardRecognizer(context)
    }

    @Provides
    @Singleton
    fun provideTouchExecutor(): TouchExecutor {
        return AccessibilityTouchExecutor()
    }
}
