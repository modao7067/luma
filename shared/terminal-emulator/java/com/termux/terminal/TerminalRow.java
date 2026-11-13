package com.termux.terminal;
import java.util.Arrays;
/** Reusable read-only projection with complete graphemes and native column geometry. */
public final class TerminalRow {
    public char[] mText;public final long[] mStyle;public boolean mLineWrap;
    private final int columns;private int used;private long revision;private int[]starts,widths;
    public TerminalRow(int columns,long style){this.columns=columns;mText=new char[columns];Arrays.fill(mText,' ');mStyle=new long[columns];Arrays.fill(mStyle,style);used=columns;starts=new int[columns];widths=new int[columns];for(int i=0;i<columns;i++){starts[i]=i;widths[i]=1;}}
    public int getSpaceUsed(){return used;}public long getStyle(int column){return mStyle[column];}public long getRevision(){return revision;}
    public boolean hasFrameGeometry(){return true;}public int frameColumnStart(int column){return findStartOfColumn(column);}
    public int frameColumnWidth(int column){return column>=0&&column<columns?widths[column]:0;}
    public int findStartOfColumn(int column){return column>=columns?used:starts[Math.max(0,column)];}
    public boolean hasNonOneWidthOrSurrogateChars(){for(int w:widths)if(w!=1)return true;return false;}
    // Arrays.equals(char[], from, to, ...) requires Android 13; support API 31.
    private static boolean sameText(char[] left,char[] right,int length){
        for(int i=0;i<length;i++)if(left[i]!=right[i])return false;
        return true;
    }
    public boolean importFrameCells(char[]text,int length,long[]styles,int[]columnStart,int[]columnWidth){
        boolean changed=length!=used||!sameText(mText,text,length)||!Arrays.equals(mStyle,styles)||!Arrays.equals(starts,columnStart)||!Arrays.equals(widths,columnWidth);
        if(!changed)return false;if(mText.length<length)mText=new char[Math.max(length,columns*2)];
        System.arraycopy(text,0,mText,0,length);used=length;System.arraycopy(styles,0,mStyle,0,columns);starts=columnStart.clone();widths=columnWidth.clone();revision++;return true;
    }
}
