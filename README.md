# Java Usage Generator (JUG)

Java Usage Generator is a JavaFX desktop application for creating sample
[Java Usage Tracker](https://docs.oracle.com/en/java/java-components/usage-tracker/)
(JUT) records. It provides a graphical interface for selecting Java versions,
applications, hosts, and operating-system versions, then combines those choices
into individual records or timed packet series.

JUG is a modular JavaFX application built with Maven and JDK 25. Its packaged
macOS and Windows applications include a platform-specific minimal Java runtime
created with `jlink`, including the JavaFX modules and native libraries it uses,
so an end user does not need to install Java, JavaFX, Maven, Python, or Node.js.

## Features

- Select Java releases across multiple JDK families.
- Refresh released JDK 8-and-later versions from `ops.java`.
- Select Java applications and their associated classpaths.
- Select host names, IP addresses, platforms, and OS versions.
- Add custom applications, hosts, and OS versions from their respective tabs.
- Search, filter, select, and clear values independently on each tab.
- Save and reload Java-version selections as JSON files.
- Export application, host, and OS selections as separate JSON files.
- Generate a random 15-field JUT record using the current selections.
- Configure record timestamps to use the current time, a random time in the last
  30, 90, or 180 days, or a custom date/time range.
- Generate a configurable series of records at millisecond, second, or minute
  intervals.
- Optionally transmit generated records over UDP.
- Configure the UDP host and port; the initial port is `32139`.
- Receive UDP JUT records on a configurable local bind address and port and
  inspect them in the application.
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

Maven compiles the named module and uses the OpenJFX Maven plugin to run `jlink`
with the platform-specific JavaFX dependencies. It then activates the host OS
profile to run `jpackage`. The `macos` profile adds macOS bundle metadata. The
`windows` profile creates a GUI launcher and a separate console launcher.

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

### Listen

The Listen tab receives UDP JUT records and displays each packet as it arrives.
The listener defaults to `0.0.0.0` on port `32139`, accepting packets addressed
to any local IPv4 interface. Use `localhost` to accept packets only from this
computer, or enter the address of a specific local network interface. A remote
computer's address cannot be used as a bind address. The bind address and port
can be changed before listening starts, and displayed packets can be cleared
without stopping the listener.

### Timestamps

Choose how timestamps are assigned to generated JUT records. The default is a
random time in the last 90 days, preserving JUG's original behavior. Other
options use the current time, a random time in the last 30 or 180 days, or a
custom start and end date/time. Timestamp controls are locked while a packet
series is running so the rule remains consistent throughout the run.

### Java Versions

Select individual Java releases or entire JDK families. Selections can be saved
as a JSON configuration and loaded again later.

Click **Update for latest versions** to retrieve Oracle's current release feed
from [ops.java](https://ops.java/releases/). The update is additive:

- Delivered standard releases and Security Alerts for JDK 8 and later are
  considered.
- Existing releases are never removed.
- JDK releases before 8 are preserved unchanged.
- Future releases are excluded.
- Security Alert identifiers are preserved as published by `ops.java`, such as
  `27.0.1.0.3` and `8u511b33`. For Java 8 alerts, the expected Usage Tracker
  `java.version` omits the build suffix, such as `1.8.0_511`.
- Existing selections remain selected; newly discovered versions appear in the
  table for the user to select.

Some Oracle/Akamai edge locations reject direct desktop HTTP clients with a
`403` response. When that happens, JUG retries through the read-only
`r.jina.ai` web renderer, verifies that the rendered source is
`https://ops.java/releases.json`, and applies the same validation and filtering
before saving anything. No catalog selections or local data are sent with
either request.

The refreshed catalog is saved outside the installed application and loaded
automatically on subsequent launches:

```text
# macOS
~/Library/Application Support/JavaUsageGenerator/catalog.json

# Windows
%APPDATA%\JavaUsageGenerator\catalog.json
```

The status line reports whether versions were added and displays the exact path
of the saved catalog. The application falls back to its bundled catalog if the
saved catalog is absent or invalid.

### Java Applications

Select applications from the bundled application catalog. Each application
includes the distinct classpath values associated with it in the source data.
Use the **+** button to add an application with its Java majors, classpaths,
commands, and start types.

### Hosts / IPs

Select bundled host names and IP addresses, grouped by platform.
Use the **+** button to add a hostname, IPv4 address, and platform.

### OS Versions

Select operating-system versions, names, architectures, and platforms.
Use the **+** button to add another OS version. New entries are selected by
default and are immediately available to the packet generator.

Custom entries are stored separately from the bundled catalogs and merged into
the available choices whenever JUG starts:

```text
# macOS
~/Library/Application Support/JavaUsageGenerator/custom-applications.json
~/Library/Application Support/JavaUsageGenerator/custom-hosts.json
~/Library/Application Support/JavaUsageGenerator/custom-os-versions.json

# Windows
%APPDATA%\JavaUsageGenerator\custom-applications.json
%APPDATA%\JavaUsageGenerator\custom-hosts.json
%APPDATA%\JavaUsageGenerator\custom-os-versions.json
```

The bundled JSON resources remain unchanged, so installing a newer JUG build
does not overwrite the user's custom entries.

## Command-Line Interface

The application image also provides a command-line interface for Java-version
selection.

On macOS:

```bash
APP="target/dist/Java Usage Generator.app/Contents/MacOS/Java Usage Generator"

"$APP" --help
"$APP" --list
"$APP" --all
"$APP" --family 8 --version 17.0.3.1 --output versions.json
"$APP" --gui --input versions.json
```

On Windows PowerShell, use the console-enabled `jug.exe` launcher:

```powershell
$JUG = ".\target\dist\Java Usage Generator\jug.exe"

& $JUG --help
& $JUG --list
& $JUG --all
& $JUG --family 8 --version 17.0.3.1 --output versions.json
& $JUG --gui --input versions.json
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

Saved selections use separate JSON configuration files:

| File | Selection |
| --- | --- |
| `generator-versions.json` | Java versions and their `java.version` mappings |
| `generator-applications.json` | Java applications and classpaths |
| `generator-hosts.json` | Hosts and IP addresses |
| `generator-os-versions.json` | Operating-system versions |

## Java Modules

The application module is `com.jtconnors.jug` and directly requires only
`java.desktop`. The linked runtime contains:

```text
com.jtconnors.jug
java.base
java.datatransfer
java.desktop
java.net.http
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

Listening on `0.0.0.0` exposes the configured UDP port on every local IPv4
interface. Ensure the operating-system and network firewalls permit the desired
senders, and use `localhost` when remote access is not needed.
