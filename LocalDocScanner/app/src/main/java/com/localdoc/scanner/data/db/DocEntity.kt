package com.localdoc.scanner.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "docs", indices = [Index("updatedAt")])
data class DocEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val sizeBytes: Long,
    val folder: String? = null,
    val tags: String = "",
    val ocrText: String = "",
    val locked: Boolean = false,
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "") val coverPath: String = "",
    @ColumnInfo(defaultValue = "''") val ocrCorrection: String = "",
    @ColumnInfo(defaultValue = "''") val ocrLegacyText: String = ""
)

@Entity(tableName = "pages", indices = [Index("docId")])
data class PageEntity(
    @PrimaryKey val id: String,
    val docId: String,
    val pageIndex: Int,
    val filePath: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(defaultValue = "") val sourcePath: String = "",
    @ColumnInfo(defaultValue = "0") val quarterTurns: Int = 0,
    @ColumnInfo(defaultValue = "") val cropPoints: String = "",
    @ColumnInfo(defaultValue = "'AUTO'") val filter: String = "AUTO",
    @ColumnInfo(defaultValue = "0") val brightness: Float = 0f,
    @ColumnInfo(defaultValue = "1") val contrast: Float = 1f,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "") val ocrText: String = "",
    @ColumnInfo(defaultValue = "") val ocrMode: String = "",
    @ColumnInfo(defaultValue = "0") val ocrUpdatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val fineRotation: Float = 0f,
    @ColumnInfo(defaultValue = "''") val ocrLayout: String = ""
)
