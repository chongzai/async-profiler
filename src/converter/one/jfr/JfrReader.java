/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.jfr;

import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JFR output produced by async-profiler.
 */
public class JfrReader implements Closeable {
    private static final int CHUNK_HEADER_SIZE = 68;
    private static final int CPOOL_OFFSET = 16;
    private static final int META_OFFSET = 24;

    private final FileChannel ch;
    private final ByteBuffer buf;

    public final long startNanos;
    public final long durationNanos;
    public final long startTicks;
    public final long ticksPerSec;

    public final Dictionary<JfrClass> types = new Dictionary<>();
    public final Map<String, JfrClass> typesByName = new HashMap<>();
    public final Dictionary<String> threads = new Dictionary<>();
    public final Dictionary<ClassRef> classes = new Dictionary<>();
    public final Dictionary<byte[]> symbols = new Dictionary<>();
    public final Dictionary<MethodRef> methods = new Dictionary<>();
    public final Dictionary<StackTrace> stackTraces = new Dictionary<>();
    public final Map<Integer, String> frameTypes = new HashMap<>();
    public final Map<Integer, String> threadStates = new HashMap<>();

    private final int executionSample;
    private final int nativeMethodSample;
    private final int allocationInNewTLAB;
    private final int allocationOutsideTLAB;
    private final int monitorEnter;
    private final int threadPark;

    public JfrReader(String fileName) throws IOException {
        this.ch = FileChannel.open(Paths.get(fileName), StandardOpenOption.READ);
        this.buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());

        long endNanos = 0;
        for (int chunkStart = 0; chunkStart < buf.capacity(); ) {
            endNanos = Math.max(endNanos, buf.getLong(chunkStart + 32) + buf.getLong(chunkStart + 40));
            chunkStart += readChunk(chunkStart);
        }

        this.startNanos = buf.getLong(32);
        this.durationNanos = endNanos - startNanos;
        this.startTicks = buf.getLong(48);
        this.ticksPerSec = buf.getLong(56);

        this.executionSample = getTypeId("jdk.ExecutionSample");
        this.nativeMethodSample = getTypeId("jdk.NativeMethodSample");
        this.allocationInNewTLAB = getTypeId("jdk.ObjectAllocationInNewTLAB");
        this.allocationOutsideTLAB = getTypeId("jdk.ObjectAllocationOutsideTLAB");
        this.monitorEnter = getTypeId("jdk.JavaMonitorEnter");
        this.threadPark = getTypeId("jdk.ThreadPark");

