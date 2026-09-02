package com.development.agent.config;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

@Component
@Order(1)
public class OpenCodeManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeManager.class);

    @Value("${ai.opencode.url}")
    private String baseUrl;

    @Value("${ai.opencode.port}")
    private int port;

    @Value("${ai.opencode.auto-install}")
    private boolean autoInstall;

    private Process opencodeProcess;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== OpenCode Manager Starting ===");

        if (isPortInUse(port)) {
            log.info("OpenCode server already running on port {}", port);
            if (isServerResponsive()) {
                log.info("OpenCode server is responsive and ready");
                return;
            }
            log.warn("Port {} is in use but server not responding, proceeding anyway", port);
            return;
        }

        if (!isOpencodeInstalled()) {
            if (autoInstall) {
                log.info("OpenCode not found. Attempting installation...");
                installOpencode();
            } else {
                log.error("OpenCode is not installed and auto-install is disabled");
                throw new RuntimeException("OpenCode is not installed. Install it manually or set ai.opencode.auto-install=true");
            }
        } else {
            log.info("OpenCode is installed");
        }

        log.info("Starting OpenCode server on port {}...", port);
        startOpencodeServer();

        if (waitForServer(30000)) {
            log.info("OpenCode server started successfully on port {}", port);
        } else {
            log.warn("OpenCode server may not be fully ready after 30s, continuing startup");
        }

        log.info("=== OpenCode Manager Complete ===");
    }

    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isOpencodeInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("opencode", "version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                log.info("OpenCode found: {}", new BufferedReader(new InputStreamReader(p.getInputStream())).lines().reduce("", String::concat));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("OpenCode not found in PATH: {}", e.getMessage());
            return false;
        }
    }

    private void installOpencode() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                installWindows();
            } else if (os.contains("mac")) {
                installMac();
            } else {
                installLinux();
            }
            log.info("OpenCode installed successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to install OpenCode: " + e.getMessage(), e);
        }
    }

    private void installWindows() throws IOException, InterruptedException {
        log.info("Installing OpenCode on Windows...");

        // Prefer npm since it is widely available (e.g. Node projects).
        String[] methods = {
            "npm install -g opencode-ai",
            "npm.cmd install -g opencode-ai",
            "irm https://opencode.ai/install | iex",
            "choco install -y opencode",
            "scoop install opencode"
        };

        for (String cmd : methods) {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            Process p = pb.start();
            boolean finished = p.waitFor(180, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                log.info("OpenCode installed on Windows via: {}", cmd);
                return;
            }
            log.warn("Installation method failed (exit {}): {}", p.exitValue(), cmd);
        }
        throw new RuntimeException("OpenCode installation failed on Windows. Try installing it manually via 'npm install -g opencode-ai'.");
    }

    private void installMac() throws IOException, InterruptedException {
        log.info("Installing OpenCode on macOS...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "curl -fsSL https://opencode.ai/install.sh | bash");
        pb.redirectErrorStream(true);
        pb.inheritIO();
        Process p = pb.start();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished || p.exitValue() != 0) {
            throw new RuntimeException("OpenCode installation failed on macOS. Exit code: " + p.exitValue());
        }
    }

    private void installLinux() throws IOException, InterruptedException {
        log.info("Installing OpenCode on Linux...");
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                "curl -fsSL https://opencode.ai/install.sh | bash");
        pb.redirectErrorStream(true);
        pb.inheritIO();
        Process p = pb.start();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished || p.exitValue() != 0) {
            throw new RuntimeException("OpenCode installation failed on Linux. Exit code: " + p.exitValue());
        }
    }

    private String resolveOpencodeExecutable() {
        // 1. Try plain command (already in the JVM's PATH)
        if (canExecute(new String[]{"opencode", "version"})) {
            return "opencode";
        }

        // 2. npm global bin dir (covers recent npm install where PATH is not refreshed)
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                ProcessBuilder pb = new ProcessBuilder("npm.cmd", "prefix", "-g");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String prefix = new BufferedReader(new InputStreamReader(p.getInputStream())).lines().reduce("", (a, b) -> a + b).trim();
                if (p.waitFor(10, TimeUnit.SECONDS) && !prefix.isEmpty()) {
                    String candidate = prefix + "\\opencode.cmd";
                    if (new File(candidate).exists()) {
                        return candidate;
                    }
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder("npm", "prefix", "-g");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String prefix = new BufferedReader(new InputStreamReader(p.getInputStream())).lines().reduce("", (a, b) -> a + b).trim();
                if (p.waitFor(10, TimeUnit.SECONDS) && !prefix.isEmpty()) {
                    String candidate = prefix + "/bin/opencode";
                    if (new File(candidate).exists()) {
                        return candidate;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve npm global opencode path: {}", e.getMessage());
        }

        throw new RuntimeException("Could not locate the 'opencode' executable after installation. " +
                "Restart the service or install it manually with 'npm install -g opencode-ai'.");
    }

    private boolean canExecute(String[] command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void startOpencodeServer() {
        try {
            String opencodeCmd = resolveOpencodeExecutable();
            log.info("Using opencode executable: {}", opencodeCmd);
            ProcessBuilder pb = new ProcessBuilder(opencodeCmd, "serve", "--port", String.valueOf(port));
            pb.redirectErrorStream(true);
            File logFile = new File("logs", "opencode.log");
            logFile.getParentFile().mkdirs();
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            opencodeProcess = pb.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (opencodeProcess != null && opencodeProcess.isAlive()) {
                    log.info("Shutting down OpenCode server...");
                    opencodeProcess.destroy();
                    try {
                        opencodeProcess.waitFor(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                }
            }));

            log.info("OpenCode server process started (PID: {})", opencodeProcess.pid());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start OpenCode server: " + e.getMessage(), e);
        }
    }

    private boolean waitForServer(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();

        while (System.currentTimeMillis() < deadline) {
            try {
                Request req = new Request.Builder().url(baseUrl + "/session").get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful() || resp.code() == 405) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isServerResponsive() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        try {
            Request req = new Request.Builder().url(baseUrl + "/session").get().build();
            try (Response resp = client.newCall(req).execute()) {
                return resp.isSuccessful() || resp.code() == 405;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
