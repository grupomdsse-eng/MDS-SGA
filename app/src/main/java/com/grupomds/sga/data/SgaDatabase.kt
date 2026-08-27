package com.grupomds.sga.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProductEntity::class,
        DeliveryNoteEntity::class,
        DeliveryLineEntity::class,
        ScanLogEntity::class,
        TransportLabelEntity::class,
        StockMovementEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SgaDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun stockMovementDao(): StockMovementDao

    companion object {
        @Volatile
        private var instance: SgaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN sheetStock INTEGER")
                db.execSQL("UPDATE products SET sheetStock = stock WHERE sheetStock IS NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transport_labels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        barcode TEXT NOT NULL,
                        scannedAt INTEGER NOT NULL,
                        FOREIGN KEY(noteId) REFERENCES delivery_notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transport_labels_noteId ON transport_labels(noteId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transport_labels_noteId_barcode ON transport_labels(noteId, barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transport_labels_scannedAt ON transport_labels(scannedAt)")
            }
        }

        fun get(context: Context): SgaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SgaDatabase::class.java,
                "sga_mds.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
