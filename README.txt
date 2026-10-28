Java Usage Generator (JUG) — standalone macOS application

Double-click Java Usage Generator.app in target/dist to customize version, application,
host, and OS-version choices on separate tabs. The application bundles its Java runtime and all JSON
catalogs; it requires no Python,
Node.js, browser, or separately installed Java. This build targets Apple Silicon
macOS. The application is locally packaged, not notarized for distribution.

Command-line mode uses the executable inside the same application:
"target/dist/Java Usage Generator.app/Contents/MacOS/Java Usage Generator" --help
"target/dist/Java Usage Generator.app/Contents/MacOS/Java Usage Generator" --family 8 --version 17.0.3.1 --output versions.properties
"target/dist/Java Usage Generator.app/Contents/MacOS/Java Usage Generator" --gui --input versions.properties

--catalog FILE loads a refreshed oracle-jdk-releases.json; the GUI has a Load
catalog button too. The bundled snapshot works even without the JUG source
folder. Refreshing the source JSON does not change the bundled snapshot.

Use the family filter to choose a JDK family, then Select entire family or
individual Include checkboxes. Search filters the displayed rows. Family and
Select all actions also include hidden rows. Save properties writes the same
schema as the earlier selector. Load selection reopens configurations from
either interface. Nothing is generated or sent over the network at this step.

The Java Applications tab searches the separate applications.json catalog and
saves generator-applications.json. Select real and Select fictional provide quick
group choices. The table, search, preview, and saved JSON include the distinct
classpath values associated with every application. This output is independent
of the version properties file.

The Hosts / IPs tab searches the separate hosts.json catalog by hostname, IP
address, or platform and saves generator-hosts.json.

The OS Versions tab searches the separate os-versions.json catalog by version,
OS name, architecture, or platform and saves generator-os-versions.json.

The leftmost Generate tab randomly chooses one selected value from every
configuration tab and a time within the previous 90 days. Each press of Generate
a sample JUT packet replaces the quoted 15-field CSV record in the tab's text
area. The current sample can be saved as sample.jut.

The lower Generate section creates a timed packet series. Choose a packet count,
an interval in milliseconds, seconds, or minutes, then start generation. Enable
Send via UDP to transmit each generated packet to a hostname and port. The
initial UDP destination is localhost:32139, matching Oracle's Java Usage Tracker
template. Packets also append to the output area as the interval elapses; the run
can be stopped and saved as packet-series.jut.

JUG is the named Java module com.jtconnors.jug. Sources are under src/main/java.
Build with Maven running on JDK 25:

JAVA_HOME=/path/to/jdk-25 mvn clean package

Maven compiles the modular JAR, uses JDK 25 jlink to create target/runtime, and
uses jpackage to create target/dist/Java Usage Generator.app. The linked runtime
contains only the JUG module and its required Java modules. Build tooling is only
needed to rebuild the application, not to run the packaged application.
