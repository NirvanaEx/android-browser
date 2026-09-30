#if BUILDFLAG(ENABLE_DESKTOP_ANDROID_EXTENSIONS)
  if (!profile->IsOffTheRecord() && !profile->IsGuestSession() &&
      !profile->IsSystemProfile() &&
      !base::CommandLine::ForCurrentProcess()->HasSwitch(
          ::switches::kDisableDefaultApps)) {
    auto upgrid_blocker = std::make_unique<ExternalProviderImpl>(
        service, base::MakeRefCounted<UpgridDefaultAdblockLoader>(), profile,
        ManifestLocation::kInvalidLocation,
        ManifestLocation::kExternalPrefDownload,
        Extension::FROM_WEBSTORE | Extension::WAS_INSTALLED_BY_DEFAULT);
    upgrid_blocker->set_auto_acknowledge(true);
    upgrid_blocker->set_install_immediately(true);
    upgrid_blocker->set_allow_updates(true);
    provider_list->push_back(std::move(upgrid_blocker));
  }
#endif
