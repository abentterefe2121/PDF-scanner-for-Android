package com.pdfscanner.app.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room DB — documents + FTS search.
 * FTS4 tokenizer note: default tokenizer splits on non-alphanumeric,
 * which can break Ethiopic words. Phase 4 adds a custom tokenizer
 * that treats Ethiopic block (U+1200-U+137F) as word characters.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pdfPath: String,
    val pageCount: Int,
    val ocrText: String = "", // indexed for search
    val folder: String = "Inbox",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE title LIKE '%' || :q || '%' OR ocrText LIKE '%' || :q || '%' ORDER BY updatedAt DESC")
    suspend fun search(q: String): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(entities = [DocumentEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pdf-scanner.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDocumentDao(db: AppDatabase): DocumentDao = db.documentDao()
}
