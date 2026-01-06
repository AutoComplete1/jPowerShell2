/*
 * Copyright 2016-2019 Javier Garcia Alonso.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.autocomplete1;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This API allows to open a session into PowerShell console and launch different commands.<br>
 * This class cannot be instantiated directly. Please use instead the method
 * PowerShell.openSession() and call the commands using the returned instance.
 * <p>
 * Once the session is finished it should be closed in order to free resources.
 * For doing that, you can either call manually close() or implement a try with resources as
 * it implements {@link AutoCloseable}.
 *
 * @author Javier Garcia Alonso
 */
public class PowerShell implements AutoCloseable {

    public static final String END_SCRIPT_STRING = "--END-JPOWERSHELL-SCRIPT--";
    //Declare logger
    private static final Logger logger = Logger.getLogger(PowerShell.class.getName());
    //Default PowerShell executable path
    private static final String DEFAULT_WIN_EXECUTABLE = "powershell.exe";
    private static final String DEFAULT_LINUX_EXECUTABLE = "powershell";
    private static final String CORE_EXECUTABLE = "pwsh";
    // Process to store PowerShell session
    private Process p;
    //PID of the process
    private long pid = -1;
    // Writer to send commands
    private PrintWriter commandWriter;
    private BufferedReader outputReader;
    // Threaded session variables
    private boolean closed = false;
    private ExecutorService executorService;
    // Config values
    private int waitPause = 5;
    private long maxWait = 10000;
    private File tempFolder = null;
    private boolean useCore = false;
    // Variables used for script mode
    private boolean scriptMode = false;

    // Private constructor. Instance using openSession method
    private PowerShell() {
    }

    /**
     * Creates a session in PowerShell console which returns an instance which allows
     * executing commands in PowerShell context.<br>
     * It uses the default PowerShell installation in the system.
     *
     * @return an instance of the class
     * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
     */
    public static PowerShell openSession() throws PowerShellNotAvailableException {
        return openSession(null);
    }

    public static PowerShell openSession(boolean useCore) throws PowerShellNotAvailableException {
        PowerShell ps = new PowerShell();
        ps.useCore = useCore;
        return ps.initialize(null);
    }

    /**
     * Creates a session in PowerShell console which returns an instance which allows
     * executing commands in PowerShell context.<br>
     * This method allows to define a PowersShell executable path different from default
     *
     * @param customPowerShellExecutablePath the path of powershell executable. If you are using
     *                                       the default installation path, call {@link #openSession()} method instead
     * @return an instance of the class
     * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
     */
    public static PowerShell openSession(String customPowerShellExecutablePath) throws PowerShellNotAvailableException {
        return openSession(customPowerShellExecutablePath, null);
    }

    /**
     * Creates a session in PowerShell console which returns an instance which allows
     * executing commands in PowerShell context.<br>
     * This method allows to define a PowersShell executable path different from default
     *
     * @param customPowerShellExecutablePath the path of powershell executable. If you are using
     *                                       the default installation path, call {@link #openSession()} method instead
     * @param config                         map with the configuration in key/value format
     * @return an instance of the class
     * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
     */
    public static PowerShell openSession(String customPowerShellExecutablePath, Map<String, String> config) throws PowerShellNotAvailableException {
        PowerShell powerShell = new PowerShell();
        powerShell.configuration(config);
        return powerShell.initialize(customPowerShellExecutablePath);
    }

    /**
     * Execute a single command in PowerShell console scriptMode and gets result
     *
     * @param command the command to execute
     * @return response with the output of the command
     */
    public static PowerShellResponse executeSingleCommand(String command) {
        PowerShellResponse response = null;

        try (PowerShell session = PowerShell.openSession()) {
            response = session.executeCommand(command);
        } catch (PowerShellNotAvailableException ex) {
            logger.log(Level.SEVERE, "PowerShell not available", ex);
        }

        return response;
    }

    /**
     * Allows overriding jPowerShell configuration using a map of key/value <br>
     * Default values are taken from file <i>jpowershell.properties</i>, which can
     * be replaced just setting it on project classpath
     * <p>
     * The values that can be overridden are:
     * <ul>
     * <li>waitPause: the pause in ms between each loop pooling for a response.
     * Default value is 10</li>
     * <li>maxWait: the maximum wait in ms for the command to execute. Default value
     * is 10000</li>
     * </ul>
     *
     * @param config map with the configuration in key/value format
     * @return instance to chain
     */
    public PowerShell configuration(Map<String, String> config) {
        if (config == null && PowerShellConfig.getConfig().getProperty("waitPause") == null) {
            return this;
        }

        if (config != null) {
            config.forEach((key, value) -> {
                switch (key) {
                    case "waitPause":
                        this.waitPause = Integer.parseInt(value);
                        break;
                    case "maxWait":
                        this.maxWait = Long.parseLong(value);
                        break;
                    case "tempFolder":
                        this.tempFolder = getTempFolder(value);
                        break;
                    case "useCore":
                        this.useCore = Boolean.parseBoolean(value);
                        break;
                }
            });

            return this;
        }

        this.waitPause = Integer.parseInt(PowerShellConfig.getConfig().getProperty("waitPause"));
        this.maxWait = Long.parseLong(PowerShellConfig.getConfig().getProperty("maxWait"));
        this.tempFolder = getTempFolder(PowerShellConfig.getConfig().getProperty("tempFolder"));

        return this;
    }

