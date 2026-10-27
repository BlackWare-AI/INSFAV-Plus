# HD thumbnail handoff

The Legacy persistent browser remains the default. The public Current/HTTP choice is removed, including migration of its saved choice to Legacy. Netscape extraction and manual records remain available without cookie tests. No changes are made to the Netscape request path in this release.

A downloaded HD response is checked by the bounded decoder without generating a discarded WebP. Before the viewer generation check it is handed to private cached image+metadata files (source at most8MiB, pending cache at most64MiB/32jobs). A separate single local worker uses the existing HdThumbnailWriter and verified SAF commit. Closing the viewer cancels unfinished network work and clears display memory, but owns no accepted conversion jobs. The private source is removed after completion. Startup recovery can requeue committed cache records for the matching selected folder; OS cache eviction is outside this guarantee. No foreground service or notification is created for local conversion.

The existing JSON/SQLite image indexes, cookies and AES-GCM vaults are unchanged. No cache file contains cookies. The busy event controls only the lower-left spinner for the current profile when its thumbnail was missing; another profile's completion cannot hide it. Actual cookie/security failures retain the existing safety stop.

Regression checks block the conversion worker, require HD readiness, close the viewer, release the worker and validate the 256WebP write. Android fixtures apply this to both retained persistent methods with real CookieManager/Keystore and controlled HTTP/browser resources. Browser checks cover mode selection and the conversion indicator's location and lifecycle. Live account behavior is reported separately.
