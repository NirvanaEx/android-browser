    @Test
    public void getItemList_topLevelExtension_preservesNativeAction() {
        ModelList group = new ModelList();
        // Like MenuModelBridge: an existing callback, but no Android MENU_ITEM_ID.
        group.add(mListItemWithModelClickCallback);
        getItemList(List.of(group), /* hasHeader= */ false);

        activateClickListener(mListItemWithModelClickCallback);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mItemClickListener, mDismissDialog);
        order.verify(mItemClickListener).onClick(mListView);
        order.verify(mDismissDialog).run();
        order.verifyNoMoreInteractions();
        verify(mClickCallback, never()).onResult(any());
    }

    @Test
    public void getItemList_extensionAndBrowserItems_dispatchToTheirOwners() {
        ModelList group = new ModelList();
        group.add(mListItemWithModelClickCallback);
        group.add(mListItemWithoutModelClickCallback);
        getItemList(List.of(group), /* hasHeader= */ true);

        activateClickListener(mListItemWithModelClickCallback);
        verify(mItemClickListener, times(1)).onClick(mListView);
        verify(mClickCallback, never()).onResult(any());
        activateClickListener(mListItemWithoutModelClickCallback);
        verify(mClickCallback, times(1)).onResult(TEST_MENU_ITEM_ID);
        verify(mItemClickListener, times(1)).onClick(mListView);
        verify(mDismissDialog, times(2)).run();
    }

    @Test
    public void getItemList_nestedExtension_preservesNativeAction() {
        ModelList group = new ModelList();
        group.add(mSubmenuLevel0);
        getItemList(List.of(group), /* hasHeader= */ false);
        activateClickListener(mSubmenuLevel0);
        activateClickListener(mSubmenuLevel1);
        verify(mDismissDialog, never()).run();
        activateClickListener(mListItemWithModelClickCallback);
        verify(mItemClickListener, times(1)).onClick(mListView);
        verify(mClickCallback, never()).onResult(any());
        verify(mDismissDialog, times(1)).run();
    }
