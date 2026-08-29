{
  # The tools the buck2 build runs, as flake packages: buck2.nix's flake.package() realises
  # `packages.<system>.<name>` on whichever machine executes the action -- this one, or a remote
  # worker that has nothing but nix -- so the same definition serves local and remote execution.
  # The root flake's devShell pulls the same packages, so the shell and the build cannot drift.
  description = "Velocity build tools";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
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
    in {
      packages = forAll (pkgs: {
        # Upstream builds with a 25 toolchain and the class files say so.
        jdk = pkgs.jdk25;
        # rustc + cargo + rust-std for the host and for the two Linux cross targets. Linking
        # for those is zig's job; rustc only needs their std.
        rust = pkgs.rust-bin.stable.latest.minimal.override {
          targets = [ "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu" ];
        };
        # The cross linker for the Linux cdylib slots.
        zig = pkgs.zig;
        # The prelude drives rustc and build scripts through small python scripts.
        python = pkgs.python3;
        # The C toolchain for code that runs on our own machines and workers: nix's cc, so a
        # build-script binary linked on a worker runs on that worker (the worker image has no
        # /lib/ld-linux for a glibc-2.17 zig link to find). The binaries buck2.nix's
        # nix_cxx_toolchain looks up, in one bin/.
        cxx = let
          cc = pkgs.stdenv.cc;
          bintools = cc.bintools;
          objcopy = if pkgs.stdenv.isDarwin
            then "${pkgs.llvmPackages.bintools-unwrapped}/bin/llvm-objcopy"
            else "${bintools.bintools}/bin/objcopy";
        in pkgs.runCommandLocal "cxx" {} (''
          mkdir -p $out/bin
          ln -s ${cc}/bin/cc $out/bin/cc
          ln -s ${cc}/bin/c++ $out/bin/c++
          for t in ar nm ranlib strip; do ln -s ${bintools}/bin/$t $out/bin/$t; done
          ln -s ${objcopy} $out/bin/objcopy
        '');
      });
    };
}
