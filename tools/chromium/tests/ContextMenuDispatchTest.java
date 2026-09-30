package org.chromium.chrome.browser.contextmenu;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.chromium.ui.listmenu.ListMenuItemProperties.CLICK_LISTENER;
import static org.chromium.ui.listmenu.ListMenuItemProperties.ENABLED;
import static org.chromium.ui.listmenu.ListMenuItemProperties.MENU_ITEM_ID;
import static org.chromium.ui.listmenu.ListMenuItemProperties.TITLE;

import android.app.Activity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.ui.listmenu.ListItemType;
import org.chromium.ui.listmenu.ListMenuItemProperties;
import org.chromium.ui.listmenu.ListMenuUtils;
import org.chromium.ui.modelutil.MVCListAdapter.ListItem;
import org.chromium.ui.modelutil.MVCListAdapter.ModelList;
import org.chromium.ui.modelutil.PropertyModel;
import java.util.ArrayList;
import java.util.List;

/** Runs the real mediator, model and hierarchy controller against Robolectric. */
@RunWith(BaseRobolectricTestRunner.class)
public class ContextMenuDispatchTest {
    @Test
    public void extensionActionRunsBeforeDismissalWithoutAndroidMenuId() {
        Activity activity = mock(Activity.class);
        List<String> calls = new ArrayList<>();
        ContextMenuMediator mediator = new ContextMenuMediator(activity, null,
                id -> { throw new AssertionError("Extension routed to browser ID " + id); },
                () -> calls.add("dismiss"));
        ListItem extension = new ListItem(ListItemType.MENU_ITEM,
                new PropertyModel.Builder(ListMenuItemProperties.ALL_KEYS)
                        .with(ENABLED, true).with(TITLE, "Translate page")
                        .with(CLICK_LISTENER, view -> calls.add("extension")).build());
        ModelList group = new ModelList();
        group.add(extension);
        mediator.updateAndGetModelList(List.of(group), false,
                ListMenuUtils.createHierarchicalMenuController(activity));
        extension.model.get(CLICK_LISTENER).onClick(null);
        assertEquals(List.of("extension", "dismiss"), calls);
    }

    @Test
    public void ordinaryMenuItemStillDispatchesAndDismisses() {
        Activity activity = mock(Activity.class);
        List<String> calls = new ArrayList<>();
        ContextMenuMediator mediator = new ContextMenuMediator(activity, null,
                id -> calls.add("browser:" + id), () -> calls.add("dismiss"));
        ListItem standard = new ListItem(ListItemType.MENU_ITEM,
                new PropertyModel.Builder(ListMenuItemProperties.ALL_KEYS)
                        .with(ENABLED, true).with(MENU_ITEM_ID, 42).build());
        ModelList group = new ModelList();
        group.add(standard);
        mediator.updateAndGetModelList(List.of(group), false,
                ListMenuUtils.createHierarchicalMenuController(activity));
        standard.model.get(CLICK_LISTENER).onClick(null);
        assertEquals(List.of("browser:42", "dismiss"), calls);
    }

    @Test
    public void nestedExtensionKeepsNavigationAndItsAction() {
        Activity activity = mock(Activity.class);
        List<String> calls = new ArrayList<>();
        ContextMenuMediator mediator = new ContextMenuMediator(activity, null,
                id -> { throw new AssertionError("Unexpected browser dispatch " + id); },
                () -> calls.add("dismiss"));
        ListItem extension = new ListItem(ListItemType.MENU_ITEM,
                new PropertyModel.Builder(ListMenuItemProperties.ALL_KEYS)
                        .with(ENABLED, true).with(TITLE, "Translate")
                        .with(CLICK_LISTENER, view -> calls.add("extension")).build());
        ListItem submenu = new ListItem(ListItemType.MENU_ITEM_WITH_SUBMENU,
                new PropertyModel.Builder(org.chromium.ui.listmenu.ListMenuSubmenuItemProperties.ALL_KEYS)
                        .with(ENABLED, true).with(TITLE, "Extension")
                        .with(org.chromium.ui.listmenu.ListMenuSubmenuItemProperties.SUBMENU_ITEMS,
                                List.of(extension)).build());
        ModelList group = new ModelList();
        group.add(submenu);
        ModelList menu = mediator.updateAndGetModelList(List.of(group), false,
                ListMenuUtils.createHierarchicalMenuController(activity));
        submenu.model.get(CLICK_LISTENER).onClick(null);
        assertEquals(List.of(), calls);
        assertEquals(extension, menu.get(1));
        extension.model.get(CLICK_LISTENER).onClick(null);
        assertEquals(List.of("extension", "dismiss"), calls);
    }

    @Test
    public void shareItemAndItsIconKeepDistinctBrowserCommands() {
        Activity activity = mock(Activity.class);
        List<String> calls = new ArrayList<>();
        ContextMenuMediator mediator = new ContextMenuMediator(activity, null,
                id -> calls.add("browser:" + id), () -> calls.add("dismiss"));
        ListItem share = new ListItem(
                ContextMenuCoordinator.ContextMenuItemType.CONTEXT_MENU_ITEM_WITH_ICON_BUTTON,
                new PropertyModel.Builder(ContextMenuItemWithIconButtonProperties.ALL_KEYS)
                        .with(ENABLED, true).with(MENU_ITEM_ID, 42)
                        .with(ContextMenuItemWithIconButtonProperties.END_BUTTON_MENU_ID, 43).build());
        ModelList group = new ModelList();
        group.add(share);
        mediator.updateAndGetModelList(List.of(group), false,
                ListMenuUtils.createHierarchicalMenuController(activity));
        share.model.get(CLICK_LISTENER).onClick(null);
        share.model.get(ContextMenuItemWithIconButtonProperties.END_BUTTON_CLICK_LISTENER).onClick(null);
        assertEquals(List.of("browser:42", "dismiss", "browser:43", "dismiss"), calls);
    }
}
