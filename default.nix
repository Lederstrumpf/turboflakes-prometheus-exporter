{ pkgs ? import <nixpkgs> { } }:

pkgs.stdenv.mkDerivation rec {
  pname = "turboflakes-monitor";
  version = "0.1.0";

  src = ./.;

  nativeBuildInputs = with pkgs; [
    clojure
    jdk
  ];

  buildInputs = with pkgs; [
    curl
  ];

  buildPhase = ''
    export HOME=$TMPDIR
    clojure -X:uberjar
  '';

  installPhase = ''
    mkdir -p $out/bin $out/share/java
    
    cp turboflakes-monitor.jar $out/share/java/
    
    cat > $out/bin/turboflakes-monitor <<EOF
    #!${pkgs.bash}/bin/bash
    export PATH="${pkgs.curl}/bin:\$PATH"
    exec ${pkgs.jdk}/bin/java -jar $out/share/java/turboflakes-monitor.jar "\$@"
    EOF
    
    chmod +x $out/bin/turboflakes-monitor
  '';

  meta = with pkgs.lib; {
    description = "Prometheus exporter for TurboFlakes validator metrics";
    license = licenses.mit;
    platforms = platforms.linux;
    maintainers = [ ];
  };
}
