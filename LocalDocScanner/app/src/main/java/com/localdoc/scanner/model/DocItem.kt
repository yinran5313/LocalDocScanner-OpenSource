package com.localdoc.scanner.model

/** 文档库中的一条记录。多页文档由 Page 组成，此处只保存汇总信息。 */
data class DocItem(
    val id: String,
    val title: String,
    val pageCount: Int,
    val updatedAt: Long,
    val sizeBytes: Long,
    val coverPath: String? = null,
    val folder: String? = null,
    val tags: String = "",
    val ocrText: String = "",
    val createdAt: Long = updatedAt,
    val legacyOcrText: String = ""
)
