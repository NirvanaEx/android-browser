package org.chromium.chrome.browser.upgrid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

@RunWith(BaseRobolectricTestRunner.class)
public class UpgridTranslateTest {
    private final UpgridTranslate.Natives mNative = mock(UpgridTranslate.Natives.class);
    private final Tab mTab = mock(Tab.class);
    private final WebContents mContents = mock(WebContents.class);

    @Before
    public void setUp() {
        UpgridTranslateJni.setInstanceForTesting(mNative);
        when(mTab.getWebContents()).thenReturn(mContents);
    }

    @Test
    public void nativePageAndEmptyTabNeverInvokeExtension() {
        assertFalse(UpgridTranslate.canTranslate(null));
        assertFalse(UpgridTranslate.translate(null));
        when(mTab.isNativePage()).thenReturn(true);
        assertFalse(UpgridTranslate.canTranslate(mTab));
        assertFalse(UpgridTranslate.translate(mTab));
        verifyNoInteractions(mNative);
    }

    @Test
    public void destroyedContentsAreRejectedBeforeJni() {
        when(mContents.isDestroyed()).thenReturn(true);
        assertFalse(UpgridTranslate.canTranslate(mTab));
        assertFalse(UpgridTranslate.translate(mTab));
        verifyNoInteractions(mNative);
    }

    @Test
    public void currentTabIsPassedToBothPermissionCheckAndDispatch() {
        when(mNative.isAvailable(mContents)).thenReturn(true);
        when(mNative.translate(mContents)).thenReturn(true);
        assertTrue(UpgridTranslate.canTranslate(mTab));
        assertTrue(UpgridTranslate.translate(mTab));
        verify(mNative).isAvailable(mContents);
        verify(mNative).translate(mContents);
    }

    @Test
    public void permissionRevokedAfterOpeningMenuDoesNotReportDispatch() {
        when(mNative.isAvailable(mContents)).thenReturn(true);
        assertTrue(UpgridTranslate.canTranslate(mTab));
        when(mNative.translate(mContents)).thenReturn(false);
        assertFalse(UpgridTranslate.translate(mTab));
    }
}
