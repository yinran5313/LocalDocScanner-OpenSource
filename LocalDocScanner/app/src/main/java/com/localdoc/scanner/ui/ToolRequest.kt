package com.localdoc.scanner.ui

import com.localdoc.scanner.model.ToolEntry
import java.io.File

data class ToolRequest(val tool: ToolEntry, val files: List<File>, val names: List<String>)
