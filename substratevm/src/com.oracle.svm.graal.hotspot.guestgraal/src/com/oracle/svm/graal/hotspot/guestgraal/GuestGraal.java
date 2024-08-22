/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.graal.hotspot.guestgraal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.oracle.svm.core.heap.UnknownObjectField;
import com.oracle.svm.core.option.RuntimeOptionValues;
import com.oracle.svm.core.option.XOptions;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.graal.hotspot.LibGraalJNIMethodScope;
import com.oracle.svm.util.ClassUtil;
import com.oracle.truffle.compiler.TruffleCompilerOptionDescriptor;
import com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id;
import jdk.graal.compiler.options.OptionDescriptor;
import jdk.graal.compiler.options.OptionDescriptors;
import jdk.graal.compiler.options.OptionDescriptorsMap;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.options.OptionsParser;
import org.graalvm.collections.EconomicMap;
import org.graalvm.jniutils.HSObject;
import org.graalvm.jniutils.JNI;
import org.graalvm.jniutils.JNI.JByteArray;
import org.graalvm.jniutils.JNI.JClass;
import org.graalvm.jniutils.JNI.JNIEnv;
import org.graalvm.jniutils.JNI.JObject;
import org.graalvm.jniutils.JNI.JObjectArray;
import org.graalvm.jniutils.JNI.JString;
import org.graalvm.jniutils.JNIExceptionWrapper;
import org.graalvm.jniutils.JNIMethodScope;
import org.graalvm.jniutils.JNIUtil;
import org.graalvm.nativebridge.BinaryOutput;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandles;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPoint.Builtin;
import org.graalvm.nativeimage.c.function.CEntryPoint.IsolateContext;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.impl.IsolateSupport;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.heap.Heap;
import com.sun.management.ThreadMXBean;

import jdk.internal.misc.Unsafe;

import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.DoCompile;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetCompilerConfigurationFactoryName;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetCompilerVersion;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetDataPatchesCount;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetExceptionHandlersCount;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetInfopoints;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetInfopointsCount;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetMarksCount;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetNodeCount;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetNodeTypes;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetSuppliedString;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetTargetCodeSize;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.GetTotalFrameSize;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.InitializeCompiler;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.InitializeRuntime;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.InstallTruffleCallBoundaryMethod;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.NewCompiler;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.PendingTransferToInterpreterOffset;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.PurgePartialEvaluationCaches;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.RegisterRuntime;
import static com.oracle.truffle.compiler.hotspot.libgraal.TruffleToLibGraal.Id.Shutdown;
import static org.graalvm.jniutils.JNIUtil.NewObjectArray;
import static org.graalvm.jniutils.JNIUtil.SetObjectArrayElement;
import static org.graalvm.jniutils.JNIUtil.createHSString;

/**
 * Encapsulates {@link CEntryPoint} implementations as well as method handles for invoking guest
 * Graal and JVMCI functionality via {@link MethodHandle}s. The method handles are only invoked in
 * static methods which allows Native Image to fold them to direct calls to the method handle
 * targets.
 */
final class GuestGraal {

    private final MethodHandle getJNIEnv;
    private final MethodHandle getSavedProperty;
    private final MethodHandle ttyPrintf;
    private final MethodHandle compileMethod;
    private final MethodHandle hashConstantOopFields;
    private final MethodHandle attachCurrentThread;
    private final MethodHandle detachCurrentThread;

