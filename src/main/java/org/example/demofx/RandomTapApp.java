package org.example.demofx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RandomTapApp extends Application {

    private static final String TELEGRAM_PACKAGE_NAME = "org.telegram.messenger";
    private static final String UI_DUMP_PATH = "/sdcard/window_dump.xml";

    private TextField deviceAddressField;
    private TextField minWaitField;
    private TextField maxWaitField;
    private Button startButton;
    private Button stopButton;
    private Button clear;
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

        clear = new Button("clear log");
        clear.setOnAction(actionEvent -> clear());

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
        grid.add(clear, 0, 6);

        primaryStage.setScene(new Scene(grid, 400, 400));
        primaryStage.show();
    }

    private void clear() {
        logArea.replaceText(0, logArea.getLength() / 2, "");
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

        Thread chatCheckThread = getChatCheckThread(deviceAddress);

        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                int retryCount = 0;
                while (retryCount < MAX_RETRIES && !isDeviceConnected(deviceAddress)) {
                    log("Device '" + deviceAddress + "' not found. Attempting to connect...");
                    executeCommand("adb connect " + deviceAddress);
                    retryCount++;
                }

                if (retryCount >= MAX_RETRIES) {
                    log("Failed to connect to device '" + deviceAddress + "' after " + MAX_RETRIES + " attempts.");
                    stopTapping();
                    return;
                }

                log("Device connected: " + deviceAddress);
                Random random = new Random();

                executor.submit(() -> {
                    while (running) {
                        if (isTelegramOnForeground(deviceAddress)) {
                            dumpUIAutomatorXML(deviceAddress);
                        }
                    }
                });
                while (running) {
                    if (isTelegramOnForeground(deviceAddress)) {
                        String chatName = getOpenedChatName();
                        if (chatName != null) {
                            if (!"Hamster Kombat".equals(chatName)) {
                                log("The opened chat is not 'Hamster Kombat'. Stopping tapping.");
                                Platform.runLater(this::stopTapping);
                                break;
                            }
                        }
                    } else {
                        log("Telegram closed");
                        stopTapping();
                        break;
                    }
                    int randX = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
                    int randY = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
                    executeCommand("adb -s " + deviceAddress + " shell input tap " + randX + " " + randY);
                    if (!isDeviceOnline(deviceAddress)) {
                        log("Device '" + deviceAddress + "' is offline. Stopping the script.");
                        stopTapping();
                        break;
                    }

                    int randWait = minWait + random.nextInt(maxWait - minWait + 1);
                    log(" Tapped at (" + randX + ", " + randY + ")" + "Waiting for " + randWait + " milliseconds");
                    Thread.sleep(randWait);
                    if (LocalTime.now().getHour() % 4 == 0 && LocalTime.now().getMinute() == 2 && LocalTime.now().getSecond() == 0) {
                        logArea.clear();
                    }
                }

            } catch (InterruptedException | IOException e) {
                log("Script interrupted.");
            } finally {
                chatCheckThread.interrupt();
                executor.shutdown();
                Platform.runLater(() -> {
                    startButton.setDisable(false);
                    stopButton.setDisable(true);
                });
            }
        });
    }

    private Thread getChatCheckThread(String deviceAddress) {
        Thread chatCheckThread = new Thread(() -> {
            try {
                while (running) {
                    if (!isTelegramOnForeground(deviceAddress)) {
                        log("Telegram closed");
                        stopTapping();
                        break;
                    }
                    Thread.sleep(1000); // Перевіряти стан кожну секунду
                }
            } catch (InterruptedException e) {
                log("Chat check thread interrupted.");
            }
        });
        chatCheckThread.start();
        return chatCheckThread;
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
        Platform.runLater(() -> logArea.appendText(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + message + "\n"));
    }

    public boolean isTelegramOnForeground(String deviceAddress) {
        try {
            Process process = Runtime.getRuntime().exec("adb -s" + deviceAddress + " shell dumpsys window windows");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                    if (line.contains(TELEGRAM_PACKAGE_NAME)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public String getActiveActivity(String deviceAddress) {
        StringBuilder result = new StringBuilder();
        try {
            // Check active activity
            Process process = Runtime.getRuntime().exec("adb -s " + deviceAddress + " shell dumpsys activity activities");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("mResumedActivity")) {
                    result.append(line.trim());
                    break;
                }
            }

            // Check focused window
            process = Runtime.getRuntime().exec("adb shell dumpsys window windows");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = reader.readLine()) != null) {
                if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                    result.append("\n").append(line.trim());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    public void dumpUIAutomatorXML(String deviceAddress) {
        try {
            // Dump the UI hierarchy to an XML file
            Process process = Runtime.getRuntime().exec("adb -s " + deviceAddress + " shell uiautomator dump " + UI_DUMP_PATH);
            process.waitFor();

            // Pull the XML file from the device to the local machine
            process = Runtime.getRuntime().exec("adb -s " + deviceAddress + " pull " + UI_DUMP_PATH);
            process.waitFor();
            System.out.println("UI Automator dump saved as window_dump.xml");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getOpenedChatName() {
        try {
            File file = new File("window_dump.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(file);
            NodeList nodeList = doc.getElementsByTagName("node");

            for (int i = 0; i < nodeList.getLength(); i++) {
                org.w3c.dom.Node node = nodeList.item(i);
                String className = node.getAttributes().getNamedItem("class").getNodeValue();
                if ("android.widget.TextView".equals(className)) {
                    String text = node.getAttributes().getNamedItem("text").getNodeValue();
                    if (text != null && !text.isEmpty()) {
                        return text;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
