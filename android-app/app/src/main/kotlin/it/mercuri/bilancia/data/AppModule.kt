package it.mercuri.bilancia.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.mercuri.bilancia.data.db.AppDatabase
import it.mercuri.bilancia.data.db.WeighingDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "weighai.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()

    @Provides
    fun provideWeighingDao(db: AppDatabase): WeighingDao = db.weighingDao()
}
