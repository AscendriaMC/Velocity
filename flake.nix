{
  description = "AscendriaMC Velocity fork — compiled through buck2";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
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
            # mccrypt. rustc/cargo are what the prelude's system rust toolchain finds on PATH;
            # reindeer turns mccrypt/Cargo.lock into third-party/rust/BUCK (`just lock-rust`).
            rustc
            cargo
            reindeer
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
