package org.example.javachess.Application;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // Carica il file FXML
            // Carica il file FXML
            var resource = App.class.getResource("/UI/MainLayout.fxml");
            System.out.println("Resource URL: " + resource);
            if (resource == null) {
                throw new IllegalStateException("Cannot find /UI/MainLayout.fxml");
            }
            FXMLLoader fxmlLoader = new FXMLLoader(resource);
            org.example.javachess.Components.ThemeManager.loadFonts();
            Scene scene = new Scene(fxmlLoader.load(), 720, 1280);
            // Fonts, stylesheet, light/dark theme, window title and icons (UI layer).
            org.example.javachess.Components.ThemeManager.install(scene, primaryStage);
            primaryStage.setScene(scene);
            boolean fullScreen = DevOptions.placeStage(primaryStage);
            primaryStage.setFullScreen(fullScreen);

            // Seleziona lo schermo desiderato (ad esempio, il secondo schermo)

            // Posiziona la finestra sullo schermo selezionato

            primaryStage.show();
            DevOptions.afterShow(primaryStage, fxmlLoader.getController());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() throws Exception {
        System.out.println("[App] Stopping application...");
        // Clean shutdown of JCEF to release cache locks
        try {
            org.cef.CefApp.getInstance().dispose();
            System.out.println("[App] JCEF disposed successfully.");
        } catch (Throwable t) {
            // Ignore if JCEF wasn't initialized
        }
        super.stop();
        System.exit(0); // Force kill to ensure no lingering processes
    }

    public static void main(String[] args) {
        try {
            nu.pattern.OpenCV.loadLocally();
            System.out.println("[App] OpenCV loaded successfully.");
        } catch (Throwable t) {
            System.err.println("[App] Failed to load OpenCV: " + t.getMessage());
            t.printStackTrace();
        }

        // Initialize Persistent Cookie Store Globally
        try {
            System.out.println("[App] Initializing Global CookieManager...");
            java.net.CookieManager cookieManager = new java.net.CookieManager(
                    new org.example.javachess.Utils.PersistentCookieStore(),
                    java.net.CookiePolicy.ACCEPT_ALL);
            java.net.CookieHandler.setDefault(cookieManager);
            System.out.println("[App] Global CookieManager set.");
        } catch (Exception e) {
            System.err.println("[App] Failed to set global CookieManager: " + e.getMessage());
            e.printStackTrace();
        }

        // SAFETY NET: Shutdown Hook for Ctrl+C or kill signals
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[App] Shutdown Hook Triggered!");
            try {
                // Check if CefApp is initialized and dispose
                org.cef.CefApp.getInstance().dispose();
                System.out.println("[App] JCEF disposed via Shutdown Hook.");
            } catch (Throwable t) {
                // Already disposed or not initialized
            }
        }));

        launch(args);
    }
}