package com.pdfscanner.app.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
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

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pdfPath: String,
    val pageCount: Int,
    val ocrText: String = "",
    val tableCsv: String = "",
    val folder: String = "Inbox",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Fts4(contentEntity = DocumentEntity::class)
@Entity(tableName = "documents_fts")
data class DocumentFtsEntity(
    val title: String,
    val ocrText: String
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    suspend fun getAll(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE folder = :folder ORDER BY updatedAt DESC")
    suspend fun getByFolder(folder: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DocumentEntity?

    /**
     * Hybrid search: FTS4 token prefix match OR LIKE substring match (ensures
     * Ethiopic partial words always match cleanly across all Android SQLite builds).
     */
    @Query(
        """
        SELECT DISTINCT d.* FROM documents d
        LEFT JOIN documents_fts fts ON d.id = fts.rowid
        WHERE (:folder = 'All' OR d.folder = :folder)
          AND (
            d.title LIKE '%' || :q || '%'
            OR d.ocrText LIKE '%' || :q || '%'
            OR fts.documents_fts MATCH :ftsQuery
          )
        ORDER BY d.updatedAt DESC
        """
    )
    suspend fun search(q: String, ftsQuery: String, folder: String = "All"): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE (:folder = 'All' OR folder = :folder)
          AND (title LIKE '%' || :q || '%' OR ocrText LIKE '%' || :q || '%')
        ORDER BY updatedAt DESC
        """
    )
    suspend fun searchLikeOnly(q: String, folder: String = "All"): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("UPDATE documents SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET folder = :folder, updatedAt = :now WHERE id = :id")
    suspend fun moveToFolder(id: Long, folder: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [DocumentEntity::class, DocumentFtsEntity::class],
    version = 3,
    exportSchema = false
)
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideDocumentDao(db: AppDatabase): DocumentDao = db.documentDao()
}
