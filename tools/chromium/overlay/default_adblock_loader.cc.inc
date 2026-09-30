#if BUILDFLAG(ENABLE_DESKTOP_ANDROID_EXTENSIONS)
// Only this fixed, Web Store-signed blocker is offered as a default component
// of Upgrid. It remains a normal, removable extension: no component privileges,
// no policy installation, and no permission grant for unrelated extensions.
class UpgridDefaultAdblockLoader : public ExternalLoader {
 public:
  UpgridDefaultAdblockLoader() = default;

  void StartLoading() override {
    base::DictValue prefs;
    prefs.SetByDottedPath(
        "ddkjiahejlhfcafbddmgiahcphecmpfh.external_update_url",
        extension_urls::GetWebstoreUpdateUrl().spec());
    LoadFinished(std::move(prefs));
  }

 protected:
  ~UpgridDefaultAdblockLoader() override = default;
};
#endif