    // Initializes PowerShell console in which we will enter the commands
    private PowerShell initialize(String customPath) throws PowerShellNotAvailableException {
        String executable;

        if (customPath != null) {
            executable = customPath;
        } else if (this.useCore) {
            executable = CORE_EXECUTABLE;
        } else {
            executable = OSDetector.isWindows() ? DEFAULT_WIN_EXECUTABLE : DEFAULT_LINUX_EXECUTABLE;
        }

        String codePage = PowerShellCodepage.getIdentifierByCodePageName(Charset.defaultCharset().name());
        ProcessBuilder pb;

        if (OSDetector.isWindows() && !this.useCore) {
            pb = new ProcessBuilder("cmd.exe", "/c", "chcp", codePage, ">", "NUL", "&", executable,
                    "-ExecutionPolicy", "Bypass", "-NoExit", "-NoProfile", "-Command", "-");
        } else {
            pb = new ProcessBuilder(executable, "-nologo", "-noexit", "-Command", "-");
        }

        pb.redirectErrorStream(true);

        try {
            p = pb.start();
            if (p.waitFor(5, TimeUnit.SECONDS) && !p.isAlive()) {
                throw new PowerShellNotAvailableException("Executable not found: " + executable);
            }
        } catch (IOException | InterruptedException ex) {
            throw new PowerShellNotAvailableException("Cannot execute PowerShell: " + executable, ex);
        }

        this.commandWriter = new PrintWriter(new OutputStreamWriter(
                new BufferedOutputStream(p.getOutputStream(), 65536), Charset.defaultCharset()), true);
        this.outputReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.pid = p.pid();

        return this;
    }

    /**
     * Execute a PowerShell command.
     * <p>
     * This method launch a thread which will be executed in the already created
     * PowerShell console context
     *
     * @param command the command to call. Ex: dir
     * @return PowerShellResponse the information returned by powerShell
     */
    public PowerShellResponse executeCommand(String command) {
        String commandOutput = "";
        boolean isError = false;
        boolean timeout = false;

        checkState();

        PowerShellCommandProcessor commandProcessor = new PowerShellCommandProcessor(this.outputReader, this.waitPause, this.scriptMode);
        Future<String> result = executorService.submit(commandProcessor);

        // Launch command
        commandWriter.println(command);

        try {
            if (!result.isDone()) {
                try {
                    commandOutput = result.get(maxWait, TimeUnit.MILLISECONDS);
                } catch (TimeoutException timeoutEx) {
                    timeout = true;
                    isError = true;
                    //Interrupt command after timeout
                    result.cancel(true);
                }
            }
        } catch (InterruptedException | ExecutionException ex) {
            logger.log(Level.SEVERE,
                    "Unexpected error when processing PowerShell command", ex);
            isError = true;
        } finally {
            // issue #2. Close and cancel processors/threads - Thanks to r4lly
            // for helping me here
            commandProcessor.close();
        }

        return new PowerShellResponse(isError, commandOutput, timeout);
    }

    public CompletableFuture<PowerShellResponse> executeCommandAsync(String command) {
        return CompletableFuture.supplyAsync(() -> executeCommand(command), executorService);
    }

    /**
     * Allows chaining command executions providing a more fluent API.
     * This method allows also to optionally handle the response in a closure
     *
     * @param command  the command to execute
     * @param response optionally, the response can be handled in a closure
     * @return The {@link PowerShell} instance
     */
    public PowerShell executeCommandAndChain(String command, PowerShellResponseHandler... response) {
        PowerShellResponse powerShellResponse = executeCommand(command);

        if (response.length > 0) {
            handleResponse(response[0], powerShellResponse);
        }

        return this;
    }

