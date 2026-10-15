# Native EPUB work packages

| Package | Status | Scope / blocker |
| --- | --- | --- |
| P01-01 | Incomplete | Foundation code, deterministic fixtures, contracts, and tests implemented locally. Gradle is blocked before configuration by the host loopback/Unix-domain-socket error; device/rendering/minified-release evidence is therefore missing. P01-02 is not cleared. |
| P01-02 | Not started | Production content resolver, durable import/storage, shared title/item mapping, database schema and migrations. |
| P01-03 | Not started | Production reader UI/preferences, modes, ToC/current section, link history and accessibility. |
| P01-04 | Not started | Durable resume/furthest progress, completion/history integration and bookmarks. |
| P01-05 | Not started | Search, selection actions and book navigation refinements. |
| P01-06 | Not started | Hostile EPUB hardening, explicit archive/path/resource budgets and broader security validation. |
| P01-07 | Not started | Backup/restore, missing-file recovery, storage presentation and cleanup ownership. |
| P01-08 | Not started | Full regression, performance budgets, oldest-SDK evidence and production release gate. |

P01-01 branch: `codex/p01-01-epub-foundation`. Intended PR title: `feat(epub): establish native reader foundation and Readium feasibility`. No push, merge, publication, or guarded upstream release action is part of this package.
