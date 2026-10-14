package com.localdoc.scanner.models

/** Some file endpoints return 200 for a honoured Range. Full hash is still mandatory. */
object DownloadRanges {
    fun retainsPrefix(status: Int, offset: Long, total: Long, range: String): Boolean {
        require(status == 200 || status == 206)
        val match=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)",RegexOption.IGNORE_CASE).matchEntire(range.trim())
        if (match!=null) {
            val (start,end,size)=match.destructured
            require(start.toLong()==offset && size.toLong()==total && end.toLong() in offset until total) { "下载源返回的文件范围不匹配" }
            return true
        }
        require(status!=206) { "下载源缺少有效的文件范围" }
        // A 200 response is ambiguous: retain tentatively; downloader hashes the complete file
        // and retries from zero on this same source if the body was actually a full response.
        return offset>0
    }
}
