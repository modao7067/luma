package io.github.modao7067.luma.shared;
import com.termux.terminal.TerminalEmulatorFactory;
final class TerminalEngines {
    static final String GHOSTTY="ghostty";
    static TerminalEmulatorFactory factory(){return TerminalEmulatorFactory.nativeFactory();}
    private TerminalEngines(){}
}
