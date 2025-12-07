package org.example.javachess.Controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainController {

    @FXML
    private StackPane mainContainer;

    private Map<String, Parent> views = new HashMap<>();
    private Map<String, Object> controllers = new HashMap<>();

    @FXML
    public void initialize() {
        loadView("HOME", "/UI/HomeView.fxml");
        navigateTo("HOME");
        
        // Preload other views in background to make navigation instant
        // Using a separate thread to avoid blocking UI startup
        new Thread(() -> {
            javafx.application.Platform.runLater(() -> {
                loadView("PVP_SETUP", "/UI/PvPSetupView.fxml");
                loadView("PVC_SETUP", "/UI/PvCSetupView.fxml");
                loadView("GAME", "/UI/GameView.fxml");
                loadView("ARCHIVE", "/UI/ArchiveView.fxml");
                loadView("THEME", "/UI/ThemeView.fxml");
                // Review view might be dynamic, so maybe load on demand or preload generic
                loadView("REVIEW", "/UI/ReviewView.fxml");
            });
        }).start();
    }

    public void navigateTo(String viewName) {
        Parent view = views.get(viewName);
        if (view == null) {
            // Lazy loading if not already loaded
            // For now, assume pre-loading or load on demand logic here
            System.err.println("View not found: " + viewName);
            return;
        }
        mainContainer.getChildren().clear();
        mainContainer.getChildren().add(view);
    }

    public void loadView(String name, String fxmlPath) {
        if (views.containsKey(name)) {
            return; // Already loaded
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent view = loader.load();
            views.put(name, view);
            
            Object controller = loader.getController();
            if (controller instanceof NavigationAware) {
                ((NavigationAware) controller).setMainController(this);
            }
            controllers.put(name, controller);
            
        } catch (IOException e) {
            System.err.println("FAILED TO LOAD VIEW: " + name + " from " + fxmlPath);
            e.printStackTrace();
        }
    }
    
    public Object getController(String name) {
        return controllers.get(name);
    }
}
