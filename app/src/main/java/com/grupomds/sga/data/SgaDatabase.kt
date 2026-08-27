package com.grupomds.sga.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProductEntity::class,
        DeliveryNoteEntity::class,
        DeliveryLineEntity::class,
        ScanLogEntity::class,
        StockMovementEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SgaDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun stockMovementDao(): StockMovementDao

    companion object {
        @Volatile
        private var instance: SgaDatabase? = null

        fun get(context: Context): SgaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SgaDatabase::class.java,
                "sga_mds.db"
            ).build().also { instance = it }
        }
    }
}
