/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.test;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.elasticsearch.gradle.internal.AntTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.AntBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A fixture for integration tests which runs in a separate process launched by Ant.
 */
class AntFixture extends AntTask implements Fixture {

    /** The path to the executable that starts the fixture. */
    @Internal
    String executable;

    private final List<String> arguments = new ArrayList<>();

    void args(String... args) {
        arguments.addAll(List.of(args));
    }

    /**
     * Environment variables for the fixture process. The value can be any object, which
     * will have toString() called at execution time.
     */
    private final Map<String, Object> environment = new HashMap<>();

    void env(String key, Object value) {
        environment.put(key, value);
    }

    /** A flag to indicate whether the command should be executed from a shell. */
    @Internal
    boolean useShell = false;

    @Internal
    int maxWaitInSeconds = 30;

    /**
     * A flag to indicate whether the fixture should be run in the foreground, or spawned.
     * It is protected so subclasses can override (eg RunTask).
     */
    protected boolean spawn = true;

    /**
     * A closure to call before the fixture is considered ready. The closure is passed the fixture object,
     * as well as a groovy AntBuilder, to enable running ant condition checks. The default wait
     * condition is for http on the http port.
     */
    @Internal
    boolean waitCondition(AntFixture fixture, AntBuilder ant ) throws IOException {
        File tmpFile = new File(fixture.getCwd(), "wait.success");
        Map<String, String> args = Map.of(
            "src", String.format("http://%s", fixture.getAddressAndPort()),
            "dest", tmpFile.toString(),
            "ignoreerrors", "true", // do not fail on error, so logging information can be flushed\n" +
            "retries", "10"
        );
        ant.invokeMethod("get", args);

        return tmpFile.exists();
    }

    private final TaskProvider<AntFixtureStop> stopTask;

    AntFixture() {
        stopTask = createStopTask();
        finalizedBy(stopTask);
    }

    @Override
    @Internal
    public TaskProvider<AntFixtureStop> getStopTask() {
        return stopTask;
    }

