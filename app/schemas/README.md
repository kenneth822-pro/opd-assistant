# Room schema export

KSP (`room.schemaLocation`) writes the versioned schema JSON for each database version into this
directory during a build. These files are committed so that:

- migrations can be reviewed in pull requests, and
- `MigrationTestHelper` / `Room.get` can verify a migration against the recorded schema.

The files appear here after the first successful CI build that runs KSP. Do not hand-edit them.
