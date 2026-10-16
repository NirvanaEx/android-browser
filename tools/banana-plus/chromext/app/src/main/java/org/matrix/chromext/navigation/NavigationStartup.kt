package org.matrix.chromext.navigation

import java.lang.reflect.Modifier
import org.matrix.chromext.utils.hookAfter
import org.matrix.chromext.utils.hookBefore

/** Pin-specific CommandLine mapping, verified against Banana's DEX before release. */
object NavigationStartup {
  var configured = false
    private set
  var beforeNative = false
    private set
  fun init(loader: ClassLoader) {
    val base = loader.loadClass("JL")
    val javaLine = loader.loadClass("HL")
    val nativeLine = loader.loadClass("IL")
    require(base.isAssignableFrom(javaLine) && base.isAssignableFrom(nativeLine))
    val get = base.getDeclaredMethod("c")
    val initialize = base.getDeclaredMethod("f", Array<String>::class.java)
    require(Modifier.isStatic(get.modifiers) && get.returnType == base && Modifier.isStatic(initialize.modifiers))
    val read = base.getDeclaredMethod("d", String::class.java)
    val append = base.getDeclaredMethod("b", String::class.java, String::class.java)
    for (cls in listOf(javaLine, nativeLine)) {
      cls.getDeclaredMethod("b", String::class.java, String::class.java).hookBefore {
        when (it.args[0]) {
          "disable-features" -> it.args[1] = NavigationSwitches.disabled(it.args[1] as? String)
          "enable-features" -> it.args[1] = NavigationSwitches.enabled(it.args[1] as? String)
        }
      }
    }
    initialize.hookBefore {
      @Suppress("UNCHECKED_CAST")
      val args = it.args[0] as? Array<String>
      it.args[0] = NavigationSwitches.arguments(args)
    }
    fun ensure() {
      val current = get.invoke(null) ?: return
      val value = read.invoke(current, "disable-features") as? String
      append.invoke(current, "disable-features", NavigationSwitches.disabled(value))
      configured = true
      if (javaLine.isInstance(current)) beforeNative = true
    }
    initialize.hookAfter { ensure() }
    // Covers a preinitialized Java command line too, without files or device-wide flags.
    ensure()
  }
}
