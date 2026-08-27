package com.grupomds.sga.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE active = 1 ORDER BY reference COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE active = 1 ORDER BY reference COLLATE NOCASE")
    suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE reference = :reference LIMIT 1")
    suspend fun byReference(reference: String): ProductEntity?

    @Query("SELECT * FROM products WHERE ean = :ean LIMIT 1")
    suspend fun byEan(ean: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    @Update
    suspend fun update(product: ProductEntity): Int

    @Query("UPDATE products SET stock = :stock, updatedAt = :updatedAt WHERE reference = :reference")
    suspend fun setStock(reference: String, stock: Int, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE products SET ean = :ean, updatedAt = :updatedAt WHERE reference = :reference")
    suspend fun assignEan(reference: String, ean: String, updatedAt: Long = System.currentTimeMillis()): Int
}

@Dao
interface DeliveryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: DeliveryNoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLines(lines: List<DeliveryLineEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(log: ScanLogEntity)

    @Query("SELECT * FROM delivery_notes WHERE id = :id LIMIT 1")
    suspend fun getNote(id: Long): DeliveryNoteEntity?

    @Query("SELECT * FROM delivery_notes WHERE number = :number LIMIT 1")
    suspend fun getNoteByNumber(number: String): DeliveryNoteEntity?

    @Query("SELECT * FROM delivery_lines WHERE noteId = :noteId ORDER BY id")
    suspend fun getLines(noteId: Long): List<DeliveryLineEntity>

    @Query("SELECT * FROM delivery_lines WHERE noteId = :noteId AND productReference = :reference LIMIT 1")
    suspend fun getLineForProduct(noteId: Long, reference: String): DeliveryLineEntity?

    @Query("SELECT * FROM scan_logs WHERE noteId = :noteId ORDER BY scannedAt DESC, id DESC LIMIT :limit")
    suspend fun getLogs(noteId: Long, limit: Int = 50): List<ScanLogEntity>

    @Query("UPDATE delivery_lines SET pickedQty = pickedQty + 1 WHERE id = :lineId AND pickedQty < expectedQty")
    suspend fun incrementPicked(lineId: Long): Int

    @Query("UPDATE delivery_notes SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, completedAt: Long?): Int

    @Query("SELECT * FROM delivery_notes ORDER BY createdAt DESC")
    fun observeHistory(): Flow<List<DeliveryNoteEntity>>
}

@Dao
interface StockMovementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(movement: StockMovementEntity)

    @Query("SELECT * FROM stock_movements ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<StockMovementEntity>
}
