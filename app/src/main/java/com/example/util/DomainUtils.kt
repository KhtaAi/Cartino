package com.example.util

object DomainUtils {
    /**
     * Extracts a clean, capitalized domain title from a website URL.
     * e.g. "vault.bitwarden.com" -> "Bitwarden"
     * e.g. "https://www.google.com/search" -> "Google"
     * If the website is blank or invalid, falls back to the provided title or "سایر".
     */
    fun extractDomainTitle(website: String, fallbackTitle: String = ""): String {
        try {
            var clean = website.trim()
            if (clean.isBlank()) {
                val fallback = fallbackTitle.trim()
                return if (fallback.isNotBlank()) fallback else "سایر"
            }
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                clean = "https://$clean"
            }
            val uri = java.net.URI(clean)
            var host = uri.host ?: clean.removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore("?").substringBefore(":")
            host = host.trim().lowercase()
            if (host.startsWith("www.")) {
                host = host.removePrefix("www.")
            }
            host = host.substringBefore(":")
            val parts = host.split(".").filter { it.isNotBlank() }
            val domainName = when {
                parts.isEmpty() -> ""
                parts.size == 1 -> parts[0]
                parts.size == 2 -> parts[0]
                parts.size >= 3 -> {
                    val secondToLast = parts[parts.size - 2]
                    val commonTlds = setOf("com", "co", "org", "net", "gov", "ac", "edu", "id", "ir")
                    if (secondToLast in commonTlds && parts.size >= 3) {
                        parts[parts.size - 3]
                    } else {
                        secondToLast
                    }
                }
                else -> parts[0]
            }
            if (domainName.isNotBlank()) {
                return domainName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        } catch (e: Throwable) {
            // ignore and fallback
        }
        val fallback = fallbackTitle.trim()
        return if (fallback.isNotBlank()) fallback else "سایر"
    }
}
