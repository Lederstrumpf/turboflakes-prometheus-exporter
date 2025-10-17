{ pkgs ? import <nixpkgs> { } }:

let
  graalvm = pkgs.graalvm-ce;
  
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
    graalvm
    makeWrapper
  ];

  buildInputs = with pkgs; [
    curl
    zlib
  ];

  buildPhase = ''
    export HOME=$TMPDIR

    # Compile the Clojure code
    mkdir -p target/classes
    cp -r src/* target/classes/

    ${graalvm}/bin/java -cp "${classpath}:target/classes" clojure.main -e "(binding [*compile-path* \"target/classes\"] (compile 'turboflakes-monitor.core))"

    # Extract all dependencies
    mkdir -p jar-contents
    cd jar-contents
    
    ${graalvm}/bin/jar xf ${clojure-core}/share/java/clojure-*.jar
    ${graalvm}/bin/jar xf ${clojure-spec-alpha}/share/java/spec.alpha-*.jar
    ${graalvm}/bin/jar xf ${clojure-core-specs-alpha}/share/java/core.specs.alpha-*.jar
    ${graalvm}/bin/jar xf ${clojure-data-json}/share/java/data.json-*.jar
    
    rm -rf META-INF/*.SF META-INF/*.DSA META-INF/*.RSA
    cp -r ../target/classes/* .
    
    ${graalvm}/bin/jar cfe ../turboflakes-monitor.jar turboflakes_monitor.core .
    cd ..

    # Create reflection configuration
    mkdir -p META-INF/native-image
    cat > META-INF/native-image/reflect-config.json <<EOF
    [
      {
        "name": "com.sun.net.httpserver.HttpServer",
        "allDeclaredConstructors": true,
        "allPublicConstructors": true,
        "allDeclaredMethods": true,
        "allPublicMethods": true
      },
      {
        "name": "com.sun.net.httpserver.HttpExchange",
        "allDeclaredMethods": true,
        "allPublicMethods": true
      },
      {
        "name": "com.sun.net.httpserver.HttpHandler",
        "allDeclaredMethods": true,
        "allPublicMethods": true
      },
      {
        "name": "java.net.InetSocketAddress",
        "allDeclaredConstructors": true,
        "allPublicConstructors": true
      },
      {
        "name": "java.util.concurrent.Executors",
        "allDeclaredMethods": true,
        "allPublicMethods": true
      }
    ]
    EOF

    # Build native image
    ${graalvm}/bin/native-image \
      --no-fallback \
      --initialize-at-build-time \
      --report-unsupported-elements-at-runtime \
      -H:+ReportExceptionStackTraces \
      -H:ReflectionConfigurationFiles=META-INF/native-image/reflect-config.json \
      --allow-incomplete-classpath \
      --verbose \
      -jar turboflakes-monitor.jar \
      turboflakes-monitor
  '';

  installPhase = ''
    mkdir -p $out/bin

    cp turboflakes-monitor $out/bin/

    wrapProgram $out/bin/turboflakes-monitor \
      --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.curl ]}
  '';

  meta = with pkgs.lib; {
    description = "Prometheus exporter for TurboFlakes validator metrics";
    license = licenses.mit;
    platforms = platforms.linux;
  };
}
