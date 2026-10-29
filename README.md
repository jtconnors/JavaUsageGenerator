# Java Usage Generator (JUG)

Java Usage Generator is a desktop application for creating sample
[Java Usage Tracker](https://docs.oracle.com/en/java/java-components/usage-tracker/)
(JUT) records. It provides a graphical interface for selecting Java versions,
applications, hosts, and operating-system versions, then combines those choices
into individual records or timed packet series.

JUG is a modular Java application built with Maven and JDK 25. Its packaged
macOS and Windows applications include a platform-specific minimal Java runtime
created with `jlink`, so an end user does not need to install Java, Maven,
Python, or Node.js.

## Features

- Select Java releases across multiple JDK families.
- Select Java applications and their associated classpaths.
- Select host names, IP addresses, platforms, and OS versions.
- Search, filter, select, and clear values independently on each tab.
- Save and reload Java-version selections as properties files.
- Export application, host, and OS selections as separate JSON files.
- Generate a random 15-field JUT record using the current selections.
- Randomize record timestamps between now and 90 days ago.
- Generate a configurable series of records at millisecond, second, or minute
  intervals.
- Optionally transmit generated records over UDP.
- Configure the UDP host and port; the initial port is `32139`.
- Save generated records as `.jut` files.

All available choices are selected when the application starts.

## Requirements

Building JUG requires:

- macOS or Windows
- JDK 25
- Apache Maven 3.9 or later

The Maven Enforcer Plugin rejects builds that are not running on JDK 25.
Native application images are not cross-compiled: build on macOS for macOS and
on Windows for Windows. The resulting image targets the architecture of the JDK
used for the build.

## Build

Clone the repository, enter its directory, and package the application.

On macOS:

```bash
git clone git@github.com:jtconnors/JavaUsageGenerator.git
cd JavaUsageGenerator
JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn clean package
```

On Windows PowerShell, with `JAVA_HOME` pointing to JDK 25:

```powershell
git clone git@github.com:jtconnors/JavaUsageGenerator.git
Set-Location JavaUsageGenerator
mvn clean package
```

Both builds create the modular JAR and linked runtime:

```text
target/java-usage-generator.jar
target/runtime/
```

The native application image is platform-specific:

```text
# macOS
target/dist/Java Usage Generator.app

# Windows
target/dist/Java Usage Generator/
```

Maven compiles the named module, runs `jlink` to construct a minimal runtime,
and activates the host OS profile to run `jpackage`. The `macos` profile adds
macOS bundle metadata. The `windows` profile creates a GUI launcher and a
separate console launcher.

## Run

On macOS, open the packaged application from Finder or run:

```bash
open "target/dist/Java Usage Generator.app"
```

On Windows, open `target\dist\Java Usage Generator` in File Explorer and
double-click `Java Usage Generator.exe`, or run from PowerShell:

```powershell
& ".\target\dist\Java Usage Generator\Java Usage Generator.exe"
```

The applications are locally packaged. The macOS image is not notarized, and
the Windows image is not code-signed.

## Application Tabs

### Generate

The upper section creates one sample JUT record. Each click replaces the sample
with a newly randomized record based on the selected Java version, application,
host, and OS version.

The lower section creates a timed packet series. Choose the number of records,
an interval and unit, and whether records should also be sent over UDP. A run can
be stopped early, and generated records can be saved together.

### Java Versions

Select individual Java releases or entire JDK families. Selections can be saved
as a properties file and loaded again later.

### Java Applications

Select applications from the bundled application catalog. Each application
includes the distinct classpath values associated with it in the source data.

### Hosts / IPs

Select bundled host names and IP addresses, grouped by platform.

### OS Versions

Select operating-system versions, names, architectures, and platforms.

## Command-Line Interface

The application image also provides a command-line interface for Java-version
selection.

On macOS:

```bash
APP="target/dist/Java Usage Generator.app/Contents/MacOS/Java Usage Generator"

"$APP" --help
"$APP" --list
"$APP" --all
"$APP" --family 8 --version 17.0.3.1 --output versions.properties
"$APP" --gui --input versions.properties
```

On Windows PowerShell, use the console-enabled `jug.exe` launcher:

```powershell
$JUG = ".\target\dist\Java Usage Generator\jug.exe"

& $JUG --help
& $JUG --list
& $JUG --all
& $JUG --family 8 --version 17.0.3.1 --output versions.properties
& $JUG --gui --input versions.properties
```

Use `--catalog FILE` to load a different Java release catalog. Existing output
files are protected unless `--force` is supplied.

## Configuration Catalogs

The application packages four independent catalogs from `src/main/resources`:

| File | Contents |
| --- | --- |
| `catalog.json` | Java families, releases, and `java.version` mappings |
| `applications.json` | Applications, commands, start types, and classpaths |
| `hosts.json` | Host names, IP addresses, and platforms |
| `os-versions.json` | OS versions, names, architectures, and platforms |

These resources are embedded in both the modular JAR and the linked runtime.

## Java Modules

The application module is `com.jtconnors.jug` and directly requires only
`java.desktop`. The linked runtime contains:

```text
com.jtconnors.jug
java.base
java.datatransfer
java.desktop
java.prefs
java.xml
```

## Verification

After packaging, run the application-level test.

On macOS:

```bash
python3 test_app.py "target/dist/Java Usage Generator.app"
```

On Windows:

```powershell
py -3 test_app.py ".\target\dist\Java Usage Generator"
```

The test verifies the packaged launcher, Java mappings, bundled catalogs,
selection loading, compatibility behavior, invalid-data handling, and overwrite
protection. Python is needed only for this optional test, not for building or
running JUG.

## Platform Packaging

Maven automatically activates one of two profiles:

| Profile | Output | Launchers |
| --- | --- | --- |
| `macos` | `Java Usage Generator.app` | Native macOS application launcher |
| `windows` | `Java Usage Generator` directory | GUI `.exe` and console `jug.exe` |

Both profiles package the same Java module and catalogs. The linked runtime is
created locally from the host's JDK 25 and therefore contains the native runtime
files for that operating system and architecture.

## UDP Notice

UDP transmission is disabled by default. Only enable it when you control or
have permission to send data to the configured destination.
