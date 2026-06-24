package com.example

import java.io.BufferedReader
import java.io.StringReader

object M3UParser {
    fun parse(m3uContent: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(StringReader(m3uContent))
        var line: String? = reader.readLine()
        
        var currentName = ""
        var currentLogoUrl = ""
        var currentGroup = "OTHERS"
        
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                // Parse attributes
                currentName = parseAttribute(trimmed, "tvg-name")
                currentLogoUrl = parseAttribute(trimmed, "tvg-logo")
                currentGroup = parseAttribute(trimmed, "group-title")
                
                // If tvg-name is empty, fallback to the display name at the end of the #EXTINF line
                if (currentName.isEmpty()) {
                    val commaIndex = trimmed.lastIndexOf(',')
                    if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                        currentName = trimmed.substring(commaIndex + 1).trim()
                    }
                }
                
                if (currentGroup.isEmpty()) {
                    currentGroup = "OTHERS"
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val streamUrl = trimmed
                val name = if (currentName.isNotEmpty()) currentName else "Channel ${channels.size + 1}"
                channels.add(Channel(
                    name = name,
                    streamUrl = streamUrl,
                    logoUrl = currentLogoUrl,
                    group = currentGroup
                ))
                // Reset for next channel
                currentName = ""
                currentLogoUrl = ""
                currentGroup = "OTHERS"
            }
            line = reader.readLine()
        }
        return channels
    }

    private fun parseAttribute(line: String, attributeName: String): String {
        val pattern = "$attributeName=\""
        val startIndex = line.indexOf(pattern)
        if (startIndex != -1) {
            val valueStart = startIndex + pattern.length
            val endIndex = line.indexOf("\"", valueStart)
            if (endIndex != -1) {
                return line.substring(valueStart, endIndex)
            }
        }
        return ""
    }
}
