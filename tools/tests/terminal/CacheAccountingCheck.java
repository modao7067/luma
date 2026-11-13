package com.termux.view;

import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class CacheAccountingCheck {
    private static final int BUDGET = 256 * 1024;
    private static final int TABLE = 16 * 1024;
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL cache accounting: " + message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        String fontZip = args[0];
        String outDir = args[1];
        System.setOut(new PrintStream(new File(outDir, "cache-accounting-check.txt")));
        try {
            Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, fontZip);
            BundledTerminalFonts.initialize(assets);
            run();
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Class<?> cacheClass = Class.forName("com.termux.view.TerminalRenderer$SingleCodePointCache");
        Constructor<?> newCache = cacheClass.getDeclaredConstructor();
        newCache.setAccessible(true);
        Field usedField = cacheClass.getDeclaredField("usedBytes");
        usedField.setAccessible(true);
        Method put = cacheClass.getDeclaredMethod("put", int.class, BundledTerminalFonts.PreparedGlyphs.class);
        Method get = cacheClass.getDeclaredMethod("get", int.class);
        Method recompute = cacheClass.getDeclaredMethod("recomputeUsedBytes");
        Method entryCount = cacheClass.getDeclaredMethod("entryCount");
        for (Method m : new Method[]{put, get, recompute, entryCount}) m.setAccessible(true);

        BundledTerminalFonts.PreparedGlyphs g1 = glyphs(1);
        BundledTerminalFonts.PreparedGlyphs g15 = glyphs(15);
        BundledTerminalFonts.PreparedGlyphs g0 = glyphs(0);
        System.out.println("shaped sizes: g0=" + g0.bytes() + " g1=" + g1.bytes() + " g15=" + g15.bytes());

        Object cache = newCache.newInstance();
        check(used(cache, usedField) == TABLE, "fresh cache accounts only the table overhead");
        check(consistent(cache, usedField, recompute), "fresh cache counter equals recomputation");

        put.invoke(cache, 65, g1);
        check(used(cache, usedField) == TABLE + 8 + g1.bytes(), "first insert accounts table + key + value");
        put.invoke(cache, 65, g15);
        check(used(cache, usedField) == TABLE + 8 + g15.bytes(),
                "same-key replacement with a bigger value replaces its bytes, got " + used(cache, usedField));
        put.invoke(cache, 65, g1);
        check(used(cache, usedField) == TABLE + 8 + g1.bytes(), "same-key replacement with a smaller value");
        check(consistent(cache, usedField, recompute), "same-key replacement keeps the counter consistent");
        check(count(cache, entryCount) == 1, "same-key replacement leaves exactly one entry");

        for (int i = 0; i < 3000; i++) put.invoke(cache, 65, (i & 1) == 0 ? g15 : g1);
        check(used(cache, usedField) <= TABLE + 8 + g15.bytes(),
                "3000 same-key replacements do not grow the counter: " + used(cache, usedField));
        check(consistent(cache, usedField, recompute), "3000 replacements stay consistent");

        cache = newCache.newInstance();
        for (int key : keysInSameSet(cacheClass, 6)) put.invoke(cache, key, g1);
        check(count(cache, entryCount) <= 4, "a set never exceeds its 4 ways, entries=" + count(cache, entryCount));
        check(used(cache, usedField) <= BUDGET, "set conflict stays within budget");
        check(consistent(cache, usedField, recompute), "set conflict stays consistent");

        BundledTerminalFonts.PreparedGlyphs huge = glyphs(11000);
        System.out.println("oversized shaped value: bytes=" + huge.bytes() + " budget=" + BUDGET);
        check(huge.bytes() + 8 > BUDGET - TABLE, "test value must actually exceed the budget");
        cache = newCache.newInstance();
        put.invoke(cache, 7, g1);
        int beforeBytes = used(cache, usedField);
        int beforeCount = count(cache, entryCount);
        put.invoke(cache, 99, huge);
        check(used(cache, usedField) == beforeBytes,
                "an oversized entry must not change the counter: " + used(cache, usedField));
        check(count(cache, entryCount) == beforeCount, "an oversized entry must not evict other entries");
        check(get.invoke(cache, 99) == null, "an oversized entry must not be stored");
        check(used(cache, usedField) <= BUDGET, "oversized entry never exceeds the budget");
        check(consistent(cache, usedField, recompute), "oversized entry keeps the counter consistent");

        cache = newCache.newInstance();
        BundledTerminalFonts.PreparedGlyphs[] rotation = {g0, g1, g15};
        for (int key = 0; key < 20000; key++) {
            put.invoke(cache, key, rotation[key % rotation.length]);
            if ((key & 0x3ff) == 0) {
                check(used(cache, usedField) <= BUDGET,
                        "budget exceeded at key " + key + ": " + used(cache, usedField));
                check(consistent(cache, usedField, recompute), "counter drifted at key " + key);
            }
        }
        check(used(cache, usedField) <= BUDGET, "budget respected after 20000 inserts");
        check(consistent(cache, usedField, recompute), "counter consistent after 20000 inserts");

        cache = newCache.newInstance();
        for (int key = 0; key < 4000; key++) put.invoke(cache, key, g15);
        int misses = 0;
        for (int key = 0; key < 4000; key++) if (get.invoke(cache, key) == null) misses++;
        check(misses > 0, "budget pressure must actually evict some keys");
        check(consistent(cache, usedField, recompute), "eviction keeps the counter consistent");
        for (int key = 0; key < 4000; key++) put.invoke(cache, key, g15);
        check(used(cache, usedField) <= BUDGET, "rebuild after eviction stays within budget");
        check(consistent(cache, usedField, recompute), "rebuild after eviction stays consistent");
        int hits = 0;
        for (int key = 3999; key >= 0; key--) if (get.invoke(cache, key) != null) hits++;
        check(hits > 0, "rebuilt entries are retrievable, hits=" + hits);

        System.out.println("ALL PASS " + checks + " cache container contract checks"
                + " (same-key replacement, set conflict, oversized refusal, budget pressure, eviction rebuild)");
    }

    private static BundledTerminalFonts.PreparedGlyphs glyphs(int count) {
        Paint paint = new Paint();
        paint.setTypeface(BundledTerminalFonts.typeface());
        paint.setTextSize(22);
        if (count == 0) return new BundledTerminalFonts.PreparedGlyphs(BundledTerminalFonts.shape(new char[0], 0, 0, paint));
        char[] text = new char[count];
        for (int i = 0; i < count; i++) text[i] = 'x';
        BundledTerminalFonts.PreparedGlyphs shaped =
                new BundledTerminalFonts.PreparedGlyphs(BundledTerminalFonts.shape(text, 0, count, paint));
        if (shaped.bytes() != 64 + count * 24)
            throw new IllegalStateException("shaping produced " + shaped.bytes() + " bytes for " + count + " chars");
        return shaped;
    }

    private static List<Integer> keysInSameSet(Class<?> cacheClass, int howMany) throws Exception {
        Method setBase = cacheClass.getDeclaredMethod("setBase", int.class);
        setBase.setAccessible(true);
        List<Integer> found = new ArrayList<>();
        Integer wanted = null;
        for (int key = 0; key < 2_000_000 && found.size() < howMany; key++) {
            int base = (Integer) setBase.invoke(null, key);
            if (wanted == null) wanted = base;
            if (base == wanted) found.add(key);
        }
        if (found.size() < howMany)
            throw new IllegalStateException("could not find " + howMany + " keys in one set");
        return found;
    }

    private static int used(Object cache, Field usedField) throws Exception {
        return usedField.getInt(cache);
    }

    private static int count(Object cache, Method entryCount) throws Exception {
        return (Integer) entryCount.invoke(cache);
    }

    private static boolean consistent(Object cache, Field usedField, Method recompute) throws Exception {
        int running = usedField.getInt(cache);
        int recomputed = (Integer) recompute.invoke(cache);
        return running == recomputed;
    }
}
