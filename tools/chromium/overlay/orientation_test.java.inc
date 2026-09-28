    @Test
    public void testManualPlayerOrientationIsScopedAndDoesNotReplaySiteRequests() {
        Activity player = Robolectric.buildActivity(Activity.class).create().get();
        Activity other = Robolectric.buildActivity(Activity.class).create().get();
        ActivityWindowAndroid window = buildMockWindowForActivity(player);
        ScreenOrientationProviderImpl instance = ScreenOrientationProviderImpl.getInstance();
        player.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        instance.setUserControlledOrientation(window, true);
        instance.lockOrientation(window, (byte) ScreenOrientationLockType.LANDSCAPE_PRIMARY);
        instance.unlockOrientation(window);
        Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, player.getRequestedOrientation());
        instance.lockOrientation(buildMockWindowForActivity(other),
                (byte) ScreenOrientationLockType.LANDSCAPE_PRIMARY);
        Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, other.getRequestedOrientation());

        instance.delayOrientationRequests(window);
        instance.lockOrientation(window, (byte) ScreenOrientationLockType.PORTRAIT_PRIMARY);
        instance.setUserControlledOrientation(window, false);
        instance.runDelayedOrientationRequests(window);
        Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, player.getRequestedOrientation());
        instance.lockOrientation(window, (byte) ScreenOrientationLockType.PORTRAIT_PRIMARY);
        Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, player.getRequestedOrientation());
    }

    @Test
    public void testPendingSiteRequestCannotOverrideManualPlayerOrientation() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        ActivityWindowAndroid window = buildMockWindowForActivity(activity);
        ScreenOrientationProviderImpl instance = ScreenOrientationProviderImpl.getInstance();
        instance.delayOrientationRequests(window);
        instance.lockOrientation(window, (byte) ScreenOrientationLockType.LANDSCAPE_PRIMARY);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        instance.setUserControlledOrientation(window, true);
        instance.runDelayedOrientationRequests(window);
        Assert.assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activity.getRequestedOrientation());
        instance.setUserControlledOrientation(window, false);
    }
