# Known issues

Open issues that do not block the first (private) release. Each is to be fixed in a later version.

## Cast: Play / Pause / Seek outside the session ordering

`cast`, `stop` and `shutdown` run one at a time and only the latest one owns the session (see `CastController`).
Play, Pause and Seek do not take part in that: they read the active renderer when pressed and send the command
outside the session lock, without a generation check. A command pressed just before Stop or before casting to
another renderer can therefore still reach the old renderer after the session ended or changed (for example Play
after Stop restarts a renderer whose relay URL is already revoked), and its result or error is written into the
state of the new session.

Fix: give the commands the session's generation and drop them (or their result) when it is no longer current.

## Android 13+: app language changed in the system settings

On Android 13 and newer the language chosen in Settings › Language is stored by the app (`AppLocale`, preference
`app_language`) and also handed to the system as the per-app language. When the language is changed outside the app
(system Settings › Apps › Folder Player Fork › Language), the app's own preference is not updated:

- the activity context is still wrapped with the stored language (`AppLocale.wrap`), so the app can keep showing the
  old language instead of the one chosen in the system settings;
- texts built outside the activity (`Strings.get`, notifications, error messages) use the stored language as well;
- Settings › Language in the app still highlights the old choice.

Choosing the language again inside the app brings both back in line. Android 12 and older are not affected (there is
no system per-app language).

Fix: on Android 13+ read the language from `LocaleManager.applicationLocales` (and stop wrapping the context there),
keeping the preference only for older versions.
