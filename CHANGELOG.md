# Changelog

## 1.1.3 (build 5)

Enlarge and extend the Missing Images copy icon downward while preserving the
existing card, count, label and button dimensions.

## 1.1.2 (build 4)

Show the view-mode selector directly in a labeled settings box, without an
expandable section. One tap opens the existing List / 2–5 column choices.

## 1.1.1 (build 3)

Restore the original person placeholder, enlarged inside the existing avatar.
Replace the scrollbar marker with a fixed gray/black ball that remains visible
and easy to grab; its rail stays invisible. The 250ms hold and list layout remain.

## 1.1.0 (build 2)

Folder chevrons point down when collapsed and up when expanded.

Overlay scroll arrows and thumb slide/fade after 440ms idle, with a 250ms drag
hold and no protruding grip. Settings begin with persistent list or
2/3/4/5-column square matrix views,
usernames below images, and a language selector in the upper-right toolbar.
Cold process startup and stale service intents leave retrieval paused until an
explicit start/resume; completed work and pending accounts are preserved.

## 1.0.0 (build 1)

Initial independent INSFAV Plus package. Larger 60px avatars and consistent 65px
virtual rows; safer profile classification, retry and system timeout handling;
durable incremental image metadata and batched snapshots; event-driven broker;
bounded shared encoded-image streams; cancellable 256-record scan checkpoints after a measured 256/512 comparison;
English/Turkish presentation, consolidated settings and diagnostics; explicit
authenticated migration of original INSFAV settings. Source files remain intact.

1.1.0 / build 2 is published as a verified signed release; see
`docs/verification/release-1.1.0.json` for its source, CI and checksum records.

Owner review: preserve conservative 45-second profile deadlines after offline
15/30/45 comparison; validate all unflushed successful metadata on recovery;
provide visible full account text without increasing row height; warn about
relative orphan classification and block it when collections are unloaded. The
owner-provided Plus logo supplies launcher, splash and README branding. Application
information keeps its metadata and actions without displaying the launcher image.

Language and account-outcome selection now use the shared AMOLED web modal in
place of platform select popups, with selected checkmarks, keyboard navigation,
focus restoration, 48px touch targets and scrollable options at large text sizes.
