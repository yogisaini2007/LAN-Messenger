
package com.lanmessenger.frontend.web;

import com.lanmessenger.app.MessengerEngine;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import netscape.javascript.JSObject;

import java.net.URL;
import java.util.Set;
import java.util.prefs.Preferences;

public final class WebMain extends Application {

    private MessengerEngine app;
    private WebBridge bridge;
    private GroupWebBridge groupsBridge;
    private VoiceWebBridge voiceBridge;
    private NativeSounds nativeSounds;

    private static final String APP_NAME = "LAN Messenger";
    
    private static final String LOGO_PATH = "/web/logo.png";

    // ---------------------------------------------
    // WINDOW CONTROLS
    // ---------------------------------------------

    public static final class WindowControls {

        private final Stage stage;

        private final Preferences prefs =
                Preferences.userNodeForPackage(
                        WebMain.class);

        private double offsetX;
        private double offsetY;

        private double restoreX;
        private double restoreY;
        private double restoreW;
        private double restoreH;

        private boolean maximized;

        public WindowControls(Stage stage) {
            this.stage = stage;
        }

        public void minimize() {
            Platform.runLater(
                    () -> stage.setIconified(true));
        }

        public void close() {
            Platform.runLater(stage::close);
        }

        public boolean isMaximized() {
            return maximized;
        }

        public void maximize() {

            Platform.runLater(() -> {

                if (!maximized) {

                    restoreX = stage.getX();
                    restoreY = stage.getY();

                    restoreW = stage.getWidth();
                    restoreH = stage.getHeight();

                    Rectangle2D bounds =
                            Screen.getPrimary()
                                    .getVisualBounds();

                    stage.setX(bounds.getMinX());
                    stage.setY(bounds.getMinY());

                    stage.setWidth(bounds.getWidth());
                    stage.setHeight(bounds.getHeight());

                    maximized = true;

                } else {

                    stage.setX(restoreX);
                    stage.setY(restoreY);

                    stage.setWidth(restoreW);
                    stage.setHeight(restoreH);

                    maximized = false;
                }
            });
        }

        public void beginMove(
                double screenX,
                double screenY
        ) {
            offsetX = screenX - stage.getX();
            offsetY = screenY - stage.getY();
        }

        public void dragMove(
                double screenX,
                double screenY
        ) {
            if (!maximized) {
                stage.setX(screenX - offsetX);
                stage.setY(screenY - offsetY);
            }
        }

        public String getTheme() {
            return prefs.get(
                    "ui-theme",
                    "midnight"
            );
        }

        public void saveTheme(String name) {

            if (Set.of(
                    "midnight",
                    "aurora",
                    "frost",
                    "pearl",
                    "sunrise"
            ).contains(name)) {

                prefs.put("ui-theme", name);
            }
        }
    }

    // ---------------------------------------------
    // APPLICATION STARTUP
    // ---------------------------------------------

    @Override
    public void start(Stage stage) throws Exception {

        app = new MessengerEngine();
        nativeSounds = new NativeSounds();

        stage.initStyle(StageStyle.UNDECORATED);

        // Application identity
        stage.setTitle(
                APP_NAME 
        );

        // -----------------------------------------
        // LOAD APPLICATION LOGO
        // -----------------------------------------

        try {

            URL logoURL =
                    getClass().getResource(LOGO_PATH);

            if (logoURL != null) {

                Image logo = new Image(
                        logoURL.toExternalForm(),
                        false
                );

                if (!logo.isError()) {

                    stage.getIcons().clear();
                    stage.getIcons().add(logo);

                    System.out.println(
                            "Application logo loaded: "
                                    + LOGO_PATH
                    );

                } else {

                    System.err.println(
                            "Unable to decode app logo: "
                                    + LOGO_PATH
                    );
                }

            } else {

                System.err.println(
                        "WARNING: Application logo missing: "
                                + LOGO_PATH
                );
            }

        } catch (Exception error) {

            System.err.println(
                    "Failed to load application logo: "
                            + error.getMessage()
            );
        }

        // -----------------------------------------
        // JAVAFX WEBVIEW
        // -----------------------------------------

        WebView view = new WebView();

        view.setContextMenuEnabled(false);

        view.getEngine().setJavaScriptEnabled(true);

        WindowControls controls =
                new WindowControls(stage);

        // -----------------------------------------
        // REGISTER JAVASCRIPT BRIDGES
        // -----------------------------------------

        view.getEngine()
                .getLoadWorker()
                .stateProperty()
                .addListener((observable, old, state) -> {

                    if (state != Worker.State.SUCCEEDED) {
                        return;
                    }

                    // Clean up optional bridges on reload.
                    if (voiceBridge != null) {

                        voiceBridge.close();
                        voiceBridge = null;
                    }

                    if (groupsBridge != null) {

                        groupsBridge.close();
                        groupsBridge = null;
                    }

                    // PRIVATE CHAT BRIDGE
                    bridge = new WebBridge(
                            app,
                            view.getEngine(),
                            stage
                    );

                    JSObject window = (JSObject)
                            view.getEngine()
                                    .executeScript("window");

                    window.setMember(
                            "lanJava",
                            bridge
                    );

                    // NATIVE NOTIFICATION SOUNDS
                    window.setMember(
                            "lanSounds",
                            nativeSounds
                    );

                    // WINDOW CONTROLS
                    window.setMember(
                            "windowControls",
                            controls
                    );

                    // GROUP CHAT BRIDGE
                    try {

                        groupsBridge = new GroupWebBridge(
                                app,
                                view.getEngine(),
                                stage
                        );

                        window.setMember(
                                "lanGroups",
                                groupsBridge
                        );

                    } catch (Exception error) {

                        System.err.println(
                                "Group bridge unavailable: "
                                        + error.getMessage()
                        );
                    }

                    // VOICE MESSAGE BRIDGE
                    try {

                        voiceBridge = new VoiceWebBridge(
                                app,
                                view.getEngine()
                        );

                        window.setMember(
                                "lanVoice",
                                voiceBridge
                        );

                    } catch (Exception error) {

                        System.err.println(
                                "Voice bridge unavailable: "
                                        + error.getMessage()
                        );
                    }

                    // ACTIVATE FRONTEND
                    view.getEngine().executeScript(
                            "if(window.LAN&&window.LAN.ready)" +
                                    "window.LAN.ready();"
                    );
                });

        // -----------------------------------------
        // LOAD HTML INTERFACE
        // -----------------------------------------

        URL page = getClass().getResource(
                "/web/index.html"
        );

        if (page == null) {

            throw new IllegalStateException(
                    "Missing /web/index.html"
            );
        }

        view.getEngine().load(
                page.toExternalForm()
        );

        // -----------------------------------------
        // WINDOW CONFIGURATION
        // -----------------------------------------

        stage.setScene(
                new Scene(view, 1250, 790)
        );

        stage.setMinWidth(900);
        stage.setMinHeight(590);

        stage.centerOnScreen();
        stage.show();

        System.out.println(
                APP_NAME + " started"
        );

    
    }

    // ---------------------------------------------
    // APPLICATION SHUTDOWN
    // ---------------------------------------------

    @Override
    public void stop() {

        if (nativeSounds != null) {

            nativeSounds.close();
            nativeSounds = null;
        }

        if (voiceBridge != null) {

            voiceBridge.close();
            voiceBridge = null;
        }

        if (groupsBridge != null) {

            groupsBridge.close();
            groupsBridge = null;
        }

        if (bridge != null && app != null) {

            app.removeListener(bridge);
            bridge = null;
        }

        if (app != null) {

            app.close();
            app = null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
