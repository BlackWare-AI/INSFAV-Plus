# Read-only INSFAV baseline

The Git bundle preserves the complete source history and annotated
`baseline/insfav-0a4ba91` reference, resolving to
`0a4ba915418e0652618879d4af2c0745ef9ca4f0`.

```sh
git bundle verify docs/archive/source/insfav-baseline.bundle
git clone --branch baseline/insfav-0a4ba91 docs/archive/source/insfav-baseline.bundle /tmp/insfav-source-audit
```

The original repository is read-only. The independent Plus history starts from
its existing `383d1e0918f1633c83760a40fbb8c93655e0609d` commit. The original
commit is preserved inside this bundle instead of publishing its executable
legacy workflow tree as a remote ref. The connected GitHub integration does not
permit repository Actions administration (HTTP 403).

Legacy workflows are archived outside `.github/workflows` before the first push.
No legacy release, publish, signing or synchronization command is invoked.