        moveToNextChunk(0);
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }

    public List<Event> readAllEvents() {
        return readAllEvents(null);
    }

    public <E extends Event> List<E> readAllEvents(Class<E> cls) {
        ArrayList<E> events = new ArrayList<>();
        for (E event; (event = readEvent(cls)) != null; ) {
            events.add(event);
        }
        Collections.sort(events);
        return events;
    }

    public Event readEvent() {
        return readEvent(null);
    }

    @SuppressWarnings("unchecked")
    public <E extends Event> E readEvent(Class<E> cls) {
        while (buf.hasRemaining() || moveToNextChunk(buf.position())) {
            int position = buf.position();
            int size = getVarint();
            int type = getVarint();

            if (type == executionSample || type == nativeMethodSample) {
                if (cls == null || cls == ExecutionSample.class) return (E) readExecutionSample();
            } else if (type == allocationInNewTLAB) {
                if (cls == null || cls == AllocationSample.class) return (E) readAllocationSample(true);
            } else if (type == allocationOutsideTLAB) {
                if (cls == null || cls == AllocationSample.class) return (E) readAllocationSample(false);
            } else if (type == monitorEnter) {
                if (cls == null || cls == ContendedLock.class) return (E) readContendedLock(false);
            } else if (type == threadPark) {
                if (cls == null || cls == ContendedLock.class) return (E) readContendedLock(true);
            }

            buf.position(position + size);
        }
        return null;
    }

    private ExecutionSample readExecutionSample() {
        long time = getVarlong();
        int tid = getVarint();
        int stackTraceId = getVarint();
        int threadState = getVarint();
        return new ExecutionSample(time, tid, stackTraceId, threadState);
    }

    private AllocationSample readAllocationSample(boolean tlab) {
        long time = getVarlong();
        int tid = getVarint();
        int stackTraceId = getVarint();
        int classId = getVarint();
        long allocationSize = getVarlong();
        long tlabSize = tlab ? getVarlong() : 0;
        return new AllocationSample(time, tid, stackTraceId, classId, allocationSize, tlabSize);
    }

    private ContendedLock readContendedLock(boolean hasTimeout) {
        long time = getVarlong();
        long duration = getVarlong();
        int tid = getVarint();
        int stackTraceId = getVarint();
        int classId = getVarint();
        if (hasTimeout) getVarlong();
        long address = getVarlong();
        return new ContendedLock(time, tid, stackTraceId, duration, classId);
    }

    private boolean moveToNextChunk(int pos) {
        int newPos = pos + CHUNK_HEADER_SIZE;
        if (newPos < buf.capacity()) {
            buf.limit(newPos);
            buf.position(newPos);
            buf.limit(pos + (int) buf.getLong(pos + 8));
            return true;
        }
        return false;
    }

    private int readChunk(int chunkStart) throws IOException {
        if (buf.getInt(chunkStart) != 0x464c5200) {
            throw new IOException("Not a valid JFR file");
        }

        int version = buf.getInt(chunkStart + 4);
        if (version < 0x20000 || version > 0x2ffff) {
            throw new IOException("Unsupported JFR version: " + (version >>> 16) + "." + (version & 0xffff));
        }

        buf.position(chunkStart + buf.getInt(chunkStart + (META_OFFSET + 4)));
        readMeta();

        buf.position(chunkStart + buf.getInt(chunkStart + (CPOOL_OFFSET + 4)));
        readConstantPool();

        return (int) buf.getLong(chunkStart + 8);
    }

    private void readMeta() {
        getVarint();
        getVarint();
        getVarlong();
        getVarlong();
        getVarlong();

        String[] strings = new String[getVarint()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = getString();
        }
        readElement(strings);
    }

    private Element readElement(String[] strings) {
        String name = strings[getVarint()];

        int attributeCount = getVarint();
        Map<String, String> attributes = new HashMap<>(attributeCount);
        for (int i = 0; i < attributeCount; i++) {
            attributes.put(strings[getVarint()], strings[getVarint()]);
        }

        Element e = createElement(name, attributes);
        int childCount = getVarint();
        for (int i = 0; i < childCount; i++) {
            e.addChild(readElement(strings));
        }
        return e;
    }

    private Element createElement(String name, Map<String, String> attributes) {
        switch (name) {
            case "class": {
                JfrClass type = new JfrClass(attributes);
                if (!attributes.containsKey("superType")) {
                    types.put(type.id, type);
                }
                typesByName.put(type.name, type);
                return type;
            }
            case "field":
                return new JfrField(attributes);
            default:
                return new Element();
        }
    }

    private void readConstantPool() {
        for (int position = buf.position(); ; buf.position(position)) {
            getVarint();
            getVarint();
            getVarlong();
            getVarlong();
            long delta = getVarlong();
            getVarint();

            int poolCount = getVarint();
            for (int i = 0; i < poolCount; i++) {
                int type = getVarint();
                readConstants(types.get(type));
            }

            if (delta == 0) {
                break;
            }
            position += delta;
        }
    }

    private void readConstants(JfrClass type) {
        switch (type.name) {
            case "jdk.types.ChunkHeader":
                buf.position(buf.position() + (CHUNK_HEADER_SIZE + 3));
                break;
            case "java.lang.Thread":
                readThreads(type.field("group") != null);
                break;
            case "java.lang.Class":
                readClasses(type.field("hidden") != null);
                break;
            case "jdk.types.Symbol":
                readSymbols();
                break;
            case "jdk.types.Method":
                readMethods();
                break;
            case "jdk.types.StackTrace":
                readStackTraces();
                break;
            case "jdk.types.FrameType":
                readMap(frameTypes);
                break;
            case "jdk.types.ThreadState":
                readMap(threadStates);
                break;
            default:
                readOtherConstants(type.fields);
        }
    }

    private void readThreads(boolean hasGroup) {
        int count = threads.preallocate(getVarint());
        for (int i = 0; i < count; i++) {
            long id = getVarlong();
            String osName = getString();
            int osThreadId = getVarint();
            String javaName = getString();
            long javaThreadId = getVarlong();
            if (hasGroup) getVarlong();
            threads.put(id, javaName != null ? javaName : osName);
        }
    }

    private void readClasses(boolean hasHidden) {
        int count = classes.preallocate(getVarint());
        for (int i = 0; i < count; i++) {
            long id = getVarlong();
            long loader = getVarlong();
            long name = getVarlong();
            long pkg = getVarlong();
            int modifiers = getVarint();
            if (hasHidden) getVarint();
            classes.put(id, new ClassRef(name));
        }
    }

    private void readMethods() {
        int count = methods.preallocate(getVarint());
        for (int i = 0; i < count; i++) {
            long id = getVarlong();
            long cls = getVarlong();
            long name = getVarlong();
            long sig = getVarlong();
            int modifiers = getVarint();
            int hidden = getVarint();
            methods.put(id, new MethodRef(cls, name, sig));
        }
    }

    private void readStackTraces() {
        int count = stackTraces.preallocate(getVarint());
        for (int i = 0; i < count; i++) {
            long id = getVarlong();
            int truncated = getVarint();
            StackTrace stackTrace = readStackTrace();
            stackTraces.put(id, stackTrace);
        }
    }

    private StackTrace readStackTrace() {
        int depth = getVarint();
        long[] methods = new long[depth];
        byte[] types = new byte[depth];
        for (int i = 0; i < depth; i++) {
            methods[i] = getVarlong();
            int line = getVarint();
            int bci = getVarint();
            types[i] = buf.get();
        }
        return new StackTrace(methods, types);
    }

    private void readSymbols() {
        int count = symbols.preallocate(getVarint());
        for (int i = 0; i < count; i++) {
            long id = getVarlong();
            if (buf.get() != 3) {
                throw new IllegalArgumentException("Invalid symbol encoding");
            }
            symbols.put(id, getBytes());
        }
    }

    private void readMap(Map<Integer, String> map) {
        int count = getVarint();
        for (int i = 0; i < count; i++) {
            map.put(getVarint(), getString());
        }
    }

    private void readOtherConstants(List<JfrField> fields) {
        int stringType = getTypeId("java.lang.String");

        boolean[] numeric = new boolean[fields.size()];
        for (int i = 0; i < numeric.length; i++) {
            JfrField f = fields.get(i);
            numeric[i] = f.constantPool || f.type != stringType;
        }

        int count = getVarint();
        for (int i = 0; i < count; i++) {
            getVarlong();
            readFields(numeric);
        }
    }

    private void readFields(boolean[] numeric) {
        for (boolean n : numeric) {
            if (n) {
                getVarlong();
            } else {
                getString();
            }
        }
    }

    private int getTypeId(String typeName) {
        JfrClass type = typesByName.get(typeName);
        return type != null ? type.id : -1;
    }

    private int getVarint() {
        int result = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = buf.get();
            result |= (b & 0x7f) << shift;
            if (b >= 0) {
                return result;
            }
        }
    }

    private long getVarlong() {
        long result = 0;
        for (int shift = 0; shift < 56; shift += 7) {
            byte b = buf.get();
            result |= (b & 0x7fL) << shift;
            if (b >= 0) {
                return result;
            }
        }
        return result | (buf.get() & 0xffL) << 56;
    }

    private String getString() {
        switch (buf.get()) {
            case 0:
                return null;
            case 1:
                return "";
            case 3:
                return new String(getBytes(), StandardCharsets.UTF_8);
            case 4: {
                char[] chars = new char[getVarint()];
                for (int i = 0; i < chars.length; i++) {
                    chars[i] = (char) getVarint();
                }
                return new String(chars);
            }
            case 5:
                return new String(getBytes(), StandardCharsets.ISO_8859_1);
            default:
                throw new IllegalArgumentException("Invalid string encoding");
        }
    }

    private byte[] getBytes() {
        byte[] bytes = new byte[getVarint()];
        buf.get(bytes);
        return bytes;
    }
}
