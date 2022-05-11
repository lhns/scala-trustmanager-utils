# scala-trustmanager-utils

[![Test Workflow](https://github.com/LolHens/scala-trustmanager-utils/workflows/test/badge.svg)](https://github.com/LolHens/scala-trustmanager-utils/actions?query=workflow%3Atest)
[![Release Notes](https://img.shields.io/github/release/LolHens/scala-trustmanager-utils.svg?maxAge=3600)](https://github.com/LolHens/scala-trustmanager-utils/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lolhens/scala-trustmanager-utils_2.13)](https://search.maven.org/artifact/de.lolhens/scala-trustmanager-utils_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/scala-trustmanager-utils.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

This project provides helpers to create, combine and load TrustManagers.

## Usage

### build.sbt

```sbt
libraryDependencies += "de.lolhens" %% "scala-trustmanager-utils" % "0.3.1"
```

### Example

Read certificate files from `https_cert_path` and add them to the default trust manager.

```scala
setDefaultTrustManager(jreTrustManagerWithEnvVar)
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
