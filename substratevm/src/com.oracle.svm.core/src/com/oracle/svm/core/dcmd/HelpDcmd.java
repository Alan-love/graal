/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, 2024, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.core.dcmd;

import org.graalvm.nativeimage.ImageSingletons;

public class HelpDcmd extends AbstractDcmd {

    public HelpDcmd() {
        this.options = new DcmdOption[]{new DcmdOption("command name", "The name of the command for which we want help", false, null)};
        this.examples = new String[]{
                        "$ jcmd <pid> help JFR.stop",
                        "$ jcmd <pid> help VM.native_memory"
        };
        this.name = "help";
        this.description = "For more information about a specific command use 'help <command>'. With no argument this will show a list of available commands.";
        this.impact = "low";
    }

    @Override
    public String parseAndExecute(String[] arguments) throws DcmdParseException {
        String commandName = null;
        if (arguments.length > 1) {
            commandName = arguments[1];
        }
        if (arguments.length > 2) {
            throw new DcmdParseException("Too many arguments specified");
        }

        if (commandName == null) {
            String[] commands = ImageSingletons.lookup(DcmdSupport.class).getRegisteredCommands();
            StringBuilder sb = new StringBuilder();
            for (String command : commands) {
                sb.append(command).append("\n");
            }
            sb.append(getName()).append("\n");
            return sb.toString();
        } else {
            Dcmd dcmd = ImageSingletons.lookup(DcmdSupport.class).getDcmd(commandName);
            if (dcmd == null) {
                throw new DcmdParseException("Specified command was not found: " + commandName);
            }
            return dcmd.printHelp();
        }
    }
}
