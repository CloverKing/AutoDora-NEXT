package com.autodora.next.di

import android.content.Context
import com.autodora.next.data.SettingsRepository
import com.autodora.next.data.StageWaveScriptRepository
import com.autodora.next.data.TeamPresetRepository
import com.autodora.next.data.TrainingDataRepository
import com.autodora.next.touch.AccessibilityTouchExecutor
import com.autodora.next.touch.TouchExecutor
import com.autodora.next.vision.BoardRecognizer
import com.autodora.next.vision.EnemyIconOnnxClassifier
import com.autodora.next.vision.EnemySkillKnowledgeMatcher
import com.autodora.next.vision.HotReloadEnemySkillParser
import com.autodora.next.vision.HybridBoardRecognizer
import com.autodora.next.vision.PaddleOcrOnnxEngine
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
    fun provideTeamPresetRepository(@ApplicationContext context: Context): TeamPresetRepository {
        return TeamPresetRepository.create(context)
    }

    @Provides
    @Singleton
    fun provideTrainingDataRepository(@ApplicationContext context: Context): TrainingDataRepository {
        return TrainingDataRepository.create(context)
    }

    @Provides
    @Singleton
    fun provideStageWaveScriptRepository(@ApplicationContext context: Context): StageWaveScriptRepository {
        return StageWaveScriptRepository.create(context)
    }

    @Provides
    @Singleton
    fun provideBoardRecognizer(@ApplicationContext context: Context): BoardRecognizer {
        return HybridBoardRecognizer(context)
    }

    @Provides
    @Singleton
    fun providePaddleOcrOnnxEngine(@ApplicationContext context: Context): PaddleOcrOnnxEngine {
        return PaddleOcrOnnxEngine(context)
    }

    @Provides
    @Singleton
    fun provideEnemyIconOnnxClassifier(@ApplicationContext context: Context): EnemyIconOnnxClassifier {
        return EnemyIconOnnxClassifier(context)
    }

    @Provides
    @Singleton
    fun provideHotReloadEnemySkillParser(@ApplicationContext context: Context): HotReloadEnemySkillParser {
        return HotReloadEnemySkillParser(context)
    }

    @Provides
    @Singleton
    fun provideEnemySkillKnowledgeMatcher(@ApplicationContext context: Context): EnemySkillKnowledgeMatcher {
        return EnemySkillKnowledgeMatcher.loadFromContext(context)
    }

    @Provides
    @Singleton
    fun provideTouchExecutor(): TouchExecutor {
        return AccessibilityTouchExecutor()
    }
}
