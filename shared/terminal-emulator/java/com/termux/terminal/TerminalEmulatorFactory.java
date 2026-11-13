package com.termux.terminal;

/** Per-session native emulator factory; production has no Java VT fallback. */
public interface TerminalEmulatorFactory {
    TerminalEmulator create(TerminalSession session, int columns, int rows,
                            int cellWidthPixels, int cellHeightPixels,
                            Integer transcriptRows, TerminalSessionClient client);
    static TerminalEmulatorFactory nativeFactory(){
        return (session,cols,rows,cw,ch,history,client)->{
            io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator.loadSystemLibrary();
            return new io.github.modao7067.luma.ghostty.GhosttyTerminalEmulator(session,cols,rows,cw,ch,history,client);
        };
    }
}
