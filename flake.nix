{
  description = "AscendriaMC Velocity fork — compiled through buck2";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # The build's tools (jdk, rustc, zig, python) are defined once, in nix/flake.nix, because
    # buck2 realises them from there on whichever machine runs an action. The shell takes the
    # same packages so that what a developer types and what the build runs cannot drift.
    tools = {
      url = "path:./nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # The test executor buck2 runs (see [test] v2_test_executor). Its own flake, because it is
    # headed for its own repository; until then, editing it means `nix flake update junit-runner`
    # before the devshell sees the change.
    junit-runner = {
      url = "path:./jvm/tools/junit-runner";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, tools, junit-runner }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s} tools.packages.${s} junit-runner.packages.${s});

    in {
      devShells = forAll (pkgs: t: runner: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            buck2
            just
            # The generator that turns gradle's resolved graph into third-party/jvm/BUCK.
            python3
            # The build's own tools, from nix/flake.nix.
            t.jdk
            t.rust
            t.zig
            t.python
            # reindeer turns mccrypt/Cargo.lock into third-party/rust/BUCK (`just lock-rust`).
            reindeer
            # `buck2 test` runs this; .buckconfig names it and finds it here on PATH.
            runner.junit-runner
          ];

          shellHook = ''
            export JAVA_HOME="${t.jdk}"

            echo "velocity devshell — jdk $(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"
            echo "  just build         compile every module through buck2"
            echo "  just lock          re-resolve with gradle and regenerate third-party/jvm"
            echo "  just lock-rust     regenerate third-party/rust from mccrypt/Cargo.lock (reindeer)"
            echo "  just test          every test, reported per test"
            echo "  just re <cmd>      the same, on remote workers (needs RE configured)"
          '';
        };
      });
    };
}
