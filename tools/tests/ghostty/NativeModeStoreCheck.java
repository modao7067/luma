package io.github.modao7067.luma.root;

import com.termux.terminal.TerminalEmulator;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class NativeModeStoreCheck {
    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    static byte[] v1Record(int[] values, long serial) {
        byte[] body = new byte[values.length * 4];
        ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).asIntBuffer().put(values);
        CRC32 crc = new CRC32(); crc.update(body);
        ByteBuffer out = ByteBuffer.allocate(16 + body.length + 4).order(ByteOrder.BIG_ENDIAN);
        out.putLong(serial).putInt(0x4c4d5331).putInt(values.length).put(body).putInt((int)crc.getValue());
        return out.array();
    }
    static byte[] annex(int[] words, long serial, int bodyCrc, boolean confident) {
        int total = 112;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        out.putInt(0x4c4d4b58).putShort((short)1).putShort((short)total)
           .putLong(serial).putInt(bodyCrc).putInt(confident ? 1 : 0);
        for (int word : words) out.putInt(word);
        CRC32 crc = new CRC32(); crc.update(out.array(), 0, total - 4);
        out.putInt((int)crc.getValue());
        return out.array();
    }
    static void write(File file, long offset, byte[] data) throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.seek(offset); out.write(data);
        }
    }
    static long newestSerial(File file) throws Exception {
        byte[] data = new byte[4096];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) { in.readFully(data); }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return Math.max(buffer.getLong(0), buffer.getLong(2048));
    }
    static int[] words() {
        int[] values = new int[TerminalEmulator.MODE_STATE_WORDS];
        values[0] = 1; values[1] = 80; values[2] = 24; values[3] = 24; values[4] = 24;
        values[5] = 0; values[9] = 24; values[11] = 80; values[14] = 1;
        for (int at : new int[]{16, 25}) { values[at + 2] = 256; values[at + 3] = 257; values[at + 8] = 1; }
        for (int i = 38; i < 70; i++) values[i] = -1;
        values[72] = 256; values[73] = 257;
        return values;
    }
    static int[] keyboard() {
        int[] kb = new int[TerminalEmulator.KEYBOARD_STATE_WORDS];
        kb[0] = 1; kb[1] = 54; kb[2] = 1; kb[3] = 1;
        return kb;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("MODE STORE START");
        try {
        File dir = new File(args[1]); if (!dir.mkdirs() && !dir.isDirectory()) throw new AssertionError("fixture");
        File file = new File(new File(dir, "terminal-modes"), "matrix.modes");

        TerminalModeStore blank = new TerminalModeStore(dir, "blank");
        TerminalModeStore.Checkpoint blankCheckpoint = blank.load();
        check(blankCheckpoint.modes == null && blankCheckpoint.empty && !blankCheckpoint.orphan,
              "all-zero first-lease file is a genuine empty start");
        blank.close();

        TerminalModeStore store = new TerminalModeStore(dir, "matrix");
        store.save(words(), keyboard(), true);
        TerminalModeStore.Checkpoint cp = store.load();
        check(cp.modes != null && cp.keyboard != null && cp.confident && !cp.orphan,
              "bound confident annex loads for restore");
        check(Arrays.equals(cp.keyboard, keyboard()), "bound payload is exact");

        byte[] raw = new byte[4096];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) { in.readFully(raw); }
        int slot = (int)(newestSerial(file) & 1) * 2048;
        ByteBuffer published = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        check(published.getLong(slot) > 0 && published.getInt(slot + 8) == 0x4c4d5331
              && published.getInt(slot + 12) == 80,
              "old 80-word reader header remains valid");

        long serial = newestSerial(file) + 1;
        int oldSlot = (int)(serial & 1) * 2048;
        int[] changed = words(); changed[3] ^= 1;
        write(file, oldSlot, v1Record(changed, serial));
        cp = store.load();
        check(cp.modes != null && cp.keyboard == null && cp.orphan && !cp.empty,
              "old writer makes the annex diagnostic, not restorable");

        File futureDir = new File(dir, "future-dir");
        File futureFolder = new File(futureDir, "terminal-modes");
        if (!futureFolder.mkdirs() && !futureFolder.isDirectory()) throw new AssertionError("future fixture");
        File future = new File(futureFolder, "future.modes");
        try (RandomAccessFile out = new RandomAccessFile(future, "rw")) { out.setLength(4096); }
        write(future, 0, v1Record(words(), 1));
        int[] futureKb = keyboard(); futureKb[2] = 7; futureKb[3] = 31;
        write(future, 2048 + 1024, annex(futureKb, 999, 0, true));
        TerminalModeStore futureStore = new TerminalModeStore(futureDir, "future");
        cp = futureStore.load();
        check(cp.modes != null && cp.keyboard == null && cp.orphan && !cp.empty,
              "serial-less future annex is never selected for restore");

        TerminalModeStore corrupt = new TerminalModeStore(dir, "corrupt");
        corrupt.save(words(), keyboard(), true);
        File corruptFile = new File(new File(dir, "terminal-modes"), "corrupt.modes");
        int corruptSlot = (int)(newestSerial(corruptFile) & 1) * 2048;
        long pos = corruptSlot + 1024 + 40;
        try (RandomAccessFile rw = new RandomAccessFile(corruptFile, "rw")) {
            rw.seek(pos); int value = rw.read(); rw.seek(pos); rw.write(value ^ 0x55);
        }
        cp = corrupt.load();
        check(cp.modes != null && cp.keyboard == null && !cp.empty,
              "corrupt annex leaves the v1 checkpoint usable and marks residue");

        store.close(); futureStore.close(); corrupt.close();
        System.out.println("NATIVE MODE STORE OK checks=" + checks);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
