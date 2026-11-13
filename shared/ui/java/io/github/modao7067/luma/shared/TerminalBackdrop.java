package io.github.modao7067.luma.shared;

import android.graphics.Color;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TextStyle;

final class TerminalBackdrop {
    static int color(TerminalEmulator emulator,int topRow){
        if(emulator==null)return Color.TRANSPARENT;
        int[] palette=emulator.mColors.mCurrentColors;
        if(emulator.isReverseVideo())return palette[TextStyle.COLOR_INDEX_FOREGROUND];
        int defaultColor=palette[TextStyle.COLOR_INDEX_BACKGROUND];
        if(defaultColor!=TerminalTheme.DEFAULT_BACKGROUND)return defaultColor;
        TerminalBuffer screen=emulator.getScreen();
        int top=Math.max(-screen.getActiveTranscriptRows(),Math.min(0,topRow));
        int bottom=top+emulator.mRows-1, right=emulator.mColumns-1;
        int midRow=(top+bottom)/2, midColumn=right/2;
        int candidate=backgroundAt(screen,top,0,palette);
        if(candidate==defaultColor)return Color.TRANSPARENT;
        if(backgroundAt(screen,top,right,palette)!=candidate
            || backgroundAt(screen,bottom,0,palette)!=candidate
            || backgroundAt(screen,bottom,right,palette)!=candidate
            || backgroundAt(screen,top,midColumn,palette)!=candidate
            || backgroundAt(screen,bottom,midColumn,palette)!=candidate
            || backgroundAt(screen,midRow,0,palette)!=candidate
            || backgroundAt(screen,midRow,right,palette)!=candidate)return Color.TRANSPARENT;
        return candidate;
    }

    private static int backgroundAt(TerminalBuffer screen,int row,int column,int[] palette){
        long style=screen.getStyleAt(row,column);
        int color=(TextStyle.decodeEffect(style)&TextStyle.CHARACTER_ATTRIBUTE_INVERSE)!=0
            ?TextStyle.decodeForeColor(style):TextStyle.decodeBackColor(style);
        return (color&0xff000000)==0xff000000?color:palette[color];
    }
}
