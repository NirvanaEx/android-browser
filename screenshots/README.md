# screenshots/

Drop folder for UI screenshots before sharing them with Claude Code.

## Why this folder exists

Anthropic's API rejects any chat with an image larger than **2000 px on any side**
when multiple images are present. Android phones produce **1080×2400** screenshots,
which trip the limit and corrupt the whole conversation — the only fix is starting
a new session and losing context.

## Workflow

1. Take screenshots on the device, transfer them here (drag-drop, ADB pull, etc.).
   Keep filenames descriptive: `home-dark.png`, `tabs-tray-empty.png`,
   `menu-adblock-on.png`. They become anchors in the chat.

2. From the project root, run:

   ```powershell
   .\tools\Resize-Screenshots.ps1
   ```

   The script downsizes anything over 1900 px to fit (preserving aspect ratio) and
   leaves smaller files alone. Output is in-place.

3. Drag the resized files into the Claude Code chat. They are now under the limit.

## Notes

- This folder is for raw inputs to Claude. **Don't** put production assets here —
  Android resources live in [app/src/main/res/](../app/src/main/res/).
- Safe to wipe between iterations: `Remove-Item .\screenshots\*.png`.
- If you ever need a single-image session at full resolution, the limit relaxes
  to 8000 px — but mixing one full-res + any other image kills the chat. Always
  resize when iterating.
