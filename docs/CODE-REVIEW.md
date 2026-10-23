# Maintenance review

The MainActivity command switch remains: it is explicit and testable, and a map
would add indirection without reducing the current commands' lifecycle coupling.
Fetcher already separates broker, queue, profile providers, scheduling, cookies,
codec, storage and diagnostics; no extra Android module or duplicate decomposition
was introduced. `image-failed` is called by bounded browser image failure handling;
`/thumb`, `/full` and `/remote-full` serve different existing viewer paths. They
remain. PublicProfileProvider's parsing compatibility tests and trusted HTTP image
transport are still used; no endpoint or transport was replaced blindly.

Expected absence/cancellation/cleanup catches remain bounded and preserve data.
Profile/queue unexpected errors use redacted categories with application line
numbers. Hard viewer/catalog OOM now pauses the shared pool, releases resources
and preserves the queue. A failed background snapshot pauses retrieval while the
durable journal remains available for recovery. Store retains an application
context, never an Activity context. Snapshot writes use AtomicFile and commit
acknowledgements only after the durable snapshot succeeds.

Lint treatment: synchronous preference commits are intentionally retained for
session generation, queue claims, schedule and recovery decisions. Their scoped
ApplySharedPref suppressions explain durability. Static singleton context warnings
are scoped to verified application-context holders. JavaScript is required only
for the local CSP-constrained UI and isolated probe WebViews. The API27 callback
uses RequiresApi; locale splitting is disabled; minimum-26 adaptive icons moved
to the base directory and received a monochrome layer. An unreferenced `ic_fetch`
resource was removed after source/XML/manifest reference search. The unused original launcher variants were archived; the original foreground artwork is archived, and the owner-supplied logo supplies the Plus icon. The source bundle
retains it. Old app-specific User-Agent suffix now uses the Plus BuildConfig
version; the Chrome compatibility signature remains unchanged.

`lint.xml` documents the deliberately pinned WorkManager dependency and legacy
three-dot search typography. Warnings are not hidden by a blanket lint baseline.
Pure google-java-format output is committed separately from these changes.
