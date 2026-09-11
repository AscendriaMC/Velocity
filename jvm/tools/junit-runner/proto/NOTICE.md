# buck2 test protocol

From https://github.com/facebook/buck2 at `1d7bb93c093b0c31530ef8d92dd2da73de1784de` — the revision
`buck2 --version` reports for the buck2 this runner is used with:

    app/buck2_test_proto/test.proto                  the protocol
    app/buck2_host_sharing_proto/host_sharing.proto  one field of ExecuteRequest2

Vendored rather than taken as a crate dependency. buck2's own crates are the nicer API, but their
proto build scripts resolve sibling protos by the buck2 repo's layout, which no cargo vendoring
reproduces -- so they build from a checkout and fail under nix. Two files and generated code cost
less than that fight.

This is buck2-internal API: it has `reserved` ranges and `Unstable_` rpcs and it will drift. When
the buck2 in use moves, re-copy both files and re-read the diff.

**Trimmed.** `test.proto` upstream imports `data.proto`, buck2's ~3800-line event schema, for two
optional fields we only ever receive: `ExecutionResult2.command_execution` and
`ExecutionDetails.execution_kind`. Both are dropped here with the import; protobuf treats their
wire data as unknown fields and ignores it. Nothing else was changed.
