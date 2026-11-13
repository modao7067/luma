#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile
R=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='luma-row-') as tmp:
 p=Path(tmp);driver=p/'RowCheck.java'
 driver.write_text('''import com.termux.terminal.TerminalRow;
public class RowCheck {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  TerminalRow row=new TerminalRow(2,0);long[] style={0,0};int[] start={0,1},width={1,1};
  check(!row.importFrameCells(new char[]{' ',' ','x'},2,style,start,width));
  check(row.getRevision()==0);
  check(row.importFrameCells(new char[]{'a','b','x'},2,style,start,width));
  check(!row.importFrameCells(new char[]{'a','b','y'},2,style,start,width));
  check(row.getRevision()==1);
  check(row.importFrameCells(new char[]{'a','c'},2,style,start,width));
  check(row.importFrameCells(new char[]{'a','c'},2,new long[]{1,0},start,width));
  check(row.importFrameCells(new char[]{'a','c'},2,new long[]{1,0},new int[]{0,0},width));
  check(row.importFrameCells(new char[]{'a','c'},2,new long[]{1,0},new int[]{0,0},new int[]{2,0}));
  check(row.importFrameCells(new char[]{'a'},1,style,new int[]{0,1},width));
  check(row.importFrameCells(new char[]{'a','b','c'},3,style,start,width));
  check(!row.importFrameCells(new char[]{'a','b','c','z'},3,style,start,width));
  check(row.getSpaceUsed()==3 && row.getRevision()==7);
  System.out.println("PASS terminal row prefix, length, style, geometry and revision semantics");
 }
}''')
 subprocess.run(['javac','--release','17','-d',tmp,str(R/'shared/terminal-emulator/java/com/termux/terminal/TerminalRow.java'),str(driver)],check=True)
 subprocess.run(['java','-cp',tmp,'RowCheck'],check=True)
