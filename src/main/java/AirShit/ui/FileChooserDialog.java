package AirShit.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.assets.JFoenixResources; // Added for JFoenix CSS
import javafx.application.Platform;
import javafx.beans.binding.Bindings; // Added for placeholder visibility
import javafx.beans.property.ObjectProperty; // Added for TilePane selection
import javafx.beans.property.SimpleObjectProperty; // Added for TilePane selection
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node; // Added for TilePane children
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane; // Added for TilePane placeholder
import javafx.scene.layout.TilePane; // Added
import javafx.scene.layout.VBox; // Added for tile structure
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.control.SplitPane; // Make sure this is imported

import javax.swing.filechooser.FileSystemView;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class FileChooserDialog {
    private static HBox breadcrumbBarContainer;

    // private static Image folderIcon; // Will use system icons
    // private static Image fileIcon; // Will use system icons
    private static Image upIcon;
    private static FileSystemView fileSystemView = FileSystemView.getFileSystemView();
    private static Map<String, Image> iconCache = new ConcurrentHashMap<>();

    // Properties for TilePane
    private static TilePane filePane;
    private static ScrollPane fileScrollPane;
    private static Label filePanePlaceholder;
    private static ObjectProperty<File> selectedFileInPane = new SimpleObjectProperty<>();

    static {
        try {
            java.net.URL upIconUrl = FileChooserDialog.class.getResource("/icons/up-arrow.png");
            if (upIconUrl != null) {
                upIcon = new Image(upIconUrl.toExternalForm());
            } else {
                System.err.println("Could not find up arrow icon: /icons/up-arrow.png");
            }
        } catch (Exception e) {
            System.err.println("Error loading up icon: " + e.getMessage());
        }
    }

    private static Image getFileIcon(File file) {
        if (file.isDirectory()) {
            // Use folder.png for directories
            String defaultIconPath = "/icons/folder.png";
            return iconCache.computeIfAbsent("_default_folder_icon_", k -> {
                try {
                    return new Image(FileChooserDialog.class.getResource(defaultIconPath).toExternalForm());
                } catch (Exception e) {
                    System.err.println("Error loading default folder icon: " + e.getMessage());
                    return null;
                }
            });
        } else {
            // Use system icon for files
            return iconCache.computeIfAbsent(file.getName(), k -> {
                javax.swing.Icon swingIcon = fileSystemView.getSystemIcon(file);
                if (swingIcon != null) {
                    try {
                        BufferedImage bImg = new BufferedImage(swingIcon.getIconWidth(), swingIcon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                        swingIcon.paintIcon(null, bImg.getGraphics(), 0, 0);
                        return SwingFXUtils.toFXImage(bImg, null);
                    } catch (Exception e) {
                        System.err.println("Error converting system icon for " + file.getName() + ": " + e.getMessage());
                    }
                }
                return null;
            });
        }
    }

    public static File showDialog(Stage owner, File initialStartDir) {
        final File[] result = new File[1];
        final Stage dialog = new Stage(StageStyle.DECORATED); // 每次建立新的 Stage
        dialog.setTitle("Select File or Directory");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);

        dialog.setWidth(800);
        dialog.setHeight(600);
        dialog.setMinWidth(600);
        dialog.setMinHeight(400);

        final File[] currentDirectoryWrapper = new File[1];
        selectedFileInPane.set(null); // 重置選擇的檔案

        JFXButton upButton = new JFXButton();
        if (upIcon != null) {
            ImageView upIconView = new ImageView(upIcon);
            upIconView.setFitHeight(16);
            upIconView.setFitWidth(16);
            upButton.setGraphic(upIconView);
        } else {
            upButton.setText("Up");
        }
        upButton.getStyleClass().add("up-button");
        upButton.setTooltip(new Tooltip("Go to parent directory"));
        breadcrumbBarContainer = new HBox();
        breadcrumbBarContainer.getStyleClass().add("breadcrumb-bar");
        HBox.setHgrow(breadcrumbBarContainer, Priority.ALWAYS);
        HBox.setHgrow(breadcrumbBarContainer, Priority.ALWAYS);

        HBox pathBar = new HBox(5, upButton, breadcrumbBarContainer);
        pathBar.setPadding(new Insets(5, 10, 5, 10));
        pathBar.setAlignment(Pos.CENTER_LEFT);

        // Conceptual root for TreeView, needed for selectPathInTree
        TreeItem<File> conceptualRootNode = new TreeItem<>();
        TreeView<File> treeView = new TreeView<>(conceptualRootNode);

        // TreeView setup
        File[] systemRoots = File.listRoots();
        if (systemRoots != null) {
            for (File rootDrive : systemRoots) {
                TreeItem<File> rootItem = createNode(rootDrive);
                conceptualRootNode.getChildren().add(rootItem);
                // Optionally expand the first root by default or the initial directory's root
            }
        }
        treeView.setShowRoot(false);
        treeView.getStyleClass().add("directory-tree-view");

        treeView.setCellFactory(tv -> {
            TreeCell<File> cell = new TreeCell<File>() { // Explicitly specify type argument
                @Override
                protected void updateItem(File item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item.getName().isEmpty() ? item.getAbsolutePath() : item.getName());
                        ImageView iconView = new ImageView(getFileIcon(item));
                        iconView.setFitHeight(16);
                        iconView.setFitWidth(16);
                        setGraphic(iconView);
                    }
                }
            };
            // Double click to expand/collapse
            cell.setOnMouseClicked(event -> {
                if (!cell.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    if (cell.getTreeItem() != null && cell.getTreeItem().isLeaf()
                            && cell.getTreeItem().getValue().isFile()) {
                        // If it's a file, select it and close dialog on double click
                        result[0] = cell.getTreeItem().getValue();
                        selectedFileInPane.set(result[0]); // Update selection in TilePane as well
                        dialog.close();
                        return;
                    }
                    // If it's a directory, toggle expansion
                    if (cell.getTreeItem() != null && !cell.getTreeItem().isLeaf()) {
                        cell.getTreeItem().setExpanded(!cell.getTreeItem().isExpanded());
                    }
                }
            });
            return cell;
        });

        // Wrap TreeView in a ScrollPane
        ScrollPane treeViewScrollPane = new ScrollPane(treeView);
        treeViewScrollPane.setFitToWidth(true);
        treeViewScrollPane.setFitToHeight(true);
        treeViewScrollPane.getStyleClass().add("tree-view-scroll-pane"); // Add style class if needed for specific
                                                                         // ScrollPane styling

        // --- Quick Access Pane ---
        VBox favoritesPane = new VBox(5);
        favoritesPane.setPadding(new Insets(10, 5, 10, 10)); // top, right, bottom, left
        favoritesPane.getStyleClass().add("favorites-pane");

        Label favoritesTitle = new Label("Quick Access");
        favoritesTitle.getStyleClass().add("favorites-title");
        favoritesPane.getChildren().add(favoritesTitle);

        String userHome = System.getProperty("user.home");
        List<File> favoriteDirs = new ArrayList<>();
        File desktopDir = new File(userHome, "Desktop");
        File downloadsDir = new File(userHome, "Downloads");
        File documentsDir = new File(userHome, "Documents");
        File picturesDir = new File(userHome, "Pictures");
        File musicDir = new File(userHome, "Music");
        File videosDir = new File(userHome, "Videos");

        if (desktopDir.exists() && desktopDir.isDirectory())
            favoriteDirs.add(desktopDir);
        if (downloadsDir.exists() && downloadsDir.isDirectory())
            favoriteDirs.add(downloadsDir);
        if (documentsDir.exists() && documentsDir.isDirectory())
            favoriteDirs.add(documentsDir);
        if (picturesDir.exists() && picturesDir.isDirectory())
            favoriteDirs.add(picturesDir);
        if (musicDir.exists() && musicDir.isDirectory())
            favoriteDirs.add(musicDir);
        if (videosDir.exists() && videosDir.isDirectory())
            favoriteDirs.add(videosDir);
        // Add more common directories if needed

        // Add system roots (Drives) to Quick Access as well, if desired
        // File[] roots = File.listRoots();
        // if (roots != null) {
        // for (File root : roots) {
        // if (root.exists() && root.isDirectory()) {
        // favoriteDirs.add(root);
        // }
        // }
        // }

        for (File dir : favoriteDirs) {
            Label favLabel = new Label(dir.getName().isEmpty() ? dir.getAbsolutePath() : dir.getName());
            ImageView iconView = new ImageView(getFileIcon(dir)); // Use getFileIcon for consistency
            iconView.setFitHeight(16);
            iconView.setFitWidth(16);
            favLabel.setGraphic(iconView);
            favLabel.setGraphicTextGap(8);
            favLabel.getStyleClass().add("favorite-link");
            favLabel.setOnMouseClicked(event -> {
                currentDirectoryWrapper[0] = dir;
                updateFilePane(currentDirectoryWrapper[0], filePane, currentDirectoryWrapper, selectedFileInPane,
                        treeView);
                updateBreadcrumbBar(breadcrumbBarContainer, currentDirectoryWrapper[0], treeView, conceptualRootNode,
                        currentDirectoryWrapper);
                selectPathInTree(conceptualRootNode, dir, treeView, true);
            });
            favoritesPane.getChildren().add(favLabel);
        }
        // END OF Quick Access Pane

        // File/Folder display (TilePane)
        filePane = new TilePane();
        filePane.setPadding(new Insets(10));
        filePane.setHgap(10);
        filePane.setVgap(10);
        filePane.getStyleClass().add("file-tile-pane");
        filePane.setAlignment(Pos.TOP_LEFT); // Ensure tiles start from top-left

        fileScrollPane = new ScrollPane(filePane);
        fileScrollPane.setFitToWidth(true);
        fileScrollPane.setFitToHeight(true);
        fileScrollPane.getStyleClass().add("file-scroll-pane");

        filePanePlaceholder = new Label("This folder is empty.");
        filePanePlaceholder.getStyleClass().add("placeholder-label");
        // StackPane to hold TilePane and placeholder
        StackPane filePaneContainer = new StackPane(fileScrollPane, filePanePlaceholder);
        filePanePlaceholder.visibleProperty().bind(Bindings.isEmpty(filePane.getChildren()));

        // Set initial directory
        currentDirectoryWrapper[0] = initialStartDir != null && initialStartDir.exists() ? initialStartDir
                : new File(System.getProperty("user.home"));
        updateFilePane(currentDirectoryWrapper[0], filePane, currentDirectoryWrapper, selectedFileInPane, treeView);
        updateBreadcrumbBar(breadcrumbBarContainer, currentDirectoryWrapper[0], treeView, conceptualRootNode,
                currentDirectoryWrapper);
        // Ensure the initial path is selected and expanded in the TreeView
        Platform.runLater(() -> selectPathInTree(conceptualRootNode, currentDirectoryWrapper[0], treeView, true));

        // Listeners and event handlers
        upButton.setOnAction(e -> {
            File parent = currentDirectoryWrapper[0].getParentFile();
            if (parent != null) {
                currentDirectoryWrapper[0] = parent;
                updateFilePane(currentDirectoryWrapper[0], filePane, currentDirectoryWrapper, selectedFileInPane,
                        treeView);
                updateBreadcrumbBar(breadcrumbBarContainer, currentDirectoryWrapper[0], treeView, conceptualRootNode,
                        currentDirectoryWrapper);
                selectPathInTree(conceptualRootNode, parent, treeView, true);
            }
        });

        treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue.getValue() != null) {
                File selectedDirInTree = newValue.getValue();
                if (selectedDirInTree.isDirectory()) {
                    currentDirectoryWrapper[0] = selectedDirInTree;
                    updateFilePane(currentDirectoryWrapper[0], filePane, currentDirectoryWrapper, selectedFileInPane,
                            treeView);
                    updateBreadcrumbBar(breadcrumbBarContainer, currentDirectoryWrapper[0], treeView,
                            treeView.getRoot(), currentDirectoryWrapper);
                    // No need to call selectPathInTree here, as this event comes from tree
                    // selection
                } else if (selectedDirInTree.isFile()) {
                    // If a file is selected in the tree, update the selectedFileInPane
                    // This might be less common UX for a tree, usually files are shown in the
                    // list/tile view
                    selectedFileInPane.set(selectedDirInTree);
                }
            }
        });

        // Main layout
        BorderPane rootLayout = new BorderPane();
        // rootLayout.setTop(headerPane); // Header at the top
        // Path bar below header
        VBox topSection = new VBox(pathBar);
        rootLayout.setCenter(topSection); // Temporarily put pathBar here, will be part of main content area

        // Create SplitPane for the main content area (Favorites | Tree | Files)
        SplitPane mainSplitPane = new SplitPane();
        mainSplitPane.getItems().addAll(favoritesPane, treeViewScrollPane, filePaneContainer); // Use filePaneContainer
        mainSplitPane.setDividerPositions(0.20, 0.45); // Adjust as needed (20% QA, 25% Tree, 55% Files)
        mainSplitPane.getStyleClass().add("main-split-pane");

        // The main content area will be the SplitPane, below the pathBar
        VBox centerArea = new VBox(pathBar, mainSplitPane);
        VBox.setVgrow(mainSplitPane, Priority.ALWAYS); // Ensure SplitPane takes available vertical space
        rootLayout.setCenter(centerArea);

        // Define the button bar
        JFXButton selectButton = new JFXButton("Select");
        selectButton.getStyleClass().add("dialog-button"); // General style, can be enhanced by CSS
        selectButton.setDefaultButton(true);
        selectButton.setOnAction(e -> {
            if (selectedFileInPane.get() != null) {
                result[0] = selectedFileInPane.get();
            } else if (currentDirectoryWrapper[0] != null && currentDirectoryWrapper[0].isDirectory()) {
                // If no specific item in the pane is selected,
                // and we are in a valid directory, select the current directory.
                result[0] = currentDirectoryWrapper[0];
            } else {
                result[0] = null; // Nothing valid to select
            }
            dialog.close();
        });

        JFXButton cancelButton = new JFXButton("Cancel");
        cancelButton.getStyleClass().add("dialog-button");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> {
            result[0] = null; // Ensure result is null on cancel
            dialog.close();
        });

        HBox buttonBar = new HBox(10); // Spacing between buttons
        buttonBar.getChildren().addAll(cancelButton, selectButton); // Order: Cancel, Select
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(15, 10, 10, 10)); // Top, Right, Bottom, Left
        buttonBar.getStyleClass().add("dialog-button-bar"); // For CSS styling

        rootLayout.setBottom(buttonBar);

        // Scene scene = new Scene(rootPane); // Scene uses the rootPane -- OLD,
        // rootPane was used when header was part of it.
        // Now, rootLayout is the main container for the scene.

        // If headerPane is not used, rootLayout is the direct root for the scene.
        // Ensure rootLayout is what's passed to the Scene constructor if headerPane is
        // removed.
        Scene scene = new Scene(rootLayout);

        // Load JFoenix and custom CSS
        try {
            scene.getStylesheets().add(
                    JFoenixResources.class.getResource("/com/jfoenix/assets/css/jfoenix-fonts.css").toExternalForm());
            scene.getStylesheets().add(
                    JFoenixResources.class.getResource("/com/jfoenix/assets/css/jfoenix-design.css").toExternalForm());
            scene.getStylesheets()
                    .add(FileChooserDialog.class.getResource("/css/file-chooser-dialog.css").toExternalForm());
        } catch (Exception cssEx) {
            System.err.println("Error loading CSS files: " + cssEx.getMessage());
            cssEx.printStackTrace();
        }
        try {
            scene.getStylesheets().add(
                    FileChooserDialog.class.getResource("/css/dark-theme.css").toExternalForm());
        } catch (Exception cssEx) {
            System.err.println("Error loading dark theme CSS file: " + cssEx.getMessage());
            cssEx.printStackTrace();
        }

        dialog.setScene(scene);
        dialog.showAndWait(); // 等待視窗關閉
        return result[0];
    }

    // New method to update TilePane
    private static void updateFilePane(File directory, TilePane tilePane, final File[] currentDirectoryWrapper,
            ObjectProperty<File> selectedFileProperty, TreeView<File> treeView) {
        currentDirectoryWrapper[0] = directory;
        tilePane.getChildren().clear();
        selectedFileProperty.set(null); // Clear selection when directory changes

        if (directory == null || !directory.isDirectory()) {
            // Placeholder will be visible due to empty children
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            // Placeholder will be visible
            return;
        }

        List<File> sortedFiles = Arrays.stream(files)
                .filter(f -> !f.isHidden())
                .sorted(Comparator.comparing(File::isDirectory).reversed()
                        .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        if (sortedFiles.isEmpty()) {
            // Placeholder will be visible
            return;
        }

        for (File file : sortedFiles) {
            ImageView iconView = new ImageView(getFileIcon(file));
            iconView.setFitHeight(48); // Standard icon size for tiles
            iconView.setFitWidth(48);
            iconView.setPreserveRatio(true);

            Label nameLabel = new Label(file.getName());
            nameLabel.setWrapText(true); // Allow text to wrap
            nameLabel.setMaxWidth(80); // Max width for the label within the tile
            nameLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER); // Center text
            nameLabel.setAlignment(Pos.CENTER);

            VBox tile = new VBox(5, iconView, nameLabel);
            tile.setAlignment(Pos.CENTER);
            tile.getStyleClass().add("file-tile");
            tile.setPadding(new Insets(8)); // Padding within the tile
            tile.setUserData(file); // Store file object with the tile
            // Set min/pref size for tiles to ensure consistency if desired
            tile.setPrefSize(100, 100); // Example: 100x100px tiles

            tile.setOnMouseClicked(event -> {
                // 清除先前選擇的樣式
                tilePane.getChildren().forEach(node -> node.getStyleClass().remove("selected"));
                tile.getStyleClass().add("selected");
                selectedFileProperty.set(file); // 更新選擇的檔案

                if (event.getButton().equals(MouseButton.PRIMARY) && event.getClickCount() == 2) {
                    if (file.isDirectory()) {
                        // 如果是目錄，導航到該目錄
                        currentDirectoryWrapper[0] = file;
                        updateFilePane(currentDirectoryWrapper[0], tilePane, currentDirectoryWrapper,
                                selectedFileProperty, treeView);
                        updateBreadcrumbBar(breadcrumbBarContainer, currentDirectoryWrapper[0], treeView,
                                treeView.getRoot(), currentDirectoryWrapper);
                        selectPathInTree(treeView.getRoot(), file, treeView, true);
                    } else {
                        // 如果是檔案，觸發 "Select" 按鈕
                        Node parent = tile.getScene().lookup(".dialog-button-bar");
                        if (parent instanceof HBox) {
                            for (Node buttonNode : ((HBox) parent).getChildren()) {
                                if (buttonNode instanceof JFXButton
                                        && "Select".equals(((JFXButton) buttonNode).getText())) {
                                    ((JFXButton) buttonNode).fire();
                                    break;
                                }
                            }
                        }
                    }
                }
            });
            tilePane.getChildren().add(tile);
        }
    }

    // updateListView method is now replaced by updateFilePane, so it should be
    // removed or commented out.
    // private static void updateListView(File directory, ListView<File> listView)
    // {…}

    // Update updateBreadcrumbBar to not require ListView
    private static void updateBreadcrumbBar(HBox breadcrumbGuiContainer, File currentPath,
            TreeView<File> treeView, TreeItem<File> conceptualRootNode,
            final File[] currentSelectedDirWrapper) {
        breadcrumbGuiContainer.getChildren().clear(); // 清空舊的 Breadcrumb
        if (currentPath == null)
            return;

        List<File> pathParts = new ArrayList<>();
        File temp = currentPath;
        while (temp != null) {
            pathParts.add(0, temp); // 從根目錄開始加入
            temp = temp.getParentFile();
        }

        for (int i = 0; i < pathParts.size(); i++) {
            File part = pathParts.get(i);
            String displayName = fileSystemView.getSystemDisplayName(part);
            if (displayName.isEmpty() && part.toPath().getNameCount() == 0) { // 根目錄 (例如 "C:\")
                displayName = part.getAbsolutePath();
            }

            if (i == pathParts.size() - 1) { // 最後一部分，表示當前目錄
                Label currentLabel = new Label(displayName);
                currentLabel.getStyleClass().add("breadcrumb-current-location");
                breadcrumbGuiContainer.getChildren().add(currentLabel);
            } else {
                Hyperlink link = new Hyperlink(displayName);
                link.getStyleClass().add("breadcrumb-link");
                link.setOnAction(e -> {
                    // 更新當前目錄
                    currentSelectedDirWrapper[0] = part;

                    // 更新檔案面板
                    updateFilePane(currentSelectedDirWrapper[0], filePane, currentSelectedDirWrapper,
                            selectedFileInPane, treeView);

                    // 更新 Breadcrumb Bar
                    updateBreadcrumbBar(breadcrumbGuiContainer, currentSelectedDirWrapper[0], treeView,
                            conceptualRootNode, currentSelectedDirWrapper);

                    // 在樹狀視圖中選中對應的節點
                    selectPathInTree(conceptualRootNode, part, treeView, true);
                });
                breadcrumbGuiContainer.getChildren().add(link);
                Label separator = new Label(">"); // 使用 ">" 作為分隔符號
                separator.getStyleClass().add("breadcrumb-separator");
                breadcrumbGuiContainer.getChildren().add(separator);
            }
        }
        currentSelectedDirWrapper[0] = currentPath; // 確保當前目錄已更新
    }

    // ... (selectPathInTree and createNode methods remain largely the same, ensure
    // they are compatible)
    private static boolean selectPathInTree(TreeItem<File> root, File targetPath, TreeView<File> treeView,
            boolean expandTarget) {
        if (root == null || targetPath == null)
            return false;

        String targetPathStr = targetPath.getAbsolutePath();
        if (targetPath.getParentFile() == null && targetPathStr.endsWith(File.separator)) {
            targetPathStr = targetPathStr.substring(0, targetPathStr.length() - 1);
        }

        for (TreeItem<File> childNode : root.getChildren()) {
            if (childNode.getValue() != null) {
                String childPathStr = childNode.getValue().getAbsolutePath();
                if (childNode.getValue().getParentFile() == null && childPathStr.endsWith(File.separator)) {
                    childPathStr = childPathStr.substring(0, childPathStr.length() - 1);
                }

                if (childPathStr.equalsIgnoreCase(targetPathStr)) {
                    treeView.getSelectionModel().select(childNode);
                    if (expandTarget)
                        childNode.setExpanded(true);

                    // 更新檔案面板
                    updateFilePane(targetPath, filePane, new File[] { targetPath }, selectedFileInPane, treeView);

                    // 滾動到選中的節點
                    int rowIndex = treeView.getRow(childNode);
                    if (rowIndex != -1) {
                        treeView.scrollTo(rowIndex);
                    }
                    return true;
                }

                // 如果目標路徑是子節點的後代
                if (targetPathStr.toLowerCase().startsWith(childPathStr.toLowerCase() + File.separator)) {
                    childNode.setExpanded(true); // 展開父節點
                    if (selectPathInTree(childNode, targetPath, treeView, expandTarget)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static TreeItem<File> createNode(final File f) {
        return new TreeItem<File>(f) {
            private boolean isLeaf;
            private boolean isFirstTimeChildren = true;
            private boolean isFirstTimeLeaf = true;

            @Override
            public ObservableList<TreeItem<File>> getChildren() {
                if (isFirstTimeChildren) {
                    isFirstTimeChildren = false;
                    super.getChildren().setAll(buildChildren(this));
                }
                return super.getChildren();
            }

            @Override
            public boolean isLeaf() {
                if (isFirstTimeLeaf) {
                    isFirstTimeLeaf = false;
                    File f = getValue();
                    isLeaf = (f == null || !f.isDirectory());
                }
                return isLeaf;
            }

            private ObservableList<TreeItem<File>> buildChildren(TreeItem<File> TreeItem) {
                File f = TreeItem.getValue();
                if (f != null && f.isDirectory()) {
                    File[] files = f.listFiles();
                    if (files != null) {
                        ObservableList<TreeItem<File>> children = FXCollections.observableArrayList();
                        Arrays.stream(files)
                                .filter(file -> file.isDirectory() && !file.isHidden()) // Only directories in tree,
                                                                                        // filter hidden
                                .sorted(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)) // Sort
                                                                                                            // directories
                                .forEach(file -> children.add(createNode(file)));
                        return children;
                    }
                }
                return FXCollections.emptyObservableList();
            }
        };
    }
}
