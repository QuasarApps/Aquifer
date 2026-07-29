# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub: go to the repository's
[**Security** tab](https://github.com/QuasarApps/aquifer/security) and choose **Report a
vulnerability**. That opens a private advisory visible only to you and the maintainers, and it is
the only channel this project offers — there is no separate security mailing address.

Useful things to include, roughly in order of how much they help:

- Which module and version (`aquifer-core`, `aquifer-persistence-file`, …).
- What an attacker gains, and what access they need to get it.
- A minimal reproduction — a failing test against the public API is ideal.
- Your assessment of severity, and any mitigation a consumer could apply today.

This is a small project with no paid on-call rotation, so rather than promise a response window it
does not staff: expect an acknowledgement when a maintainer next picks up the repository, and a fix
prioritised above ordinary work once the report is confirmed. You will be credited in the advisory
and the changelog unless you ask otherwise.

## Supported versions

Before 1.0, only the most recent release is supported. Fixes land on `develop` and ship in the next
version rather than being backported.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Anything earlier | ❌ — upgrade |

## What is in scope

Aquifer is a caching data layer, so its security surface is mostly about **what ends up on disk and
what crosses the network on your behalf**:

- **Cached data at rest.** `aquifer-persistence-file` writes one JSON file per key; the
  `aquifer-persistence-sqldelight` adapter writes rows to your database. A defect that exposes cached
  values beyond what the configuration asks for is in scope.
- **The `ValueCipher` seam.** `JsonFileSourceOfTruth` accepts a `cipher` that encrypts each entry's
  serialized bytes, with the entry's key passed as authenticated associated data so a ciphertext
  cannot be relocated to another key's file and served as that key's value. A flaw that lets that
  binding be bypassed, or that writes plaintext when a cipher is configured, is in scope.
- **Conditional fetching.** `aquifer-okhttp` replays `ETag`/`Last-Modified` validators it captured
  earlier. A defect that sends a validator to the wrong origin or leaks one into an unintended
  request is in scope.
- **Dependency vulnerabilities** reachable through Aquifer's own use of a dependency.

## What is not a vulnerability

These are documented, deliberate behaviours. Reporting them is welcome as an *issue* if the
documentation is unclear, but they are not security defects:

- **Persistence is plaintext unless you configure a `cipher`.** The default stores serialized JSON
  as-is. Encrypting cached data is opt-in, and the [README](README.md) says so.
- **Filenames are a SHA-256 of the key, which is not a confidentiality guarantee.** The hash exists
  to make arbitrary key strings filesystem-safe, not to hide them. Keys drawn from a small or
  guessable space — sequential IDs, email addresses — can be recovered by hashing candidates and
  comparing. Treat the *presence* of a file as revealing its key.
- **Aquifer does not manage keys.** A `ValueCipher` is a seam you implement; where its key material
  lives (the Android Keystore, a KMS) and how it is rotated are yours.
- **`put` is a local write, not a mutation queue.** A later fetch can overwrite it. This is a
  correctness contract, described under
  [*What Aquifer is not*](README.md#what-aquifer-is-not), not a security boundary.
- **The cache is not a trust boundary.** A cached value is whatever your fetcher returned; Aquifer
  neither validates nor sanitises it. Data that arrives untrusted is still untrusted after a
  round-trip through the cache.
- **Anything that presupposes attacker code already executing inside your process.** At that point
  the attacker reads the plaintext values Aquifer hands your app and the cache is not the weakest
  link.

  This exclusion is about *in-process execution only* — deliberately **not** about storage access.
  Read or write access to the cache directory is precisely the threat `ValueCipher` and its
  key-binding exist to address: the entry's key is authenticated associated data specifically so
  that a blob swapped into another key's file fails to decrypt rather than being served as that
  key's value. A defect there is in scope, as is anything that writes plaintext when a cipher is
  configured.
