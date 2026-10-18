# Native EPUB work packages

Package scopes follow section 8 of [`DEVELOPMENT_PLAN.md`](DEVELOPMENT_PLAN.md); this table only adds status.

| Package | Deliverable (from the plan) | Depends on | Status |
| --- | --- | --- | --- |
| P01-01 | Baseline, dependency integration and engine feasibility | None | Merged into `novels`: PR [#1](https://github.com/MohamedMaged9258/mihon/pull/1), merge `e81de7603` (reviewed tip `c7f5ebf06`) |
| P01-02 | Data model, identity and local publication/import services | P01-01 | In review: PR P01-02-PR-LINK; evidence in [`TEST_MATRIX.md`](TEST_MATRIX.md) |
| P01-03 | Shared-library integration and EPUB reader routing | P01-02 | Not started |
| P01-04 | Durable Resume, lifecycle, history and completion | P01-03 | Not started |
| P01-05 | Complete reading controls, navigation and accessibility | P01-04 | Not started |
| P01-06 | Offline availability, storage and recovery | P01-05 | Not started |
| P01-07 | Backup, restore and upgrade compatibility | P01-06 | Not started |
| P01-08 | Regression testing, release packaging and user/developer docs | P01-07 | Not started |

P01-01 branch: `novels-p01-01-foundation`, merged into `novels` through [PR #1](https://github.com/MohamedMaged9258/mihon/pull/1).

P01-02 branch: `feature/p01-02-epub-data-import`, started from `novels` at `e81de7603` and targeting `novels` (`feat(epub): add publication data model and managed local imports`). No merge, publication or upstream release action is part of this package.

P01-02 provides the services, schema and repositories. These remain open:

- P01-03: the release-facing Import action and centralized reader routing (`PublicationAssetResolver` is ready for it).
- P01-04: live Resume, history and completion writes.
- P01-05: settings, search and bookmark UI.
- P01-06: relinking, storage management and deleting owned files.
- P01-07: backup fields.

The diagnostic close-and-open checkpoint file added in P01-01 is not the durable Resume repository; that remains P01-04 scope.
