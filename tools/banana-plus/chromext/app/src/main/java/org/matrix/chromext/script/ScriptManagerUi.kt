package org.matrix.chromext.script

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.BuildConfig
import org.matrix.chromext.Chrome
import org.matrix.chromext.Resource
import org.matrix.chromext.proxy.UserScriptProxy
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

/** Local manager in the browser renderer; loopback-only authenticated API. */
object ScriptManagerUi {
  private const val MAX_SOURCE = 2 * 1024 * 1024
  private fun prefs() = Chrome.getContext().getSharedPreferences("BananaScriptManager", Context.MODE_PRIVATE)
  fun isEnabled(id: String): Boolean = prefs().getBoolean(id, true)

  private fun metadata(meta: String, key: String): List<String> =
      Regex("(?m)^//\\s+@" + Regex.escape(key) + "[ \\t]+([^\\r\\n]+)")
          .findAll(meta).map { it.groupValues[1].trim() }.toList()

  private fun describe(script: Script): JSONObject = JSONObject().apply {
    put("id", script.id)
    put("name", metadata(script.meta, "name").firstOrNull() ?: script.id)
    put("namespace", metadata(script.meta, "namespace").firstOrNull() ?: "")
    put("description", metadata(script.meta, "description").firstOrNull() ?: "")
    put("version", metadata(script.meta, "version").firstOrNull() ?: "—")
    put("matches", JSONArray(script.match.toList()))
    put("enabled", isEnabled(script.id))
  }


