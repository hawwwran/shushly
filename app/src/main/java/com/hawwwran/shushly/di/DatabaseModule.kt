package com.hawwwran.shushly.di

import android.content.Context
import androidx.room.Room
import com.hawwwran.shushly.core.data.db.AppLearningDao
import com.hawwwran.shushly.core.data.db.DecisionHistoryDao
import com.hawwwran.shushly.core.data.db.MIGRATION_1_2
import com.hawwwran.shushly.core.data.db.MIGRATION_2_3
import com.hawwwran.shushly.core.data.db.ShushlyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Room can't be `@Binds`-bound; it's provided here. [AppModule] stays the abstract @Binds module. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShushlyDatabase =
        Room.databaseBuilder(context, ShushlyDatabase::class.java, "shushly.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    @Singleton
    fun provideDecisionHistoryDao(database: ShushlyDatabase): DecisionHistoryDao =
        database.decisionHistoryDao()

    @Provides
    @Singleton
    fun provideAppLearningDao(database: ShushlyDatabase): AppLearningDao =
        database.appLearningDao()
}
