{
  # Self-contained on purpose: this is meant to move to its own repository, and nothing here knows
  # anything about the tree it currently sits in. A consumer takes the package, puts it on PATH,
  # and points `[test] v2_test_executor` at the name.
  description = "buck2 test executor reporting each JUnit test";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    crane.url = "github:ipetkov/crane";
  };

  outputs = { self, nixpkgs, crane }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      packages = forAll (pkgs:
        let
          junit-runner = (crane.mkLib pkgs).buildPackage {
            # Not cleanCargoSource: build.rs reads proto/, which that would drop.
            src = pkgs.lib.fileset.toSource {
              root = ./.;
              fileset = pkgs.lib.fileset.unions [
                ./Cargo.toml
                ./Cargo.lock
                ./build.rs
                ./src
                ./proto
              ];
            };
            strictDeps = true;
            # tonic-build shells out to protoc.
            nativeBuildInputs = [ pkgs.protobuf ];
          };
        in {
          inherit junit-runner;
          default = junit-runner;
        });

      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.cargo pkgs.rustc pkgs.protobuf pkgs.rust-analyzer ];
        };
      });
    };
}