  private var baseUrl: String? = null
  private const val LAST_URL = "_manager_last_url"
  fun handleNavigation(url: String): Boolean {
    if (baseUrl != null && url == baseUrl) return true
    if (url == prefs().getString(LAST_URL, null)) {
      Handler(Looper.getMainLooper()).post { show(Chrome.getContext() as Activity) }
      return true
    }
    return false
  }
  fun show(activity: Activity) {
    if (baseUrl == null) {
      Resource.enrich(activity)
      val html = activity.assets.open("banana-manager.html").use { it.readBytes() }
      val server = ServerSocket()
      server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
      val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
      val origin = "http://127.0.0.1:${server.localPort}"
      val path = "/$token/"
      baseUrl = origin + path
      prefs().edit().putString(LAST_URL, baseUrl).apply()
      Chrome.IO.submit {
        while (!server.isClosed) {
          val socket = server.accept()
          Chrome.IO.submit { serve(socket, origin, path, token, html) }
        }
      }
    }
    UserScriptProxy.loadUrl.invoke(Chrome.getTab(), UserScriptProxy.newLoadUrlParams(baseUrl!!))
  }
  private fun serve(socket: Socket, origin: String, path: String, token: String, html: ByteArray) {
    socket.use { client -> runCatching {
      client.soTimeout = 15000
      val input = client.getInputStream().buffered()
      fun line(): String {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
          val ch = input.read()
          if (ch < 0 || ch == 10) break
          require(buffer.size() < 8192)
          if (ch != 13) buffer.write(ch)
        }
        return buffer.toString("US-ASCII")
      }
      val request = line().split(" ")
      require(request.size == 3)
      val headers = mutableMapOf<String,String>()
      repeat(64) {
        if (!headers.containsKey("_done")) {
          val row = line()
          if (row.isEmpty()) headers["_done"] = "true"
          else { val pair=row.split(":",limit=2);require(pair.size==2);headers[pair[0].lowercase()]=pair[1].trim() }
        }
      }
      fun send(status: Int, type: String, bytes: ByteArray) {
        val extra="Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n"
        client.getOutputStream().apply {
          write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
          write(bytes);flush()
        }
      }
      if(headers["host"]!=origin.removePrefix("http://")) {send(403,"text/plain","Forbidden".toByteArray());return}
      if(request[0]=="GET" && request[1]==path) {send(200,"text/html",html);return}
      if(request[0]!="POST" || request[1] !in listOf(path+"api",path+"fetch") ||
          headers["x-banana-manager"]!=token || headers["origin"]?.let{it!=origin}==true ||
          headers["sec-fetch-site"]?.let{it!="same-origin"}==true ||
          !headers["content-type"].orEmpty().startsWith("application/json")) {
        send(403,"text/plain","Forbidden".toByteArray());return
      }
      val length=headers["content-length"]?.toIntOrNull()?:0
      require(length in 1..(MAX_SOURCE*6+1024))
      val bytes=ByteArray(length);var offset=0
      while(offset<length){val n=input.read(bytes,offset,length-offset);require(n>0);offset+=n}
      val raw=bytes.toString(Charsets.UTF_8)
      val result=if(request[1]==path+"api") operate(raw) else fetchSource(JSONObject(raw).getString("url"))
      send(200,"application/json",result.toString().toByteArray())
    } }
  }
  private fun operate(raw: String): JSONObject {
    val latch=CountDownLatch(1)
    var answer=JSONObject().put("ok",false).put("error","Операция не завершена")
    Handler(Looper.getMainLooper()).post {
      try {
            val data = JSONObject(raw)
            val id = data.optString("id")
            val script = ScriptDbManager.scripts.find { it.id == id }
            val value: Any = when (data.getString("op")) {
              "navigationGuard" -> org.matrix.chromext.navigation.NavigationGuard.status()
              "list" -> JSONArray(ScriptDbManager.scripts.map { describe(it) })
              "read" -> {
                requireNotNull(script) { "Скрипт уже удалён" }
                describe(script).put("source", script.meta + script.code)
              }
              "toggle" -> {
                requireNotNull(script) { "Скрипт уже удалён" }
                check(prefs().edit().putBoolean(id, data.getBoolean("enabled")).commit())
                describe(script)
              }
              "delete" -> {
                requireNotNull(script) { "Скрипт уже удалён" }
                ScriptDbHelper(Chrome.getContext()).use { helper ->
                  check(helper.writableDatabase.delete("script", "id = ?", arrayOf(id)) == 1)
                }
                ScriptDbManager.scripts.remove(script)
                prefs().edit().remove(id).apply()
                true
              }
              "install" -> {
                val source = data.getString("source")
                require(source.toByteArray().size <= MAX_SOURCE) { "Файл больше 2 МБ" }
                val parsed = requireNotNull(parseScript(source)) { "Нужен userscript с блоком метаданных и @match или @include" }
                val old = ScriptDbManager.scripts.find { it.id == parsed.id }
                require(old == null || data.optBoolean("replace")) { "Скрипт уже установлен. Подтверди обновление." }
                parsed.storage = old?.storage ?: parsed.storage
                ScriptDbHelper(Chrome.getContext()).use { helper ->
                  val values = ContentValues().apply {
                    put("id", parsed.id); put("meta", parsed.meta); put("code", parsed.code)
                    if (parsed.storage != null) put("storage", parsed.storage.toString())
                  }
                  check(helper.writableDatabase.replaceOrThrow("script", null, values) != -1L)
                }
                ScriptDbManager.scripts.removeAll { it.id == parsed.id }
                ScriptDbManager.scripts.add(parsed)
                describe(parsed)
              }
              else -> error("Неизвестная операция")
            }
            answer = JSONObject().put("ok", true).put("value", value)

      } catch(e:Exception) {answer=JSONObject().put("ok",false).put("error",e.message?:"Не удалось выполнить действие")}
      finally {latch.countDown()}
    }
    latch.await(15,TimeUnit.SECONDS)
    return answer
  }
  private fun fetchSource(address: String): JSONObject = try {
            var url = URL(address.trim())
            var source: String? = null
            for (redirect in 0..5) {
              require(url.protocol == "https" && url.userInfo == null) { "Используй ссылку https:// без логина и пароля" }
              val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 12000; readTimeout = 12000
                setRequestProperty("User-Agent", "Mozilla/5.0 BananaScriptManager/1.0")
                setRequestProperty("Accept", "application/javascript,text/javascript,text/plain,*/*;q=0.1")
              }
              try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                  url = URL(url, requireNotNull(connection.getHeaderField("Location")))
                  continue
                }
                require(status == 200) { "Сервер ответил HTTP $status" }
                require(connection.contentLengthLong <= MAX_SOURCE) { "Файл больше 2 МБ" }
                val bytes = connection.inputStream.use { input ->
                  val output = java.io.ByteArrayOutputStream()
                  val buffer = ByteArray(8192)
                  while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_SOURCE) { "Файл больше 2 МБ" }
                    output.write(buffer, 0, count)
                  }
                  output.toByteArray()
                }
                source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                break
              } finally { connection.disconnect() }
            }
            requireNotNull(source) { "Слишком много перенаправлений" }

    JSONObject().put("ok",true).put("source",source).put("url",url.toString())
  } catch(e:Exception) {JSONObject().put("ok",false).put("error",e.message?:"Не удалось загрузить скрипт")}
}