    @Override
    protected void runAnt(AntBuilder ant) {
        // reset everything
        try {
            getFileSystemOperations().delete((ops) ->
                ops.delete(getBaseDir())
            );
            if (! getCwd().mkdirs() ) {
                throw new RuntimeException("failed to create needed directories");
            }
            final String realExecutable;
            // We need to choose which executable we are using. In shell mode, or when we
            // are spawning and thus using the wrapper script, the executable is the shell.
            if (useShell || spawn) {
                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    realExecutable = "cmd";
                } else {
                    realExecutable = "sh";
                }
            } else {
                realExecutable = executable;
            }
            if (spawn) {
                writeWrapperScript(executable);
            }
            Stream<String> lines = getCommandString().lines();
            lines.forEach((line) -> getLogger().info(line));

            Map<String, String> args = Map.of(
                "executable", realExecutable, "spawn", Boolean.toString(spawn), "dir", getCwd().toString(), "taskname", getName()
            );
            ant.invokeMethod("exec", args);

            String failedProp = "failed${name}";
            // first wait for resources, or the failure marker from the wrapper script
            Map<String, String> waitArgs = Map.of(
                "maxwait", Integer.toString(maxWaitInSeconds),
                "maxwaitunit", "second",
                "checkevery", "500",
                "checkeveryunit", "millisecond",
                "timeoutproperty", failedProp
            );

            ant.invokeMethod("waitfor", waitArgs);

            if (ant.getProject().getProperty(failedProp) != null || getFailureMarker().exists()) {
                fail(String.format("Failed to start %s", getName()));
            }

            // the process is started (has a pid) and is bound to a network interface
            // so now evaluates if the waitCondition is successful
            // TODO: change this to a loop?
            boolean success;
            try {
                success = waitCondition(this, ant);
                if (success == false) {
                    fail(String.format("Wait condition failed for %s", getName()));
                }
            } catch (Exception e) {
                String msg = "Wait condition caught exception for ${name}";
                getLogger().error(msg, e);
                fail(msg, e);
            }
        } catch (IOException e) {
            getLogger().error("got an error when running ant task", e);
        }
    }

    /** Returns a debug string used to log information about how the fixture was run. */
    @Internal
    protected String getCommandString() throws IOException {
        StringBuilder commandString = new StringBuilder("%n");
        commandString.append(String.format("%s configuration:%n", getName()));
        commandString.append("-----------------------------------------%n");
        commandString.append(String.format("  cwd: %s%n", getCwd()));
        commandString.append(String.format("  command: %s %s%n", executable, String.join(" ", arguments)));
        commandString.append("  environment:%n");
        environment.forEach((k,v) -> commandString.append(String.format("    %s: %s%n", k, v)));

        if (spawn) {
            commandString.append("%n  [${wrapperScript.name}]%n");
            try(BufferedReader br = new BufferedReader(new FileReader(getWrapperScript()))) {
                br.lines().forEach((String line) -> commandString.append(String.format("    %s%n", line)));
            }
        }
        return commandString.toString();
    }

    /**
     * Writes a script to run the real executable, so that stdout/stderr can be captured.
     * TODO: this could be removed if we do use our own ProcessBuilder and pump output from the process
     */
    private void writeWrapperScript(String executable) throws IOException {
        if (! getWrapperScript().getParentFile().mkdirs() ) {
            throw new IOException("could not create required directories");
        }

        String argsPasser = "\"$@\"";
        String exitMarker = "; if [ \\$? != 0 ]; then touch run.failed; fi";
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            argsPasser = "%*";
            exitMarker = "\r\n if \"%errorlevel%\" neq \"0\" ( type nul >> run.failed )";
        }
        try(BufferedWriter writer = new BufferedWriter(new FileWriter(getWrapperScript()))) {
            writer.write(String.format("\"%s\" %s > run.log 2>&1 %s", executable, argsPasser, exitMarker));
        }
    }

    /** Fail the build with the given message, and logging relevant info*/
    private void fail(String msg, Exception... suppressed) throws IOException {
        if (getLogger().isInfoEnabled() == false) {
            // We already log the command at info level. No need to do it twice.
            Stream<String> lines = getCommandString().lines();
            lines.forEach(line -> getLogger().error(line));
        }
        getLogger().error("${name} output:");
        getLogger().error("-----------------------------------------");
        getLogger().error("  failure marker exists: ${failureMarker.exists()}");
        getLogger().error("  pid file exists: ${pidFile.exists()}");
        getLogger().error("  ports file exists: ${portsFile.exists()}");
        // also dump the log file for the startup script (which will include ES logging output to stdout)
        if (getRunLog().exists()) {
            getLogger().error("\n  [log]");
            try(BufferedReader br = new BufferedReader(new FileReader(getRunLog()))) {
                br.lines().forEach(line-> getLogger().error(String.format("    %s", line)));
            }
        }

        getLogger().error("-----------------------------------------");
        GradleException toThrow = new GradleException(msg);
        for (Exception e : suppressed) {
            toThrow.addSuppressed(e);
        }
        throw toThrow;
    }

    /** Adds a task to kill an elasticsearch node with the given pidfile */
    private final TaskProvider<AntFixtureStop> createStopTask() {
        final AntFixture fixture = this;
        TaskProvider<AntFixtureStop> stop = getProject().getTasks().register("${name}#stop", AntFixtureStop.class);
        stop.configure(t -> t.setFixture(fixture));

        fixture.finalizedBy(stop);
        return stop;
    }

    /**
     * A path relative to the build dir that all configuration and runtime files
     * will live in for this fixture
     */
    @Internal
    protected File getBaseDir() {
        return new File(getProject().getBuildDir(), "fixtures/${name}");
    }

    /** Returns the working directory for the process. Defaults to "cwd" inside baseDir. */
    @Internal
    protected File getCwd() {
        return new File(getBaseDir(), "cwd");
    }

    /** Returns the file the process writes its pid to. Defaults to "pid" inside baseDir. */
    @Internal
    protected File getPidFile() {
        return new File(getBaseDir(), "pid");
    }

    /** Reads the pid file and returns the process' pid */
    @Internal
    public int getPid() throws IOException {
        String pid;
        try(BufferedReader br = new BufferedReader(new FileReader(getPidFile()))) {
            pid = br.readLine();
            if (pid == null) {
                throw new RuntimeException("error reading pid file");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Integer.parseInt(pid);
    }

    /** Returns the file the process writes its bound ports to. Defaults to "ports" inside baseDir. */
    @Internal
    protected File getPortsFile() {
        return new File(getBaseDir(), "ports");
    }

    /** Returns an address and port suitable for a uri to connect to this node over http */
    @Internal
    String getAddressAndPort() throws IOException {
        String rv;
        try(BufferedReader br = new BufferedReader(new FileReader(getPortsFile()))) {
            rv = br.readLine();
        }

        return rv;
    }

    /** Returns a file that wraps around the actual command when {@code spawn == true}. */
    @Internal
    protected File getWrapperScript() {
        return new File(getCwd(), Os.isFamily(Os.FAMILY_WINDOWS) ? "run.bat" : "run");
    }

    /** Returns a file that the wrapper script writes when the command failed. */
    @Internal
    protected File getFailureMarker() {
        return new File(getCwd(), "run.failed");
    }

    /** Returns a file that the wrapper script writes when the command failed. */
    @Internal
    protected File getRunLog() {
        return new File(getCwd(), "run.log");
    }
}
