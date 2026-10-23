# INSFAV Plus delivery

- Work only in INSFAV-Plus for this project.
- For requested APK deliveries, publish a signed release and provide its APK.
  Debug builds are internal test artifacts unless the user explicitly asks for one.
- Keep the Plus package and signing certificate stable across releases; increase
  versionCode for every published APK. Use the existing Plus publish/verification
  workflow and never expose signing secrets in logs, commits or public artifacts.
- Edit the UI in `web/` and regenerate packaged assets with `npm run web`.