    // Handle response in callback way
    private void handleResponse(PowerShellResponseHandler response, PowerShellResponse powerShellResponse) {
        try {
            response.handle(powerShellResponse);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "PowerShell not available", ex);
        }
    }

    /**
     * Indicates if the last executed command finished in error
     *
     * @return boolean
     */
    public boolean isLastCommandInError() {
        return !Boolean.parseBoolean(executeCommand("$?").getCommandOutput());
    }

    /**
     * Executed the provided PowerShell script in PowerShell console and gets
     * result.
     *
     * @param scriptPath the full path of the script
     * @return response with the output of the command
     */
    public PowerShellResponse executeScript(String scriptPath) {
        return executeScript(scriptPath, "");
    }

    /**
     * Executed the provided PowerShell script in PowerShell console and gets
     * result.
     *
     * @param scriptPath the full path of the script
     * @param params     the parameters of the script
     * @return response with the output of the command
     */
    @SuppressWarnings("WeakerAccess")
    public PowerShellResponse executeScript(String scriptPath, String params) {
        try (BufferedReader srcReader = new BufferedReader(new FileReader(scriptPath))) {
            return executeScript(srcReader, params);
        } catch (FileNotFoundException fnfex) {
            logger.log(Level.SEVERE,
                    "Unexpected error when processing PowerShell script: file not found", fnfex);
            return new PowerShellResponse(true, "Wrong script path: " + scriptPath, false);
        } catch (IOException ioe) {
            logger.log(Level.SEVERE,
                    "Unexpected error when processing PowerShell script", ioe);
            return new PowerShellResponse(true, "IO error reading: " + scriptPath, false);
        }
    }

    /**
     * Execute the provided PowerShell script in PowerShell console and gets
     * result.
     *
     * @param srcReader the script as BufferedReader (when loading File from jar)
     * @return response with the output of the command
     */
    public PowerShellResponse executeScript(BufferedReader srcReader) {
        return executeScript(srcReader, "");
    }

    /**
     * Execute the provided PowerShell script in PowerShell console and gets
     * result.
     *
     * @param srcReader the script as BufferedReader (when loading File from jar)
     * @param params    the parameters of the script
     * @return response with the output of the command
     */
    public PowerShellResponse executeScript(BufferedReader srcReader, String params) {
        PowerShellResponse response;
        if (srcReader != null) {
            File tmpFile = createWriteTempFile(srcReader);
            if (tmpFile != null) {
                this.scriptMode = true;
                response = executeCommand(tmpFile.getAbsolutePath() + " " + params);
                this.scriptMode = false;
                tmpFile.delete();
            } else {
                response = new PowerShellResponse(true, "Cannot create temp script file!", false);
            }
        } else {
            logger.log(Level.SEVERE, "Script buffered reader is null!");
            response = new PowerShellResponse(true, "Script buffered reader is null!", false);
        }

        return response;
    }

    // Writes a temp powershell script file based on the srcReader
    private File createWriteTempFile(BufferedReader srcReader) {
        File tmpFile = null;
        try {
            tmpFile = File.createTempFile("psscript_" + System.currentTimeMillis(), ".ps1", this.tempFolder);

            try (BufferedWriter tmpWriter = new BufferedWriter(new FileWriter(tmpFile))) {
                srcReader.transferTo(tmpWriter);

                tmpWriter.newLine();
                tmpWriter.write("Write-Output \"" + END_SCRIPT_STRING + "\"");
            }
        } catch (IOException ioException) {
            logger.log(Level.SEVERE, "Unexpected error when processing temporary PowerShell script", ioException);
        }
        return tmpFile;
    }

    /**
     * Closes all the resources used to maintain the PowerShell context
     */
    @Override
    public void close() {
        if (!this.closed) {
            try {
                Future<String> closeTask = executorService.submit(() -> {
                    commandWriter.println("exit");
                    p.waitFor();
                    return "OK";
                });
                if (!closeAndWait(closeTask) && this.pid > 0) {
                    //If it can be closed, force kill the process
                    Logger.getLogger(PowerShell.class.getName()).log(Level.INFO,
                            "Forcing PowerShell to close. PID: " + this.pid);
                    try {
                        Runtime.getRuntime().exec("taskkill.exe /PID " + pid + " /F /T");
                        this.closed = true;
                    } catch (IOException e) {
                        Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE,
                                "Unexpected error while killing powershell process", e);
                    }
                }
            } catch (InterruptedException | ExecutionException ex) {
                logger.log(Level.SEVERE,
                        "Unexpected error when when closing PowerShell", ex);
            } finally {
                commandWriter.close();
                try {
                    if (p.isAlive()) {
                        outputReader.close();
                    }
                } catch (IOException ex) {
                    logger.log(Level.SEVERE,
                            "Unexpected error when when closing streams", ex);
                }
                if (this.executorService != null) {
                    try {
                        this.executorService.shutdownNow();
                        this.executorService.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        logger.log(Level.SEVERE,
                                "Unexpected error when when shutting down thread pool", ex);
                    }

                }
                this.closed = true;
            }
        }
    }

    private boolean closeAndWait(Future<String> task) throws InterruptedException, ExecutionException {
        boolean closed = true;
        if (!task.isDone()) {
            try {
                task.get(maxWait, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeoutEx) {
                logger.log(Level.WARNING,
                        "Powershell process cannot be closed. Session seems to be blocked");
                //Interrupt command after timeout
                task.cancel(true);
                closed = false;
            }
        }
        return closed;
    }

    //Checks if PowerShell have been already closed
    private void checkState() {
        if (this.closed) {
            throw new IllegalStateException("PowerShell is already closed. Please open a new session.");
        }
    }

    //Return the temp folder File object or null if the path does not exist
    private File getTempFolder(String tempPath) {
        if (tempPath != null) {
            File folder = new File(tempPath);
            if (folder.exists()) {
                return folder;
            }
        }
        return null;
    }
}
