{
  description = "Clojure development flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShell = pkgs.mkShell {
          name = "clojure-dev";
          buildInputs = [
            pkgs.clojure
            pkgs.leiningen
            pkgs.babashka
          ];
          shellHook = ''
            echo "Welcome to the Clojure dev shell!"
          '';
        };
      });
}
