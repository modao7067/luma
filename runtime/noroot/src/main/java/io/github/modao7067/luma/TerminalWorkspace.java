package io.github.modao7067.luma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TerminalWorkspace<T> {
    private final ArrayList<T> windows = new ArrayList<>();
    private final Map<String,T> dedicated = new LinkedHashMap<>();
    private T current, lastOrdinary;

    List<T> all() { return Collections.unmodifiableList(windows); }
    List<T> ordinary() {
        ArrayList<T> result = new ArrayList<>();
        for (T window : windows) if (isOrdinary(window)) result.add(window);
        return Collections.unmodifiableList(result);
    }
    boolean isOrdinary(T window) { return windows.contains(window) && !dedicated.containsValue(window); }
    T ordinarySelection() {
        if (isOrdinary(lastOrdinary)) return lastOrdinary;
        for (T window : windows) if (isOrdinary(window)) return window;
        return null;
    }
    boolean allows(String dedicatedId, T window) {
        return dedicatedId == null ? isOrdinary(window) : window != null && window == dedicated.get(dedicatedId);
    }
    void addOrdinary(T window) { windows.add(window); select(window); }
    void addDedicated(String id, T window) {
        if (dedicated.containsKey(id)) throw new IllegalStateException("Duplicate session terminal");
        windows.add(window); dedicated.put(id, window); select(window);
    }
    void select(T window) {
        if (!windows.contains(window)) throw new IllegalArgumentException("Unknown window");
        current = window;
        if (isOrdinary(window)) lastOrdinary = window;
    }
    T current() { return current; }
    void replace(T old, T replacement) {
        int index = windows.indexOf(old);
        if (index < 0) throw new IllegalArgumentException("Unknown window");
        windows.set(index, replacement);
        for (Map.Entry<String,T> entry : dedicated.entrySet()) if (entry.getValue() == old) entry.setValue(replacement);
        if (lastOrdinary == old) lastOrdinary = replacement;
        if (current == old) current = replacement;
    }
    void remove(T window) {
        if (!windows.remove(window)) return;
        boolean wasDedicated = dedicated.values().removeIf(value -> value == window);
        if (lastOrdinary == window) lastOrdinary = null;
        if (current == window) current = wasDedicated ? null : ordinarySelection();
    }
    void clear() { windows.clear(); dedicated.clear(); current = lastOrdinary = null; }
}
