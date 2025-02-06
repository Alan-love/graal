/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package org.graalvm.visualizer.source.impl.ui;

import org.graalvm.visualizer.source.Language;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileStateInvalidException;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.ImageDecorator;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.util.Exceptions;
import org.openide.util.lookup.Lookups;

import java.awt.Image;
import java.beans.BeanInfo;
import java.util.Collections;

/**
 * @author sdedic
 */
public class LanguageNode extends AbstractNode {
    private final Language lang;
    private Image icon;
    private FileObject iconFile;

    public LanguageNode(Language l) {
        super(Children.LEAF, Lookups.fixed(l));
        this.lang = l;

        setDisplayName(lang.getDisplayName());
        setName(lang.getGraalID());

        if (lang.isHostLanguage()) {
            setIconBaseWithExtension("org/graalvm/visualizer/source/resources/lang-truffle.png"); // NOI18N
            iconFile = null;
        } else {
            FileObject f = FileUtil.getConfigFile("IGV/Languages").getFileObject(lang.getGraalID());
            if (f == null) {
                setIconBaseWithExtension("org/graalvm/visualizer/source/resources/lang-guest.png"); // NOI18N
            }
            iconFile = f;
        }
    }

    @Override
    public Image getOpenedIcon(int type) {
        if (icon != null) {
            return icon;
        }
        return super.getOpenedIcon(type);
    }

    @Override
    public Image getIcon(int type) {
        if (icon != null) {
            return icon;
        }
        boolean set = false;
        if (iconFile != null) {
            try {
                ImageDecorator deco = (ImageDecorator) iconFile.getFileSystem().getDecorator();
                Image ico = deco.annotateIcon(null, type, Collections.singleton(iconFile));
                if (ico != null) {
                    return ico;
                }
                setIconBaseWithExtension("org/graalvm/visualizer/source/resources/lang-guest.png"); // NOI18N
                iconFile = null;
            } catch (FileStateInvalidException ex) {
                Exceptions.printStackTrace(ex);
                set = true;
            }
        }
        Image i = super.getIcon(BeanInfo.ICON_COLOR_16x16);
        if (set) {
            icon = i;
        }
        return i;
    }
}
