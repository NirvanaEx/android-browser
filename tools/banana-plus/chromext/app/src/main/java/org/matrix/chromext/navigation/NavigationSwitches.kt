package org.matrix.chromext.navigation

/** Preserve all unrelated flags; only require navigation checks to finish before network I/O. */
object NavigationSwitches {
  const val FEATURE = "AsyncNavigationIntercept"
  private fun featureName(value: String) = value.substringBefore('<').substringBefore(':')
  fun disabled(value: String?): String =
      (value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && featureName(it) != FEATURE } + FEATURE).joinToString(",")
  fun enabled(value: String?): String =
      value.orEmpty().split(',').filter { featureName(it.trim()) != FEATURE }.joinToString(",")
  fun arguments(input: Array<String>?): Array<String> {
    val args = input?.toMutableList() ?: mutableListOf("")
    if (args.isEmpty()) args.add("")
    val end = args.indexOf("--").let { if (it < 0) args.size else it }
    val prefix = args.take(end).toMutableList()
    var disabledFeatures: String? = null
    prefix.removeAll { arg ->
      if (arg.startsWith("--disable-features=")) {
        disabledFeatures = listOfNotNull(disabledFeatures, arg.substringAfter('=')).joinToString(",")
        true
      } else false
    }
    for (i in prefix.indices) {
      if (prefix[i].startsWith("--enable-features=")) prefix[i] = "--enable-features=" + enabled(prefix[i].substringAfter('='))
    }
    prefix.add("--disable-features=" + disabled(disabledFeatures))
    return (prefix + args.drop(end)).toTypedArray()
  }
}
