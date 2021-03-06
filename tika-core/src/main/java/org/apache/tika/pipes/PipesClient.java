/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.pipes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.tika.pipes.async.AsyncConfig;
import org.apache.tika.pipes.emitter.EmitData;
import org.apache.tika.utils.ProcessUtils;

public class PipesClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(PipesClient.class);

    private Process process;
    private final PipesConfigBase pipesConfig;
    private DataOutputStream output;
    private DataInputStream input;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    public PipesClient(PipesConfigBase pipesConfig) {
        this.pipesConfig = pipesConfig;
    }

    private int filesProcessed = 0;

    public int getFilesProcessed() {
        return filesProcessed;
    }

    private boolean ping() {
        if (process == null || ! process.isAlive()) {
            return false;
        }
        try {
            output.write(PipesServer.PING);
            output.flush();
            int ping = input.read();
            if (ping == PipesServer.PING) {
                return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        if (process != null) {
            process.destroyForcibly();
        }
        executorService.shutdownNow();
    }

    public PipesResult process(FetchEmitTuple t) throws IOException {
        if (! ping()) {
            restart();
        }
        if (pipesConfig.getMaxFilesProcessed() > 0 &&
                filesProcessed >= pipesConfig.getMaxFilesProcessed()) {
            LOG.info("restarting server after hitting max files: " + filesProcessed);
            restart();
        }
        return actuallyProcess(t);
    }

    private PipesResult actuallyProcess(FetchEmitTuple t) {
        long start = System.currentTimeMillis();
        FutureTask<PipesResult> futureTask = new FutureTask<>(() -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bos)) {
                objectOutputStream.writeObject(t);
            }
            byte[] bytes = bos.toByteArray();
            output.write(PipesServer.CALL);
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();

            return readResults(t, start);
        });

        try {
            executorService.execute(futureTask);
            return futureTask.get(pipesConfig.getTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            return PipesResult.INTERRUPTED_EXCEPTION;
        } catch (ExecutionException e) {
            long elapsed = System.currentTimeMillis() - start;
            destroyWithPause();
            if (!process.isAlive() && PipesServer.TIMEOUT_EXIT_CODE == process.exitValue()) {
                LOG.warn("server timeout: {} in {} ms", t.getId(), elapsed);
                return PipesResult.TIMEOUT;
            }
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS);
                if (process.isAlive()) {
                    LOG.warn("crash: {} in {} ms with no exit code available", t.getId(), elapsed);
                } else {
                    LOG.warn("crash: {} in {} ms with exit code {}", t.getId(), elapsed, process.exitValue());
                }
            } catch (InterruptedException interruptedException) {
                //swallow
            }
            return PipesResult.UNSPECIFIED_CRASH;
        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            process.destroyForcibly();
            LOG.warn("client timeout: {} in {} ms", t.getId(), elapsed);
            return PipesResult.TIMEOUT;
        } finally {
            futureTask.cancel(true);
        }
    }

    private void destroyWithPause() {
        //wait just a little bit to let process end to get exit value
        //if there's a timeout on the server side
        try {
            process.waitFor(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            //swallow
        } finally {
            process.destroyForcibly();
        }

    }

    private PipesResult readResults(FetchEmitTuple t, long start) throws IOException {
        int status = input.read();
        long millis = System.currentTimeMillis() - start;
        switch (status) {
            case PipesServer.OOM:
                LOG.warn("oom: {} in {} ms", t.getId(), millis);
                return PipesResult.OOM;
            case PipesServer.TIMEOUT:
                LOG.warn("server response timeout: {} in {} ms", t.getId(), millis);
                return PipesResult.TIMEOUT;
            case PipesServer.EMIT_EXCEPTION:
                LOG.warn("emit exception: {} in {} ms", t.getId(), millis);
                return readMessage(PipesResult.STATUS.EMIT_EXCEPTION);
            case PipesServer.NO_EMITTER_FOUND:
                LOG.warn("no emitter found: " + t.getId());
                return PipesResult.NO_EMITTER_FOUND;
            case PipesServer.PARSE_SUCCESS:
            case PipesServer.PARSE_EXCEPTION_EMIT:
                LOG.info("parse success: {} in {} ms", t.getId(), millis);
                return deserializeEmitData();
            case PipesServer.PARSE_EXCEPTION_NO_EMIT:
                return readMessage(PipesResult.STATUS.PARSE_EXCEPTION_NO_EMIT);
            case PipesServer.EMIT_SUCCESS:
                LOG.info("emit success: {} in {} ms", t.getId(), millis);
                return PipesResult.EMIT_SUCCESS;
            case PipesServer.EMIT_SUCCESS_PARSE_EXCEPTION:
                return readMessage(PipesResult.STATUS.EMIT_SUCCESS_PARSE_EXCEPTION);
            default :
                throw new IOException("problem reading response from server " + status);
        }

    }

    private PipesResult readMessage(PipesResult.STATUS status) throws IOException {
        int length = input.readInt();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String msg = new String(bytes, StandardCharsets.UTF_8);
        return new PipesResult(status, msg);
    }

    private PipesResult deserializeEmitData() throws IOException {
        int length = input.readInt();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try (ObjectInputStream objectInputStream =
                     new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return new PipesResult((EmitData)objectInputStream.readObject());
        } catch (ClassNotFoundException e) {
            LOG.error("class not found exception deserializing data", e);
            //this should be catastrophic
            throw new RuntimeException(e);
        }

    }

    private void restart() throws IOException {
        if (process != null) {
            process.destroyForcibly();
            LOG.info("restarting process");
        } else {
            LOG.info("starting process");
        }
        ProcessBuilder pb = new ProcessBuilder(getCommandline());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        input = new DataInputStream(process.getInputStream());
        output = new DataOutputStream(process.getOutputStream());
        //wait for ready signal
        FutureTask<Integer> futureTask = new FutureTask<>(() -> {
            int b = input.read();
            if (b != PipesServer.READY) {
                throw new RuntimeException("Couldn't start server: " + b +
                        " Make absolutely certain that your logger is not writing to stdout.");
            }
            return 1;
        });
        executorService.submit(futureTask);
        try {
            futureTask.get(pipesConfig.getStartupTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            return;
        } catch (ExecutionException e) {
            LOG.error("couldn't start server", e);
            process.destroyForcibly();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            LOG.error("couldn't start server in time", e);
            process.destroyForcibly();
            throw new RuntimeException(e);
        } finally {
            futureTask.cancel(true);
        }
    }

    private String[] getCommandline() {
        List<String> configArgs = pipesConfig.getForkedJvmArgs();
        boolean hasClassPath = false;
        boolean hasHeadless = false;
        boolean hasExitOnOOM = false;
        boolean hasLog4j = false;
        for (String arg : configArgs) {
            if (arg.startsWith("-Djava.awt.headless")) {
                hasHeadless = true;
            }
            if (arg.equals("-cp") || arg.equals("--classpath")) {
                hasClassPath = true;
            }
            if (arg.equals("-XX:+ExitOnOutOfMemoryError") ||
                    arg.equals("-XX:+CrashOnOutOfMemoryError")) {
                hasExitOnOOM = true;
            }
            if (arg.startsWith("-Dlog4j.configuration")) {
                hasLog4j = true;
            }
        }

        List<String> commandLine = new ArrayList<>();
        String javaPath = pipesConfig.getJavaPath();
        commandLine.add(ProcessUtils.escapeCommandLine(javaPath));
        if (! hasClassPath) {
            commandLine.add("-cp");
            commandLine.add(System.getProperty("java.class.path"));
        }
        if (! hasHeadless) {
            commandLine.add("-Djava.awt.headless=true");
        }
        if (! hasExitOnOOM) {
            //warn
        }
        if (! hasLog4j) {
            commandLine.add("-Dlog4j.configurationFile=classpath:pipes-fork-server-default-log4j2.xml");
        }
        commandLine.addAll(configArgs);
        commandLine.add("org.apache.tika.pipes.PipesServer");
        commandLine.add(
                ProcessUtils.escapeCommandLine(pipesConfig.getTikaConfig().toAbsolutePath().toString()));

        //turn off emit batching
        String maxForEmitBatchBytes = "0";
        if (pipesConfig instanceof AsyncConfig) {
            maxForEmitBatchBytes =
                    Long.toString(((AsyncConfig)pipesConfig).getMaxForEmitBatchBytes());
        }
        commandLine.add(maxForEmitBatchBytes);
        commandLine.add(Long.toString(pipesConfig.getTimeoutMillis()));
        commandLine.add(Long.toString(pipesConfig.getShutdownClientAfterMillis()));
        LOG.debug("commandline: " + commandLine);
        return commandLine.toArray(new String[0]);
    }
}
