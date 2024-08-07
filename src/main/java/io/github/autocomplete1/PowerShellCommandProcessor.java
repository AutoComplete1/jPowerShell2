/*
 * Copyright 2016-2018 Javier Garcia Alonso.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processor used to send commands to PowerShell console.<p>
 * It works as an independent thread and its results are collected using the Future interface.
 *
 * @author Javier Garcia Alonso
 */
class PowerShellCommandProcessor implements Callable<String> {

    private static final String CRLF = "\r\n";

    private boolean closed = false;

    private final BufferedReader outputReader;

    private final boolean scriptMode;

    private final int waitPause;

    /**
     * Constructor that takes the output and the input of the PowerShell session
     *
     * @param outputReader outputReader of the powershell session
     * @param waitPause    long the wait pause in milliseconds
     * @param scriptMode   boolean indicates if the command executes a script
     */
    public PowerShellCommandProcessor(BufferedReader outputReader, int waitPause, boolean scriptMode) {
        this.outputReader = outputReader;
        this.waitPause = waitPause;
        this.scriptMode = scriptMode;
    }

    /**
     * Calls the command and returns its output
     *
     * @return String output of call
     * @throws InterruptedException error when reading data
     */
    public String call() throws InterruptedException {
        StringBuilder powerShellOutput = new StringBuilder();

        try {
            if (startReading()) {
                readData(powerShellOutput);
            }
        } catch (IOException ioe) {
            Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE, "Unexpected error reading PowerShell output", ioe);
            return ioe.getMessage();
        }

        //Remove last CRLF from result
        return powerShellOutput.toString().replaceAll("\\s+$", "");
    }

    //Reads all data from output
    private void readData(StringBuilder powerShellOutput) throws IOException {
        String line;
        while (null != (line = this.outputReader.readLine())) {

            //In the case of script mode it finishes when the last line is read
            if (this.scriptMode) {
                if (line.equals(PowerShell.END_SCRIPT_STRING)) {
                    break;
                }
            }

            powerShellOutput.append(line).append(CRLF);

            //When not in script mode, it exits when the command is finished
            if (!this.scriptMode) {
                try {
                    if (this.closed || !canContinueReading()) {
                        break;
                    }
                } catch (InterruptedException ex) {
                    Logger.getLogger(PowerShellCommandProcessor.class.getName()).log(Level.SEVERE, "Error executing command and reading result", ex);
                }
            }
        }
    }

    //Checks when we can start reading the output. Timeout if it takes too long in order to avoid hangs
    private boolean startReading() throws IOException, InterruptedException {
        //If the reader is not ready, gives it some milliseconds
        while (!this.outputReader.ready()) {
            synchronized (this) {
                wait(this.waitPause);
            }
            if (this.closed) {
                return false;
            }
        }
        return true;
    }

    //Checks when we have the reader can continue to read.
    private boolean canContinueReading() throws IOException, InterruptedException {
        //If the reader is not ready, gives it some milliseconds
        //It is important to do that, because the ready method guarantees that the readline will not be blocking
        if (!this.outputReader.ready()) {
            synchronized (this) {
                wait(this.waitPause);
            }
        }

        //If not ready yet, wait a moment to make sure it is finished
        if (!this.outputReader.ready()) {
            synchronized (this) {
                wait(50);
            }
        }

        return this.outputReader.ready();
    }

    /**
     * Closes the command processor, canceling the current work if not finish
     */
    public void close() {
        this.closed = true;
    }
}
