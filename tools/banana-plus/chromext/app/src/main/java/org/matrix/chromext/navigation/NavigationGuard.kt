package org.matrix.chromext.navigation

import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import org.json.JSONObject
import org.matrix.chromext.Resource
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.hookAfter
import org.matrix.chromext.utils.hookBefore

/** Synchronous browser-process hooks, installed before any WindowAndroid/tab navigation. */
object NavigationGuard {
  const val VERSION = "1.0.1"
  private const val ASSET = "assets/banana-navigation-hosts.txt"
  private var rules: NavigationRules? = null
  private var hooks = 0
  private var ready = false
  private var failure: String? = null
  private val checked = AtomicLong()
  private val blocked = AtomicLong()
  private val redirected = AtomicLong()
  private val serverRedirects = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
  private var lastHost: String? = null

  @Synchronized fun init(loader: ClassLoader) {
    if (ready || failure != null) return
    try {
      NavigationStartup.init(loader)
      val path = requireNotNull(Resource.modulePath())
      rules = ZipFile(path).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry(ASSET))).bufferedReader().use {
          NavigationRules.fromLines(it.lineSequence())
        }
      }
      val navigation = loader.loadClass("org.chromium.content_public.browser.NavigationHandle")
      val gurl = loader.loadClass("org.chromium.url.GURL")
      val delegate = loader.loadClass("org.chromium.components.navigation_interception.InterceptNavigationDelegate")
      val spec = gurl.declaredFields.single {
        it.type == String::class.java && !Modifier.isStatic(it.modifiers)
      }.also { it.isAccessible = true }
      val redirectedMethod = navigation.getDeclaredMethod("didRedirect", gurl, Boolean::class.javaPrimitiveType)
      redirectedMethod.hookAfter { serverRedirects.add(it.thisObject) }
      navigation.getDeclaredMethod("release").hookBefore { serverRedirects.remove(it.thisObject) }

      // Pinned Banana 20.15 / Chromium 146: normal tabs, overlay content and web-app tabs.
      // Verify the superclass and full JNI signature; never guess a boolean method by position.
      val methods = listOf("cG0", "Ad1", "lC1").map { name ->
        val cls = loader.loadClass(name)
        require(delegate.isAssignableFrom(cls))
        cls.getDeclaredMethod("shouldIgnoreNavigation", navigation, gurl,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).also {
          require(it.returnType == Boolean::class.javaPrimitiveType && !Modifier.isAbstract(it.modifiers))
        }
      }
      methods.forEach { method ->
        method.hookBefore { call ->
          checked.incrementAndGet()
          val address = spec.get(call.args[1]) as? String
          val host = address?.let { rules?.blockedHost(it) }
          if (host != null) {
            blocked.incrementAndGet()
            if (serverRedirects.contains(call.args[0])) redirected.incrementAndGet()
            lastHost = host
            // Chromium's own throttle cancels before following Location; no stop/reload/replay.
            call.result = true
          }
        }
        hooks++
      }
      ready = hooks == methods.size
      Log.i("Banana navigation guard $VERSION ready: $hooks hooks, undefined hosts")
    } catch (error: Throwable) {
      failure = error.javaClass.simpleName
      Log.e("Banana navigation guard initialization failed: $failure")
    }
  }

  fun status(): JSONObject = JSONObject().apply {
    put("version", VERSION)
    put("ready", ready && NavigationStartup.configured && NavigationStartup.beforeNative)
    put("synchronousChecksConfigured", NavigationStartup.configured)
    put("configuredBeforeNative", NavigationStartup.beforeNative)
    put("hooks", hooks)
    put("blockHosts", rules?.blockCount ?: 0)
    put("exceptionHosts", rules?.exceptionCount ?: 0)
    put("checked", checked.get())
    put("blocked", blocked.get())
    put("blockedServerRedirects", redirected.get())
    put("lastBlockedHost", lastHost ?: JSONObject.NULL)
    put("failure", failure ?: JSONObject.NULL)
  }
}
