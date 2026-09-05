package com.example.util

object DomainUtils {
    /**
     * Checks if the given address or URL corresponds to a local or private network address:
     * - localhost, 127.0.0.1, ::1
     * - Class A: 10.0.0.0/8 (starts with 10.)
     * - Class B: 172.16.0.0/12 (172.16. to 172.31.)
     * - Class C: 192.168.0.0/16 (starts with 192.168.)
     */
    fun isLocalAddress(raw: String): Boolean {
        var s = raw.trim()
        if (s.isBlank()) return false

        // Quick check for direct loopback tokens
        val direct = s.lowercase()
        if (direct == "localhost" || direct == "127.0.0.1" || direct == "::1" || direct == "[::1]") {
            return true
        }

        // Strip schemes
        if (s.startsWith("http://", ignoreCase = true)) {
            s = s.substring(7).trim()
        } else if (s.startsWith("https://", ignoreCase = true)) {
            s = s.substring(8).trim()
        }

        // Strip path, query, and userinfo if present
        s = s.substringBefore("/").substringBefore("?").substringAfter("@").trim()

        // Handle IPv6 bracket format e.g. [::1]:8080 or [::1]
        if (s.startsWith("[")) {
            val ipv6 = s.substringAfter("[").substringBefore("]").trim()
            if (ipv6 == "::1" || ipv6.equals("localhost", ignoreCase = true)) {
                return true
            }
        }

        // Strip port
        val host = s.substringBefore(":").trim().lowercase()

        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return true
        }

        // Class A: 10.x.x.x
        if (host == "10" || host.startsWith("10.")) {
            return true
        }

        // Class C: 192.168.x.x
        if (host == "192.168" || host.startsWith("192.168.") || host.startsWith("192.168")) {
            return true
        }

        // Class B: 172.16.0.0 to 172.31.255.255
        if (host.startsWith("172.")) {
            val secondOctet = host.substringAfter("172.").substringBefore(".").toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) {
                return true
            }
        }

        return false
    }

    /**
     * Extracts a clean, capitalized domain title from a website URL.
     * e.g. "vault.bitwarden.com" -> "Bitwarden"
     * e.g. "https://www.google.com/search" -> "Google"
     * If the website is local/private, returns "Local Address".
     * If the website is blank or invalid, falls back to the provided title or "سایر".
     */
    fun extractDomainTitle(website: String, fallbackTitle: String = ""): String {
        val trimmed = website.trim()
        if (isLocalAddress(trimmed)) {
            return "Local Address"
        }

        try {
            var clean = trimmed
            if (clean.isBlank()) {
                val fallback = fallbackTitle.trim()
                if (isLocalAddress(fallback)) {
                    return "Local Address"
                }
                return if (fallback.isNotBlank()) fallback else "سایر"
            }
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                clean = "https://$clean"
            }
            val uri = java.net.URI(clean)
            var host = uri.host ?: clean.removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore("?").substringBefore(":")
            host = host.trim().lowercase()
            if (isLocalAddress(host)) {
                return "Local Address"
            }
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
        if (isLocalAddress(fallback)) {
            return "Local Address"
        }
        return if (fallback.isNotBlank()) fallback else "سایر"
    }
}
