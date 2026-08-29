{
  description = "AscendriaMC Velocity fork — compiled through buck2";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # nixpkgs' rustc carries the host's rust-std only. mccrypt is cross-built for the two Linux
    # slots in native/src/main/resources, and that needs their std alongside the host's.
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, rust-overlay }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f (import nixpkgs {
        system = s;
        overlays = [ rust-overlay.overlays.default ];
      }));
      # rustc + cargo + rust-std for the host and for the two cross targets. Linking for those is
      # zig's job (toolchains//BUCK); rustc only needs their std.
      rust = pkgs: pkgs.rust-bin.stable.latest.minimal.override {
        targets = [ "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu" ];
      };
    in {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            buck2
            just
            # The generator that turns gradle's resolved graph into third-party/jvm/BUCK.
            python3
            # Upstream builds with a 25 toolchain and the class files say so; the rules take
            # javac/jar/java from PATH, so this is the only place the version is chosen.
            jdk25
            # mccrypt. The prelude's system rust toolchain finds this rustc/cargo on PATH;
            # reindeer turns mccrypt/Cargo.lock into third-party/rust/BUCK (`just lock-rust`).
            # zig cc is the linker for the Linux slots (toolchains/BUCK, bzl/zig.bzl).
            (rust pkgs)
            reindeer
            zig
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk25}"

            # buck2 reads .buckconfig then .buckconfig.local; the latter is generated (and
            # gitignored) because it records the identity of the JDK found on PATH.
            if [ ! -f .buckconfig.local ]; then
              just bootstrap || echo "  -> failed; run 'just bootstrap' by hand"
            fi

            echo "velocity devshell — jdk $(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/')"
            echo "  just build         compile every module through buck2"
            echo "  just lock          re-resolve with gradle and regenerate third-party/jvm"
            echo "  just lock-rust     regenerate third-party/rust from mccrypt/Cargo.lock (reindeer)"
          '';
        };
      });
    };
}
