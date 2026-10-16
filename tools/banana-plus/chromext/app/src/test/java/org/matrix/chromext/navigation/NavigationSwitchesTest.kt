package org.matrix.chromext.navigation
import org.junit.Assert.*
import org.junit.Test
class NavigationSwitchesTest {
 @Test fun mergesWithoutRemovingOtherFeatures() {
   assertEquals("Other,Other2,AsyncNavigationIntercept", NavigationSwitches.disabled("Other,AsyncNavigationIntercept,Other2"))
   assertEquals("Other,AsyncNavigationIntercept", NavigationSwitches.disabled("Other,AsyncNavigationIntercept<Trial:param/value"))
 }
 @Test fun removesOnlyTheAsynchronousOptimizationFromEnableList() {
   assertEquals("A,B", NavigationSwitches.enabled("A,AsyncNavigationIntercept<Trial,B"))
 }
 @Test fun preservesArgumentsAndTerminator() {
   assertArrayEquals(arrayOf("browser", "--other=unchanged", "--enable-features=A", "--disable-features=X,AsyncNavigationIntercept", "--", "--disable-features=literal"),
       NavigationSwitches.arguments(arrayOf("browser", "--other=unchanged", "--enable-features=A,AsyncNavigationIntercept", "--disable-features=X", "--", "--disable-features=literal")))
 }
 @Test fun initializesWithoutAnExistingCommandLine() {
   assertArrayEquals(arrayOf("", "--disable-features=AsyncNavigationIntercept"), NavigationSwitches.arguments(null))
 }
}
