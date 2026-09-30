// Copyright 2026 Upgrid contributors. All rights reserved.

#include <memory>

#include "content/public/browser/browser_context.h"
#include "content/public/browser/web_contents.h"
#include "extensions/buildflags/buildflags.h"

#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
#include "base/values.h"
#include "components/sessions/content/session_tab_helper.h"
#include "extensions/browser/event_router.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_util.h"
#include "extensions/common/api/commands/commands_handler.h"
#include "extensions/common/permissions/permissions_data.h"
#endif

// JNI conversions must precede this generated header.
#include "chrome/android/chrome_jni_headers/UpgridTranslate_jni.h"

namespace {
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
constexpr char kTranslatorId[] = "gkkkcomfmldkigajkmljnbpiajbpbgdg";
constexpr char kTranslateCommand[] = "hotkey-toggle-translation";

const extensions::Extension* GetTranslator(content::WebContents* contents) {
  if (!contents || contents->IsBeingDestroyed() ||
      !contents->GetLastCommittedURL().SchemeIsHTTPOrHTTPS()) {
    return nullptr;
  }
  auto* context = contents->GetBrowserContext();
  auto* registry = extensions::ExtensionRegistry::Get(context);
  if (!registry) {
    return nullptr;
  }
  const auto* extension = registry->enabled_extensions().GetByID(kTranslatorId);
  if (!extension ||
      (context->IsOffTheRecord() &&
       !extensions::util::IsIncognitoEnabled(kTranslatorId, context))) {
    return nullptr;
  }
  const auto* commands = extensions::CommandsInfo::GetNamedCommands(extension);
  if (!commands || !commands->contains(kTranslateCommand) ||
      extension->permissions_data()->GetPageAccess(
          contents->GetLastCommittedURL(),
          sessions::SessionTabHelper::IdForTab(contents).id(), nullptr) !=
          extensions::PermissionsData::PageAccess::kAllowed) {
    return nullptr;
  }
  return extension;
}
#endif
}  // namespace

static bool JNI_UpgridTranslate_IsAvailable(JNIEnv* env,
                                          content::WebContents* contents) {
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
  return GetTranslator(contents) != nullptr;
#else
  return false;
#endif
}

static bool JNI_UpgridTranslate_Translate(JNIEnv* env,
                                        content::WebContents* contents) {
#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
  // Revalidate at click time: installation, permissions and the page may have
  // changed since the menu was opened. Never execute in a hidden tab.
  if (!GetTranslator(contents) ||
      contents->GetVisibility() != content::Visibility::VISIBLE) {
    return false;
  }
  auto* context = contents->GetBrowserContext();
  auto* router = extensions::EventRouter::Get(context);
  if (!router) {
    return false;
  }
  // Use the same event as Android's extension command registry. TWP resolves
  // the active tab and uses its existing Google/target-language settings.
  // No script injection, permission grants, or synthetic keyboard shortcuts.
  base::ListValue args;
  args.Append(kTranslateCommand);
  auto event = std::make_unique<extensions::Event>(
      extensions::events::COMMANDS_ON_COMMAND, "commands.onCommand",
      std::move(args), context);
  event->user_gesture = extensions::EventRouter::UserGestureState::kEnabled;
  router->DispatchEventToExtension(kTranslatorId, std::move(event));
  return true;
#else
  return false;
#endif
}

DEFINE_JNI(UpgridTranslate)
