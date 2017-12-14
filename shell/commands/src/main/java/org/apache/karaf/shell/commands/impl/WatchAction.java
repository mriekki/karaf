/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.commands.impl;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.Parsing;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.api.console.CommandLine;
import org.apache.karaf.shell.api.console.Completer;
import org.apache.karaf.shell.api.console.Parser;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.api.console.SessionFactory;
import org.apache.karaf.shell.commands.impl.WatchAction.WatchParser;
import org.apache.karaf.shell.support.ShellUtil;
import org.apache.karaf.shell.support.completers.CommandsCompleter;
import org.apache.karaf.shell.support.parsing.CommandLineImpl;
import org.apache.karaf.shell.support.parsing.DefaultParser;

@Command(scope = "shell", name = "watch", description = "Watches & refreshes the output of a command")
@Parsing(WatchParser.class)
@Service
public class WatchAction implements Action {

    @Option(name = "-n", aliases = {"--interval"}, description = "The interval between executions of the command in seconds", required = false, multiValued = false)
    private long interval = 1;
    
    @Option(name = "-a", aliases = {"--append"}, description = "The output should be appended but not clear the console", required = false, multiValued = false)
    private boolean append = false;

    @Argument(index = 0, name = "command", description = "The command to watch / refresh", required = true, multiValued = false)
    @Completion(SubCommandCompleter.class)
    private String arguments;

    @Reference
    Session session;

    @Reference
    SessionFactory sessionFactory;

    private ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    @Override
    public Object execute() throws Exception {
        if (arguments == null || arguments.length() == 0) {
            System.err.println("Argument expected");
        } else {
            WatchTask watchTask = new WatchTask(arguments.trim());
            executorService.scheduleAtFixedRate(watchTask, 0, interval, TimeUnit.SECONDS);
            try {
                session.getKeyboard().read();
                watchTask.abort();
            } finally {
                executorService.shutdownNow();
                watchTask.close();
            }
        }
        return null;
    }

    public class WatchTask implements Runnable {

        private final String command;

        Session session;
        ByteArrayOutputStream byteArrayOutputStream = null;
        PrintStream printStream = null;
        boolean doDisplay = true;

        public WatchTask(String command) {
            this.command = command;
        }

        public void run() {
            try {
                byteArrayOutputStream = new ByteArrayOutputStream();
                printStream = new PrintStream(byteArrayOutputStream);
                InputStream is = new ByteArrayInputStream(new byte[0]);
                session = sessionFactory.create(is, printStream, printStream, WatchAction.this.session);
                String output = "";
                try {
                    session.execute(command);
                } catch (Exception e) {
                    ShellUtil.logException(session, e);
                }
                output = byteArrayOutputStream.toString();
                if (doDisplay) {
                    if (!append) {
                        System.out.print("\33[2J");
                        System.out.print("\33[1;1H");
                    }
                    System.out.print(output);
                    System.out.flush();
                }
                byteArrayOutputStream.close();
                session.close();
            } catch (Exception e) {
                // ignore
            }
        }

        public void abort() {
            doDisplay = false;
        }

        public void close() throws IOException {
            if (this.session != null) {
                this.session.close();
            }
            if (this.byteArrayOutputStream != null) {
                this.byteArrayOutputStream.close();
            }
            if (this.printStream != null) {
                printStream.close();
            }
        }
    }

    @Service
    public static class WatchParser implements Parser {
        @Override
        public CommandLine parse(Session session, String command, int cursor) {
            int n1 = 0;
            while (n1 < command.length() && Character.isWhitespace(command.charAt(n1))) {
                n1++;
                if (n1 == command.length()) {
                    throw new IllegalArgumentException();
                }
            }
            int n2 = n1 + 1;
            while (!Character.isWhitespace(command.charAt(n2))) {
                n2++;
                if (n2 == command.length()) {
                    return new CommandLineImpl(
                            new String[]{command.substring(n1)},
                            0,
                            cursor - n1,
                            cursor,
                            command
                    );
                }
            }
            int n3 = n2 + 1;
            while (n3 < command.length() && Character.isWhitespace(command.charAt(n3))) {
                n3++;
                if (n3 == command.length()) {
                    return new CommandLineImpl(
                            new String[]{command.substring(n1, n2), ""},
                            cursor >= n2 ? 1 : 0,
                            cursor >= n2 ? 0 : cursor - n1,
                            cursor,
                            command
                    );
                }
            }
            return new CommandLineImpl(
                    new String[]{command.substring(n1, n2), command.substring(n3)},
                    cursor >= n3 ? 1 : 0,
                    cursor >= n3 ? cursor - n3 : cursor - n1,
                    cursor,
                    command
            );
        }

        @Override
        public String preprocess(Session session, CommandLine cmdLine) {
            StringBuilder parsed = new StringBuilder();
            for (int i = 0; i < cmdLine.getArguments().length; i++) {
                String arg = cmdLine.getArguments()[i];
                if (i > 0) {
                    parsed.append(" \"");
                }
                parsed.append(arg);
                if (i > 0) {
                    parsed.append("\"");
                }
            }
            return parsed.toString();
        }
    }

    @Service
    public static class SubCommandCompleter implements Completer {

        @Override
        public int complete(Session session, CommandLine commandLine, List<String> candidates) {
            String arg = commandLine.getCursorArgument();
            int pos = commandLine.getArgumentPosition();
            CommandLine cmdLine = new DefaultParser().parse(session, arg, pos);
            Completer completer = session.getRegistry().getService(CommandsCompleter.class);
            List<String> cands = new ArrayList<>();
            int res = completer.complete(session, cmdLine, cands);
            for (String cand : cands) {
                candidates.add(arg.substring(0, cmdLine.getBufferPosition() - cmdLine.getArgumentPosition()) + cand);
            }
            if (res >= 0) {
                res += commandLine.getBufferPosition() - commandLine.getArgumentPosition();
            }
            return res;
        }

    }

}
