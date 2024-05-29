package org.example.demofx;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RandomTapApp extends Application {

    private TextField deviceAddressField;
    private TextField minWaitField;
    private TextField maxWaitField;
    private Button startButton;
    private Button stopButton;
    private TextArea logArea;

    private ExecutorService executor;
    private volatile boolean running;

    private static final int MIN_X = 300;
    private static final int MAX_X = 1312;
    private static final int MIN_Y = 270;
    private static final int MAX_Y = 690;
    private static final int MAX_RETRIES = 5;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Random Tap App");

        deviceAddressField = new TextField();
        deviceAddressField.setPromptText("Enter device address");

        minWaitField = new TextField();
        minWaitField.setPromptText("Enter min wait time (ms)");

        maxWaitField = new TextField();
        maxWaitField.setPromptText("Enter max wait time (ms)");

        startButton = new Button("Start");
        startButton.setOnAction(event -> startTapping());

        stopButton = new Button("Stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stopTapping());

        logArea = new TextArea();
        logArea.setEditable(false);

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(10));
        grid.setVgap(10);
        grid.setHgap(10);
        grid.add(new Label("Device Address:"), 0, 0);
        grid.add(deviceAddressField, 1, 0);
        grid.add(new Label("Min Wait (ms):"), 0, 1);
        grid.add(minWaitField, 1, 1);
        grid.add(new Label("Max Wait (ms):"), 0, 2);
        grid.add(maxWaitField, 1, 2);
        grid.add(startButton, 0, 3);
        grid.add(stopButton, 1, 3);
        grid.add(new Label("Log:"), 0, 4);
        grid.add(logArea, 0, 5, 2, 1);

        primaryStage.setScene(new Scene(grid, 400, 400));
        primaryStage.show();
    }

    private void startTapping() {
        String deviceAddress = deviceAddressField.getText() == null ? "127.0.0.1:5555" : deviceAddressField.getText();
        int minWait, maxWait;

        try {
            minWait = Integer.parseInt(minWaitField.getText()) > 0 ? Integer.parseInt(minWaitField.getText()) : 100;
            maxWait = Integer.parseInt(maxWaitField.getText()) > 0 ? Integer.parseInt(maxWaitField.getText()) : 500;
        } catch (NumberFormatException e) {
            log("Invalid wait time values.");
            return;
        }

        running = true;
        startButton.setDisable(true);
        stopButton.setDisable(false);

        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                int retryCount = 0;
                while (retryCount < MAX_RETRIES && !isDeviceConnected(deviceAddress)) {
                    log("Device '" + deviceAddress + "' not found. Attempting to connect...");
                    executeCommand("adb connect " + deviceAddress);
                    Thread.sleep(1500);
                    retryCount++;
                }

                if (retryCount >= MAX_RETRIES) {
                    log("Failed to connect to device '" + deviceAddress + "' after " + MAX_RETRIES + " attempts.");
                    stopTapping();
                    return;
                }

                log("Device connected: " + deviceAddress);

                Random random = new Random();
                while (running) {
                    int randX = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
                    int randY = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
                    executeCommand("adb -s " + deviceAddress + " shell input tap " + randX + " " + randY);
                    if (!isDeviceOnline(deviceAddress)) {
                        log("Device '" + deviceAddress + "' is offline. Stopping the script.");
                        stopTapping();
                        break;
                    }

                    int randWait = minWait + random.nextInt(maxWait - minWait + 1);
                    log("Tapped at (" + randX + ", " + randY + ")" + "Waiting for " + randWait + " milliseconds");
                    Thread.sleep(randWait);
                }
            } catch (InterruptedException | IOException e) {
                log("Script interrupted.");
            } finally {
                executor.shutdown();
                Platform.runLater(() -> {
                    startButton.setDisable(false);
                    stopButton.setDisable(true);
                });
            }
        });
    }

    private void stopTapping() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
        startButton.setDisable(false);
        stopButton.setDisable(true);
    }

    private boolean isDeviceConnected(String deviceAddress) {
        try {
            Process process = executeCommand("adb devices");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(deviceAddress)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log("Error checking device connection: " + e.getMessage());
        }
        return false;
    }

    private boolean isDeviceOnline(String deviceAddress) {
        try {
            Process process = executeCommand("adb -s " + deviceAddress + " shell echo online");
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            log("Error checking device online status: " + e.getMessage());
            return false;
        }
    }

    private Process executeCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }
}
