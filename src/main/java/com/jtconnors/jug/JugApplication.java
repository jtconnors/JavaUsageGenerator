package com.jtconnors.jug;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;

import static com.jtconnors.jug.JugSelector.*;

public final class JugApplication extends Application {
    private record LaunchData(Catalog versions, Set<String> selectedVersions,
                              ApplicationCatalog applications, HostCatalog hosts,
                              OsVersionCatalog osVersions) {}

    private static LaunchData launchData;
    private Stage stage;
    private VersionPane versions;
    private ApplicationPane applications;
    private HostPane hosts;
    private OsVersionPane osVersions;
    private TimestampPane timestamps;
    private GeneratePane generate;
    private ReceivePane receive;

    static void configure(Catalog versions, Set<String> selectedVersions,
                          ApplicationCatalog applications, HostCatalog hosts,
                          OsVersionCatalog osVersions) {
        launchData = new LaunchData(versions, new HashSet<>(selectedVersions), applications, hosts, osVersions);
    }

    @Override
    public void start(Stage stage) {
        if (launchData == null) throw new IllegalStateException("JUG was started without catalog data");
        this.stage = stage;
        versions = new VersionPane(launchData.versions, launchData.selectedVersions);
        applications = new ApplicationPane(launchData.applications);
        hosts = new HostPane(launchData.hosts);
        osVersions = new OsVersionPane(launchData.osVersions);
        timestamps = new TimestampPane();
        generate = new GeneratePane();
        receive = new ReceivePane();

        var tabs = new TabPane(
            tab("Generate", generate), tab("Listen", receive), tab("Timestamps", timestamps),
            tab("Java Versions", versions), tab("Java Applications", applications),
            tab("Hosts / IPs", hosts), tab("OS Versions", osVersions)
        );
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var scene = new Scene(tabs, 1280, 820);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/jug.css")).toExternalForm());
        stage.setTitle("JUG - Java Usage Generator");
        stage.setMinWidth(980);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            generate.stopSeries();
            receive.stopListening("Listener stopped.");
        });
        stage.show();
    }

    private Tab tab(String title, Node content) { return new Tab(title, content); }

    private Label title(String text) {
        var label = new Label(text);
        label.getStyleClass().add("page-title");
        return label;
    }

    private Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button primaryButton(String text, Runnable action) {
        var button = button(text, action);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private void setStatus(Label label, String tone, String message) {
        label.getStyleClass().removeAll("status-success", "status-warning", "status-error");
        if (tone != null) label.getStyleClass().add("status-" + tone);
        label.setText(message);
    }

    private Button addButton(String description, Runnable action) {
        var button = button("+", action);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(description));
        button.setAccessibleText(description);
        return button;
    }

    private VBox field(String label, Node control) {
        var box = new VBox(4, new Label(label), control);
        box.getStyleClass().add("field");
        return box;
    }

    private TextArea outputArea(int rows) {
        var area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(rows);
        area.getStyleClass().add("code-area");
        return area;
    }

    private void error(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        var alert = new Alert(Alert.AlertType.ERROR, message == null || message.isBlank() ? cause.getClass().getSimpleName() : message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText("Could not complete action");
        alert.showAndWait();
    }

    private boolean confirmReplace(Path path) {
        if (!Files.exists(path)) return true;
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "Replace " + path.getFileName() + "?", ButtonType.YES, ButtonType.NO);
        alert.initOwner(stage);
        alert.setHeaderText("Existing file");
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private Path chooseOpen(String title) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        var file = chooser.showOpenDialog(stage);
        return file == null ? null : file.toPath();
    }

    private Path chooseSave(String title, String name) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(name);
        var file = chooser.showSaveDialog(stage);
        return file == null ? null : file.toPath();
    }

    private <T> TableColumn<T, Boolean> includeColumn(Function<T, String> id, Set<String> selected, Runnable refresh) {
        var column = new TableColumn<T, Boolean>("Include");
        column.setPrefWidth(72);
        column.setMinWidth(72);
        column.setMaxWidth(72);
        column.setSortable(false);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final CheckBox check = new CheckBox();
            {
                setAlignment(Pos.CENTER);
                check.setOnAction(event -> {
                    T row = getTableRow().getItem();
                    if (row == null) return;
                    if (check.isSelected()) selected.add(id.apply(row)); else selected.remove(id.apply(row));
                    refresh.run();
                });
            }
            @Override protected void updateItem(Boolean value, boolean empty) {
                super.updateItem(value, empty);
                T row = empty ? null : getTableRow().getItem();
                if (row == null) setGraphic(null);
                else {
                    check.setSelected(selected.contains(id.apply(row)));
                    setGraphic(check);
                }
            }
        });
        return column;
    }

    private <T> TableColumn<T, String> textColumn(String name, Function<T, String> value, double width) {
        var column = new TableColumn<T, String>(name);
        column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(value.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private <T> void setColumns(TableView<T> table, List<TableColumn<T, ?>> columns) {
        table.getColumns().setAll(columns);
    }

    abstract class SelectionPane<T> extends BorderPane {
        final Set<String> selected = new HashSet<>();
        final ObservableList<T> visible = FXCollections.observableArrayList();
        final TableView<T> table = new TableView<>(visible);
        final TextField search = new TextField();
        final Label info = new Label();
        final Label count = new Label();
        final Label status = new Label();
        final TextArea preview = outputArea(20);
        final Button save = new Button("Save JSON configuration...");
        final FlowPane actions = new FlowPane(7, 7);
        List<T> entries;
        String catalogDate;
        final Function<T, String> id;
        final Function<T, String> searchable;
        final String noun;

        SelectionPane(String heading, String noun, String searchLabel, String initialStatus,
                      String date, List<T> entries, Function<T, String> id, Function<T, String> searchable) {
            this.noun = noun;
            this.catalogDate = date;
            this.entries = new ArrayList<>(entries);
            this.id = id;
            this.searchable = searchable;
            getStyleClass().add("workspace");
            entries.forEach(entry -> selected.add(id.apply(entry)));
            setPadding(new Insets(18));
            search.setPromptText(searchLabel);
            search.setPrefWidth(260);
            search.setMaxWidth(320);
            search.textProperty().addListener((ignored, oldValue, value) -> filter());
            status.setText(initialStatus);
            status.getStyleClass().add("status");
            save.getStyleClass().add("primary-button");
            actions.setAlignment(Pos.CENTER_LEFT);
            var controls = new VBox(8, new HBox(8, field(searchLabel, search)), actions);
            setTop(new VBox(7, title(heading), info, controls));

            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            table.setPlaceholder(new Label("No matching entries"));
            var right = new VBox(8, count, preview, save);
            VBox.setVgrow(preview, Priority.ALWAYS);
            right.setMinWidth(360);
            var split = new SplitPane(table, right);
            split.setDividerPositions(.62);
            setCenter(split);
            BorderPane.setMargin(split, new Insets(12, 0, 10, 0));
            setBottom(status);
        }

        void finish() {
            save.setOnAction(event -> saveConfiguration());
            filter();
        }

        abstract String configuration();
        abstract String summary();
        abstract String defaultFileName();

        void filter() {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            visible.setAll(entries.stream().filter(entry -> searchable.apply(entry).toLowerCase(Locale.ROOT).contains(query)).toList());
            refresh();
        }

        void refresh() {
            table.refresh();
            info.setText(summary());
            count.setText(selected.size() + " " + noun + " selected - " + visible.size() + " displayed");
            save.setDisable(selected.isEmpty());
            if (selected.isEmpty()) preview.setText("Select at least one " + noun.replaceAll("s$", "") + ".");
            else preview.setText(configuration());
            preview.positionCaret(0);
        }

        void selectAll() { entries.forEach(entry -> selected.add(id.apply(entry))); refresh(); }
        void clear() { selected.clear(); refresh(); }

        void replaceCatalog(String date, List<T> next) {
            var known = new HashSet<String>();
            next.forEach(entry -> known.add(id.apply(entry)));
            int previous = selected.size();
            selected.retainAll(known);
            catalogDate = date;
            entries = new ArrayList<>(next);
            filter();
            status.setText("Catalog loaded; " + (previous - selected.size()) + " unavailable choices removed.");
        }

        void saveConfiguration() {
            Path path = chooseSave("Save " + noun + " configuration", defaultFileName());
            if (path == null || !confirmReplace(path)) return;
            try {
                Files.writeString(path, configuration(), StandardCharsets.UTF_8);
                status.setText("Saved " + selected.size() + " " + noun + " to " + path + ".");
            } catch (Exception exception) { error(exception); }
        }
    }

    final class VersionPane extends SelectionPane<Entry> {
        Catalog catalog;
        final ComboBox<String> family = new ComboBox<>();
        final Button includeFamily = new Button("Select family");
        final Button excludeFamily = new Button("Clear family");
        final Button update = new Button("Update for latest versions");

        VersionPane(Catalog catalog, Set<String> initial) {
            super("Java Usage Generator - select Java versions", "versions", "Search versions",
                "Choose versions, then save the JSON configuration.", catalog.date(), catalog.entries(),
                Entry::version, entry -> entry.family() + " " + entry.version() + " " + entry.label() + " " + entry.javaVersion());
            this.catalog = catalog;
            selected.clear(); selected.addAll(initial);
            setColumns(table, List.of(includeColumn(Entry::version, selected, this::refresh),
                textColumn("Family", entry -> "JDK " + entry.family(), 105),
                textColumn("Version / update", Entry::label, 240),
                textColumn("Expected java.version", Entry::javaVersion, 220)));
            family.setPrefWidth(150);
            family.setOnAction(event -> filter());
            rebuildFamilies();
            includeFamily.setOnAction(event -> chooseFamily(true));
            excludeFamily.setOnAction(event -> chooseFamily(false));
            actions.getChildren().addAll(field("Release family", family), includeFamily, excludeFamily,
                button("Select all versions", this::selectAll), button("Clear selection", this::clear),
                button("Load selection...", this::loadSelection), button("Load catalog...", this::loadCatalog), update);
            update.setOnAction(event -> updateCatalog());
            finish();
        }

        @Override void filter() {
            if (family == null) return;
            String chosen = chosenFamily();
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            visible.setAll(entries.reversed().stream().filter(entry -> (chosen == null || entry.family().equals(chosen))
                && searchable.apply(entry).toLowerCase(Locale.ROOT).contains(query)).toList());
            includeFamily.setDisable(chosen == null);
            excludeFamily.setDisable(chosen == null);
            refresh();
        }

        String chosenFamily() {
            String value = family.getValue();
            return value == null || value.equals("All families") ? null : value.substring(4);
        }

        void rebuildFamilies() {
            family.getItems().setAll("All families");
            catalog.families().reversed().forEach(id -> family.getItems().add("JDK " + id));
            family.getSelectionModel().selectFirst();
        }

        void chooseFamily(boolean include) {
            String id = chosenFamily();
            if (id != null) { selectFamily(catalog, selected, id, include); refresh(); }
        }

        @Override String configuration() { return versionConfiguration(catalog, selected); }
        @Override String summary() { return "Catalog " + catalog.date() + " - " + catalog.families().size() + " families - " + catalog.entries().size() + " versions"; }
        @Override String defaultFileName() { return "generator-versions.json"; }

        void setCatalog(Catalog next) {
            catalog = next;
            catalogDate = next.date();
            entries = new ArrayList<>(next.entries());
            rebuildFamilies();
            filter();
        }

        void loadSelection() {
            Path path = chooseOpen("Load Java Versions JSON configuration");
            if (path == null) return;
            try {
                Set<String> next = readSelection(path, catalog);
                selected.clear(); selected.addAll(next); refresh();
                status.setText("Loaded " + next.size() + " choices. Customize and save a revised file.");
            } catch (Exception exception) { error(exception); }
        }

        void loadCatalog() {
            Path path = chooseOpen("Load Oracle JDK JSON catalog");
            if (path == null) return;
            try {
                Catalog next = readCatalog(Files.readString(path));
                var known = new HashSet<String>(); next.entries().forEach(entry -> known.add(entry.version()));
                int previous = selected.size(); selected.retainAll(known); setCatalog(next);
                status.setText("Catalog loaded; " + (previous - selected.size()) + " unavailable choices removed.");
            } catch (Exception exception) { error(exception); }
        }

        void updateCatalog() {
            update.setDisable(true);
            status.setText("Checking ops.java for released JDK 8 and later versions, including Security Alerts...");
            var task = new Task<CatalogUpdate>() { @Override protected CatalogUpdate call() throws Exception { return fetchLatestCatalog(catalog); } };
            task.setOnSucceeded(event -> {
                var result = task.getValue(); setCatalog(result.catalog());
                String change = result.additions() == 0 ? "No new versions were available." : "Added " + result.additions() + " versions.";
                status.setText(change + (result.fallback() ? " Retrieved through the read-only web fallback." : "") + " Catalog saved to " + result.path() + ".");
                update.setDisable(false);
            });
            task.setOnFailed(event -> { update.setDisable(false); status.setText("Update failed."); error(task.getException()); });
            Thread.ofVirtual().name("jug-catalog-update").start(task);
        }
    }

    final class ApplicationPane extends SelectionPane<ApplicationEntry> {
        ApplicationCatalog catalog;
        ApplicationPane(ApplicationCatalog catalog) {
            super("Java Usage Generator - select Java applications", "applications", "Search applications",
                "Choose applications, then save the JSON configuration.", catalog.date(), catalog.entries(), ApplicationEntry::id,
                entry -> entry.name() + " " + entry.type() + " " + entry.category() + " " + String.join(" ", entry.javaMajors()) + " " + String.join(" ", entry.classPaths()));
            this.catalog = catalog;
            setColumns(table, List.of(includeColumn(ApplicationEntry::id, selected, this::refresh),
                textColumn("Application", ApplicationEntry::name, 220), textColumn("Type", ApplicationEntry::type, 90),
                textColumn("Category", ApplicationEntry::category, 150), textColumn("Java major(s)", e -> String.join(", ", e.javaMajors()), 120),
                textColumn("Classpath", e -> String.join(" | ", e.classPaths()), 360)));
            actions.getChildren().addAll(button("Select all applications", this::selectAll),
                button("Select real", () -> { entries.stream().filter(e -> e.type().equals("Real")).forEach(e -> selected.add(e.id())); refresh(); }),
                button("Select fictional", () -> { entries.stream().filter(e -> e.type().equals("Fictional")).forEach(e -> selected.add(e.id())); refresh(); }),
                button("Clear selection", this::clear), button("Load catalog...", this::loadCatalog), addButton("Add Java application", this::addApplication));
            finish();
        }
        @Override String configuration() { return applicationConfiguration(catalog, selected); }
        @Override String summary() { return "Catalog " + catalog.date() + " - " + catalog.entries().size() + " applications"; }
        @Override String defaultFileName() { return "generator-applications.json"; }
        void loadCatalog() { Path path=chooseOpen("Load Java Applications JSON catalog"); if(path==null)return; try{var next=readApplicationCatalog(Files.readString(path));catalog=next;replaceCatalog(next.date(),next.entries());}catch(Exception e){error(e);} }
        void addApplication() {
            var name=new TextField();var type=new ComboBox<>(FXCollections.observableArrayList("Real","Fictional"));type.getSelectionModel().selectFirst();var category=new TextField();var majors=new TextField();var classPaths=new TextArea();var commands=new TextArea();var starts=new TextField("VM start");
            var grid=form();addRow(grid,0,"Application name",name);addRow(grid,1,"Type",type);addRow(grid,2,"Category",category);addRow(grid,3,"Java major(s)",majors);addRow(grid,4,"Classpaths (one per line)",classPaths);addRow(grid,5,"Java commands (one per line)",commands);addRow(grid,6,"Start type(s)",starts);
            if(!showAddDialog("Add Java application",grid,()->{String value=name.getText().trim();if(value.isEmpty()||category.getText().trim().isEmpty()||commaSeparated(majors.getText()).isEmpty()||lines(classPaths.getText()).isEmpty()||lines(commands.getText()).isEmpty()||commaSeparated(starts.getText()).isEmpty())return "Complete all required fields.";return entries.stream().anyMatch(e->e.name().equalsIgnoreCase(value))?"An application with this name already exists.":null;}))return;
            try{var entry=new ApplicationEntry(nextCustomId("APP-",entries.stream().map(ApplicationEntry::id).toList()),name.getText().trim(),type.getValue(),category.getText().trim(),commaSeparated(majors.getText()),lines(classPaths.getText()),lines(commands.getText()),commaSeparated(starts.getText()));appendCustomApplication(customApplicationsPath(),entry);entries.add(entry);catalog=new ApplicationCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id());search.clear();filter();status.setText("Added "+entry.name()+" and saved it to "+customApplicationsPath()+".");}catch(Exception e){error(e);}
        }
    }

    final class HostPane extends SelectionPane<HostEntry> {
        HostCatalog catalog;
        HostPane(HostCatalog catalog) {
            super("Java Usage Generator - select hosts and IP addresses", "hosts", "Search hosts/IPs",
                "Choose hosts, then save the JSON configuration.",catalog.date(),catalog.entries(),HostEntry::id,e->e.value()+" "+e.platform());this.catalog=catalog;
            setColumns(table,List.of(includeColumn(HostEntry::id,selected,this::refresh),textColumn("Hostname",HostEntry::hostName,220),textColumn("IP address",HostEntry::ipAddress,180),textColumn("Platform",HostEntry::platform,140)));
            actions.getChildren().addAll(button("Select all hosts",this::selectAll),button("Clear selection",this::clear),button("Load catalog...",this::loadCatalog),addButton("Add host or IP address",this::addHost));finish();
        }
        @Override String configuration(){return hostConfiguration(catalog,selected);}@Override String summary(){return "Catalog "+catalog.date()+" - "+catalog.entries().size()+" hosts";}@Override String defaultFileName(){return "generator-hosts.json";}
        void loadCatalog(){Path path=chooseOpen("Load Hosts / IPs JSON catalog");if(path==null)return;try{var next=readHostCatalog(Files.readString(path));catalog=next;replaceCatalog(next.date(),next.entries());}catch(Exception e){error(e);}}
        void addHost(){var name=new TextField();var ip=new TextField();var platform=new ComboBox<>(FXCollections.observableArrayList("Linux","Windows","macOS","Solaris"));platform.setEditable(true);platform.getSelectionModel().selectFirst();var grid=form();addRow(grid,0,"Hostname",name);addRow(grid,1,"IP address",ip);addRow(grid,2,"Platform",platform);if(!showAddDialog("Add host or IP address",grid,()->{String host=name.getText().trim(),address=ip.getText().trim(),os=platform.getEditor().getText().trim();if(host.isEmpty()||address.isEmpty()||os.isEmpty())return "Complete all required fields.";if(!validIpAddress(address))return "Enter a valid IPv4 address.";if(entries.stream().anyMatch(e->e.hostName().equalsIgnoreCase(host)))return "A host with this name already exists.";return entries.stream().anyMatch(e->e.ipAddress().equals(address))?"A host with this IP address already exists.":null;}))return;try{String host=name.getText().trim(),address=ip.getText().trim();var entry=new HostEntry(nextCustomId("HOST-",entries.stream().map(HostEntry::id).toList()),host+"/"+address,host,address,platform.getEditor().getText().trim());appendCustomHost(customHostsPath(),entry);entries.add(entry);catalog=new HostCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id());search.clear();filter();status.setText("Added "+entry.value()+" and saved it to "+customHostsPath()+".");}catch(Exception e){error(e);}}
    }

    final class OsVersionPane extends SelectionPane<OsVersionEntry> {
        OsVersionCatalog catalog;
        OsVersionPane(OsVersionCatalog catalog){super("Java Usage Generator - select OS versions","OS versions","Search OS versions","Choose OS versions, then save the JSON configuration.",catalog.date(),catalog.entries(),OsVersionEntry::id,e->e.version()+" "+e.platform()+" "+String.join(" ",e.osNames())+" "+String.join(" ",e.architectures()));this.catalog=catalog;setColumns(table,List.of(includeColumn(OsVersionEntry::id,selected,this::refresh),textColumn("OS version",OsVersionEntry::version,200),textColumn("Platform",OsVersionEntry::platform,130),textColumn("OS name(s)",e->String.join(", ",e.osNames()),240),textColumn("Architecture(s)",e->String.join(", ",e.architectures()),180)));actions.getChildren().addAll(button("Select all OS versions",this::selectAll),button("Clear selection",this::clear),button("Load catalog...",this::loadCatalog),addButton("Add OS version",this::addOsVersion));finish();}
        @Override String configuration(){return osVersionConfiguration(catalog,selected);}@Override String summary(){return "Catalog "+catalog.date()+" - "+catalog.entries().size()+" OS versions";}@Override String defaultFileName(){return "generator-os-versions.json";}
        void loadCatalog(){Path path=chooseOpen("Load OS Versions JSON catalog");if(path==null)return;try{var next=readOsVersionCatalog(Files.readString(path));catalog=next;replaceCatalog(next.date(),next.entries());}catch(Exception e){error(e);}}
        void addOsVersion(){var version=new TextField();var platform=new ComboBox<>(FXCollections.observableArrayList("Linux","Windows","macOS","Solaris"));platform.setEditable(true);platform.getSelectionModel().selectFirst();var names=new TextArea();var architectures=new TextField();var grid=form();addRow(grid,0,"OS version",version);addRow(grid,1,"Platform",platform);addRow(grid,2,"OS names (one per line)",names);addRow(grid,3,"Architectures",architectures);if(!showAddDialog("Add OS version",grid,()->{String value=version.getText().trim();if(value.isEmpty()||platform.getEditor().getText().isBlank()||lines(names.getText()).isEmpty()||commaSeparated(architectures.getText()).isEmpty())return "Complete all required fields.";return entries.stream().anyMatch(e->e.version().equalsIgnoreCase(value))?"This OS version already exists.":null;}))return;try{var entry=new OsVersionEntry(nextCustomId("OSV-",entries.stream().map(OsVersionEntry::id).toList()),version.getText().trim(),platform.getEditor().getText().trim(),lines(names.getText()),commaSeparated(architectures.getText()));appendCustomOsVersion(customOsVersionsPath(),entry);entries.add(entry);catalog=new OsVersionCatalog(LocalDate.now().toString(),List.copyOf(entries));selected.add(entry.id());search.clear();filter();status.setText("Added "+entry.version()+" and saved it to "+customOsVersionsPath()+".");}catch(Exception e){error(e);}}
    }

    private GridPane form(){var grid=new GridPane();grid.setHgap(12);grid.setVgap(9);grid.getColumnConstraints().addAll(new ColumnConstraints(155),new ColumnConstraints(360));return grid;}
    private void addRow(GridPane grid,int row,String label,Control control){if(control instanceof TextArea area){area.setPrefRowCount(3);area.setWrapText(false);}control.setMaxWidth(Double.MAX_VALUE);grid.add(new Label(label),0,row);grid.add(control,1,row);GridPane.setHgrow(control,Priority.ALWAYS);GridPane.setValignment(grid.getChildren().get(grid.getChildren().size()-2),javafx.geometry.VPos.TOP);}
    private boolean showAddDialog(String title,Node form,java.util.function.Supplier<String> validator){var dialog=new Dialog<ButtonType>();dialog.initOwner(stage);dialog.setTitle(title);dialog.getDialogPane().setContent(form);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL,new ButtonType("Add",ButtonBar.ButtonData.OK_DONE));while(true){var result=dialog.showAndWait();if(result.isEmpty()||result.get().getButtonData()!=ButtonBar.ButtonData.OK_DONE)return false;String message=validator.get();if(message==null)return true;var alert=new Alert(Alert.AlertType.WARNING,message,ButtonType.OK);alert.initOwner(stage);alert.setHeaderText("Check this entry");alert.showAndWait();}}

    final class TimestampPane extends BorderPane {
        final ComboBox<String> mode=new ComboBox<>(FXCollections.observableArrayList("Use current time","Random time in the last 30 days","Random time in the last 90 days","Random time in the last 180 days","Custom date/time range"));
        final DatePicker startDate=new DatePicker(LocalDate.now().minusDays(90)),endDate=new DatePicker(LocalDate.now());
        final Spinner<Integer> startHour=spinner(0,23,0),startMinute=spinner(0,59,0),startSecond=spinner(0,59,0),endHour=spinner(0,23,23),endMinute=spinner(0,59,59),endSecond=spinner(0,59,59);
        final GridPane custom=form();final Label message=new Label();
        TimestampPane(){getStyleClass().add("workspace");setPadding(new Insets(18));mode.getSelectionModel().select(2);mode.setPrefWidth(280);addRow(custom,0,"Start date",startDate);custom.add(timeBox(startHour,startMinute,startSecond),1,1);custom.add(new Label("Start time"),0,1);addRow(custom,2,"End date",endDate);custom.add(timeBox(endHour,endMinute,endSecond),1,3);custom.add(new Label("End time"),0,3);message.getStyleClass().add("supporting-text");var controls=new VBox(12,field("Timestamp rule",mode),custom,message);setTop(new VBox(12,title("Java Usage Generator - configure JUT timestamps"),controls));mode.setOnAction(e->refresh());refresh();}
        HBox timeBox(Spinner<Integer> h,Spinner<Integer> m,Spinner<Integer> s){return new HBox(5,h,new Label(":"),m,new Label(":"),s);}
        long timestamp(Random random,long now){return switch(mode.getSelectionModel().getSelectedIndex()){case 0->now;case 1->randomTimestamp(now-30*DAY_MILLIS,now,random);case 3->randomTimestamp(now-180*DAY_MILLIS,now,random);case 4->randomTimestamp(customMillis(startDate,startHour,startMinute,startSecond),customMillis(endDate,endHour,endMinute,endSecond),random);default->randomTimestamp(now-90*DAY_MILLIS,now,random);};}
        void validateRange(){if(mode.getSelectionModel().getSelectedIndex()==4)randomTimestamp(customMillis(startDate,startHour,startMinute,startSecond),customMillis(endDate,endHour,endMinute,endSecond),new Random());}
        long customMillis(DatePicker date,Spinner<Integer> h,Spinner<Integer> m,Spinner<Integer> s){if(date.getValue()==null)throw new IllegalArgumentException("Choose both custom dates.");return ZonedDateTime.of(date.getValue(),LocalTime.of(h.getValue(),m.getValue(),s.getValue()),ZoneId.systemDefault()).toInstant().toEpochMilli();}
        void refresh(){boolean customMode=mode.getSelectionModel().getSelectedIndex()==4;custom.setDisable(!customMode);custom.setVisible(customMode);custom.setManaged(customMode);message.setText(switch(mode.getSelectionModel().getSelectedIndex()){case 0->"Generated records use the time at which each packet is created.";case 1->"Generated records use a random time within the previous 30 days.";case 3->"Generated records use a random time within the previous 180 days.";case 4->"Generated records use a random time within the custom range.";default->"Generated records use a random time within the previous 90 days.";});}
        void setLocked(boolean locked){mode.setDisable(locked);custom.setDisable(locked||mode.getSelectionModel().getSelectedIndex()!=4);}
    }

    final class GeneratePane extends BorderPane {
        final TextArea sample=outputArea(4),series=outputArea(15);final Spinner<Integer> packetCount=spinner(1,1_000_000,10),interval=spinner(0,1_000_000,1),udpPort=spinner(1,65535,DEFAULT_UDP_PORT);final ComboBox<String> unit=new ComboBox<>(FXCollections.observableArrayList("ms","seconds","minutes"));final CheckBox udp=new CheckBox("Send packets over UDP");final TextField udpHost=new TextField("localhost");final Button start=new Button("Start generation"),stop=new Button("Stop"),save=new Button("Save packets...");final Label seriesStatus=new Label("Ready to generate packets.");final List<String> packets=new ArrayList<>();Timeline timeline;DatagramSocket socket;InetAddress address;int destinationPort,target;boolean running;
        GeneratePane(){getStyleClass().add("workspace");setPadding(new Insets(18));unit.getSelectionModel().selectFirst();sample.setPromptText("Generate a sample to see a JUT record.");var sampleButtons=new HBox(8,primaryButton("Generate a sample JUT packet",this::sample),button("Clear",()->sample.clear()));sampleButtons.setAlignment(Pos.CENTER_LEFT);var top=new VBox(8,title("Generate a sample JUT packet"),sampleButtons,sample);var settings=new FlowPane(10,8,field("Packets",packetCount),field("Interval",interval),field("Unit",unit),udp,field("Destination host",udpHost),field("UDP port",udpPort));settings.setAlignment(Pos.CENTER_LEFT);udp.selectedProperty().addListener((o,a,b)->udpControls());start.getStyleClass().add("primary-button");stop.getStyleClass().add("danger-button");save.getStyleClass().add("quiet-button");start.setOnAction(e->startSeries());stop.setOnAction(e->stopSeries());save.setOnAction(e->saveSeries());stop.setDisable(true);save.setDisable(true);seriesStatus.getStyleClass().add("status");var commands=new HBox(8,start,button("Clear",this::clearSeries),stop,save);var bottom=new VBox(8,title("Generate a JUT packet series"),settings,commands,seriesStatus,series);VBox.setVgrow(series,Priority.ALWAYS);var split=new SplitPane(top,bottom);split.setOrientation(Orientation.VERTICAL);split.setDividerPositions(.31);setCenter(split);udpControls();}
        void udpControls(){udpHost.setDisable(!udp.isSelected()||running);udpPort.setDisable(!udp.isSelected()||running);}
        String nextPacket(Random random){return sampleJutPacket(versions.catalog,versions.selected,applications.catalog,applications.selected,hosts.catalog,hosts.selected,osVersions.catalog,osVersions.selected,random,timestamps.timestamp(random,System.currentTimeMillis()));}
        void sample(){try{sample.setText(nextPacket(new Random()));sample.positionCaret(0);}catch(Exception e){error(e);}}
        void startSeries(){try{timestamps.validateRange();target=packetCount.getValue();packets.clear();series.clear();if(udp.isSelected()){if(udpHost.getText().isBlank())throw new IllegalArgumentException("Enter a UDP hostname.");address=InetAddress.getByName(udpHost.getText().trim());destinationPort=udpPort.getValue();socket=new DatagramSocket();}setRunning(true);appendPacket();if(running&&packets.size()<target){long factor=switch(unit.getValue()){case "seconds"->1000L;case "minutes"->60000L;default->1L;};timeline=new Timeline(new KeyFrame(Duration.millis(Math.max(1,interval.getValue()*factor)),e->appendPacket()));timeline.setCycleCount(Timeline.INDEFINITE);timeline.play();}}catch(Exception e){closeUdp();setRunning(false);setStatus(seriesStatus,"error","Unable to start: "+failure(e));}}
        void appendPacket(){try{String next=nextPacket(new Random());if(socket!=null){byte[] data=next.getBytes(StandardCharsets.UTF_8);if(data.length>65507)throw new IOException("Packet exceeds the UDP payload limit.");socket.send(new DatagramPacket(data,data.length,address,destinationPort));}packets.add(next);series.appendText(next);series.positionCaret(series.getLength());setStatus(seriesStatus,null,(socket==null?"Generated ":"Generated and sent ")+packets.size()+" of "+target+" packets.");if(packets.size()>=target)finishSeries("Completed "+target+" packets"+(socket==null?"":" via UDP to "+udpHost.getText().trim()+":"+destinationPort)+".");}catch(Exception e){finishSeries("Stopped after "+packets.size()+" packets: "+failure(e));}}
        void setRunning(boolean running){this.running=running;start.setDisable(running);stop.setDisable(!running);packetCount.setDisable(running);interval.setDisable(running);unit.setDisable(running);udp.setDisable(running);save.setDisable(running||packets.isEmpty());timestamps.setLocked(running);udpControls();}
        void finishSeries(String message){if(timeline!=null){timeline.stop();timeline=null;}closeUdp();setRunning(false);setStatus(seriesStatus,message.startsWith("Completed")?"success":"warning",message);}
        void stopSeries(){if(running)finishSeries("Stopped after "+packets.size()+" of "+target+" packets.");}
        void closeUdp(){if(socket!=null){socket.close();socket=null;}address=null;}
        void clearSeries(){packets.clear();series.clear();save.setDisable(true);setStatus(seriesStatus,null,"Generated packet series cleared.");}
        void saveSeries(){Path path=chooseSave("Save JUT packet series","packet-series.jut");if(path==null||!confirmReplace(path))return;try{Files.writeString(path,String.join("",packets),StandardCharsets.UTF_8);seriesStatus.setText("Saved "+packets.size()+" packets to "+path+".");}catch(Exception e){error(e);}}
    }

    final class ReceivePane extends BorderPane {
        final TextField host=new TextField("0.0.0.0");final Spinner<Integer> port=spinner(1,65535,DEFAULT_UDP_PORT);final Button listen=new Button("Start listening"),stop=new Button("Stop");final Label status=new Label("Not listening.");final TextArea packets=outputArea(28);volatile DatagramSocket receiveSocket;int count;
        ReceivePane(){getStyleClass().add("workspace");setPadding(new Insets(18));host.setPrefWidth(200);listen.getStyleClass().add("primary-button");stop.getStyleClass().add("danger-button");status.getStyleClass().add("status");listen.setOnAction(e->startListening());stop.setOnAction(e->stopListening("Listener stopped."));stop.setDisable(true);var controls=new HBox(10,field("Local bind address",host),field("UDP port",port),listen,stop,button("Clear",()->{packets.clear();count=0;setStatus(status,receiveSocket==null?null:"success",receiveSocket==null?"Not listening.":"Listening on "+endpoint()+". No packets received yet.");}));controls.setAlignment(Pos.BOTTOM_LEFT);setTop(new VBox(8,title("Listen for UDP JUT packets"),status,controls));setCenter(packets);BorderPane.setMargin(packets,new Insets(12,0,0,0));}
        String endpoint(){return host.getText().trim()+":"+port.getValue();}
        void startListening(){try{if(host.getText().isBlank())throw new IllegalArgumentException("Enter a local bind address.");var socket=new DatagramSocket(null);try{socket.bind(new InetSocketAddress(InetAddress.getByName(host.getText().trim()),port.getValue()));}catch(Exception e){socket.close();throw e;}receiveSocket=socket;count=0;setListening(true);setStatus(status,"success","Listening on "+endpoint()+". No packets received yet.");Thread.ofVirtual().name("jug-udp-receiver").start(()->receive(socket));}catch(Exception e){stopListening("Unable to listen: "+failure(e));}}
        void receive(DatagramSocket socket){try{while(!socket.isClosed()){var buffer=new byte[65507];var packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);String text=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8),source=packet.getAddress().getHostAddress()+":"+packet.getPort();Platform.runLater(()->append(socket,text,source));}}catch(SocketException e){if(!socket.isClosed())failed(socket,e);}catch(IOException e){failed(socket,e);}}
        void append(DatagramSocket socket,String text,String source){if(receiveSocket!=socket)return;if(packets.getLength()>0&&!packets.getText().endsWith("\n")&&!text.startsWith("\n"))packets.appendText("\n");packets.appendText(text);packets.positionCaret(packets.getLength());count++;setStatus(status,"success","Listening on "+endpoint()+". Received "+count+" packet"+(count==1?"":"s")+"; last sender "+source+".");}
        void failed(DatagramSocket socket,Exception e){Platform.runLater(()->{if(receiveSocket==socket)stopListening("Listener stopped: "+failure(e));});}
        void setListening(boolean active){host.setDisable(active);port.setDisable(active);listen.setDisable(active);stop.setDisable(!active);}
        void stopListening(String message){var socket=receiveSocket;receiveSocket=null;if(socket!=null)socket.close();setListening(false);setStatus(status,message.startsWith("Unable")||message.startsWith("Listener stopped:")?"error":message.startsWith("Listener stopped")?"warning":null,message);}
    }

    private static Spinner<Integer> spinner(int min,int max,int value){var spinner=new Spinner<Integer>(min,max,value);spinner.setEditable(true);spinner.setPrefWidth(105);return spinner;}
    private static String failure(Throwable error){String message=error.getMessage();return message==null||message.isBlank()?error.getClass().getSimpleName():message;}
}