    /**
     * Returns the {@link GuestGraal} instance registered in the {@link ImageSingletons}.
     */
    private static GuestGraal singleton() {
        return ImageSingletons.lookup(GuestGraal.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    GuestGraal(Map<String, MethodHandle> handles) {
        this.getJNIEnv = handles.get("getJNIEnv");
        this.getSavedProperty = handles.get("getSavedProperty");
        this.ttyPrintf = handles.get("ttyPrintf");
        this.compileMethod = handles.get("compileMethod");
        this.hashConstantOopFields = handles.get("hashConstantOopFields");
        this.attachCurrentThread = handles.get("attachCurrentThread");
        this.detachCurrentThread = handles.get("detachCurrentThread");
    }

    /**
     * Calls {@code jdk.graal.compiler.hotspot.guestgraal.RunTime#getJNIEnv()}.
     */
    static JNI.JNIEnv getJNIEnv() {
        try {
            long raw = (long) singleton().getJNIEnv.invoke();
            return WordFactory.unsigned(raw);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    /**
     * Calls {@code jdk.graal.compiler.serviceprovider.GraalServices#getSavedProperty(String)}.
     */
    static String getSavedProperty(String name) {
        try {
            return (String) singleton().getSavedProperty.invoke(name);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    static boolean attachCurrentThread(boolean daemon, long[] isolate) {
        try {
            return (boolean) singleton().attachCurrentThread.invoke(daemon, isolate);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    static boolean detachCurrentThread(boolean release) {
        try {
            return (boolean) singleton().detachCurrentThread.invoke(release);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    /**
     * Calls {@code jdk.graal.compiler.debug.TTY#printf(String, Object...)}.
     */
    static void ttyPrintf(String format, Object... args) {
        try {
            singleton().ttyPrintf.invoke(format, args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    /**
     * The implementation of
     * {@code jdk.graal.compiler.hotspot.test.LibGraalCompilationDriver#compileMethodInLibgraal}.
     *
     * @param methodHandle the method to be compiled. This is a handle to a
     *            {@code HotSpotResolvedJavaMethod} in HotSpot's heap. A value of 0L can be passed
     *            to use this method for the side effect of initializing a
     *            {@code HotSpotGraalCompiler} instance without doing any compilation.
     * @param useProfilingInfo specifies if profiling info should be used during the compilation
     * @param installAsDefault specifies if the compiled code should be installed for the
     *            {@code Method*} associated with {@code methodHandle}
     * @param printMetrics specifies if global metrics should be printed and reset
     * @param optionsAddress native byte buffer storing a serialized {@code OptionValues} object
     * @param optionsSize the number of bytes in the buffer
     * @param optionsHash hash code of bytes in the buffer (computed with
     *            {@link Arrays#hashCode(byte[])})
     * @param stackTraceAddress a native buffer in which a serialized stack trace can be returned.
     *            The caller will only read from this buffer if this method returns 0. A returned
     *            serialized stack trace is returned in this buffer with the following format:
     *
     *            <pre>
     *            struct {
     *                int   length;
     *                byte  data[length]; // Bytes from a stack trace printed to a ByteArrayOutputStream.
     *            }
     *            </pre>
     *
     *            where {@code length} truncated to {@code stackTraceCapacity - 4} if necessary
     *
     * @param stackTraceCapacity the size of the stack trace buffer
     * @param timeAndMemBufferAddress 16-byte native buffer to store result of time and memory
     *            measurements of the compilation
     * @param profilePathBufferAddress native buffer containing a 0-terminated C string representing
     *            {@code Options#LoadProfiles} path.
     * @return a handle to a {@code InstalledCode} in HotSpot's heap or 0 if compilation failed
     */
    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_jdk_graal_compiler_hotspot_test_LibGraalCompilationDriver_compileMethodInLibgraal", include = GuestGraalFeature.IsEnabled.class)
    private static long compileMethod(JNIEnv jniEnv,
                    PointerBase jclass,
                    @CEntryPoint.IsolateThreadContext long isolateThread,
                    long methodHandle,
                    boolean useProfilingInfo,
                    boolean installAsDefault,
                    boolean printMetrics,
                    boolean eagerResolving,
                    long optionsAddress,
                    int optionsSize,
                    int optionsHash,
                    long stackTraceAddress,
                    int stackTraceCapacity,
                    long timeAndMemBufferAddress,
                    long profilePathBufferAddress) {
        try (JNIMethodScope jniScope = new JNIMethodScope("compileMethod", jniEnv)) {
            if (methodHandle == 0L) {
                return 0L;
            }
            String profileLoadPath;
            if (profilePathBufferAddress > 0) {
                profileLoadPath = CTypeConversion.toJavaString(WordFactory.pointer(profilePathBufferAddress));
            } else {
                profileLoadPath = null;
            }
            BiConsumer<Long, Long> timeAndMemConsumer;
            Supplier<Long> currentThreadAllocatedBytes;
            if (timeAndMemBufferAddress != 0) {
                timeAndMemConsumer = (timeSpent, bytesAllocated) -> {
                    Unsafe.getUnsafe().putLong(timeAndMemBufferAddress, bytesAllocated);
                    Unsafe.getUnsafe().putLong(timeAndMemBufferAddress + 8, timeSpent);
                };
                ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
                currentThreadAllocatedBytes = () -> threadMXBean.getCurrentThreadAllocatedBytes();
            } else {
                timeAndMemConsumer = null;
                currentThreadAllocatedBytes = null;
            }

            return (long) singleton().compileMethod.invoke(methodHandle, useProfilingInfo,
                            installAsDefault, printMetrics, eagerResolving,
                            optionsAddress, optionsSize, optionsHash,
                            profileLoadPath, timeAndMemConsumer, currentThreadAllocatedBytes);
        } catch (Throwable t) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            t.printStackTrace(new PrintStream(baos));
            byte[] stackTrace = baos.toByteArray();
            int length = Math.min(stackTraceCapacity - Integer.BYTES, stackTrace.length);
            Unsafe.getUnsafe().putInt(stackTraceAddress, length);
            Unsafe.getUnsafe().copyMemory(stackTrace, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, stackTraceAddress + Integer.BYTES, length);
            return 0L;
        } finally {
            /*
             * libgraal doesn't use a dedicated reference handler thread, so we trigger the
             * reference handling manually when a compilation finishes.
             */
            doReferenceHandling();
        }
    }

    @CEntryPoint(name = "Java_jdk_graal_compiler_hotspot_test_LibGraalCompilerTest_hashConstantOopFields", include = GuestGraalFeature.IsEnabled.class)
    private static long hashConstantOopFields(JNIEnv jniEnv,
                    PointerBase jclass,
                    @CEntryPoint.IsolateThreadContext long isolateThread,
                    long typeHandle,
                    boolean useScope,
                    int iterations,
                    int oopsPerIteration,
                    boolean verbose) {
        try (JNIMethodScope scope = new JNIMethodScope("hashConstantOopFields", jniEnv)) {
            Runnable doReferenceHandling = GuestGraal::doReferenceHandling;
            return (long) singleton().hashConstantOopFields.invoke(typeHandle, useScope, iterations, oopsPerIteration, verbose, doReferenceHandling);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(jniEnv, t);
            return 0;
        }
    }

    /**
     * Since reference handling is synchronous in libgraal, explicitly perform it here and then run
     * any code which is expecting to process a reference queue to let it clean up.
     */
    static void doReferenceHandling() {
        Heap.getHeap().doReferenceHandling();
        synchronized (GuestGraalJVMCISubstitutions.Target_jdk_vm_ci_hotspot_Cleaner.class) {
            GuestGraalJVMCISubstitutions.Target_jdk_vm_ci_hotspot_Cleaner.clean();
        }
    }

    /**
     * Options configuring the VM in which libgraal is running.
     */
    @UnknownObjectField(fullyQualifiedTypes = "org.graalvm.collections.EconomicMapImpl") //
    static EconomicMap<String, OptionDescriptor> vmOptionDescriptors = EconomicMap.create();

    static void initializeOptions(Map<String, String> settings) {
        EconomicMap<String, String> nonXSettings = processXOptions(settings);
        EconomicMap<OptionKey<?>, Object> vmOptionValues = OptionValues.newOptionMap();
        Iterable<OptionDescriptors> vmOptionLoader = List.of(new OptionDescriptorsMap(vmOptionDescriptors));
        OptionsParser.parseOptions(nonXSettings, vmOptionValues, vmOptionLoader);
        RuntimeOptionValues.singleton().update(vmOptionValues);
    }

    /**
     * Extracts and processes the {@link XOptions} in {@code settings}.
     *
     * @return the entries in {@code settings} that do not correspond to {@link XOptions}
     */
    private static EconomicMap<String, String> processXOptions(Map<String, String> settings) {
        EconomicMap<String, String> nonXSettings = EconomicMap.create(settings.size());
        for (var e : settings.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key.startsWith("X") && value.isEmpty()) {
                String xarg = key.substring(1);
                if (XOptions.setOption(xarg)) {
                    continue;
                }
            }
            nonXSettings.put(key, value);
        }
        return nonXSettings;
    }

    static void printOptions(PrintStream out, String prefix) {
        RuntimeOptionValues vmOptions = RuntimeOptionValues.singleton();
        Iterable<OptionDescriptors> vmOptionLoader = Collections.singletonList(new OptionDescriptorsMap(vmOptionDescriptors));
        vmOptions.printHelp(vmOptionLoader, out, prefix, true);
    }
}

final class GuestGraalLibGraalScope {

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_LibGraalScope_getIsolateThreadIn", builtin = Builtin.GET_CURRENT_THREAD)
    private static native IsolateThread getIsolateThreadIn(PointerBase env, PointerBase hsClazz, @IsolateContext Isolate isolate);

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_LibGraalScope_attachThreadTo", builtin = Builtin.ATTACH_THREAD)
    static native long attachThreadTo(PointerBase env, PointerBase hsClazz, @IsolateContext long isolate);

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_LibGraalScope_detachThreadFrom", builtin = Builtin.DETACH_THREAD)
    static native void detachThreadFrom(PointerBase env, PointerBase hsClazz, @CEntryPoint.IsolateThreadContext long isolateThread);

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_LibGraalScope_getIsolateId")
    @SuppressWarnings("unused")
    public static long getIsolateId(PointerBase env, PointerBase jclass, @CEntryPoint.IsolateThreadContext long isolateThreadId) {
        return ImageSingletons.lookup(IsolateSupport.class).getIsolateID();
    }
}

final class GuestGraalTruffleToLibGraalEntryPoints {

    private static final String GRAAL_ENTRY_POINT_CLASS = "jdk.graal.compiler.hotspot.guestgraal.truffle.GraalEntryPoint";

    private final MethodHandle initializeIsolate;
    private final MethodHandle registerRuntime;
    private final MethodHandle initializeRuntime;
    private final MethodHandle newCompiler;
    private final MethodHandle initializeCompiler;
    private final MethodHandle getCompilerConfigurationFactoryName;
    private final MethodHandle doCompile;
    private final MethodHandle shutdown;
    private final MethodHandle installTruffleCallBoundaryMethod;
    private final MethodHandle installTruffleReservedOopMethod;
    private final MethodHandle pendingTransferToInterpreterOffset;
    private final MethodHandle getString;
    private final MethodHandle getNodeCount;
    private final MethodHandle getNodeTypes;
    private final MethodHandle getTargetCodeSize;
    private final MethodHandle getTotalFrameSize;
    private final MethodHandle getExceptionHandlersCount;
    private final MethodHandle getInfopointsCount;
    private final MethodHandle getInfopoints;
    private final MethodHandle listCompilerOptions;
    private final MethodHandle existsCompilerOption;
    private final MethodHandle validateCompilerOption;
    private final MethodHandle getMarksCount;
    private final MethodHandle getDataPatchesCount;
    private final MethodHandle purgePartialEvaluationCaches;
    private final MethodHandle getCompilerVersion;

    @Platforms(Platform.HOSTED_ONLY.class)
    GuestGraalTruffleToLibGraalEntryPoints(Lookup guestGraalLookup) {
        Map<String, MethodHandle> handles = new HashMap<>();
        try {
            Class<?> graalEntryPointClass = guestGraalLookup.findClass(GRAAL_ENTRY_POINT_CLASS);
            Set<String> methodNames = new HashSet<>();
            Arrays.stream(Id.values()).map(Id::getMethodName).forEach(methodNames::add);
            methodNames.remove("releaseHandle"); // Implemented in native-image host
            for (Method m : graalEntryPointClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
                    String methodName = m.getName();
                    if (methodNames.remove(methodName)) {
                        handles.put(methodName, guestGraalLookup.unreflect(m));
                    }
                }
            }
            if (!methodNames.isEmpty()) {
                throw new RuntimeException(String.format("Cannot find methods for following ids %s in %s", methodNames, GRAAL_ENTRY_POINT_CLASS));
            }
        } catch (ClassNotFoundException | IllegalAccessException e) {
            throw VMError.shouldNotReachHere(e);
        }

        this.initializeIsolate = getOrFail(handles, Id.InitializeIsolate);
        this.registerRuntime = getOrFail(handles, Id.RegisterRuntime);
        this.initializeRuntime = getOrFail(handles, Id.InitializeRuntime);
        this.newCompiler = getOrFail(handles, Id.NewCompiler);
        this.initializeCompiler = getOrFail(handles, Id.InitializeCompiler);
        this.getCompilerConfigurationFactoryName = getOrFail(handles, Id.GetCompilerConfigurationFactoryName);
        this.doCompile = getOrFail(handles, Id.DoCompile);
        this.shutdown = getOrFail(handles, Id.Shutdown);
        this.installTruffleCallBoundaryMethod = getOrFail(handles, Id.InstallTruffleCallBoundaryMethod);
        this.installTruffleReservedOopMethod = getOrFail(handles, Id.InstallTruffleReservedOopMethod);
        this.pendingTransferToInterpreterOffset = getOrFail(handles, Id.PendingTransferToInterpreterOffset);
        this.getString = getOrFail(handles, Id.GetSuppliedString);
        this.getNodeCount = getOrFail(handles, Id.GetNodeCount);
        this.getNodeTypes = getOrFail(handles, Id.GetNodeTypes);
        this.getTargetCodeSize = getOrFail(handles, Id.GetTargetCodeSize);
        this.getTotalFrameSize = getOrFail(handles, Id.GetTotalFrameSize);
        this.getExceptionHandlersCount = getOrFail(handles, Id.GetExceptionHandlersCount);
        this.getInfopointsCount = getOrFail(handles, Id.GetInfopointsCount);
        this.getInfopoints = getOrFail(handles, Id.GetInfopoints);
        this.listCompilerOptions = getOrFail(handles, Id.ListCompilerOptions);
        this.existsCompilerOption = getOrFail(handles, Id.CompilerOptionExists);
        this.validateCompilerOption = getOrFail(handles, Id.ValidateCompilerOption);
        this.getMarksCount = getOrFail(handles, Id.GetMarksCount);
        this.getDataPatchesCount = getOrFail(handles, Id.GetDataPatchesCount);
        this.purgePartialEvaluationCaches = getOrFail(handles, Id.PurgePartialEvaluationCaches);
        this.getCompilerVersion = getOrFail(handles, Id.GetCompilerVersion);
    }

    private static MethodHandle getOrFail(Map<String, MethodHandle> handles, Id id) {
        MethodHandle handle = handles.get(id.getMethodName());
        if (handle != null) {
            return handle;
        } else {
            throw new NoSuchElementException(id.getMethodName());
        }
    }

    private static JNIMethodScope openScope(Enum<?> id, JNIEnv env) {
        Objects.requireNonNull(id, "Id must be non null.");
        String scopeName = ClassUtil.getUnqualifiedName(GuestGraalTruffleToLibGraalEntryPoints.class) + "::" + id;
        // Todo fixme: Do call to guest and to find out if we have Java frame anchor
        return LibGraalJNIMethodScope.open(scopeName, env, true);
    }

    private static GuestGraalTruffleToLibGraalEntryPoints singleton() {
        return ImageSingletons.lookup(GuestGraalTruffleToLibGraalEntryPoints.class);
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_initializeIsolate")
    public static void initializeIsolate(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, JClass runtimeClass) {
        try (JNIMethodScope s = openScope(Id.InitializeIsolate, env)) {
            TruffleFromLibGraalStartPoint.initializeJNI(runtimeClass);
            singleton().initializeIsolate.invoke();
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_registerRuntime")
    public static boolean registerRuntime(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, JObject truffleRuntime) {
        try (JNIMethodScope s = openScope(RegisterRuntime, env)) {
            return (boolean) singleton().registerRuntime.invoke(JNIUtil.NewWeakGlobalRef(env, truffleRuntime, "TruffleCompilerRuntime").rawValue());
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return false;
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_initializeRuntime")
    public static long initializeRuntime(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId,
                    JObject truffleRuntime, JClass hsClassLoaderDelegate) {
        try (JNIMethodScope s = openScope(InitializeRuntime, env)) {
            HSObject hsHandle = new HSObject(env, truffleRuntime);
            Object hsTruffleRuntime = singleton().initializeRuntime.invoke(hsHandle, hsClassLoaderDelegate.rawValue());
            return LibGraalObjectHandles.create(hsTruffleRuntime);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0L;
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_newCompiler")
    public static long newCompiler(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long truffleRuntimeHandle) {
        try (JNIMethodScope s = openScope(NewCompiler, env)) {
            Object truffleRuntime = LibGraalObjectHandles.resolve(truffleRuntimeHandle, Object.class);
            Object compiler = singleton().newCompiler.invoke(truffleRuntime);
            return LibGraalObjectHandles.create(compiler);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @SuppressWarnings("unused")
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_initializeCompiler")
    public static void initializeCompiler(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long compilerHandle, JObject hsCompilable,
                    boolean firstInitialization) {
        try (JNIMethodScope scope = openScope(InitializeCompiler, env)) {
            Object compiler = LibGraalObjectHandles.resolve(compilerHandle, Object.class);
            singleton().initializeCompiler.invoke(compiler, new HSObject(scope, hsCompilable), firstInitialization);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getCompilerConfigurationFactoryName")
    public static JString getCompilerConfigurationFactoryName(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long truffleRuntimeHandle) {
        JNIMethodScope scope = openScope(GetCompilerConfigurationFactoryName, env);
        try (JNIMethodScope s = scope) {
            String name = (String) singleton().getCompilerConfigurationFactoryName.invoke();
            scope.setObjectResult(createHSString(env, name));
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_doCompile")
    public static void doCompile(JNIEnv env,
                    JClass hsClazz,
                    @CEntryPoint.IsolateThreadContext long isolateThreadId,
                    long compilerHandle,
                    JObject hsTask,
                    JObject hsCompilable,
                    JObject hsListener) {
        try (JNIMethodScope scope = openScope(DoCompile, env)) {
            Object compiler = LibGraalObjectHandles.resolve(compilerHandle, Object.class);
            Object taskHsHandle = hsTask.isNull() ? null : new HSObject(scope, hsTask);
            Object compilableHsHandle = new HSObject(scope, hsCompilable);
            Object listenerHsHandle = hsListener.isNull() ? null : new HSObject(scope, hsListener);
            try {
                singleton().doCompile.invoke(compiler, taskHsHandle, compilableHsHandle, listenerHsHandle);
            } finally {
                Heap.getHeap().doReferenceHandling();
                Heap.getHeap().getGC().collectionHint(true);
            }
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_shutdown")
    public static void shutdown(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(Shutdown, env)) {
            Object compiler = LibGraalObjectHandles.resolve(handle, Object.class);
            singleton().shutdown.invoke(compiler);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_installTruffleCallBoundaryMethod")
    public static void installTruffleCallBoundaryMethod(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle, long methodHandle) {
        try (JNIMethodScope s = openScope(InstallTruffleCallBoundaryMethod, env)) {
            Object compiler = LibGraalObjectHandles.resolve(handle, Object.class);
            singleton().installTruffleCallBoundaryMethod.invoke(compiler, methodHandle);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings({"unused", "try"})
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_installTruffleReservedOopMethod")
    public static void installTruffleReservedOopMethod(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle, long methodHandle) {
        try (JNIMethodScope s = openScope(Id.InstallTruffleReservedOopMethod, env)) {
            Object compiler = LibGraalObjectHandles.resolve(handle, Object.class);
            singleton().installTruffleReservedOopMethod.invoke(compiler, methodHandle);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @SuppressWarnings("unused")
    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_pendingTransferToInterpreterOffset")
    public static int pendingTransferToInterpreterOffset(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle, JObject hsCompilable) {
        try (JNIMethodScope scope = openScope(PendingTransferToInterpreterOffset, env)) {
            Object compiler = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().pendingTransferToInterpreterOffset.invoke(compiler, new HSObject(scope, hsCompilable));
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getSuppliedString")
    @SuppressWarnings({"unused", "unchecked", "try"})
    public static JString getString(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        JNIMethodScope scope = openScope(GetSuppliedString, env);
        try (JNIMethodScope s = scope) {
            Object stringSupplier = LibGraalObjectHandles.resolve(handle, Object.class);
            if (stringSupplier != null) {
                String stackTrace = (String) singleton().getString.invoke(stringSupplier);
                scope.setObjectResult(JNIUtil.createHSString(env, stackTrace));
            } else {
                scope.setObjectResult(WordFactory.nullPointer());
            }
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getNodeCount")
    @SuppressWarnings({"unused", "try"})
    public static int getNodeCount(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetNodeCount, env)) {
            Object graphInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getNodeCount.invoke(graphInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getNodeTypes")
    @SuppressWarnings({"unused", "try"})
    public static JObjectArray getNodeTypes(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle, boolean simpleNames) {
        JNIMethodScope scope = openScope(GetNodeTypes, env);
        try (JNIMethodScope s = scope) {
            Object graphInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            String[] nodeTypes = (String[]) singleton().getNodeTypes.invoke(graphInfo, simpleNames);
            JClass componentType = getStringClass(env);
            JObjectArray res = NewObjectArray(env, nodeTypes.length, componentType, WordFactory.nullPointer());
            for (int i = 0; i < nodeTypes.length; i++) {
                SetObjectArrayElement(env, res, i, JNIUtil.createHSString(env, nodeTypes[i]));
            }
            scope.setObjectResult(res);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    private static JClass getStringClass(JNIEnv env) {
        return JNIUtil.NewGlobalRef(env, JNIUtil.findClass(env, "java/lang/String"), "Class<java.lang.String>");
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getTargetCodeSize")
    @SuppressWarnings({"unused", "try"})
    public static int getTargetCodeSize(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetTargetCodeSize, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getTargetCodeSize.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getTotalFrameSize")
    @SuppressWarnings({"unused", "try"})
    public static int getTotalFrameSize(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetTotalFrameSize, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getTotalFrameSize.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getExceptionHandlersCount")
    @SuppressWarnings({"unused", "try"})
    public static int getExceptionHandlersCount(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetExceptionHandlersCount, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getExceptionHandlersCount.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getInfopointsCount")
    @SuppressWarnings({"unused", "try"})
    public static int getInfopointsCount(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetInfopointsCount, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getInfopointsCount.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getInfopoints")
    @SuppressWarnings({"unused", "try"})
    public static JObjectArray getInfopoints(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        JNIMethodScope scope = openScope(GetInfopoints, env);
        try (JNIMethodScope s = scope) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            String[] infoPoints = (String[]) singleton().getInfopoints.invoke(compilationResultInfo);
            JClass componentType = getStringClass(env);
            JObjectArray res = NewObjectArray(env, infoPoints.length, componentType, WordFactory.nullPointer());
            for (int i = 0; i < infoPoints.length; i++) {
                SetObjectArrayElement(env, res, i, createHSString(env, infoPoints[i]));
            }
            scope.setObjectResult(res);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getMarksCount")
    @SuppressWarnings({"unused", "try"})
    public static int getMarksCount(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetMarksCount, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getMarksCount.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getDataPatchesCount")
    @SuppressWarnings({"unused", "try"})
    public static int getDataPatchesCount(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try (JNIMethodScope s = openScope(GetDataPatchesCount, env)) {
            Object compilationResultInfo = LibGraalObjectHandles.resolve(handle, Object.class);
            return (int) singleton().getDataPatchesCount.invoke(compilationResultInfo);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return 0;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_listCompilerOptions")
    @SuppressWarnings({"unused", "try"})
    public static JByteArray listCompilerOptions(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId) {
        JNIMethodScope scope = openScope(Id.ListCompilerOptions, env);
        try (JNIMethodScope s = scope) {
            Object[] options = (Object[]) singleton().listCompilerOptions.invoke();
            BinaryOutput.ByteArrayBinaryOutput out = BinaryOutput.create();
            out.writeInt(options.length);
            for (int i = 0; i < options.length; i++) {
                TruffleCompilerOptionDescriptor descriptor = (TruffleCompilerOptionDescriptor) options[i];
                out.writeUTF(descriptor.name());
                out.writeInt(descriptor.type().ordinal());
                out.writeBoolean(descriptor.deprecated());
                out.writeUTF(descriptor.help());
                out.writeUTF(descriptor.deprecationMessage());
            }
            JByteArray res = JNIUtil.createHSArray(env, out.getArray());
            scope.setObjectResult(res);
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_compilerOptionExists")
    @SuppressWarnings({"unused", "try"})
    public static boolean existsCompilerOption(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, JString optionName) {
        try (JNIMethodScope scope = openScope(Id.CompilerOptionExists, env)) {
            return (boolean) singleton().existsCompilerOption.invoke(JNIUtil.createString(env, optionName));
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            return false;
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_validateCompilerOption")
    @SuppressWarnings({"unused", "try"})
    public static JString validateCompilerOption(JNIEnv env, JClass hsClazz, @CEntryPoint.IsolateThreadContext long isolateThreadId, JString optionName, JString optionValue) {
        JNIMethodScope scope = openScope(Id.ValidateCompilerOption, env);
        try (JNIMethodScope s = scope) {
            String result = (String) singleton().validateCompilerOption.invoke(JNIUtil.createString(env, optionName), JNIUtil.createString(env, optionValue));
            scope.setObjectResult(JNIUtil.createHSString(env, result));
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_purgePartialEvaluationCaches")
    @SuppressWarnings({"unused", "try"})
    public static void purgePartialEvaluationCaches(JNIEnv env, JClass hsClass, @CEntryPoint.IsolateThreadContext long isolateThreadId, long compilerHandle) {
        try (JNIMethodScope s = openScope(PurgePartialEvaluationCaches, env)) {
            Object compiler = LibGraalObjectHandles.resolve(compilerHandle, Object.class);
            if (compiler != null) {
                singleton().purgePartialEvaluationCaches.invoke(compiler);
            }
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
        }
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_TruffleToLibGraalCalls_getCompilerVersion")
    @SuppressWarnings({"unused", "try"})
    public static JString getCompilerVersion(JNIEnv env, JClass hsClass, @CEntryPoint.IsolateThreadContext long isolateThreadId) {
        JNIMethodScope scope = openScope(GetCompilerVersion, env);
        try (JNIMethodScope s = scope) {
            String version = (String) singleton().getCompilerVersion.invoke();
            scope.setObjectResult(createHSString(env, version));
        } catch (Throwable t) {
            JNIExceptionWrapper.throwInHotSpot(env, t);
            scope.setObjectResult(WordFactory.nullPointer());
        }
        return scope.getObjectResult();
    }

    @CEntryPoint(name = "Java_com_oracle_truffle_runtime_hotspot_libgraal_LibGraalObject_releaseHandle")
    public static boolean releaseHandle(JNIEnv jniEnv, JClass jclass, @CEntryPoint.IsolateThreadContext long isolateThreadId, long handle) {
        try {
            ObjectHandles.getGlobal().destroy(WordFactory.pointer(handle));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
