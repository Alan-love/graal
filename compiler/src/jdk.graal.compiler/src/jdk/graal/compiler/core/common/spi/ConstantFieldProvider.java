/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.core.common.spi;

import jdk.graal.compiler.options.OptionValues;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * Implements the logic that decides whether a field read should be constant folded.
 */
public interface ConstantFieldProvider {

    interface ConstantFieldTool<T> {

        OptionValues getOptions();

        JavaConstant readValue();

        JavaConstant getReceiver();

        /**
         * The reason why this constant folding was attempted. Ideally this is a
         * {@link jdk.vm.ci.code.BytecodePosition}, where available, or a {@link String}
         * description, however it can be {@code null}.
         */
        Object getReason();

        T foldConstant(JavaConstant ret);

        T foldStableArray(JavaConstant ret, int stableDimensions, boolean isDefaultStable);
    }

    /**
     * Decide whether a read from the {@code field} should be constant folded. This should return
     * {@link ConstantFieldTool#foldConstant} or {@link ConstantFieldTool#foldStableArray} if the
     * read should be constant folded, or {@code null} otherwise.
     */
    <T> T readConstantField(ResolvedJavaField field, ConstantFieldTool<T> tool);

    /**
     * Returns {@code true} if a field may be constant folded even though it is not declared as
     * {@code final}. This applies to well-known fields such as {@code String#hash}.
     */
    default boolean maybeFinal(@SuppressWarnings("unused") ResolvedJavaField field) {
        return false;
    }
}
