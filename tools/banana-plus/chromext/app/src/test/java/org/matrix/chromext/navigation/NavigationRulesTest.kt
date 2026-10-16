package org.matrix.chromext.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NavigationRulesTest {
  private fun fixture() = NavigationRules.fromLines(sequenceOf(
      "+ads.example", "+login.ads.example", "-login.ads.example", "+xn--e1afmkfd.example"))

  @Test fun labelBoundariesAndSubdomains() {
    val p = fixture()
    assertEquals("ads.example", p.blockedHost("https://ads.example/landing"))
    assertEquals("a.ads.example", p.blockedHost("http://a.ads.example:8080/landing"))
    assertNull(p.blockedHost("https://notads.example/"))
    assertNull(p.blockedHost("https://ads.example.safe.example/"))
    assertNull(p.blockedHost("https://safe.example/ads.example?q=https://ads.example"))
  }
  @Test fun canonicalHostAndUserInfo() {
    val p = fixture()
    assertEquals("ads.example", p.blockedHost("HTTPS://ADS.EXAMPLE.:443/x"))
    assertEquals("ads.example", p.blockedHost("https://safe.example@ads.example/"))
    assertNull(p.blockedHost("https://ads.example@safe.example/"))
    assertEquals("xn--e1afmkfd.example", p.blockedHost("https://xn--e1afmkfd.example/x"))
  }
  @Test fun documentExceptionWinsOverParentAndExactBlock() {
    val p = fixture()
    assertNull(p.blockedHost("https://login.ads.example/callback"))
    assertNull(p.blockedHost("https://sub.login.ads.example/callback"))
    assertEquals("other.ads.example", p.blockedHost("https://other.ads.example/callback"))
  }
  @Test fun nonWebAndMalformedAddressesPassToBrowser() {
    val p = fixture()
    for (url in listOf("about:blank", "javascript:alert(1)", "intent://ads.example/x",
        "data:text/plain,ads.example", "https://[::1]/", "https://127.0.0.1/", "/relative",
        "http://%broken", "https://safe.example/%xx", "https://safe.example/")) {
      assertNull(url, p.blockedHost(url))
    }
  }
  @Test fun corruptPolicyDoesNotSilentlyDisableProtection() {
    for (row in listOf("", "+com", "+ads.example/path", "+ADS.example", "ads.example", "+*.ads.example")) {
      try { NavigationRules.fromLines(sequenceOf(row)); fail(row) } catch (_: IllegalArgumentException) {}
    }
  }
  @Test fun shippedAdvertisingHostsAndRealSiteControls() {
    val p = File("src/main/assets/banana-navigation-hosts.txt").bufferedReader().use {
      NavigationRules.fromLines(it.lineSequence())
    }
    assertTrue(p.blockCount > 80000)
    for (host in listOf("pemsrv.com", "s.pemsrv.com", "enrtx.com")) {
      assertEquals(host, p.blockedHost("https://$host/redirect?x=1"))
    }
    for (host in listOf("pervtube.net", "pxp.farm", "porn-xp.eu", "pervl4.xtremestream.xyz",
        "accounts.google.com", "github.com", "youtube.com", "example.org")) {
      assertNull(host, p.blockedHost("https://$host/redirect?x=1"))
    }
  }
}
