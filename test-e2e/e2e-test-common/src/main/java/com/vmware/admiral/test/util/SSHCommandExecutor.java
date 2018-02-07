/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.test.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.LocalSourceFile;

public class SSHCommandExecutor {

    private final String target;
    private final String username;
    private final String password;
    private final int port;

    public SSHCommandExecutor(AuthContext auth, int port) {
        this.target = auth.getTarget();
        this.username = auth.getUsername();
        this.password = auth.getPassword();
        this.port = port;
    }

    public CommandResult execute(String command, int timeoutSeconds) {
        try {
            SSHClient client = new SSHClient();
            client.addHostKeyVerifier((a, b, c) -> true);
            client.connect(target, port);
            try {
                client.authPassword(username, password);
                Session session = client.startSession();
                try {
                    Command cmd = session.exec(command);
                    cmd.join(timeoutSeconds, TimeUnit.SECONDS);
                    cmd.close();
                    String output = IOUtils.readFully(cmd.getInputStream()).toString().trim();
                    String error = IOUtils.readFully(cmd.getErrorStream()).toString().trim();
                    int exitStatus = cmd.getExitStatus();
                    CommandResult result = new CommandResult(exitStatus, output, error);
                    return result;
                } finally {
                    session.close();
                }
            } finally {
                client.disconnect();
                client.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendFile(File file,
            String remotePath) throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier((a, b, c) -> true);
        client.connect(target, port);
        try {
            client.authPassword(username, password);
            client.useCompression();
            LocalSourceFile localFile = new FileSystemFile(file);
            client.newSCPFileTransfer().upload(localFile, remotePath);
        } finally {
            client.disconnect();
            client.close();
        }
    }

    public static class CommandResult {

        private final String output;
        private final int exitStatus;
        private final String errorOutput;

        private CommandResult(int exitStatus, String output, String errorOutput) {
            this.exitStatus = exitStatus;
            this.output = output;
            this.errorOutput = errorOutput;
        }

        public String getOutput() {
            return output;
        }

        public int getExitStatus() {
            return exitStatus;
        }

        public String getErrorOutput() {
            return errorOutput;
        }

    }

}
