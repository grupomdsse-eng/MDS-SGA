package com.grupomds.sga.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [Index(value = ["ean"], unique = true)]
)
data class ProductEntity(
    @PrimaryKey val reference: String,
    val ean: String? = null,
    val description: String,
    val stock: Int = 0,
    val location: String = "",
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "delivery_notes",
    indices = [Index(value = ["number"], unique = true)]
)
data class DeliveryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val customer: String = "",
    val rawOcrText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}

@Entity(
    tableName = "delivery_lines",
    foreignKeys = [
        ForeignKey(
            entity = DeliveryNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("productReference")]
)
data class DeliveryLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val productReference: String,
    val description: String,
    val expectedQty: Int,
    val pickedQty: Int = 0
)

@Entity(
    tableName = "scan_logs",
    foreignKeys = [
        ForeignKey(
            entity = DeliveryNoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("scannedAt")]
)
data class ScanLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val barcode: String,
    val productReference: String? = null,
    val accepted: Boolean,
    val message: String,
    val scannedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "stock_movements",
    indices = [Index("productReference"), Index("createdAt")]
)
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productReference: String,
    val delta: Int,
    val stockAfter: Int,
    val reason: String,
    val deliveryNoteNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PickingSnapshot(
    val note: DeliveryNoteEntity,
    val lines: List<DeliveryLineEntity>,
    val scanLogs: List<ScanLogEntity>
)
