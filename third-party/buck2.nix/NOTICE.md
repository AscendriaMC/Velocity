# buck2.nix, vendored

A snapshot of https://github.com/ZentriaMC/buck2.nix at commit
`22450dca5ff4399b7489f18a31a73c219ffa5d5e` (a fork of https://github.com/tweag/buck2.nix,
Apache-2.0, see LICENSE), used as the `nix` cell. Vendored rather than pulled as an external
git cell because the commits this build needs -- `flake.package(local_only = False)`, which is
what lets `nix build` run on a remote worker -- are not published yet. Replace with

    [external_cells]
      nix = git
    [external_cell_nix]
      git_origin = https://github.com/ZentriaMC/buck2.nix.git
      commit_hash = ...

once they are. Files: flake.bzl, toolchains/{rust,python,cxx}.bzl; nothing modified.
