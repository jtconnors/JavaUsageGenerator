package com.jtconnors.jug;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

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
                JugApplication.configure(catalog,selected,applicationCatalog,hostCatalog,osVersionCatalog);
                javafx.application.Application.launch(JugApplication.class);
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
}
