    MenuFrame(
        contentModifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        scrollState = scrollState,
        // MenuNavigation manages its own scrolling. Keep it outside the scrollable
        // content column so Compose receives a finite maximum height.
        header = {
            if (accessPoint != MenuAccessPoint.Home) MenuNavigation(
                isSiteLoading = isSiteLoading,
                isExtensionsExpanded = false,
                isMoreMenuExpanded = false,
                onBackButtonClick = onBackButtonClick,
                onForwardButtonClick = onForwardButtonClick,
                onRefreshButtonClick = onRefreshButtonClick,
                onStopButtonClick = onStopButtonClick,
                onShareButtonClick = onShareButtonClick,
                goBackState = if (canGoBack) MenuItemState.ENABLED else MenuItemState.DISABLED,
                goForwardState = if (canGoForward) MenuItemState.ENABLED else MenuItemState.DISABLED,
            )
        },
        footer = {},
    ) {
        if (accessPoint != MenuAccessPoint.Home) {
            MenuGroup {
                MenuItem(
                    label = stringResource(if (isBookmarked) R.string.upgrid_edit_bookmark else R.string.upgrid_bookmark),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_bookmark_24),
                    onClick = if (isBookmarked) onEditBookmarkButtonClick else onBookmarkPageMenuClick,
                )
                MenuItem(
                    label = stringResource(R.string.upgrid_find),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_search_24),
                    onClick = onFindInPageMenuClick,
                )
                MenuItem(
                    label = stringResource(if (isDesktopMode) R.string.upgrid_desktop_on else R.string.upgrid_desktop),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_device_desktop_24),
                    onClick = onSwitchToDesktopSiteMenuClick,
                )
            }
        }
        LibraryMenuGroup(
            isDownloadHighlighted = isDownloadHighlighted,
            onBookmarksMenuClick = onBookmarksMenuClick,
            onHistoryMenuClick = onHistoryMenuClick,
            onDownloadsMenuClick = onDownloadsMenuClick,
            onPasswordsMenuClick = onPasswordsMenuClick,
        )
        MenuGroup {
            org.mozilla.fenix.upgrid.UpgridAdblockSwitch()
            MenuItem(
                label = stringResource(R.string.upgrid_extensions),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_extension_24),
                onClick = onExtensionsMenuClick,
            )
            if (isExtensionsExpanded) extensionSubmenu()
            MenuItem(
                label = stringResource(R.string.upgrid_settings),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_settings_24),
                onClick = onSettingsButtonClick,
            )
        }
    }
