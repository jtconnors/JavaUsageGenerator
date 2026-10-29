package com.jtconnors.jug;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

public class JugSelector {
    static final int DEFAULT_UDP_PORT=32139;
    static final long DAY_MILLIS=24L*60*60*1000;
    record Entry(String family, String version, String label, String javaVersion, String kind) {}
    record Catalog(String date, List<Entry> entries, List<String> families) {}
    record FeedDownload(String body, boolean fallback) {}
    record CatalogUpdate(Catalog catalog, int additions, Path path, boolean fallback) {}
    record ApplicationEntry(String id, String name, String type, String category, List<String> javaMajors, List<String> classPaths, List<String> javaCommands, List<String> startTypes) {}
    record ApplicationCatalog(String date, List<ApplicationEntry> entries) {}
    record HostEntry(String id, String value, String hostName, String ipAddress, String platform) {}
    record HostCatalog(String date, List<HostEntry> entries) {}
    record OsVersionEntry(String id, String version, String platform, List<String> osNames, List<String> architectures) {}
    record OsVersionCatalog(String date, List<OsVersionEntry> entries) {}
    static final String HELP = """
        Java Usage Generator (JUG) — standalone application
        No arguments opens the GUI.
        --gui                  Open customization window
        --list                 List family, version, and javaVersion
        --family ID            Include an entire family (repeatable)
        --version VERSION      Include a version (repeatable)
        --all                  Include all cataloged versions
        --exclude-family ID    Exclude a family after inclusions (repeatable)
        --exclude-version V    Exclude a version after inclusions (repeatable)
        --input FILE           Load a saved selection
        --output FILE          Save JSON (otherwise print to stdout)
        --force                Allow replacing an existing output
        --catalog FILE         Load an updated JSON catalog
        --help                 Print this help
        Family choices expand to the versions currently listed in the catalog.
        """;
    static final Set<String> FLAGS = Set.of("gui", "list", "all", "force", "help");
    static final Set<String> VALUES = Set.of("family", "version", "exclude-family", "exclude-version", "catalog", "applications-catalog", "hosts-catalog", "os-versions-catalog", "input", "output");
    static Map<String, List<String>> options(String[] args) {
        var options = new LinkedHashMap<String, List<String>>();
        for (int i=0; i<args.length; i++) {
            String arg=args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("Unknown argument: " + arg);
            String key=arg.substring(2);
            if (!FLAGS.contains(key) && !VALUES.contains(key)) throw new IllegalArgumentException("Unknown option: " + arg);
            String value="true";
            if (VALUES.contains(key)) {
                if (++i==args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for " + arg);
                value=args[i];
            }
            if (Set.of("catalog", "input", "output").contains(key) && options.containsKey(key)) throw new IllegalArgumentException("Repeated option: " + arg);
            options.computeIfAbsent(key, k->new ArrayList<>()).add(value);
        }
        return options;
    }
    static String value(Map<String,List<String>> options, String key) { return options.get(key).getFirst(); }
    public static void main(String[] args) {
        try {
            var options=options(args);
            if (options.containsKey("help")) { System.out.print(HELP); return; }
            Catalog catalog=options.containsKey("catalog")
                ? readCatalog(Files.readString(Path.of(value(options,"catalog"))))
                : readDefaultCatalog();
            ApplicationCatalog applicationCatalog;
            if (options.containsKey("applications-catalog")) applicationCatalog=readApplicationCatalog(Files.readString(Path.of(value(options,"applications-catalog"))));
            else applicationCatalog=readDefaultApplicationCatalog();
            HostCatalog hostCatalog;
            if (options.containsKey("hosts-catalog")) hostCatalog=readHostCatalog(Files.readString(Path.of(value(options,"hosts-catalog"))));
            else hostCatalog=readDefaultHostCatalog();
            OsVersionCatalog osVersionCatalog;
            if (options.containsKey("os-versions-catalog")) osVersionCatalog=readOsVersionCatalog(Files.readString(Path.of(value(options,"os-versions-catalog"))));
            else osVersionCatalog=readDefaultOsVersionCatalog();
            if (options.containsKey("list")) {
                if (options.containsKey("gui")) throw new IllegalArgumentException("Choose --gui or --list");
                System.out.println("Family\tVersion\tjavaVersion");
                for (var e:catalog.entries) System.out.println(e.family+"\t"+e.version+"\t"+e.javaVersion);
                return;
            }
            Set<String> selected=options.containsKey("input") ? readSelection(Path.of(value(options,"input")),catalog) : new HashSet<>();
            boolean guiRequested=args.length==0 || options.containsKey("gui");
            boolean hasVersionBase=options.containsKey("input") || options.containsKey("all") || options.containsKey("family") || options.containsKey("version");
            if(guiRequested && !hasVersionBase) for(var e:catalog.entries) selected.add(e.version);
            if (options.containsKey("all")) for (var e:catalog.entries) selected.add(e.version);
            for (String family:options.getOrDefault("family",List.of())) selectFamily(catalog,selected,family,true);
            for (String version:options.getOrDefault("version",List.of())) { requireVersion(catalog,version); selected.add(version); }
            for (String family:options.getOrDefault("exclude-family",List.of())) selectFamily(catalog,selected,family,false);
            for (String version:options.getOrDefault("exclude-version",List.of())) { requireVersion(catalog,version); selected.remove(version); }
            if (args.length==0 || options.containsKey("gui")) {
                if (options.containsKey("output") || options.containsKey("force")) throw new IllegalArgumentException("Use Save JSON configuration in GUI mode");
                final Catalog initial=catalog;
                SwingUtilities.invokeLater(()->{
                    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                    catch (Exception ignored) {}
                    new Window(initial,selected,applicationCatalog,hostCatalog,osVersionCatalog).setVisible(true);
                });
            } else {
                String content=versionConfiguration(catalog,selected);
                if (!options.containsKey("output")) System.out.print(content);
                else {
                    Path output=Path.of(value(options,"output")).toAbsolutePath().normalize();
                    for (String source:List.of("catalog","input")) if(options.containsKey(source)) {
                        Path input=Path.of(value(options,source)).toAbsolutePath().normalize();
                        if(output.equals(input) || (Files.exists(output) && Files.isSameFile(output,input))) throw new IllegalArgumentException("Output must differ from catalog and input");
                    }
                    Files.writeString(output,content,StandardCharsets.UTF_8,options.containsKey("force")?new StandardOpenOption[]{StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING}:new StandardOpenOption[]{StandardOpenOption.CREATE_NEW});
                    System.err.println("Saved "+selected.size()+" versions to "+output);
                }
            }
        } catch(Exception error) { System.err.println("Error: "+error.getMessage()); System.exit(1); }
    }
    static Catalog readDefaultCatalog() throws IOException {
        Path saved=userCatalogPath();
        if(Files.isRegularFile(saved)) {
            try { return readCatalog(Files.readString(saved,StandardCharsets.UTF_8)); }
            catch(RuntimeException ignored) {}
        }
        try(var stream=JugSelector.class.getResourceAsStream("/catalog.json")) {
            if(stream==null) throw new IOException("Bundled catalog missing");
            return readCatalog(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    static Path userDataDirectory() {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT),home=System.getProperty("user.home");
        if(os.contains("mac")) return Path.of(home,"Library","Application Support","JavaUsageGenerator");
        if(os.contains("win")) {
            String appData=System.getenv("APPDATA");
            return Path.of(appData==null||appData.isBlank()?Path.of(home,"AppData","Roaming").toString():appData,"JavaUsageGenerator");
        }
        return Path.of(home,".java-usage-generator");
    }
    static Path userCatalogPath() { return userDataDirectory().resolve("catalog.json"); }
    static Path customApplicationsPath() { return userDataDirectory().resolve("custom-applications.json"); }
    static Path customHostsPath() { return userDataDirectory().resolve("custom-hosts.json"); }
    static Path customOsVersionsPath() { return userDataDirectory().resolve("custom-os-versions.json"); }
    static String bundledResource(String name) throws IOException {
        try(var stream=JugSelector.class.getResourceAsStream(name)) {
            if(stream==null) throw new IOException("Bundled resource missing: "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static ApplicationCatalog readDefaultApplicationCatalog() throws IOException {
        var base=readApplicationCatalog(bundledResource("/applications.json"));
        Path path=customApplicationsPath();
        return Files.isRegularFile(path)?mergeApplicationCatalogs(base,readApplicationCatalog(Files.readString(path,StandardCharsets.UTF_8))):base;
    }
    static HostCatalog readDefaultHostCatalog() throws IOException {
        var base=readHostCatalog(bundledResource("/hosts.json"));Path path=customHostsPath();
        return Files.isRegularFile(path)?mergeHostCatalogs(base,readHostCatalog(Files.readString(path,StandardCharsets.UTF_8))):base;
    }
    static OsVersionCatalog readDefaultOsVersionCatalog() throws IOException {
        var base=readOsVersionCatalog(bundledResource("/os-versions.json"));Path path=customOsVersionsPath();
        return Files.isRegularFile(path)?mergeOsVersionCatalogs(base,readOsVersionCatalog(Files.readString(path,StandardCharsets.UTF_8))):base;
    }
    static void writeAtomically(Path path,String content) throws IOException {
        Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),path.getFileName().toString()+"-",".tmp");
        try {Files.writeString(temporary,content,StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temporary);}
    }
    static CatalogUpdate fetchLatestCatalog(Catalog current) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        FeedDownload download=downloadReleaseFeed(client);
        Catalog merged=mergeOracleReleases(current,download.body);
        Path path=userCatalogPath();
        writeAtomically(path,catalogJson(merged));
        var oldVersions=new HashSet<String>();current.entries.forEach(entry->oldVersions.add(entry.version));
        int additions=(int)merged.entries.stream().filter(entry->!oldVersions.contains(entry.version)).count();
        return new CatalogUpdate(merged,additions,path,download.fallback);
    }
    static FeedDownload downloadReleaseFeed(HttpClient client) throws Exception {
        Exception directFailure;
        try {
            var response=sendReleaseRequest(client,"https://ops.java/releases.json","application/json");
            if(response.statusCode()==200) return new FeedDownload(response.body(),false);
            directFailure=new IOException("Oracle release service returned HTTP "+response.statusCode());
        } catch(Exception exception) { directFailure=exception; }
        try {
            var response=sendReleaseRequest(client,"https://r.jina.ai/https://ops.java/releases.json","text/plain");
            if(response.statusCode()!=200) throw new IOException("web fallback returned HTTP "+response.statusCode());
            return new FeedDownload(extractFallbackJson(response.body()),true);
        } catch(Exception fallbackFailure) {
            String directMessage=directFailure.getMessage()==null?directFailure.getClass().getSimpleName():directFailure.getMessage();
            String fallbackMessage=fallbackFailure.getMessage()==null?fallbackFailure.getClass().getSimpleName():fallbackFailure.getMessage();
            throw new IOException("Could not retrieve ops.java releases. "+directMessage+"; "+fallbackMessage,fallbackFailure);
        }
    }
    static HttpResponse<String> sendReleaseRequest(HttpClient client,String url,String accept) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).header("Accept",accept).header("User-Agent","JavaUsageGenerator/1.0").GET().build();
        return client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static String extractFallbackJson(String body) {
        String source="URL Source: https://ops.java/releases.json",marker="Markdown Content:";
        if(!body.contains(source)) throw new IllegalArgumentException("web fallback did not identify ops.java as its source");
        int markerAt=body.indexOf(marker),start=markerAt<0?-1:body.indexOf('{',markerAt+marker.length()),end=body.lastIndexOf('}');
        if(start<0 || end<start) throw new IllegalArgumentException("web fallback did not return the Oracle release JSON");
        return body.substring(start,end+1);
    }
    @SuppressWarnings("unchecked")
    static Catalog mergeOracleReleases(Catalog current,String text) {
        var root=(Map<String,Object>)new Json(text).read();
        String generated=(String)root.get("generated");
        if(generated==null || !generated.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Oracle release feed has an invalid generation date");
        var data=(Map<String,Object>)root.get("data");
        var releases=(List<Object>)data.get("releases");
        if(releases==null || releases.isEmpty()) throw new IllegalArgumentException("Oracle release feed contains no releases");
        var existing=new HashSet<String>();current.entries.forEach(entry->existing.add(entry.version));
        var additions=new ArrayList<Entry>();int delivered=0;
        for(var raw:releases) {
            var item=(Map<String,Object>)raw;
            if(!"DELIVERED".equals(item.get("status"))) continue;
            delivered++;
            int family=((java.math.BigDecimal)item.get("family")).intValueExact();
            String version=(String)item.get("version"),type=(String)item.get("type");
            boolean securityAlert="SA".equals(type);
            if(family<8 || version==null || !supportedVersion(family,version,securityAlert) || existing.contains(version)) continue;
            String javaVersion=expectedJavaVersion(family,version);
            String label=version.equals(String.valueOf(family))?"GA (JDK "+family+")":securityAlert?version+" (Security Alert)":version;
            additions.add(new Entry(String.valueOf(family),version,label,javaVersion,version.equals(String.valueOf(family))?"ga":"update"));
            existing.add(version);
        }
        if(delivered==0) throw new IllegalArgumentException("Oracle release feed contains no delivered releases");
        var legacy=new ArrayList<Entry>();var modern=new TreeMap<Integer,List<Entry>>();
        for(var entry:current.entries) {
            int family=familyNumber(entry.family);
            if(family<8) legacy.add(entry);else modern.computeIfAbsent(family,key->new ArrayList<>()).add(entry);
        }
        for(var entry:additions) modern.computeIfAbsent(Integer.parseInt(entry.family),key->new ArrayList<>()).add(entry);
        var merged=new ArrayList<Entry>(legacy);var families=new ArrayList<String>();
        for(var entry:legacy) if(!families.contains(entry.family)) families.add(entry.family);
        for(var group:modern.entrySet()) {
            group.getValue().sort((left,right)->compareVersions(left.version,right.version));
            merged.addAll(group.getValue());families.add(String.valueOf(group.getKey()));
        }
        return new Catalog(current.date.compareTo(generated)>=0?current.date:generated,List.copyOf(merged),List.copyOf(families));
    }
    static boolean standardVersion(int family,String version) {
        if(family==8) return version.matches("8(?:u\\d+)?");
        return version.matches(java.util.regex.Pattern.quote(String.valueOf(family))+"(?:\\.\\d+){0,3}");
    }
    static boolean supportedVersion(int family,String version,boolean securityAlert) {
        if(standardVersion(family,version)) return true;
        if(!securityAlert) return false;
        if(family==8) return version.matches("8u\\d+b\\d+");
        return version.matches(java.util.regex.Pattern.quote(String.valueOf(family))+"(?:\\.\\d+){4}");
    }
    static String expectedJavaVersion(int family,String version) {
        if(family!=8) return version;
        if(version.equals("8")) return "1.8.0";
        var match=java.util.regex.Pattern.compile("8u(\\d+)(?:b\\d+)?").matcher(version);
        if(!match.matches()) throw new IllegalArgumentException("Unsupported JDK 8 release version: "+version);
        return String.format(Locale.ROOT,"1.8.0_%02d",Integer.parseInt(match.group(1)));
    }
    static int familyNumber(String family) { return family.equals("1.4.2")?1:Integer.parseInt(family); }
    static int compareVersions(String left,String right) {
        int[] a=Arrays.stream(left.replaceFirst("^[^0-9]*","").split("[^0-9]+")).filter(part->!part.isEmpty()).mapToInt(Integer::parseInt).toArray();
        int[] b=Arrays.stream(right.replaceFirst("^[^0-9]*","").split("[^0-9]+")).filter(part->!part.isEmpty()).mapToInt(Integer::parseInt).toArray();
        for(int i=0;i<Math.max(a.length,b.length);i++){int av=i<a.length?a[i]:0,bv=i<b.length?b[i]:0;if(av!=bv)return Integer.compare(av,bv);}return left.compareTo(right);
    }
    static String catalogJson(Catalog catalog) {
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"asOf\": \"").append(catalog.date).append("\",\n  \"description\": \"JDK version catalog grouped by release family for selecting individual versions or all listed versions in a family.\",\n  \"sourceUrl\": \"https://ops.java/releases/\",\n  \"releases\": [\n");
        for(int i=0;i<catalog.families.size();i++) {
            String family=catalog.families.get(i);var entries=catalog.entries.stream().filter(entry->entry.family.equals(family)).toList();
            text.append("    {\n      \"id\": \"").append(jsonString(family)).append("\",\n      \"label\": \"JDK ").append(jsonString(family)).append("\",\n      \"versions\": [\n");
            for(int j=0;j<entries.size();j++){var entry=entries.get(j);text.append("        {\n          \"version\": \"").append(jsonString(entry.version)).append("\",\n          \"label\": \"").append(jsonString(entry.label)).append("\",\n          \"kind\": \"").append(jsonString(entry.kind)).append("\",\n          \"javaVersion\": \"").append(jsonString(entry.javaVersion)).append("\"\n        }");if(j+1<entries.size())text.append(',');text.append('\n');}
            text.append("      ]\n    }");if(i+1<catalog.families.size())text.append(',');text.append('\n');
        }
        return text.append("  ]\n}\n").toString();
    }
    static void requireVersion(Catalog catalog,String version) {
        if(catalog.entries.stream().noneMatch(e->e.version.equals(version))) throw new IllegalArgumentException("Unknown version: "+version);
    }
    static void selectFamily(Catalog catalog,Set<String> selected,String family,boolean include) {
        if(!catalog.families.contains(family)) throw new IllegalArgumentException("Unknown family: "+family);
        for(var e:catalog.entries) if(e.family.equals(family)) { if(include) selected.add(e.version); else selected.remove(e.version); }
    }
    static String versionConfiguration(Catalog catalog,Set<String> selected) {
        if(selected.isEmpty()) throw new IllegalArgumentException("Select at least one version");
        var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.version)).toList();
        if(chosen.size()!=selected.size()) throw new IllegalArgumentException("Selection contains unavailable versions");
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"versionCount\": ").append(chosen.size()).append(",\n  \"versions\": [\n");
        for(int i=0;i<chosen.size();i++) {
            var entry=chosen.get(i);
            text.append("    {\n      \"family\": \"").append(jsonString(entry.family)).append("\",\n      \"version\": \"").append(jsonString(entry.version)).append("\",\n      \"javaVersion\": \"").append(jsonString(entry.javaVersion)).append("\"\n    }");
            if(i+1<chosen.size()) text.append(',');
            text.append('\n');
        }
        return text.append("  ]\n}\n").toString();
    }
    @SuppressWarnings("unchecked")
    static Set<String> readSelection(Path path,Catalog catalog) throws IOException {
        var data=(Map<String,Object>)new Json(Files.readString(path,StandardCharsets.UTF_8)).read();
        if(!data.keySet().equals(Set.of("schemaVersion","catalogAsOf","versionCount","versions"))) throw new IllegalArgumentException("Unexpected version configuration fields");
        int schema=((java.math.BigDecimal)data.get("schemaVersion")).intValueExact();
        if(schema!=1) throw new IllegalArgumentException("Unsupported selection schema");
        String catalogDate=(String)data.get("catalogAsOf");
        if(catalogDate==null || !catalogDate.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid catalog date");
        int count=((java.math.BigDecimal)data.get("versionCount")).intValueExact();
        if(count<1 || count>catalog.entries.size()) throw new IllegalArgumentException("Invalid selection count");
        var versions=(List<Object>)data.get("versions");
        if(versions.size()!=count) throw new IllegalArgumentException("Version count does not match versions array");
        var known=new HashMap<String,Entry>(); for(var e:catalog.entries) known.put(e.version,e);
        var selected=new HashSet<String>();
        for(var raw:versions) {
            var item=(Map<String,Object>)raw;
            if(!item.keySet().equals(Set.of("family","version","javaVersion"))) throw new IllegalArgumentException("Unexpected selected version fields");
            String version=(String)item.get("version");
            Entry e=known.get(version);
            if(e==null || !selected.add(version)) throw new IllegalArgumentException("Unknown or duplicate version: "+version);
            if(!e.family.equals(item.get("family")) || !e.javaVersion.equals(item.get("javaVersion"))) throw new IllegalArgumentException("Catalog mapping mismatch: "+version);
        }
        return selected;
    }
    @SuppressWarnings("unchecked")
    static Catalog readCatalog(String text) {
        var data=(Map<String,Object>)new Json(text).read();
        String date=(String)data.get("asOf");
        if(date==null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid catalog date");
        var entries=new ArrayList<Entry>(); var families=new ArrayList<String>(); var versions=new HashSet<String>();
        for(var raw:(List<Object>)data.get("releases")) {
            var group=(Map<String,Object>)raw; String family=(String)group.get("id");
            if(family==null || !family.matches("[A-Za-z0-9._+-]+") || families.contains(family)) throw new IllegalArgumentException("Invalid family");
            families.add(family);
            var updates=(List<Object>)group.get("versions");
            if(updates.isEmpty()) throw new IllegalArgumentException("Empty family");
            for(var update:updates) {
                var item=(Map<String,Object>)update;
                String version=(String)item.get("version"),javaVersion=(String)item.get("javaVersion"),label=(String)item.get("label"),kind=(String)item.get("kind");
                if(version==null || javaVersion==null || label==null || !Set.of("ga","update").contains(kind) || !version.matches("[A-Za-z0-9._+-]+") || !javaVersion.matches("[A-Za-z0-9._+-]+") || !versions.add(version)) throw new IllegalArgumentException("Invalid or duplicate version");
                entries.add(new Entry(family,version,label,javaVersion,kind));
            }
        }
        if(entries.isEmpty()) throw new IllegalArgumentException("Empty catalog");
        return new Catalog(date,List.copyOf(entries),List.copyOf(families));
    }
    @SuppressWarnings("unchecked")
    static ApplicationCatalog readApplicationCatalog(String text) {
        var data=(Map<String,Object>)new Json(text).read();
        String date=(String)data.get("asOf");
        if(date==null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid application catalog date");
        var entries=new ArrayList<ApplicationEntry>();var ids=new HashSet<String>();var names=new HashSet<String>();
        for(var raw:(List<Object>)data.get("applications")) {
            var item=(Map<String,Object>)raw;
            String id=(String)item.get("id"),name=(String)item.get("name"),type=(String)item.get("type"),category=(String)item.get("category");
            var majors=((List<Object>)item.get("javaMajors")).stream().map(String::valueOf).toList();
            var classPaths=((List<Object>)item.get("classPaths")).stream().map(String::valueOf).toList();
            var javaCommands=((List<Object>)item.get("javaCommands")).stream().map(String::valueOf).toList();
            var startTypes=((List<Object>)item.get("startTypes")).stream().map(String::valueOf).toList();
            if(id==null || !id.matches("APP-\\d{3,}") || !ids.add(id) || name==null || name.isBlank() || !names.add(name) || type==null || category==null || majors.isEmpty() || classPaths.isEmpty() || classPaths.stream().anyMatch(String::isBlank) || new HashSet<>(classPaths).size()!=classPaths.size() || javaCommands.isEmpty() || javaCommands.stream().anyMatch(String::isBlank) || startTypes.isEmpty() || startTypes.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid or duplicate application");
            entries.add(new ApplicationEntry(id,name,type,category,majors,classPaths,javaCommands,startTypes));
        }
        if(entries.isEmpty()) throw new IllegalArgumentException("Empty application catalog");
        return new ApplicationCatalog(date,List.copyOf(entries));
    }
    static String applicationCatalogJson(ApplicationCatalog catalog) {
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"asOf\": \"").append(catalog.date).append("\",\n  \"description\": \"Custom Java applications created in Java Usage Generator.\",\n  \"applications\": [\n");
        for(int i=0;i<catalog.entries.size();i++){var entry=catalog.entries.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"name\": \"").append(jsonString(entry.name)).append("\",\n      \"type\": \"").append(jsonString(entry.type)).append("\",\n      \"category\": \"").append(jsonString(entry.category)).append("\",\n      \"javaMajors\": ");appendJsonArray(text,entry.javaMajors,"      ");text.append(",\n      \"classPaths\": ");appendJsonArray(text,entry.classPaths,"      ");text.append(",\n      \"javaCommands\": ");appendJsonArray(text,entry.javaCommands,"      ");text.append(",\n      \"startTypes\": ");appendJsonArray(text,entry.startTypes,"      ");text.append("\n    }");if(i+1<catalog.entries.size())text.append(',');text.append('\n');}
        return text.append("  ]\n}\n").toString();
    }
    static ApplicationCatalog mergeApplicationCatalogs(ApplicationCatalog base,ApplicationCatalog custom) {
        var entries=new ArrayList<ApplicationEntry>(base.entries);var ids=new HashSet<String>();var names=new HashSet<String>();base.entries.forEach(entry->{ids.add(entry.id);names.add(entry.name);});
        for(var entry:custom.entries)if(!ids.add(entry.id)||!names.add(entry.name))throw new IllegalArgumentException("Custom application duplicates a bundled application: "+entry.name);else entries.add(entry);
        return new ApplicationCatalog(base.date.compareTo(custom.date)>=0?base.date:custom.date,List.copyOf(entries));
    }
    static void appendCustomApplication(Path path,ApplicationEntry entry) throws IOException {
        var existing=Files.isRegularFile(path)?readApplicationCatalog(Files.readString(path,StandardCharsets.UTF_8)):new ApplicationCatalog(LocalDate.now().toString(),List.of());
        var entries=new ArrayList<>(existing.entries);entries.add(entry);var updated=new ApplicationCatalog(LocalDate.now().toString(),List.copyOf(entries));readApplicationCatalog(applicationCatalogJson(updated));writeAtomically(path,applicationCatalogJson(updated));
    }
    static String applicationConfiguration(ApplicationCatalog catalog,Set<String> selected) {
        if(selected.isEmpty()) throw new IllegalArgumentException("Select at least one application");
        var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.id)).toList();
        if(chosen.size()!=selected.size()) throw new IllegalArgumentException("Application selection contains unavailable entries");
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"applicationCount\": ").append(chosen.size()).append(",\n  \"applications\": [\n");
        for(int i=0;i<chosen.size();i++) { var entry=chosen.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"name\": \"").append(jsonString(entry.name)).append("\",\n      \"classPaths\": [\n");for(int j=0;j<entry.classPaths.size();j++){text.append("        \"").append(jsonString(entry.classPaths.get(j))).append('"');if(j+1<entry.classPaths.size())text.append(',');text.append('\n');}text.append("      ]\n    }");if(i+1<chosen.size())text.append(',');text.append('\n'); }
        return text.append("  ]\n}\n").toString();
    }
    @SuppressWarnings("unchecked")
    static HostCatalog readHostCatalog(String text) {
        var data=(Map<String,Object>)new Json(text).read();String date=(String)data.get("asOf");
        if(date==null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid host catalog date");
        var entries=new ArrayList<HostEntry>();var ids=new HashSet<String>();var values=new HashSet<String>();var names=new HashSet<String>();var addresses=new HashSet<String>();
        for(var raw:(List<Object>)data.get("hosts")) {var item=(Map<String,Object>)raw;String id=(String)item.get("id"),value=(String)item.get("value"),name=(String)item.get("hostName"),address=(String)item.get("ipAddress"),platform=(String)item.get("platform");if(id==null || !id.matches("HOST-\\d{3,}") || !ids.add(id) || name==null || name.isBlank() || !names.add(name) || !validIpAddress(address) || !addresses.add(address) || value==null || !value.equals(name+"/"+address) || !values.add(value) || platform==null || platform.isBlank()) throw new IllegalArgumentException("Invalid or duplicate host");entries.add(new HostEntry(id,value,name,address,platform));}
        if(entries.isEmpty()) throw new IllegalArgumentException("Empty host catalog");return new HostCatalog(date,List.copyOf(entries));
    }
    static String hostCatalogJson(HostCatalog catalog) {
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"asOf\": \"").append(catalog.date).append("\",\n  \"description\": \"Custom hosts created in Java Usage Generator.\",\n  \"hosts\": [\n");
        for(int i=0;i<catalog.entries.size();i++){var entry=catalog.entries.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"value\": \"").append(jsonString(entry.value)).append("\",\n      \"hostName\": \"").append(jsonString(entry.hostName)).append("\",\n      \"ipAddress\": \"").append(jsonString(entry.ipAddress)).append("\",\n      \"platform\": \"").append(jsonString(entry.platform)).append("\"\n    }");if(i+1<catalog.entries.size())text.append(',');text.append('\n');}
        return text.append("  ]\n}\n").toString();
    }
    static HostCatalog mergeHostCatalogs(HostCatalog base,HostCatalog custom) {
        var entries=new ArrayList<HostEntry>(base.entries);var ids=new HashSet<String>();var names=new HashSet<String>();var addresses=new HashSet<String>();base.entries.forEach(entry->{ids.add(entry.id);names.add(entry.hostName);addresses.add(entry.ipAddress);});
        for(var entry:custom.entries)if(!ids.add(entry.id)||!names.add(entry.hostName)||!addresses.add(entry.ipAddress))throw new IllegalArgumentException("Custom host duplicates a bundled host: "+entry.value);else entries.add(entry);
        return new HostCatalog(base.date.compareTo(custom.date)>=0?base.date:custom.date,List.copyOf(entries));
    }
    static void appendCustomHost(Path path,HostEntry entry) throws IOException {
        var existing=Files.isRegularFile(path)?readHostCatalog(Files.readString(path,StandardCharsets.UTF_8)):new HostCatalog(LocalDate.now().toString(),List.of());var entries=new ArrayList<>(existing.entries);entries.add(entry);var updated=new HostCatalog(LocalDate.now().toString(),List.copyOf(entries));readHostCatalog(hostCatalogJson(updated));writeAtomically(path,hostCatalogJson(updated));
    }
    static String hostConfiguration(HostCatalog catalog,Set<String> selected) {
        if(selected.isEmpty()) throw new IllegalArgumentException("Select at least one host");var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.id)).toList();if(chosen.size()!=selected.size()) throw new IllegalArgumentException("Host selection contains unavailable entries");var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"hostCount\": ").append(chosen.size()).append(",\n  \"hosts\": [\n");for(int i=0;i<chosen.size();i++){var entry=chosen.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"value\": \"").append(jsonString(entry.value)).append("\",\n      \"hostName\": \"").append(jsonString(entry.hostName)).append("\",\n      \"ipAddress\": \"").append(jsonString(entry.ipAddress)).append("\"\n    }");if(i+1<chosen.size())text.append(',');text.append('\n');}return text.append("  ]\n}\n").toString();
    }
    @SuppressWarnings("unchecked")
    static OsVersionCatalog readOsVersionCatalog(String text) {
        var data=(Map<String,Object>)new Json(text).read();String date=(String)data.get("asOf");if(date==null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid OS version catalog date");var entries=new ArrayList<OsVersionEntry>();var ids=new HashSet<String>();var versions=new HashSet<String>();for(var raw:(List<Object>)data.get("osVersions")){var item=(Map<String,Object>)raw;String id=(String)item.get("id"),version=(String)item.get("version"),platform=(String)item.get("platform");var names=((List<Object>)item.get("osNames")).stream().map(String::valueOf).toList();var architectures=((List<Object>)item.get("architectures")).stream().map(String::valueOf).toList();if(id==null || !id.matches("OSV-\\d{3,}") || !ids.add(id) || version==null || version.isBlank() || !versions.add(version) || platform==null || platform.isBlank() || names.isEmpty() || names.stream().anyMatch(String::isBlank) || architectures.isEmpty() || architectures.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid or duplicate OS version");entries.add(new OsVersionEntry(id,version,platform,names,architectures));}if(entries.isEmpty())throw new IllegalArgumentException("Empty OS version catalog");return new OsVersionCatalog(date,List.copyOf(entries));
    }
    static String osVersionCatalogJson(OsVersionCatalog catalog) {
        var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"asOf\": \"").append(catalog.date).append("\",\n  \"description\": \"Custom OS versions created in Java Usage Generator.\",\n  \"osVersions\": [\n");
        for(int i=0;i<catalog.entries.size();i++){var entry=catalog.entries.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"version\": \"").append(jsonString(entry.version)).append("\",\n      \"platform\": \"").append(jsonString(entry.platform)).append("\",\n      \"osNames\": ");appendJsonArray(text,entry.osNames,"      ");text.append(",\n      \"architectures\": ");appendJsonArray(text,entry.architectures,"      ");text.append("\n    }");if(i+1<catalog.entries.size())text.append(',');text.append('\n');}
        return text.append("  ]\n}\n").toString();
    }
    static OsVersionCatalog mergeOsVersionCatalogs(OsVersionCatalog base,OsVersionCatalog custom) {
        var entries=new ArrayList<OsVersionEntry>(base.entries);var ids=new HashSet<String>();var versions=new HashSet<String>();base.entries.forEach(entry->{ids.add(entry.id);versions.add(entry.version);});for(var entry:custom.entries)if(!ids.add(entry.id)||!versions.add(entry.version))throw new IllegalArgumentException("Custom OS version duplicates a bundled version: "+entry.version);else entries.add(entry);return new OsVersionCatalog(base.date.compareTo(custom.date)>=0?base.date:custom.date,List.copyOf(entries));
    }
    static void appendCustomOsVersion(Path path,OsVersionEntry entry) throws IOException {
        var existing=Files.isRegularFile(path)?readOsVersionCatalog(Files.readString(path,StandardCharsets.UTF_8)):new OsVersionCatalog(LocalDate.now().toString(),List.of());var entries=new ArrayList<>(existing.entries);entries.add(entry);var updated=new OsVersionCatalog(LocalDate.now().toString(),List.copyOf(entries));readOsVersionCatalog(osVersionCatalogJson(updated));writeAtomically(path,osVersionCatalogJson(updated));
    }
    static void appendJsonArray(StringBuilder text,List<String> values,String indent) {text.append("[\n");for(int i=0;i<values.size();i++){text.append(indent).append("  \"").append(jsonString(values.get(i))).append('"');if(i+1<values.size())text.append(',');text.append('\n');}text.append(indent).append(']');}
    static boolean validIpAddress(String address) {if(address==null||!address.matches("\\d{1,3}(?:\\.\\d{1,3}){3}"))return false;return Arrays.stream(address.split("\\.")).allMatch(part->{int value=Integer.parseInt(part);return value<=255;});}
    static List<String> lines(String value) {return value.lines().map(String::trim).filter(part->!part.isEmpty()).distinct().toList();}
    static List<String> commaSeparated(String value) {return Arrays.stream(value.split("[,\\r\\n]+")).map(String::trim).filter(part->!part.isEmpty()).distinct().toList();}
    static String nextCustomId(String prefix,Collection<String> ids) {int next=900001;for(String id:ids)if(id.startsWith(prefix))try{next=Math.max(next,Integer.parseInt(id.substring(prefix.length()))+1);}catch(NumberFormatException ignored){}return prefix+next;}
    static String osVersionConfiguration(OsVersionCatalog catalog,Set<String> selected) {
        if(selected.isEmpty())throw new IllegalArgumentException("Select at least one OS version");var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.id)).toList();if(chosen.size()!=selected.size())throw new IllegalArgumentException("OS version selection contains unavailable entries");var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"osVersionCount\": ").append(chosen.size()).append(",\n  \"osVersions\": [\n");for(int i=0;i<chosen.size();i++){var entry=chosen.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"version\": \"").append(jsonString(entry.version)).append("\",\n      \"platform\": \"").append(jsonString(entry.platform)).append("\",\n      \"osNames\": [");for(int j=0;j<entry.osNames.size();j++){if(j>0)text.append(", ");text.append('"').append(jsonString(entry.osNames.get(j))).append('"');}text.append("],\n      \"architectures\": [");for(int j=0;j<entry.architectures.size();j++){if(j>0)text.append(", ");text.append('"').append(jsonString(entry.architectures.get(j))).append('"');}text.append("]\n    }");if(i+1<chosen.size())text.append(',');text.append('\n');}return text.append("  ]\n}\n").toString();
    }
    static <T> T randomItem(List<T> values,Random random) { return values.get(random.nextInt(values.size())); }
    static long randomTimestamp(long start,long end,Random random) {if(start>end)throw new IllegalArgumentException("Custom timestamp start must not be after the end.");if(start==end)return start;return start+(long)(random.nextDouble()*((double)(end-start)+1));}
    static String csvField(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    static String sampleJutPacket(Catalog versionCatalog,Set<String> selectedVersions,ApplicationCatalog applicationCatalog,Set<String> selectedApplications,HostCatalog hostCatalog,Set<String> selectedHosts,OsVersionCatalog osVersionCatalog,Set<String> selectedOsVersions,Random random,long timestampMillis) {
        var versions=versionCatalog.entries.stream().filter(entry->selectedVersions.contains(entry.version)).toList();var applications=applicationCatalog.entries.stream().filter(entry->selectedApplications.contains(entry.id)).toList();var hosts=hostCatalog.entries.stream().filter(entry->selectedHosts.contains(entry.id)).toList();var osVersions=osVersionCatalog.entries.stream().filter(entry->selectedOsVersions.contains(entry.id)).toList();if(versions.isEmpty() || applications.isEmpty() || hosts.isEmpty() || osVersions.isEmpty())throw new IllegalArgumentException("Each tab must have at least one selected value.");var version=randomItem(versions,random);var application=randomItem(applications,random);var host=randomItem(hosts,random);var osVersion=randomItem(osVersions,random);String formattedTime=new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy",Locale.US).format(new Date(timestampMillis));String javaHome=switch(osVersion.platform){case "Windows"->"C:\\Java\\jdk-"+version.version;case "macOS"->"/Library/Java/JavaVirtualMachines/jdk-"+version.version+".jdk/Contents/Home";default->"/opt/java/jdk-"+version.version;};var fields=List.of(randomItem(application.startTypes,random),formattedTime,host.value,randomItem(application.javaCommands,random),javaHome,version.javaVersion,version.javaVersion,"Oracle Corporation","Oracle Corporation",randomItem(osVersion.osNames,random),randomItem(osVersion.architectures,random),osVersion.version,"",randomItem(application.classPaths,random),"");return fields.stream().map(JugSelector::csvField).collect(java.util.stream.Collectors.joining(","))+"\n";
    }
    static String jsonString(String value) { return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t"); }
    // Small JSON reader: standard JSON objects, arrays, strings, numbers and literals.
    static class Json {
        final String text; int position=0;
        Json(String text){this.text=text;}
        void space(){while(position<text.length() && " \r\n\t".indexOf(text.charAt(position))>=0)position++;}
        char next(){if(position>=text.length())throw new IllegalArgumentException("Unexpected end of JSON");return text.charAt(position++);}
        boolean take(char c){space();if(position<text.length()&&text.charAt(position)==c){position++;return true;}return false;}
        void expect(char c){if(!take(c))throw new IllegalArgumentException("Expected '"+c+"' in JSON");}
        Object read(){Object result=parse(0);space();if(position!=text.length())throw new IllegalArgumentException("Trailing JSON data");return result;}
        Object parse(int depth){
            if(depth>100)throw new IllegalArgumentException("JSON nesting exceeds limit");space();
            if(position>=text.length())throw new IllegalArgumentException("Missing JSON value");
            char c=text.charAt(position);
            if(c=='"')return string();
            if(take('{')){var map=new LinkedHashMap<String,Object>();if(take('}'))return map;do{space();String key=string();expect(':');if(map.containsKey(key))throw new IllegalArgumentException("Duplicate JSON key");map.put(key,parse(depth+1));}while(take(','));expect('}');return map;}
            if(take('[')){var list=new ArrayList<Object>();if(take(']'))return list;do{list.add(parse(depth+1));}while(take(','));expect(']');return list;}
            for(String literal:List.of("true","false","null"))if(text.startsWith(literal,position)){position+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
            var match=java.util.regex.Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").matcher(text.substring(position));
            if(!match.lookingAt())throw new IllegalArgumentException("Invalid JSON value");position+=match.end();return new java.math.BigDecimal(match.group());
        }
        String string(){expect('"');var result=new StringBuilder();while(true){char c=next();if(c=='"')return result.toString();if(c<32)throw new IllegalArgumentException("Invalid JSON string");if(c!='\\'){result.append(c);continue;}char escape=next();switch(escape){case '"','\\','/'->result.append(escape);case 'b'->result.append('\b');case 'f'->result.append('\f');case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');case 'u'->{if(position+4>text.length())throw new IllegalArgumentException("Invalid Unicode escape");result.append((char)Integer.parseInt(text.substring(position,position+4),16));position+=4;}default->throw new IllegalArgumentException("Invalid escape");}}}
    }
    static class Window extends JFrame {
        Catalog catalog; final Set<String> selected;
        final JComboBox<String> family=new JComboBox<>(); final JTextField search=new JTextField();
        final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose versions, then save the JSON configuration.");
        List<Entry> visible=new ArrayList<>(); boolean changing=false;
        final JTextArea preview=new JTextArea(); final VersionTable model=new VersionTable(); final JTable table=new JTable(model);
        final JButton save=new JButton("Save JSON configuration..."),include=new JButton("Select entire family"),exclude=new JButton("Clear family"),updateLatest=new JButton("Update for latest versions");
        Window(Catalog catalog,Set<String> selected,ApplicationCatalog applicationCatalog,HostCatalog hostCatalog,OsVersionCatalog osVersionCatalog) {
            super("JUG - Java Usage Generator");this.catalog=catalog;this.selected=selected;
            setDefaultCloseOperation(EXIT_ON_CLOSE);setSize(1120,760);setMinimumSize(new Dimension(820,540));setLocationRelativeTo(null);
            JPanel root=new JPanel(new BorderLayout(12,12));root.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));setContentPane(root);
            var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select Java versions");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);
            var controls=new JPanel(new GridLayout(2,1,6,6));var filters=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));family.setPreferredSize(new Dimension(160,28));filters.add(family);filters.add(searchPanel("Search versions",search));updateLatest.setToolTipText("Fetch released JDK 8 and later versions, including Security Alerts, from ops.java");updateLatest.addActionListener(event->updateForLatestVersions());filters.add(updateLatest);search.setToolTipText("Search versions or expected java.version values");search.getAccessibleContext().setAccessibleName("Search versions");family.getAccessibleContext().setAccessibleName("JDK family");controls.add(filters);
            var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(include);buttons.add(exclude);buttons.add(button("Select all versions",()->{catalog().entries.forEach(e->selected.add(e.version));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load selection…",this::loadSelection));buttons.add(button("Load catalog…",this::loadCatalog));controls.add(buttons);top.add(controls,BorderLayout.SOUTH);root.add(top,BorderLayout.NORTH);
            table.setRowHeight(28);table.setFillsViewportHeight(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getColumnModel().getColumn(0).setMaxWidth(65);
            var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));preview.getAccessibleContext().setAccessibleName("JSON configuration preview");right.add(new JScrollPane(preview),BorderLayout.CENTER);
            var footer=new JPanel(new BorderLayout(6,6));footer.add(new JLabel("Selections are a snapshot of currently listed versions."),BorderLayout.NORTH);footer.add(save,BorderLayout.SOUTH);right.add(footer,BorderLayout.SOUTH);
            var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);root.add(split,BorderLayout.CENTER);root.add(status,BorderLayout.SOUTH);
            include.addActionListener(e->chooseFamily(true));exclude.addActionListener(e->chooseFamily(false));save.addActionListener(e->saveSelection());
            family.addActionListener(e->{if(!changing)filter();});search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
            setCatalog(catalog);refresh();
            var cardsLayout=new CardLayout();var cards=new JPanel(cardsLayout);var tabBar=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));var group=new ButtonGroup();var applicationPanel=new ApplicationPanel(applicationCatalog);var hostPanel=new HostPanel(hostCatalog);var osVersionPanel=new OsVersionPanel(osVersionCatalog);var timestampPanel=new TimestampPanel();var generatePanel=new GeneratePanel(applicationPanel,hostPanel,osVersionPanel,timestampPanel);var receivePanel=new ReceivePanel();String[] tabNames={"Generate","Listen","Timestamps","Java Versions","Java Applications","Hosts / IPs","OS Versions"};JPanel[] panels={generatePanel,receivePanel,timestampPanel,root,applicationPanel,hostPanel,osVersionPanel};for(int i=0;i<tabNames.length;i++){String name=tabNames[i];var tab=new JToggleButton(name);tab.setFocusPainted(false);tab.setMargin(new Insets(9,14,9,14));tab.addActionListener(event->cardsLayout.show(cards,name));group.add(tab);tabBar.add(tab);cards.add(panels[i],name);if(i==0)tab.setSelected(true);}var navigation=new JPanel(new BorderLayout());navigation.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(190,190,190)));navigation.add(tabBar,BorderLayout.WEST);var shell=new JPanel(new BorderLayout());shell.add(navigation,BorderLayout.NORTH);shell.add(cards,BorderLayout.CENTER);setContentPane(shell);addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosing(java.awt.event.WindowEvent event){receivePanel.stopListening("Listener stopped.");}});
        }
        class TimestampPanel extends JPanel {
            final String[] choices={"Use current time","Random time in the last 30 days","Random time in the last 90 days","Random time in the last 180 days","Custom date/time range"};final JComboBox<String> mode=new JComboBox<>(choices);final JSpinner rangeStart=new JSpinner(new SpinnerDateModel(new Date(System.currentTimeMillis()-90L*DAY_MILLIS),null,null,Calendar.MINUTE)),rangeEnd=new JSpinner(new SpinnerDateModel(new Date(),null,null,Calendar.MINUTE));final JLabel status=new JLabel();boolean generationRunning;
            TimestampPanel(){super(new BorderLayout());setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var header=new JPanel(new BorderLayout());var title=new JLabel("Configure JUT timestamps");title.setFont(title.getFont().deriveFont(Font.BOLD,22));header.add(title,BorderLayout.NORTH);var form=new JPanel(new GridBagLayout());var modePanel=labeled("Timestamp mode",mode);var startPanel=labeled("Custom start",rangeStart);var endPanel=labeled("Custom end",rangeEnd);var constraints=new GridBagConstraints();constraints.gridx=0;constraints.gridy=0;constraints.anchor=GridBagConstraints.NORTHWEST;constraints.insets=new Insets(0,0,0,12);form.add(modePanel,constraints);constraints.gridx=1;form.add(startPanel,constraints);constraints.gridx=2;form.add(endPanel,constraints);constraints.gridx=3;constraints.weightx=1;constraints.fill=GridBagConstraints.HORIZONTAL;form.add(Box.createHorizontalGlue(),constraints);var stack=new JPanel();stack.setLayout(new BoxLayout(stack,BoxLayout.Y_AXIS));header.setAlignmentX(Component.LEFT_ALIGNMENT);form.setAlignmentX(Component.LEFT_ALIGNMENT);status.setAlignmentX(Component.LEFT_ALIGNMENT);stack.add(header);stack.add(Box.createVerticalStrut(12));stack.add(form);stack.add(Box.createVerticalStrut(8));stack.add(status);add(stack,BorderLayout.NORTH);mode.setSelectedIndex(2);mode.getAccessibleContext().setAccessibleName("Timestamp generation mode");rangeStart.getAccessibleContext().setAccessibleName("Custom timestamp start");rangeEnd.getAccessibleContext().setAccessibleName("Custom timestamp end");rangeStart.setEditor(new JSpinner.DateEditor(rangeStart,"yyyy-MM-dd HH:mm:ss"));rangeEnd.setEditor(new JSpinner.DateEditor(rangeEnd,"yyyy-MM-dd HH:mm:ss"));mode.addActionListener(event->updateControls());rangeStart.addChangeListener(event->updateControls());rangeEnd.addChangeListener(event->updateControls());updateControls();}
            JPanel labeled(String text,JComponent component){var field=new JPanel(new BorderLayout(3,3));field.add(new JLabel(text),BorderLayout.NORTH);field.add(component,BorderLayout.CENTER);return field;}
            void setGenerationRunning(boolean running){generationRunning=running;updateControls();}
            void updateControls(){boolean custom=mode.getSelectedIndex()==4;mode.setEnabled(!generationRunning);rangeStart.setEnabled(custom&&!generationRunning);rangeEnd.setEnabled(custom&&!generationRunning);if(custom){long start=((Date)rangeStart.getValue()).getTime(),end=((Date)rangeEnd.getValue()).getTime();status.setText(start<=end?"Each packet will use a random time within the custom range.":"Custom start must not be after custom end.");}else status.setText(switch(mode.getSelectedIndex()){case 0->"Each packet will use the time at which it is generated.";case 1->"Each packet will use a random time in the last 30 days.";case 3->"Each packet will use a random time in the last 180 days.";default->"Each packet will use a random time in the last 90 days.";});}
            void validateRange(){if(mode.getSelectedIndex()==4)randomTimestamp(((Date)rangeStart.getValue()).getTime(),((Date)rangeEnd.getValue()).getTime(),new Random(0));}
            long timestamp(Random random,long now){return switch(mode.getSelectedIndex()){case 0->now;case 1->randomTimestamp(now-30L*DAY_MILLIS,now,random);case 2->randomTimestamp(now-90L*DAY_MILLIS,now,random);case 3->randomTimestamp(now-180L*DAY_MILLIS,now,random);default->randomTimestamp(((Date)rangeStart.getValue()).getTime(),((Date)rangeEnd.getValue()).getTime(),random);};}
        }
        class GeneratePanel extends JPanel {
            final ApplicationPanel applications;final HostPanel hosts;final OsVersionPanel osVersions;final TimestampPanel timestamps;final JTextArea packet=new JTextArea(),seriesOutput=new JTextArea();final JLabel summary=new JLabel("Uses one random selected value from every configuration tab."),seriesStatus=new JLabel("Configure the number of packets and the delay between each packet.");final JButton save=new JButton("Save sample.jut..."),sampleClear=new JButton("Clear"),start=new JButton("Start generation"),seriesClear=new JButton("Clear"),stop=new JButton("Stop"),saveSeries=new JButton("Save packet-series.jut...");final JSpinner packetCount=new JSpinner(new SpinnerNumberModel(10,1,1000,1)),interval=new JSpinner(new SpinnerNumberModel(1,1,3600,1)),udpPort=new JSpinner(new SpinnerNumberModel(DEFAULT_UDP_PORT,1,65535,1));final JComboBox<String> intervalUnit=new JComboBox<>(new String[]{"Seconds","Milliseconds","Minutes"});final JCheckBox udpEnabled=new JCheckBox("Send via UDP");final JTextField udpHost=new JTextField("localhost",18);final List<String> seriesPackets=new ArrayList<>();javax.swing.Timer seriesTimer;DatagramSocket udpSocket;InetAddress udpAddress;int udpDestinationPort,seriesTarget;
            GeneratePanel(ApplicationPanel applications,HostPanel hosts,OsVersionPanel osVersions,TimestampPanel timestamps){super(new BorderLayout());this.applications=applications;this.hosts=hosts;this.osVersions=osVersions;this.timestamps=timestamps;setBorder(BorderFactory.createEmptyBorder(14,14,14,14));var sampleSection=new JPanel(new BorderLayout(8,8));sampleSection.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(205,212,207)),BorderFactory.createEmptyBorder(12,12,12,12)));var sampleHeader=new JPanel(new BorderLayout(6,6));var sampleTitle=new JLabel("Generate a sample JUT packet");sampleTitle.setFont(sampleTitle.getFont().deriveFont(Font.BOLD,18));sampleHeader.add(sampleTitle,BorderLayout.NORTH);sampleHeader.add(summary,BorderLayout.CENTER);var sampleActions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));sampleActions.add(button("Generate a sample JUT packet",this::generate));sampleClear.addActionListener(event->clearSample());sampleActions.add(sampleClear);save.setEnabled(false);save.addActionListener(event->save());sampleActions.add(save);sampleHeader.add(sampleActions,BorderLayout.SOUTH);sampleSection.add(sampleHeader,BorderLayout.NORTH);configureOutput(packet,"Sample JUT packet");packet.setRows(4);packet.setText("Generate a packet to preview it here.");sampleSection.add(new JScrollPane(packet),BorderLayout.CENTER);
                var seriesSection=new JPanel(new BorderLayout(8,8));seriesSection.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(205,212,207)),BorderFactory.createEmptyBorder(12,12,12,12)));var seriesHeader=new JPanel(new BorderLayout(6,6));var seriesTitle=new JLabel("Generate a packet series");seriesTitle.setFont(seriesTitle.getFont().deriveFont(Font.BOLD,18));seriesHeader.add(seriesTitle,BorderLayout.NORTH);seriesHeader.add(seriesStatus,BorderLayout.CENTER);var controls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));controls.add(labeled("Packet count",packetCount));controls.add(labeled("Interval",interval));controls.add(labeled("Interval unit",intervalUnit));start.addActionListener(event->startSeries());controls.add(start);seriesClear.addActionListener(event->clearSeries());controls.add(seriesClear);stop.setEnabled(false);stop.addActionListener(event->stopSeries());controls.add(stop);saveSeries.setEnabled(false);saveSeries.addActionListener(event->saveSeries());controls.add(saveSeries);var udpControls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));udpEnabled.addActionListener(event->updateUdpControls());udpControls.add(udpEnabled);udpHost.getAccessibleContext().setAccessibleName("UDP hostname");var portEditor=new JSpinner.NumberEditor(udpPort,"0");portEditor.getFormat().setGroupingUsed(false);udpPort.setEditor(portEditor);udpPort.getAccessibleContext().setAccessibleName("UDP port");udpControls.add(labeled("Hostname",udpHost));udpControls.add(labeled("UDP port",udpPort));var controlStack=new JPanel(new GridLayout(2,1,6,6));controlStack.add(controls);controlStack.add(udpControls);seriesHeader.add(controlStack,BorderLayout.SOUTH);seriesSection.add(seriesHeader,BorderLayout.NORTH);configureOutput(seriesOutput,"Generated packet series");seriesOutput.setText("Generated packets will appear here as the interval elapses.");seriesSection.add(new JScrollPane(seriesOutput),BorderLayout.CENTER);var split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,sampleSection,seriesSection);split.setResizeWeight(.28);split.setDividerLocation(210);split.setBorder(null);add(split,BorderLayout.CENTER);updateUdpControls();}
            JPanel labeled(String text,JComponent component){var field=new JPanel(new BorderLayout(3,3));field.add(new JLabel(text),BorderLayout.NORTH);field.add(component,BorderLayout.CENTER);return field;}
            void configureOutput(JTextArea output,String accessibleName){output.setEditable(false);output.setLineWrap(true);output.setWrapStyleWord(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));output.getAccessibleContext().setAccessibleName(accessibleName);}
            void generate(){try{var random=new Random();String next=sampleJutPacket(catalog,selected,applications.catalog,applications.selected,hosts.catalog,hosts.selected,osVersions.catalog,osVersions.selected,random,timestamps.timestamp(random,System.currentTimeMillis()));packet.setText(next);packet.setCaretPosition(0);summary.setText("Generated a fresh packet from the current selections.");save.setEnabled(true);}catch(Exception exception){packet.setText("");save.setEnabled(false);summary.setText(exception.getMessage());}}
            void clearSample(){packet.setText("");save.setEnabled(false);summary.setText("Sample packet cleared.");}
            void save(){var chooser=chooser("Save sample JUT packet");chooser.setSelectedFile(new File("sample.jut"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,packet.getText(),StandardCharsets.UTF_8);summary.setText("Saved sample packet to "+path);}catch(Exception exception){error(exception);}}
            void updateUdpControls(){boolean enabled=udpEnabled.isSelected()&&udpEnabled.isEnabled();udpHost.setEnabled(enabled);udpPort.setEnabled(enabled);}
            void setSeriesRunning(boolean running){packetCount.setEnabled(!running);interval.setEnabled(!running);intervalUnit.setEnabled(!running);udpEnabled.setEnabled(!running);start.setEnabled(!running);seriesClear.setEnabled(!running);stop.setEnabled(running);saveSeries.setEnabled(!running&&!seriesPackets.isEmpty());timestamps.setGenerationRunning(running);updateUdpControls();}
            void startSeries(){try{timestamps.validateRange();seriesTarget=(Integer)packetCount.getValue();seriesPackets.clear();seriesOutput.setText("");if(udpEnabled.isSelected()){String hostname=udpHost.getText().trim();if(hostname.isEmpty())throw new IllegalArgumentException("Enter a UDP hostname.");udpAddress=InetAddress.getByName(hostname);udpDestinationPort=(Integer)udpPort.getValue();udpSocket=new DatagramSocket();}setSeriesRunning(true);appendSeriesPacket();if(seriesPackets.size()<seriesTarget){long multiplier=switch(intervalUnit.getSelectedIndex()){case 1->1L;case 2->60000L;default->1000L;};int delay=(int)Math.min(Integer.MAX_VALUE,(Integer)interval.getValue()*multiplier);seriesTimer=new javax.swing.Timer(delay,event->appendSeriesPacket());seriesTimer.start();}}catch(Exception exception){closeUdp();setSeriesRunning(false);seriesStatus.setText("Unable to start: "+failureMessage(exception));}}
            void appendSeriesPacket(){try{var random=new Random();String next=sampleJutPacket(catalog,selected,applications.catalog,applications.selected,hosts.catalog,hosts.selected,osVersions.catalog,osVersions.selected,random,timestamps.timestamp(random,System.currentTimeMillis()));if(udpSocket!=null){byte[] data=next.getBytes(StandardCharsets.UTF_8);if(data.length>65507)throw new IOException("Packet exceeds the UDP payload limit.");udpSocket.send(new DatagramPacket(data,data.length,udpAddress,udpDestinationPort));}seriesPackets.add(next);seriesOutput.append(next);seriesOutput.setCaretPosition(seriesOutput.getDocument().getLength());seriesStatus.setText((udpSocket==null?"Generated ":"Generated and sent ")+seriesPackets.size()+" of "+seriesTarget+" packets.");if(seriesPackets.size()>=seriesTarget){String destination=udpSocket==null?"":" via UDP to "+udpHost.getText().trim()+":"+udpDestinationPort;finishSeries("Completed "+seriesTarget+" packets"+destination+".");}}catch(Exception exception){finishSeries("Stopped after "+seriesPackets.size()+" packets: "+failureMessage(exception));}}
            String failureMessage(Exception exception){String message=exception.getMessage();return message==null||message.isBlank()?exception.getClass().getSimpleName():message;}
            void closeUdp(){if(udpSocket!=null){udpSocket.close();udpSocket=null;}udpAddress=null;udpDestinationPort=0;}
            void finishSeries(String message){if(seriesTimer!=null){seriesTimer.stop();seriesTimer=null;}closeUdp();setSeriesRunning(false);seriesStatus.setText(message);}
            void stopSeries(){finishSeries("Stopped after "+seriesPackets.size()+" of "+seriesTarget+" packets.");}
            void clearSeries(){seriesPackets.clear();seriesOutput.setText("");saveSeries.setEnabled(false);seriesStatus.setText("Generated packet series cleared.");}
            void saveSeries(){var chooser=chooser("Save JUT packet series");chooser.setSelectedFile(new File("packet-series.jut"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,String.join("",seriesPackets),StandardCharsets.UTF_8);seriesStatus.setText("Saved "+seriesPackets.size()+" packets to "+path);}catch(Exception exception){error(exception);}}
        }
        class ReceivePanel extends JPanel {
            final JTextField host=new JTextField("0.0.0.0",18);final JSpinner port=new JSpinner(new SpinnerNumberModel(DEFAULT_UDP_PORT,1,65535,1));final JButton start=new JButton("Start listening"),stop=new JButton("Stop"),clear=new JButton("Clear");final JLabel status=new JLabel("Not listening.");final JTextArea packets=new JTextArea();volatile DatagramSocket receiveSocket;Thread receiveThread;int receivedCount;
            ReceivePanel(){super(new BorderLayout(12,12));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var header=new JPanel(new BorderLayout(8,8));var title=new JLabel("Listen for UDP JUT packets");title.setFont(title.getFont().deriveFont(Font.BOLD,22));header.add(title,BorderLayout.NORTH);header.add(status,BorderLayout.CENTER);var controls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));host.getAccessibleContext().setAccessibleName("Local bind address");host.setToolTipText("Use 0.0.0.0 for all local IPv4 interfaces, localhost for this computer only, or a specific local interface address");port.getAccessibleContext().setAccessibleName("Receive UDP port");var portEditor=new JSpinner.NumberEditor(port,"0");portEditor.getFormat().setGroupingUsed(false);port.setEditor(portEditor);controls.add(labeled("Local bind address",host));controls.add(labeled("UDP port",port));start.addActionListener(event->startListening());controls.add(start);stop.setEnabled(false);stop.addActionListener(event->stopListening("Listener stopped."));controls.add(stop);clear.addActionListener(event->{packets.setText("");receivedCount=0;status.setText(receiveSocket==null?"Not listening.":"Listening on "+endpoint()+". No packets received yet.");});controls.add(clear);header.add(controls,BorderLayout.SOUTH);add(header,BorderLayout.NORTH);packets.setEditable(false);packets.setLineWrap(true);packets.setWrapStyleWord(false);packets.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));packets.getAccessibleContext().setAccessibleName("Received UDP JUT packets");add(new JScrollPane(packets),BorderLayout.CENTER);}
            JPanel labeled(String text,JComponent component){var field=new JPanel(new BorderLayout(3,3));field.add(new JLabel(text),BorderLayout.NORTH);field.add(component,BorderLayout.CENTER);return field;}
            String endpoint(){return host.getText().trim()+":"+port.getValue();}
            void setListening(boolean listening){host.setEnabled(!listening);port.setEnabled(!listening);start.setEnabled(!listening);stop.setEnabled(listening);}
            DatagramSocket bindSocket(InetAddress address,int bindPort)throws IOException{var socket=new DatagramSocket(null);try{socket.bind(new InetSocketAddress(address,bindPort));return socket;}catch(IOException|RuntimeException exception){socket.close();throw exception;}}
            void startListening(){try{String bindHost=host.getText().trim();if(bindHost.isEmpty())throw new IllegalArgumentException("Enter a local bind address.");var socket=bindSocket(InetAddress.getByName(bindHost),(Integer)port.getValue());receiveSocket=socket;receivedCount=0;setListening(true);status.setText("Listening on "+endpoint()+". No packets received yet.");receiveThread=Thread.ofVirtual().name("jug-udp-receiver").start(()->receivePackets(socket));}catch(Exception exception){stopListening("Unable to listen: "+failureMessage(exception));}}
            void receivePackets(DatagramSocket socket){try{while(!socket.isClosed()){var buffer=new byte[65507];var packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);String text=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);String source=packet.getAddress().getHostAddress()+":"+packet.getPort();SwingUtilities.invokeLater(()->appendPacket(socket,text,source));}}catch(SocketException exception){if(!socket.isClosed())listenerFailed(socket,exception);}catch(IOException exception){listenerFailed(socket,exception);}}
            void appendPacket(DatagramSocket socket,String text,String source){if(receiveSocket!=socket)return;if(packets.getDocument().getLength()>0&&!packets.getText().endsWith("\n")&&!text.startsWith("\n"))packets.append("\n");packets.append(text);packets.setCaretPosition(packets.getDocument().getLength());receivedCount++;status.setText("Listening on "+endpoint()+". Received "+receivedCount+" packet"+(receivedCount==1?"":"s")+"; last sender "+source+".");}
            void listenerFailed(DatagramSocket socket,Exception exception){SwingUtilities.invokeLater(()->{if(receiveSocket==socket)stopListening("Listener stopped: "+failureMessage(exception));});}
            String failureMessage(Exception exception){String message=exception.getMessage();return message==null||message.isBlank()?exception.getClass().getSimpleName():message;}
            void stopListening(String message){DatagramSocket socket=receiveSocket;receiveSocket=null;if(socket!=null)socket.close();receiveThread=null;setListening(false);status.setText(message);}
        }
        interface EntryAction { void run() throws Exception; }
        class EntryDialog {
            final JDialog dialog;final JButton add=new JButton("Add");final JLabel validation=new JLabel(" ");final Supplier<String> validator;
            EntryDialog(String title,JPanel form,Supplier<String> validator,EntryAction action){this.validator=validator;dialog=new JDialog(Window.this,title,true);dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);var content=new JPanel(new BorderLayout(10,10));content.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));content.add(form,BorderLayout.CENTER);validation.setForeground(new Color(170,35,35));var footer=new JPanel(new BorderLayout(8,8));footer.add(validation,BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));var cancel=new JButton("Cancel");cancel.addActionListener(event->dialog.dispose());buttons.add(cancel);buttons.add(add);footer.add(buttons,BorderLayout.EAST);content.add(footer,BorderLayout.SOUTH);dialog.setContentPane(content);watch(form);add.addActionListener(event->{try{action.run();dialog.dispose();}catch(Exception exception){error(exception);}});update();dialog.getRootPane().setDefaultButton(add);dialog.pack();dialog.setMinimumSize(new Dimension(520,dialog.getHeight()));dialog.setLocationRelativeTo(Window.this);}
            void watch(Component component){if(component instanceof JTextComponent text)text.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){update();}public void removeUpdate(DocumentEvent e){update();}public void changedUpdate(DocumentEvent e){update();}});if(component instanceof JComboBox<?> combo)combo.addActionListener(event->update());if(component instanceof JSpinner spinner)spinner.addChangeListener(event->update());if(component instanceof Container container)for(Component child:container.getComponents())watch(child);}
            void update(){String message=validator.get();add.setEnabled(message==null);validation.setText(message==null?" ":message);}
            void show(){dialog.setVisible(true);}
        }
        JPanel entryForm(){var form=new JPanel(new GridBagLayout());form.setBorder(BorderFactory.createEmptyBorder(0,0,4,0));return form;}
        void addEntryField(JPanel form,int row,String label,JComponent field){var labelConstraints=new GridBagConstraints();labelConstraints.gridx=0;labelConstraints.gridy=row;labelConstraints.anchor=GridBagConstraints.NORTHWEST;labelConstraints.insets=new Insets(5,0,5,10);form.add(new JLabel(label),labelConstraints);var fieldConstraints=new GridBagConstraints();fieldConstraints.gridx=1;fieldConstraints.gridy=row;fieldConstraints.weightx=1;fieldConstraints.fill=GridBagConstraints.HORIZONTAL;fieldConstraints.insets=new Insets(3,0,3,0);form.add(field,fieldConstraints);}
        JTextArea entryArea(int rows){var area=new JTextArea(rows,36);area.setLineWrap(false);return area;}
        JScrollPane entryScroll(JTextArea area){var scroll=new JScrollPane(area);scroll.setPreferredSize(new Dimension(420,Math.max(64,area.getRows()*22)));return scroll;}
        JButton addButton(String tooltip,Runnable action){var button=button("+",action);button.setToolTipText(tooltip);button.getAccessibleContext().setAccessibleName(tooltip);button.setPreferredSize(new Dimension(34,28));return button;}
        String comboText(JComboBox<String> combo){Object value=combo.isEditable()?combo.getEditor().getItem():combo.getSelectedItem();return value==null?"":value.toString().trim();}
        class ApplicationPanel extends JPanel {
            ApplicationCatalog catalog;final Set<String> selected=new HashSet<>();List<ApplicationEntry> visible=new ArrayList<>();
            final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose applications, then save the JSON configuration.");final JTextArea preview=new JTextArea();final ApplicationTable model=new ApplicationTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            ApplicationPanel(ApplicationCatalog catalog) {
                super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));
                var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select Java applications");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search application names, types, or categories");search.getAccessibleContext().setAccessibleName("Search applications");controls.add(searchPanel("Search applications",search),BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all applications",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Select real",()->{catalog().entries.stream().filter(entry->entry.type.equals("Real")).forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Select fictional",()->{catalog().entries.stream().filter(entry->entry.type.equals("Fictional")).forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));buttons.add(addButton("Add Java application",this::addApplication));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
                table.setRowHeight(28);table.setFillsViewportHeight(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={65,220,90,160,105,460};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.62);split.setDividerLocation(660);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
                search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();
            }
            ApplicationCatalog catalog(){return catalog;}
            void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.name+" "+entry.type+" "+entry.category+" "+String.join(" ",entry.javaMajors)+" "+String.join(" ",entry.classPaths)).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}
            void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" applications");count.setText(selected.size()+" applications selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one application.":applicationConfiguration(catalog,selected));preview.setCaretPosition(0);}
            void addApplication(){var name=new JTextField();var type=new JComboBox<>(new String[]{"Real","Fictional"});var category=new JTextField();var majors=new JTextField();var classPaths=entryArea(3);var commands=entryArea(3);var startTypes=new JTextField("VM start");var form=entryForm();addEntryField(form,0,"Application name",name);addEntryField(form,1,"Type",type);addEntryField(form,2,"Category",category);addEntryField(form,3,"Java major(s)",majors);addEntryField(form,4,"Classpaths (one per line)",entryScroll(classPaths));addEntryField(form,5,"Java commands (one per line)",entryScroll(commands));addEntryField(form,6,"Start type(s)",startTypes);Supplier<String> validator=()->{String value=name.getText().trim();if(value.isEmpty()||category.getText().trim().isEmpty()||commaSeparated(majors.getText()).isEmpty()||lines(classPaths.getText()).isEmpty()||lines(commands.getText()).isEmpty()||commaSeparated(startTypes.getText()).isEmpty())return "Complete all required fields.";if(catalog.entries.stream().anyMatch(entry->entry.name.equalsIgnoreCase(value)))return "An application with this name already exists.";return null;};new EntryDialog("Add Java application",form,validator,()->{var ids=catalog.entries.stream().map(ApplicationEntry::id).toList();var entry=new ApplicationEntry(nextCustomId("APP-",ids),name.getText().trim(),comboText(type),category.getText().trim(),commaSeparated(majors.getText()),lines(classPaths.getText()),lines(commands.getText()),commaSeparated(startTypes.getText()));appendCustomApplication(customApplicationsPath(),entry);var entries=new ArrayList<>(catalog.entries);entries.add(entry);catalog=new ApplicationCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id);search.setText("");filter();status.setText("Added "+entry.name+" and saved it to "+customApplicationsPath()+".");}).show();}
            void loadCatalog(){var chooser=chooser("Load Java Applications JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readApplicationCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}
            void save(){var chooser=chooser("Save application configuration");chooser.setSelectedFile(new File("generator-applications.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,applicationConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" applications to "+path);}catch(Exception exception){error(exception);}}
            class ApplicationTable extends AbstractTableModel {final String[] names={"Include","Application","Type","Category","Java major(s)","Classpath"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.name;case 2->entry.type;case 3->entry.category;case 4->String.join(", ",entry.javaMajors);default->String.join(" | ",entry.classPaths);};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        class HostPanel extends JPanel {
            HostCatalog catalog;final Set<String> selected=new HashSet<>();List<HostEntry> visible=new ArrayList<>();final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose hosts, then save the JSON configuration.");final JTextArea preview=new JTextArea();final HostTable model=new HostTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            HostPanel(HostCatalog catalog){super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select hosts and IP addresses");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search hostnames, IP addresses, or platforms");search.getAccessibleContext().setAccessibleName("Search hosts and IP addresses");controls.add(searchPanel("Search hosts/IPs",search),BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all hosts",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));buttons.add(addButton("Add host or IP address",this::addHost));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);table.setRowHeight(28);table.setFillsViewportHeight(true);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();}
            HostCatalog catalog(){return catalog;}void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.value+" "+entry.platform).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" hosts");count.setText(selected.size()+" hosts selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one host.":hostConfiguration(catalog,selected));preview.setCaretPosition(0);}void addHost(){var name=new JTextField();var address=new JTextField();var platform=new JComboBox<>(new String[]{"Linux","Windows","macOS","Solaris"});platform.setEditable(true);var form=entryForm();addEntryField(form,0,"Hostname",name);addEntryField(form,1,"IP address",address);addEntryField(form,2,"Platform",platform);Supplier<String> validator=()->{String host=name.getText().trim(),ip=address.getText().trim();if(host.isEmpty()||ip.isEmpty()||comboText(platform).isEmpty())return "Complete all required fields.";if(!validIpAddress(ip))return "Enter a valid IPv4 address.";if(catalog.entries.stream().anyMatch(entry->entry.hostName.equalsIgnoreCase(host)))return "A host with this name already exists.";if(catalog.entries.stream().anyMatch(entry->entry.ipAddress.equals(ip)))return "A host with this IP address already exists.";return null;};new EntryDialog("Add host or IP address",form,validator,()->{String host=name.getText().trim(),ip=address.getText().trim();var ids=catalog.entries.stream().map(HostEntry::id).toList();var entry=new HostEntry(nextCustomId("HOST-",ids),host+"/"+ip,host,ip,comboText(platform));appendCustomHost(customHostsPath(),entry);var entries=new ArrayList<>(catalog.entries);entries.add(entry);catalog=new HostCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id);search.setText("");filter();status.setText("Added "+entry.value+" and saved it to "+customHostsPath()+".");}).show();}void loadCatalog(){var chooser=chooser("Load Hosts / IPs JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readHostCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}void save(){var chooser=chooser("Save host configuration");chooser.setSelectedFile(new File("generator-hosts.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,hostConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" hosts to "+path);}catch(Exception exception){error(exception);}}
            class HostTable extends AbstractTableModel {final String[] names={"Include","Hostname","IP address","Platform"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.hostName;case 2->entry.ipAddress;default->entry.platform;};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        class OsVersionPanel extends JPanel {
            OsVersionCatalog catalog;final Set<String> selected=new HashSet<>();List<OsVersionEntry> visible=new ArrayList<>();final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose OS versions, then save the JSON configuration.");final JTextArea preview=new JTextArea();final OsVersionTable model=new OsVersionTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            OsVersionPanel(OsVersionCatalog catalog){super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select OS versions");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search versions, OS names, architectures, or platforms");search.getAccessibleContext().setAccessibleName("Search OS versions");controls.add(searchPanel("Search OS versions",search),BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all OS versions",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));buttons.add(addButton("Add OS version",this::addOsVersion));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);table.setRowHeight(28);table.setFillsViewportHeight(true);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();}
            OsVersionCatalog catalog(){return catalog;}void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.version+" "+entry.platform+" "+String.join(" ",entry.osNames)+" "+String.join(" ",entry.architectures)).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" OS versions");count.setText(selected.size()+" OS versions selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one OS version.":osVersionConfiguration(catalog,selected));preview.setCaretPosition(0);}void addOsVersion(){var version=new JTextField();var platform=new JComboBox<>(new String[]{"Linux","Windows","macOS","Solaris"});platform.setEditable(true);var names=entryArea(3);var architectures=new JTextField();var form=entryForm();addEntryField(form,0,"OS version",version);addEntryField(form,1,"Platform",platform);addEntryField(form,2,"OS names (one per line)",entryScroll(names));addEntryField(form,3,"Architectures",architectures);Supplier<String> validator=()->{String value=version.getText().trim();if(value.isEmpty()||comboText(platform).isEmpty()||lines(names.getText()).isEmpty()||commaSeparated(architectures.getText()).isEmpty())return "Complete all required fields.";if(catalog.entries.stream().anyMatch(entry->entry.version.equalsIgnoreCase(value)))return "This OS version already exists.";return null;};new EntryDialog("Add OS version",form,validator,()->{var ids=catalog.entries.stream().map(OsVersionEntry::id).toList();var entry=new OsVersionEntry(nextCustomId("OSV-",ids),version.getText().trim(),comboText(platform),lines(names.getText()),commaSeparated(architectures.getText()));appendCustomOsVersion(customOsVersionsPath(),entry);var entries=new ArrayList<>(catalog.entries);entries.add(entry);catalog=new OsVersionCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id);search.setText("");filter();status.setText("Added "+entry.version+" and saved it to "+customOsVersionsPath()+".");}).show();}void loadCatalog(){var chooser=chooser("Load OS Versions JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readOsVersionCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}void save(){var chooser=chooser("Save OS version configuration");chooser.setSelectedFile(new File("generator-os-versions.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,osVersionConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" OS versions to "+path);}catch(Exception exception){error(exception);}}
            class OsVersionTable extends AbstractTableModel {final String[] names={"Include","OS version","Platform","OS name(s)","Architecture(s)"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.version;case 2->entry.platform;case 3->String.join(", ",entry.osNames);default->String.join(", ",entry.architectures);};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        Catalog catalog(){return catalog;}
        JButton button(String text,Runnable action){var button=new JButton(text);button.addActionListener(e->action.run());return button;}
        JPanel searchPanel(String label,JTextField field){field.setPreferredSize(new Dimension(260,28));var panel=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));panel.add(new JLabel(label));panel.add(field);return panel;}
        void setCatalog(Catalog next){catalog=next;changing=true;family.removeAllItems();family.addItem("All families");for(String id:next.families.reversed())family.addItem("JDK "+id);changing=false;info.setText("Catalog "+next.date+" · "+next.families.size()+" families · "+next.entries.size()+" versions");filter();}
        String chosenFamily(){String text=(String)family.getSelectedItem();return text==null||text.equals("All families")?null:text.substring(4);}
        void chooseFamily(boolean value){String id=chosenFamily();if(id!=null){selectFamily(catalog,selected,id,value);refresh();}}
        void filter(){String id=chosenFamily(),query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.reversed().stream().filter(e->(id==null||e.family.equals(id)) && (e.version+" "+e.javaVersion+" "+e.label+" JDK "+e.family).toLowerCase(Locale.ROOT).contains(query)).toList();include.setEnabled(id!=null);exclude.setEnabled(id!=null);model.fireTableDataChanged();refresh();}
        void refresh(){model.fireTableDataChanged();count.setText(selected.size()+" versions selected · "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one version.":versionConfiguration(catalog,selected));preview.setCaretPosition(0);}
        JFileChooser chooser(String title){var chooser=new JFileChooser();chooser.setDialogTitle(title);return chooser;}
        void error(Exception error){JOptionPane.showMessageDialog(this,error.getMessage(),"Could not complete action",JOptionPane.ERROR_MESSAGE);}
        void loadSelection(){var chooser=chooser("Load Java Versions JSON configuration");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Set<String> next=readSelection(chooser.getSelectedFile().toPath(),catalog);selected.clear();selected.addAll(next);refresh();status.setText("Loaded "+next.size()+" choices. Customize and save a revised file.");}catch(Exception error){error(error);}}
        void loadCatalog(){var chooser=chooser("Load Oracle JDK JSON catalog");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Catalog next=readCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(e->known.add(e.version));int previous=selected.size();selected.retainAll(known);setCatalog(next);status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception error){error(error);}}
        void updateForLatestVersions(){updateLatest.setEnabled(false);status.setText("Checking ops.java for released JDK 8 and later versions, including Security Alerts...");new SwingWorker<CatalogUpdate,Void>(){protected CatalogUpdate doInBackground()throws Exception{return fetchLatestCatalog(catalog);}protected void done(){try{var update=get();setCatalog(update.catalog);String change=update.additions==0?"No new versions were available.":"Added "+update.additions+" versions.";String route=update.fallback?" Retrieved through the read-only web fallback.":"";status.setText(change+route+" Catalog saved to "+update.path);}catch(Exception exception){Throwable cause=exception.getCause()==null?exception:exception.getCause();String message=cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage();status.setText("Update failed: "+message);error(new Exception(message));}finally{updateLatest.setEnabled(true);}}}.execute();}
        void saveSelection(){var chooser=chooser("Save Java Versions JSON configuration");chooser.setSelectedFile(new File("generator-versions.json"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,versionConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" choices to "+path);}catch(Exception error){error(error);}}
        class VersionTable extends AbstractTableModel {
            final String[] names={"Include","Family","Version / update","Expected java.version"};
            public int getRowCount(){return visible.size();}public int getColumnCount(){return 4;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}
            public Object getValueAt(int row,int column){Entry e=visible.get(row);return switch(column){case 0->selected.contains(e.version);case 1->"JDK "+e.family;case 2->e.label;default->e.javaVersion;};}
            public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).version);else selected.remove(visible.get(row).version);refresh();}
        }
    }
}
