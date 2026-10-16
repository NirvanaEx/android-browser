package org.matrix.chromext.navigation

import java.net.URI
import java.util.Locale

/** Main-document destination policy. No network I/O, URL history or page data. */
class NavigationRules private constructor(
    private val blocks: Set<String>,
    private val exceptions: Set<String>,
) {
  val blockCount: Int get() = blocks.size
  val exceptionCount: Int get() = exceptions.size

  fun blockedHost(address: String): String? {
    val uri = try { URI(address) } catch (_: Exception) { return null }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
    val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
    // GURL has already canonicalized the host. Match DNS labels, never URL substrings.
    if (host.isEmpty() || host.contains(':')) return null
    var suffix = host
    var blocked = false
    while (true) {
      if (suffix in exceptions) return null
      if (suffix in blocks) blocked = true
      val dot = suffix.indexOf('.')
      if (dot < 0) break
      suffix = suffix.substring(dot + 1)
    }
    return if (blocked) host else null
  }

  companion object {
    private val domain = Regex("(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]+")
    fun fromLines(lines: Sequence<String>): NavigationRules {
      val blocks = HashSet<String>()
      val exceptions = HashSet<String>()
      var count = 0
      lines.forEach { raw ->
        val line = raw.trim()
        if (line.isNotEmpty() && !line.startsWith('#')) {
          require(++count <= 150000 && line.length <= 255)
          require(line[0] == '+' || line[0] == '-')
          val host = line.substring(1)
          require(domain.matches(host))
          if (line[0] == '+') blocks.add(host) else exceptions.add(host)
        }
      }
      require(blocks.isNotEmpty())
      return NavigationRules(blocks, exceptions)
    }
  }
}
