void LocalFrameMojoHandler::UpgridSiteOptions(
    uint64_t token,
    const String& command,
    const String& option,
    UpgridSiteOptionsCallback callback) {
  const String failure = "{\"ok\":false,\"error\":\"unsupported\"}";
  if (!IsUpgridVideoCurrent(token) || !upgrid_open_requested_ ||
      !Fullscreen::IsFullscreenElement(*upgrid_video_) ||
      command.length() > 16 || option.length() > 128) {
    std::move(callback).Run(failure);
    return;
  }
  // Only this selected video's YouTube document can use the site adapter.
  const String host = frame_->GetDocument()->Url().Host().ToString();
  if (!(host == "youtube.com" || host.EndsWith(".youtube.com") ||
        host == "youtube-nocookie.com" ||
        host.EndsWith(".youtube-nocookie.com")) ||
      !frame_->DomWindow()->CanExecuteScripts(kNotAboutToExecuteScript)) {
    std::move(callback).Run(failure);
    return;
  }
  ScriptState* script_state = ToScriptStateForMainWorld(frame_);
  if (!script_state || !script_state->ContextIsValid()) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Isolate* isolate = script_state->GetIsolate();
  v8::HandleScope handles(isolate);
  ScriptState::Scope scope(script_state);
  v8::TryCatch exceptions(isolate);
  const String source = R"UPGRIDJS(
@@UPGRID_YOUTUBE_OPTIONS@@
)UPGRIDJS";
  auto evaluation = ClassicScript::CreateUnspecifiedScript(source)
                        ->RunScriptOnScriptStateAndReturnValue(script_state);
  if (evaluation.GetResultType() !=
      ScriptEvaluationResult::ResultType::kSuccess) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Local<v8::Value> function = evaluation.GetSuccessValue();
  if (!function->IsFunction() || !IsUpgridVideoCurrent(token)) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Local<v8::Value> arguments[] = {
      ToV8Traits<Element>::ToV8(script_state, upgrid_video_.Get()),
      V8String(isolate, command), V8String(isolate, option)};
  v8::Local<v8::Value> result;
  if (!V8ScriptRunner::CallFunction(function.As<v8::Function>(),
                                    frame_->DomWindow(), v8::Undefined(isolate),
                                    3, arguments, isolate)
           .ToLocal(&result) ||
      !IsUpgridVideoCurrent(token) || !result->IsString() ||
      result.As<v8::String>()->Length() > 16384) {
    std::move(callback).Run(failure);
    return;
  }
  std::move(callback).Run(ToCoreString(isolate, result.As<v8::String>()));
}
