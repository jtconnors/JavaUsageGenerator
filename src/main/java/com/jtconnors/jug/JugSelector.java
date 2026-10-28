package com.jtconnors.jug;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class JugSelector {
    record Entry(String family, String version, String label, String javaVersion) {}
    record Catalog(String date, List<Entry> entries, List<String> families) {}
    record ApplicationEntry(String id, String name, String type, String category, List<String> javaMajors, int sourceRecords, List<String> classPaths, List<String> javaCommands, List<String> startTypes) {}
    record ApplicationCatalog(String date, List<ApplicationEntry> entries) {}
    record HostEntry(String id, String value, String hostName, String ipAddress, String platform, int sourceRecords) {}
    record HostCatalog(String date, List<HostEntry> entries) {}
    record OsVersionEntry(String id, String version, String platform, List<String> osNames, List<String> architectures, int sourceRecords) {}
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
        --output FILE          Save properties (otherwise print to stdout)
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
            Catalog catalog;
            if (options.containsKey("catalog")) catalog=readCatalog(Files.readString(Path.of(value(options,"catalog"))));
            else try (var stream=JugSelector.class.getResourceAsStream("/catalog.json")) {
                if (stream==null) throw new IOException("Bundled catalog missing");
                catalog=readCatalog(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            ApplicationCatalog applicationCatalog;
            if (options.containsKey("applications-catalog")) applicationCatalog=readApplicationCatalog(Files.readString(Path.of(value(options,"applications-catalog"))));
            else try (var stream=JugSelector.class.getResourceAsStream("/applications.json")) {
                if (stream==null) throw new IOException("Bundled application catalog missing");
                applicationCatalog=readApplicationCatalog(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            HostCatalog hostCatalog;
            if (options.containsKey("hosts-catalog")) hostCatalog=readHostCatalog(Files.readString(Path.of(value(options,"hosts-catalog"))));
            else try (var stream=JugSelector.class.getResourceAsStream("/hosts.json")) {
                if (stream==null) throw new IOException("Bundled host catalog missing");
                hostCatalog=readHostCatalog(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            OsVersionCatalog osVersionCatalog;
            if (options.containsKey("os-versions-catalog")) osVersionCatalog=readOsVersionCatalog(Files.readString(Path.of(value(options,"os-versions-catalog"))));
            else try (var stream=JugSelector.class.getResourceAsStream("/os-versions.json")) {
                if (stream==null) throw new IOException("Bundled OS version catalog missing");
                osVersionCatalog=readOsVersionCatalog(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
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
                if (options.containsKey("output") || options.containsKey("force")) throw new IllegalArgumentException("Use Save properties in GUI mode");
                final Catalog initial=catalog;
                SwingUtilities.invokeLater(()->{
                    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                    catch (Exception ignored) {}
                    new Window(initial,selected,applicationCatalog,hostCatalog,osVersionCatalog).setVisible(true);
                });
            } else {
                String content=properties(catalog,selected);
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
    static void requireVersion(Catalog catalog,String version) {
        if(catalog.entries.stream().noneMatch(e->e.version.equals(version))) throw new IllegalArgumentException("Unknown version: "+version);
    }
    static void selectFamily(Catalog catalog,Set<String> selected,String family,boolean include) {
        if(!catalog.families.contains(family)) throw new IllegalArgumentException("Unknown family: "+family);
        for(var e:catalog.entries) if(e.family.equals(family)) { if(include) selected.add(e.version); else selected.remove(e.version); }
    }
    static String properties(Catalog catalog,Set<String> selected) {
        if(selected.isEmpty()) throw new IllegalArgumentException("Select at least one version");
        var text=new StringBuilder("# Java Usage Tracker generator: selected versions\n# Explicit snapshot; selecting a family includes its currently listed versions.\ngenerator.selection.schemaVersion=1\ngenerator.catalog.asOf=").append(catalog.date).append("\ngenerator.selection.count=").append(selected.size()).append('\n');
        int index=0;
        for(var e:catalog.entries) if(selected.contains(e.version)) {
            String prefix="generator.selection."+index++;
            text.append('\n').append(prefix).append(".family=").append(e.family).append('\n').append(prefix).append(".version=").append(e.version).append('\n').append(prefix).append(".javaVersion=").append(e.javaVersion).append('\n');
        }
        if(index!=selected.size()) throw new IllegalArgumentException("Selection contains unavailable versions");
        return text.toString();
    }
    static Set<String> readSelection(Path path,Catalog catalog) throws IOException {
        var settings=new Properties(){ public synchronized Object put(Object key,Object value) {
            if(containsKey(key)) throw new IllegalArgumentException("Duplicate property: "+key);
            return super.put(key,value);
        }};
        try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) { settings.load(reader); }
        if(!"1".equals(settings.getProperty("generator.selection.schemaVersion"))) throw new IllegalArgumentException("Unsupported selection schema");
        if(!settings.getProperty("generator.catalog.asOf", "").matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid catalog date");
        int count=Integer.parseInt(settings.getProperty("generator.selection.count","0"));
        if(count<1 || count>catalog.entries.size()) throw new IllegalArgumentException("Invalid selection count");
        var known=new HashMap<String,Entry>(); for(var e:catalog.entries) known.put(e.version,e);
        var selected=new HashSet<String>();
        for(int i=0;i<count;i++) {
            String prefix="generator.selection."+i,version=settings.getProperty(prefix+".version");
            Entry e=known.get(version);
            if(e==null || !selected.add(version)) throw new IllegalArgumentException("Unknown or duplicate version: "+version);
            if(!e.family.equals(settings.getProperty(prefix+".family")) || !e.javaVersion.equals(settings.getProperty(prefix+".javaVersion"))) throw new IllegalArgumentException("Catalog mapping mismatch: "+version);
        }
        if(settings.size()!=3+3*count) throw new IllegalArgumentException("Unexpected selection properties");
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
                String version=(String)item.get("version"),javaVersion=(String)item.get("javaVersion"),label=(String)item.get("label");
                if(version==null || javaVersion==null || label==null || !version.matches("[A-Za-z0-9._+-]+") || !javaVersion.matches("[A-Za-z0-9._+-]+") || !versions.add(version)) throw new IllegalArgumentException("Invalid or duplicate version");
                entries.add(new Entry(family,version,label,javaVersion));
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
            int records=((java.math.BigDecimal)item.get("sourceRecords")).intValueExact();
            if(id==null || !id.matches("APP-\\d{3,}") || !ids.add(id) || name==null || name.isBlank() || !names.add(name) || type==null || category==null || majors.isEmpty() || records<1 || classPaths.isEmpty() || classPaths.stream().anyMatch(String::isBlank) || new HashSet<>(classPaths).size()!=classPaths.size() || javaCommands.isEmpty() || javaCommands.stream().anyMatch(String::isBlank) || startTypes.isEmpty() || startTypes.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid or duplicate application");
            entries.add(new ApplicationEntry(id,name,type,category,majors,records,classPaths,javaCommands,startTypes));
        }
        if(entries.isEmpty()) throw new IllegalArgumentException("Empty application catalog");
        return new ApplicationCatalog(date,List.copyOf(entries));
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
        for(var raw:(List<Object>)data.get("hosts")) {var item=(Map<String,Object>)raw;String id=(String)item.get("id"),value=(String)item.get("value"),name=(String)item.get("hostName"),address=(String)item.get("ipAddress"),platform=(String)item.get("platform");int records=((java.math.BigDecimal)item.get("sourceRecords")).intValueExact();if(id==null || !id.matches("HOST-\\d{3,}") || !ids.add(id) || name==null || name.isBlank() || !names.add(name) || address==null || !address.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || !addresses.add(address) || value==null || !value.equals(name+"/"+address) || !values.add(value) || platform==null || platform.isBlank() || records<1) throw new IllegalArgumentException("Invalid or duplicate host");entries.add(new HostEntry(id,value,name,address,platform,records));}
        if(entries.isEmpty()) throw new IllegalArgumentException("Empty host catalog");return new HostCatalog(date,List.copyOf(entries));
    }
    static String hostConfiguration(HostCatalog catalog,Set<String> selected) {
        if(selected.isEmpty()) throw new IllegalArgumentException("Select at least one host");var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.id)).toList();if(chosen.size()!=selected.size()) throw new IllegalArgumentException("Host selection contains unavailable entries");var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"hostCount\": ").append(chosen.size()).append(",\n  \"hosts\": [\n");for(int i=0;i<chosen.size();i++){var entry=chosen.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"value\": \"").append(jsonString(entry.value)).append("\",\n      \"hostName\": \"").append(jsonString(entry.hostName)).append("\",\n      \"ipAddress\": \"").append(jsonString(entry.ipAddress)).append("\"\n    }");if(i+1<chosen.size())text.append(',');text.append('\n');}return text.append("  ]\n}\n").toString();
    }
    @SuppressWarnings("unchecked")
    static OsVersionCatalog readOsVersionCatalog(String text) {
        var data=(Map<String,Object>)new Json(text).read();String date=(String)data.get("asOf");if(date==null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid OS version catalog date");var entries=new ArrayList<OsVersionEntry>();var ids=new HashSet<String>();var versions=new HashSet<String>();for(var raw:(List<Object>)data.get("osVersions")){var item=(Map<String,Object>)raw;String id=(String)item.get("id"),version=(String)item.get("version"),platform=(String)item.get("platform");var names=((List<Object>)item.get("osNames")).stream().map(String::valueOf).toList();var architectures=((List<Object>)item.get("architectures")).stream().map(String::valueOf).toList();int records=((java.math.BigDecimal)item.get("sourceRecords")).intValueExact();if(id==null || !id.matches("OSV-\\d{3,}") || !ids.add(id) || version==null || version.isBlank() || !versions.add(version) || platform==null || platform.isBlank() || names.isEmpty() || names.stream().anyMatch(String::isBlank) || architectures.isEmpty() || architectures.stream().anyMatch(String::isBlank) || records<1) throw new IllegalArgumentException("Invalid or duplicate OS version");entries.add(new OsVersionEntry(id,version,platform,names,architectures,records));}if(entries.isEmpty())throw new IllegalArgumentException("Empty OS version catalog");return new OsVersionCatalog(date,List.copyOf(entries));
    }
    static String osVersionConfiguration(OsVersionCatalog catalog,Set<String> selected) {
        if(selected.isEmpty())throw new IllegalArgumentException("Select at least one OS version");var chosen=catalog.entries.stream().filter(entry->selected.contains(entry.id)).toList();if(chosen.size()!=selected.size())throw new IllegalArgumentException("OS version selection contains unavailable entries");var text=new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"catalogAsOf\": \"").append(catalog.date).append("\",\n  \"osVersionCount\": ").append(chosen.size()).append(",\n  \"osVersions\": [\n");for(int i=0;i<chosen.size();i++){var entry=chosen.get(i);text.append("    {\n      \"id\": \"").append(jsonString(entry.id)).append("\",\n      \"version\": \"").append(jsonString(entry.version)).append("\",\n      \"platform\": \"").append(jsonString(entry.platform)).append("\",\n      \"osNames\": [");for(int j=0;j<entry.osNames.size();j++){if(j>0)text.append(", ");text.append('"').append(jsonString(entry.osNames.get(j))).append('"');}text.append("],\n      \"architectures\": [");for(int j=0;j<entry.architectures.size();j++){if(j>0)text.append(", ");text.append('"').append(jsonString(entry.architectures.get(j))).append('"');}text.append("]\n    }");if(i+1<chosen.size())text.append(',');text.append('\n');}return text.append("  ]\n}\n").toString();
    }
    static <T> T randomItem(List<T> values,Random random) { return values.get(random.nextInt(values.size())); }
    static String csvField(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    static String sampleJutPacket(Catalog versionCatalog,Set<String> selectedVersions,ApplicationCatalog applicationCatalog,Set<String> selectedApplications,HostCatalog hostCatalog,Set<String> selectedHosts,OsVersionCatalog osVersionCatalog,Set<String> selectedOsVersions,Random random,long now) {
        var versions=versionCatalog.entries.stream().filter(entry->selectedVersions.contains(entry.version)).toList();var applications=applicationCatalog.entries.stream().filter(entry->selectedApplications.contains(entry.id)).toList();var hosts=hostCatalog.entries.stream().filter(entry->selectedHosts.contains(entry.id)).toList();var osVersions=osVersionCatalog.entries.stream().filter(entry->selectedOsVersions.contains(entry.id)).toList();if(versions.isEmpty() || applications.isEmpty() || hosts.isEmpty() || osVersions.isEmpty())throw new IllegalArgumentException("Each tab must have at least one selected value.");var version=randomItem(versions,random);var application=randomItem(applications,random);var host=randomItem(hosts,random);var osVersion=randomItem(osVersions,random);long ninetyDays=90L*24*60*60*1000;var timestamp=new Date(now-(long)(random.nextDouble()*ninetyDays));String formattedTime=new java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy",Locale.US).format(timestamp);String javaHome=switch(osVersion.platform){case "Windows"->"C:\\Java\\jdk-"+version.version;case "macOS"->"/Library/Java/JavaVirtualMachines/jdk-"+version.version+".jdk/Contents/Home";default->"/opt/java/jdk-"+version.version;};var fields=List.of(randomItem(application.startTypes,random),formattedTime,host.value,randomItem(application.javaCommands,random),javaHome,version.javaVersion,version.javaVersion,"Oracle Corporation","Oracle Corporation",randomItem(osVersion.osNames,random),randomItem(osVersion.architectures,random),osVersion.version,"",randomItem(application.classPaths,random),"");return fields.stream().map(JugSelector::csvField).collect(java.util.stream.Collectors.joining(","))+"\n";
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
        final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose versions, then save your properties file.");
        List<Entry> visible=new ArrayList<>(); boolean changing=false;
        final JTextArea preview=new JTextArea(); final VersionTable model=new VersionTable(); final JTable table=new JTable(model);
        final JButton save=new JButton("Save properties…"),include=new JButton("Select entire family"),exclude=new JButton("Clear family");
        Window(Catalog catalog,Set<String> selected,ApplicationCatalog applicationCatalog,HostCatalog hostCatalog,OsVersionCatalog osVersionCatalog) {
            super("JUG - Java Usage Generator");this.catalog=catalog;this.selected=selected;
            setDefaultCloseOperation(EXIT_ON_CLOSE);setSize(1120,760);setMinimumSize(new Dimension(820,540));setLocationRelativeTo(null);
            JPanel root=new JPanel(new BorderLayout(12,12));root.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));setContentPane(root);
            var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select Java versions");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);
            var controls=new JPanel(new GridLayout(2,1,6,6));var filters=new JPanel(new BorderLayout(8,8));family.setPreferredSize(new Dimension(160,28));filters.add(family,BorderLayout.WEST);filters.add(search,BorderLayout.CENTER);search.setToolTipText("Search versions or expected java.version values");search.getAccessibleContext().setAccessibleName("Search versions");family.getAccessibleContext().setAccessibleName("JDK family");controls.add(filters);
            var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(include);buttons.add(exclude);buttons.add(button("Select all versions",()->{catalog().entries.forEach(e->selected.add(e.version));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load selection…",this::loadSelection));buttons.add(button("Load catalog…",this::loadCatalog));controls.add(buttons);top.add(controls,BorderLayout.SOUTH);root.add(top,BorderLayout.NORTH);
            table.setRowHeight(28);table.setFillsViewportHeight(true);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.getColumnModel().getColumn(0).setMaxWidth(65);
            var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));preview.getAccessibleContext().setAccessibleName("Properties preview");right.add(new JScrollPane(preview),BorderLayout.CENTER);
            var footer=new JPanel(new BorderLayout(6,6));footer.add(new JLabel("Selections are a snapshot of currently listed versions."),BorderLayout.NORTH);footer.add(save,BorderLayout.SOUTH);right.add(footer,BorderLayout.SOUTH);
            var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);root.add(split,BorderLayout.CENTER);root.add(status,BorderLayout.SOUTH);
            include.addActionListener(e->chooseFamily(true));exclude.addActionListener(e->chooseFamily(false));save.addActionListener(e->saveSelection());
            family.addActionListener(e->{if(!changing)filter();});search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
            setCatalog(catalog);refresh();
            var cardsLayout=new CardLayout();var cards=new JPanel(cardsLayout);var tabBar=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));var group=new ButtonGroup();var applicationPanel=new ApplicationPanel(applicationCatalog);var hostPanel=new HostPanel(hostCatalog);var osVersionPanel=new OsVersionPanel(osVersionCatalog);var generatePanel=new GeneratePanel(applicationPanel,hostPanel,osVersionPanel);String[] tabNames={"Generate","Java Versions","Java Applications","Hosts / IPs","OS Versions"};JPanel[] panels={generatePanel,root,applicationPanel,hostPanel,osVersionPanel};for(int i=0;i<tabNames.length;i++){String name=tabNames[i];var tab=new JToggleButton(name);tab.setFocusPainted(false);tab.setMargin(new Insets(9,14,9,14));tab.addActionListener(event->cardsLayout.show(cards,name));group.add(tab);tabBar.add(tab);cards.add(panels[i],name);if(i==0)tab.setSelected(true);}var navigation=new JPanel(new BorderLayout());navigation.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(190,190,190)));navigation.add(tabBar,BorderLayout.WEST);var shell=new JPanel(new BorderLayout());shell.add(navigation,BorderLayout.NORTH);shell.add(cards,BorderLayout.CENTER);setContentPane(shell);
        }
        class GeneratePanel extends JPanel {
            final ApplicationPanel applications;final HostPanel hosts;final OsVersionPanel osVersions;final JTextArea packet=new JTextArea(),seriesOutput=new JTextArea();final JLabel summary=new JLabel("Uses one random selected value from every configuration tab."),seriesStatus=new JLabel("Configure the number of packets and the delay between each packet.");final JButton save=new JButton("Save sample.jut..."),start=new JButton("Start generation"),stop=new JButton("Stop"),saveSeries=new JButton("Save packet-series.jut...");final JSpinner packetCount=new JSpinner(new SpinnerNumberModel(10,1,1000,1)),interval=new JSpinner(new SpinnerNumberModel(1,1,3600,1)),udpPort=new JSpinner(new SpinnerNumberModel(32139,1,65535,1));final JComboBox<String> intervalUnit=new JComboBox<>(new String[]{"Seconds","Milliseconds","Minutes"});final JCheckBox udpEnabled=new JCheckBox("Send via UDP");final JTextField udpHost=new JTextField("localhost",18);final List<String> seriesPackets=new ArrayList<>();javax.swing.Timer seriesTimer;DatagramSocket udpSocket;InetAddress udpAddress;int udpDestinationPort,seriesTarget;
            GeneratePanel(ApplicationPanel applications,HostPanel hosts,OsVersionPanel osVersions){super(new BorderLayout());this.applications=applications;this.hosts=hosts;this.osVersions=osVersions;setBorder(BorderFactory.createEmptyBorder(14,14,14,14));var sampleSection=new JPanel(new BorderLayout(8,8));sampleSection.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(205,212,207)),BorderFactory.createEmptyBorder(12,12,12,12)));var sampleHeader=new JPanel(new BorderLayout(6,6));var sampleTitle=new JLabel("Generate a sample JUT packet");sampleTitle.setFont(sampleTitle.getFont().deriveFont(Font.BOLD,18));sampleHeader.add(sampleTitle,BorderLayout.NORTH);sampleHeader.add(summary,BorderLayout.CENTER);var sampleActions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));sampleActions.add(button("Generate a sample JUT packet",this::generate));save.setEnabled(false);save.addActionListener(event->save());sampleActions.add(save);sampleHeader.add(sampleActions,BorderLayout.SOUTH);sampleSection.add(sampleHeader,BorderLayout.NORTH);configureOutput(packet,"Sample JUT packet");packet.setRows(4);packet.setText("Generate a packet to preview it here.");sampleSection.add(new JScrollPane(packet),BorderLayout.CENTER);
                var seriesSection=new JPanel(new BorderLayout(8,8));seriesSection.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(205,212,207)),BorderFactory.createEmptyBorder(12,12,12,12)));var seriesHeader=new JPanel(new BorderLayout(6,6));var seriesTitle=new JLabel("Generate a packet series");seriesTitle.setFont(seriesTitle.getFont().deriveFont(Font.BOLD,18));seriesHeader.add(seriesTitle,BorderLayout.NORTH);seriesHeader.add(seriesStatus,BorderLayout.CENTER);var controls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));controls.add(labeled("Packet count",packetCount));controls.add(labeled("Interval",interval));controls.add(labeled("Interval unit",intervalUnit));start.addActionListener(event->startSeries());controls.add(start);stop.setEnabled(false);stop.addActionListener(event->stopSeries());controls.add(stop);saveSeries.setEnabled(false);saveSeries.addActionListener(event->saveSeries());controls.add(saveSeries);var udpControls=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));udpEnabled.addActionListener(event->updateUdpControls());udpControls.add(udpEnabled);udpHost.getAccessibleContext().setAccessibleName("UDP hostname");var portEditor=new JSpinner.NumberEditor(udpPort,"0");portEditor.getFormat().setGroupingUsed(false);udpPort.setEditor(portEditor);udpPort.getAccessibleContext().setAccessibleName("UDP port");udpControls.add(labeled("Hostname",udpHost));udpControls.add(labeled("UDP port",udpPort));var controlStack=new JPanel(new GridLayout(2,1,6,6));controlStack.add(controls);controlStack.add(udpControls);seriesHeader.add(controlStack,BorderLayout.SOUTH);seriesSection.add(seriesHeader,BorderLayout.NORTH);configureOutput(seriesOutput,"Generated packet series");seriesOutput.setText("Generated packets will appear here as the interval elapses.");seriesSection.add(new JScrollPane(seriesOutput),BorderLayout.CENTER);var split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,sampleSection,seriesSection);split.setResizeWeight(.28);split.setDividerLocation(210);split.setBorder(null);add(split,BorderLayout.CENTER);updateUdpControls();}
            JPanel labeled(String text,JComponent component){var field=new JPanel(new BorderLayout(3,3));field.add(new JLabel(text),BorderLayout.NORTH);field.add(component,BorderLayout.CENTER);return field;}
            void configureOutput(JTextArea output,String accessibleName){output.setEditable(false);output.setLineWrap(true);output.setWrapStyleWord(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));output.getAccessibleContext().setAccessibleName(accessibleName);}
            void generate(){try{String next=sampleJutPacket(catalog,selected,applications.catalog,applications.selected,hosts.catalog,hosts.selected,osVersions.catalog,osVersions.selected,new Random(),System.currentTimeMillis());packet.setText(next);packet.setCaretPosition(0);summary.setText("Generated a fresh packet from the current selections.");save.setEnabled(true);}catch(Exception exception){packet.setText("");save.setEnabled(false);summary.setText(exception.getMessage());}}
            void save(){var chooser=chooser("Save sample JUT packet");chooser.setSelectedFile(new File("sample.jut"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,packet.getText(),StandardCharsets.UTF_8);summary.setText("Saved sample packet to "+path);}catch(Exception exception){error(exception);}}
            void updateUdpControls(){boolean enabled=udpEnabled.isSelected()&&udpEnabled.isEnabled();udpHost.setEnabled(enabled);udpPort.setEnabled(enabled);}
            void setSeriesRunning(boolean running){packetCount.setEnabled(!running);interval.setEnabled(!running);intervalUnit.setEnabled(!running);udpEnabled.setEnabled(!running);start.setEnabled(!running);stop.setEnabled(running);saveSeries.setEnabled(!running&&!seriesPackets.isEmpty());updateUdpControls();}
            void startSeries(){try{seriesTarget=(Integer)packetCount.getValue();seriesPackets.clear();seriesOutput.setText("");if(udpEnabled.isSelected()){String hostname=udpHost.getText().trim();if(hostname.isEmpty())throw new IllegalArgumentException("Enter a UDP hostname.");udpAddress=InetAddress.getByName(hostname);udpDestinationPort=(Integer)udpPort.getValue();udpSocket=new DatagramSocket();}setSeriesRunning(true);appendSeriesPacket();if(seriesPackets.size()<seriesTarget){long multiplier=switch(intervalUnit.getSelectedIndex()){case 1->1L;case 2->60000L;default->1000L;};int delay=(int)Math.min(Integer.MAX_VALUE,(Integer)interval.getValue()*multiplier);seriesTimer=new javax.swing.Timer(delay,event->appendSeriesPacket());seriesTimer.start();}}catch(Exception exception){closeUdp();setSeriesRunning(false);seriesStatus.setText("Unable to start: "+failureMessage(exception));}}
            void appendSeriesPacket(){try{String next=sampleJutPacket(catalog,selected,applications.catalog,applications.selected,hosts.catalog,hosts.selected,osVersions.catalog,osVersions.selected,new Random(),System.currentTimeMillis());if(udpSocket!=null){byte[] data=next.getBytes(StandardCharsets.UTF_8);if(data.length>65507)throw new IOException("Packet exceeds the UDP payload limit.");udpSocket.send(new DatagramPacket(data,data.length,udpAddress,udpDestinationPort));}seriesPackets.add(next);seriesOutput.append(next);seriesOutput.setCaretPosition(seriesOutput.getDocument().getLength());seriesStatus.setText((udpSocket==null?"Generated ":"Generated and sent ")+seriesPackets.size()+" of "+seriesTarget+" packets.");if(seriesPackets.size()>=seriesTarget){String destination=udpSocket==null?"":" via UDP to "+udpHost.getText().trim()+":"+udpDestinationPort;finishSeries("Completed "+seriesTarget+" packets"+destination+".");}}catch(Exception exception){finishSeries("Stopped after "+seriesPackets.size()+" packets: "+failureMessage(exception));}}
            String failureMessage(Exception exception){String message=exception.getMessage();return message==null||message.isBlank()?exception.getClass().getSimpleName():message;}
            void closeUdp(){if(udpSocket!=null){udpSocket.close();udpSocket=null;}udpAddress=null;udpDestinationPort=0;}
            void finishSeries(String message){if(seriesTimer!=null){seriesTimer.stop();seriesTimer=null;}closeUdp();setSeriesRunning(false);seriesStatus.setText(message);}
            void stopSeries(){finishSeries("Stopped after "+seriesPackets.size()+" of "+seriesTarget+" packets.");}
            void saveSeries(){var chooser=chooser("Save JUT packet series");chooser.setSelectedFile(new File("packet-series.jut"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,String.join("",seriesPackets),StandardCharsets.UTF_8);seriesStatus.setText("Saved "+seriesPackets.size()+" packets to "+path);}catch(Exception exception){error(exception);}}
        }
        class ApplicationPanel extends JPanel {
            ApplicationCatalog catalog;final Set<String> selected=new HashSet<>();List<ApplicationEntry> visible=new ArrayList<>();
            final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose applications, then save the JSON configuration.");final JTextArea preview=new JTextArea();final ApplicationTable model=new ApplicationTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            ApplicationPanel(ApplicationCatalog catalog) {
                super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));
                var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select Java applications");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search application names, types, or categories");controls.add(search,BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all applications",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Select real",()->{catalog().entries.stream().filter(entry->entry.type.equals("Real")).forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Select fictional",()->{catalog().entries.stream().filter(entry->entry.type.equals("Fictional")).forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
                table.setRowHeight(28);table.setFillsViewportHeight(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);int[] widths={65,220,90,160,105,105,460};for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.62);split.setDividerLocation(660);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
                search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();
            }
            ApplicationCatalog catalog(){return catalog;}
            void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.name+" "+entry.type+" "+entry.category+" "+String.join(" ",entry.javaMajors)+" "+String.join(" ",entry.classPaths)).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}
            void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" applications");count.setText(selected.size()+" applications selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one application.":applicationConfiguration(catalog,selected));preview.setCaretPosition(0);}
            void loadCatalog(){var chooser=chooser("Load Java Applications JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readApplicationCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}
            void save(){var chooser=chooser("Save application configuration");chooser.setSelectedFile(new File("generator-applications.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,applicationConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" applications to "+path);}catch(Exception exception){error(exception);}}
            class ApplicationTable extends AbstractTableModel {final String[] names={"Include","Application","Type","Category","Java major(s)","Source records","Classpath"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:column==5?Integer.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.name;case 2->entry.type;case 3->entry.category;case 4->String.join(", ",entry.javaMajors);case 5->entry.sourceRecords;default->String.join(" | ",entry.classPaths);};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        class HostPanel extends JPanel {
            HostCatalog catalog;final Set<String> selected=new HashSet<>();List<HostEntry> visible=new ArrayList<>();final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose hosts, then save the JSON configuration.");final JTextArea preview=new JTextArea();final HostTable model=new HostTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            HostPanel(HostCatalog catalog){super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select hosts and IP addresses");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search hostnames, IP addresses, or platforms");controls.add(search,BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all hosts",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);table.setRowHeight(28);table.setFillsViewportHeight(true);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();}
            HostCatalog catalog(){return catalog;}void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.value+" "+entry.platform).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" hosts");count.setText(selected.size()+" hosts selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one host.":hostConfiguration(catalog,selected));preview.setCaretPosition(0);}void loadCatalog(){var chooser=chooser("Load Hosts / IPs JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readHostCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}void save(){var chooser=chooser("Save host configuration");chooser.setSelectedFile(new File("generator-hosts.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,hostConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" hosts to "+path);}catch(Exception exception){error(exception);}}
            class HostTable extends AbstractTableModel {final String[] names={"Include","Hostname","IP address","Platform","Source records"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:column==4?Integer.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.hostName;case 2->entry.ipAddress;case 3->entry.platform;default->entry.sourceRecords;};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        class OsVersionPanel extends JPanel {
            OsVersionCatalog catalog;final Set<String> selected=new HashSet<>();List<OsVersionEntry> visible=new ArrayList<>();final JTextField search=new JTextField();final JLabel info=new JLabel(),count=new JLabel(),status=new JLabel("Choose OS versions, then save the JSON configuration.");final JTextArea preview=new JTextArea();final OsVersionTable model=new OsVersionTable();final JTable table=new JTable(model);final JButton save=new JButton("Save JSON configuration...");
            OsVersionPanel(OsVersionCatalog catalog){super(new BorderLayout(12,12));this.catalog=catalog;catalog.entries.forEach(entry->selected.add(entry.id));setBorder(BorderFactory.createEmptyBorder(18,18,18,18));var top=new JPanel(new BorderLayout(8,8));var title=new JLabel("Java Usage Generator - select OS versions");title.setFont(title.getFont().deriveFont(Font.BOLD,22));top.add(title,BorderLayout.NORTH);top.add(info,BorderLayout.CENTER);var controls=new JPanel(new BorderLayout(8,8));search.setToolTipText("Search versions, OS names, architectures, or platforms");controls.add(search,BorderLayout.CENTER);var buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));buttons.add(button("Select all OS versions",()->{catalog().entries.forEach(entry->selected.add(entry.id));refresh();}));buttons.add(button("Clear selection",()->{selected.clear();refresh();}));buttons.add(button("Load catalog...",this::loadCatalog));controls.add(buttons,BorderLayout.SOUTH);top.add(controls,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);table.setRowHeight(28);table.setFillsViewportHeight(true);table.getColumnModel().getColumn(0).setMaxWidth(65);var right=new JPanel(new BorderLayout(10,10));right.setBorder(BorderFactory.createEmptyBorder(0,10,0,0));right.add(count,BorderLayout.NORTH);preview.setEditable(false);preview.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));right.add(new JScrollPane(preview),BorderLayout.CENTER);right.add(save,BorderLayout.SOUTH);var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(table),right);split.setResizeWeight(.58);split.setDividerLocation(610);add(split,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});save.addActionListener(event->save());filter();}
            OsVersionCatalog catalog(){return catalog;}void filter(){String query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.stream().filter(entry->(entry.version+" "+entry.platform+" "+String.join(" ",entry.osNames)+" "+String.join(" ",entry.architectures)).toLowerCase(Locale.ROOT).contains(query)).toList();model.fireTableDataChanged();refresh();}void refresh(){model.fireTableDataChanged();info.setText("Catalog "+catalog.date+" - "+catalog.entries.size()+" OS versions");count.setText(selected.size()+" OS versions selected - "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one OS version.":osVersionConfiguration(catalog,selected));preview.setCaretPosition(0);}void loadCatalog(){var chooser=chooser("Load OS Versions JSON catalog");if(chooser.showOpenDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;try{var next=readOsVersionCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(entry->known.add(entry.id));int previous=selected.size();selected.retainAll(known);catalog=next;filter();status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception exception){error(exception);}}void save(){var chooser=chooser("Save OS version configuration");chooser.setSelectedFile(new File("generator-os-versions.json"));if(chooser.showSaveDialog(Window.this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(Window.this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,osVersionConfiguration(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" OS versions to "+path);}catch(Exception exception){error(exception);}}
            class OsVersionTable extends AbstractTableModel {final String[] names={"Include","OS version","Platform","OS name(s)","Architecture(s)","Source records"};public int getRowCount(){return visible.size();}public int getColumnCount(){return names.length;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:column==5?Integer.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}public Object getValueAt(int row,int column){var entry=visible.get(row);return switch(column){case 0->selected.contains(entry.id);case 1->entry.version;case 2->entry.platform;case 3->String.join(", ",entry.osNames);case 4->String.join(", ",entry.architectures);default->entry.sourceRecords;};}public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).id);else selected.remove(visible.get(row).id);refresh();}}
        }
        Catalog catalog(){return catalog;}
        JButton button(String text,Runnable action){var button=new JButton(text);button.addActionListener(e->action.run());return button;}
        void setCatalog(Catalog next){catalog=next;changing=true;family.removeAllItems();family.addItem("All families");for(String id:next.families.reversed())family.addItem("JDK "+id);changing=false;info.setText("Catalog "+next.date+" · "+next.families.size()+" families · "+next.entries.size()+" versions");filter();}
        String chosenFamily(){String text=(String)family.getSelectedItem();return text==null||text.equals("All families")?null:text.substring(4);}
        void chooseFamily(boolean value){String id=chosenFamily();if(id!=null){selectFamily(catalog,selected,id,value);refresh();}}
        void filter(){String id=chosenFamily(),query=search.getText().trim().toLowerCase(Locale.ROOT);visible=catalog.entries.reversed().stream().filter(e->(id==null||e.family.equals(id)) && (e.version+" "+e.javaVersion+" "+e.label+" JDK "+e.family).toLowerCase(Locale.ROOT).contains(query)).toList();include.setEnabled(id!=null);exclude.setEnabled(id!=null);model.fireTableDataChanged();refresh();}
        void refresh(){model.fireTableDataChanged();count.setText(selected.size()+" versions selected · "+visible.size()+" displayed");save.setEnabled(!selected.isEmpty());preview.setText(selected.isEmpty()?"Select at least one version.":properties(catalog,selected));preview.setCaretPosition(0);}
        JFileChooser chooser(String title){var chooser=new JFileChooser();chooser.setDialogTitle(title);return chooser;}
        void error(Exception error){JOptionPane.showMessageDialog(this,error.getMessage(),"Could not complete action",JOptionPane.ERROR_MESSAGE);}
        void loadSelection(){var chooser=chooser("Load generator selection");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Set<String> next=readSelection(chooser.getSelectedFile().toPath(),catalog);selected.clear();selected.addAll(next);refresh();status.setText("Loaded "+next.size()+" choices. Customize and save a revised file.");}catch(Exception error){error(error);}}
        void loadCatalog(){var chooser=chooser("Load Oracle JDK JSON catalog");if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;try{Catalog next=readCatalog(Files.readString(chooser.getSelectedFile().toPath()));var known=new HashSet<String>();next.entries.forEach(e->known.add(e.version));int previous=selected.size();selected.retainAll(known);setCatalog(next);status.setText("Catalog loaded; "+(previous-selected.size())+" unavailable choices removed.");}catch(Exception error){error(error);}}
        void saveSelection(){var chooser=chooser("Save generator version properties");chooser.setSelectedFile(new File("generator-versions.properties"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path path=chooser.getSelectedFile().toPath();try{if(Files.exists(path)&&JOptionPane.showConfirmDialog(this,"Replace "+path.getFileName()+"?","Existing file",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;Files.writeString(path,properties(catalog,selected),StandardCharsets.UTF_8);status.setText("Saved "+selected.size()+" choices to "+path);}catch(Exception error){error(error);}}
        class VersionTable extends AbstractTableModel {
            final String[] names={"Include","Family","Version / update","Expected java.version"};
            public int getRowCount(){return visible.size();}public int getColumnCount(){return 4;}public String getColumnName(int column){return names[column];}public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}public boolean isCellEditable(int row,int column){return column==0;}
            public Object getValueAt(int row,int column){Entry e=visible.get(row);return switch(column){case 0->selected.contains(e.version);case 1->"JDK "+e.family;case 2->e.label;default->e.javaVersion;};}
            public void setValueAt(Object value,int row,int column){if(Boolean.TRUE.equals(value))selected.add(visible.get(row).version);else selected.remove(visible.get(row).version);refresh();}
        }
    }
}
