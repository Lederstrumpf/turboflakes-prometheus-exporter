{
  pkgs ? import <nixpkgs> { },
}:

let
  clojure-data-json = pkgs.fetchMavenArtifact {
    groupId = "org.clojure";
    artifactId = "data.json";
    version = "2.4.0";
    sha256 = "sha256-7D8vmU4e7dQgMTxFK6VRjF9cl75RUt/tVlC8ZhFIat8=";
  };

  clojure-core = pkgs.fetchMavenArtifact {
    groupId = "org.clojure";
    artifactId = "clojure";
    version = "1.11.1";
    sha256 = "sha256-I4G26UI6tGUVFFWUSQPROlYkPWAGuRlK/Bv0+HEMtN4=";
  };

  clojure-spec-alpha = pkgs.fetchMavenArtifact {
    groupId = "org.clojure";
    artifactId = "spec.alpha";
    version = "0.3.218";
    sha256 = "sha256-Z+yJjrVcZqlXpVJ53YXRN2u5lL2HZosrDeHrO5foquA=";
  };

  clojure-core-specs-alpha = pkgs.fetchMavenArtifact {
    groupId = "org.clojure";
    artifactId = "core.specs.alpha";
    version = "0.2.62";
    sha256 = "sha256-Bu6owHC75FwVhWfkQ0OWgbyMRukSNBT4G/oyukLWy8g=";
  };

  classpath = pkgs.lib.concatStringsSep ":" [
    "${clojure-core}/share/java/*"
    "${clojure-spec-alpha}/share/java/*"
    "${clojure-core-specs-alpha}/share/java/*"
    "${clojure-data-json}/share/java/*"
  ];

in
pkgs.stdenv.mkDerivation rec {
  pname = "turboflakes-monitor";
  version = "0.1.0";

  src = ./.;

  nativeBuildInputs = with pkgs; [
    jdk
    makeWrapper
  ];

  buildInputs = with pkgs; [
    curl
  ];

  buildPhase = ''
    export HOME=$TMPDIR

    # Compile the Clojure code
    mkdir -p target/classes

    # Copy source files to target for compilation
    cp -r src/* target/classes/

    # Use clojure.main to compile with source in classpath
    ${pkgs.jdk}/bin/java -cp "${classpath}:target/classes" clojure.main -e "(binding [*compile-path* \"target/classes\"] (compile 'turboflakes-monitor.core))"

    # Extract all dependencies into a single directory
    mkdir -p jar-contents
    cd jar-contents

    ${pkgs.jdk}/bin/jar xf ${clojure-core}/share/java/clojure-*.jar
    ${pkgs.jdk}/bin/jar xf ${clojure-spec-alpha}/share/java/spec.alpha-*.jar
    ${pkgs.jdk}/bin/jar xf ${clojure-core-specs-alpha}/share/java/core.specs.alpha-*.jar
    ${pkgs.jdk}/bin/jar xf ${clojure-data-json}/share/java/data.json-*.jar

    # Remove signature files that can cause issues
    rm -rf META-INF/*.SF META-INF/*.DSA META-INF/*.RSA

    # Copy compiled classes and source
    cp -r ../target/classes/* .

    # Create the final JAR
    ${pkgs.jdk}/bin/jar cfe ../turboflakes-monitor.jar turboflakes_monitor.core .

    cd ..
  '';

  installPhase = ''
    mkdir -p $out/bin $out/share/java

    cp turboflakes-monitor.jar $out/share/java/

    makeWrapper ${pkgs.jdk}/bin/java $out/bin/turboflakes-monitor \
      --add-flags "-jar $out/share/java/turboflakes-monitor.jar" \
      --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.curl ]}
  '';

  meta = with pkgs.lib; {
    description = "Prometheus exporter for TurboFlakes validator metrics";
    license = licenses.mit;
    platforms = platforms.linux;
    maintainers = [ ];
  };
}
