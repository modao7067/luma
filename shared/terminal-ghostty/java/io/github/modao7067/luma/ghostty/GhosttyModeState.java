package io.github.modao7067.luma.ghostty;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class GhosttyModeState {
    private static final int[] MODES = {1,5,6,7,25,66,1000,1002,1004,1006,2004,69,1003,1016};
    private static final int[] BITS = {1,2,4,8,16,32,64,128,256,512,1024,2048,8192,16384};
    private static void mode(StringBuilder out,int mode,boolean set) { out.append("\033[?").append(mode).append(set?'h':'l'); }
    private static void color(StringBuilder out,int value,boolean foreground) {
        if(value==(foreground?256:257)) {out.append("\033[").append(foreground?39:49).append('m');return;}
        out.append("\033[").append(foreground?38:48);
        if(value>=0&&value<256)out.append(";5;").append(value);
        else out.append(";2;").append((value>>16)&255).append(';').append((value>>8)&255).append(';').append(value&255);
        out.append('m');
    }
    private static void style(StringBuilder out,int effect,int foreground,int background) {
        out.append("\033[0");
        int[] masks={1,2,4,8,16,32,64,256}; int[] sgr={1,3,4,5,7,8,9,2};
        for(int i=0;i<masks.length;i++)if((effect&masks[i])!=0)out.append(';').append(sgr[i]);
        out.append('m');color(out,foreground,true);color(out,background,false);
    }
    private static void charset(StringBuilder out,int[] state,int at) {
        out.append("\033(").append(state[at]!=0?'0':'B').append("\033)").append(state[at+1]!=0?'0':'B');
        out.append(state[at+2]!=0?'\017':'\016');
    }
    private static void cursor(StringBuilder out,int row,int col) { out.append("\033[").append(row+1).append(';').append(col+1).append('H'); }
    private static void saved(StringBuilder out,int[] state,int at) {
        mode(out,6,false);cursor(out,Math.max(0,state[at]),Math.max(0,state[at+1]));
        style(out,state[at+4],state[at+2],state[at+3]);charset(out,state,at+6);
        mode(out,6,(state[at+5]&4)!=0);cursor(out,Math.max(0,state[at]),Math.max(0,state[at+1]));out.append("\0337");
    }
    static byte[] restore(int[] v,int columns,int rows) { return restore(v,0,columns,rows); }
    static byte[] restore(int[] v,int partialPrefix,int columns,int rows) {
        if(v==null||v.length!=80||v[0]!=1||v[1]<2||v[2]<2)throw new IllegalArgumentException("Invalid terminal modes");
        StringBuilder out=new StringBuilder("\033c");
        saved(out,v,16);
        out.append("\033[?47h");saved(out,v,25);out.append("\033[?47l\0338");
        if(v[5]!=0)out.append("\033[?1049h");
        for(int i=0;i<MODES.length;i++){mode(out,MODES[i],(v[4]&BITS[i])!=0);out.append("\033[?").append(MODES[i]).append('s');}
        for(int i=0;i<MODES.length;i++)if(MODES[i]!=6)mode(out,MODES[i],(v[3]&BITS[i])!=0);
        out.append("\033[4").append(v[6]!=0?'h':'l');
        int top=Math.max(0,Math.min(rows-2,v[8]));int bottom=v[9]==v[2]?rows:Math.max(top+2,Math.min(rows,v[9]));
        int left=Math.max(0,Math.min(columns-2,v[10]));int right=v[11]==v[1]?columns:Math.max(left+2,Math.min(columns,v[11]));
        out.append("\033[").append(top+1).append(';').append(bottom).append('r');
        if((v[3]&2048)!=0)out.append("\033[").append(left+1).append(';').append(right).append('s');
        charset(out,v,12);style(out,v[71],v[72],v[73]);
        out.append("\033[").append(v[7]==1?4:v[7]==2?6:2).append(" q");
        boolean origin=(v[3]&4)!=0;mode(out,6,origin);
        int row=Math.max(0,Math.min(rows-1,v[75]));int col=Math.max(0,Math.min(columns-1,v[74]));
        cursor(out,origin?Math.max(0,row-top):row,origin?Math.max(0,col-left):col);
        pending(out,v,partialPrefix);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] setup=out.toString().getBytes(StandardCharsets.UTF_8);bytes.write(setup,0,setup.length);
        if(v[34]==0&&((v[76]>>>24)&0x80)!=0){int length=(v[76]>>>24)&3;for(int i=0;i<length;i++)bytes.write((v[76]>>>(16-i*8))&255);}
        return bytes.toByteArray();
    }
    private static void pending(StringBuilder out,int[] v,int partialPrefix) {
        int state=v[34];
        if(state==0)return;
        if(state==1){out.append('\033');return;}
        if(state==2||state==3||state==4||state==9){out.append('\033').append(state==2?'#':state==3?'(':state==4?')':'%');return;}
        if(state==10||state==11){
            out.append("\033]");int count=(v[76]>>>24)&255;
            if(count>0&&count<=15){for(int i=0;i<count;i++)out.append((char)((v[76+(i+1)/4]>>>(24-((i+1)%4)*8))&255));}
            else out.append("999999;");
            if(state==11)out.append('\033');return;
        }
        if(state==13){out.append("\033P0q");if(v[37]!=0)out.append('\033');return;}
        if(state==20||state==21){out.append("\033_X");if(state==21)out.append('\033');return;}
        out.append("\033[");if(state==7||state==14)out.append('?');else if(state==12)out.append('>');
        else if((state==6||state==22)&&(partialPrefix=='<'||partialPrefix=='='||partialPrefix=='>'||partialPrefix=='?'))out.append((char)partialPrefix);
        for(int i=0;i<=Math.min(31,Math.max(0,v[35]));i++){
            if(i>0)out.append((v[36]&(1<<(i-1)))!=0?':':';');if(v[38+i]>=0)out.append(v[38+i]);
        }
        if(state==8||state==14)out.append('$');else if(state==15)out.append(' ');else if(state==16)out.append('*');
        else if(state==17)out.append('"');else if(state==18)out.append('\'');else if(state==19)out.append('!');
    }
    static boolean modeChanged(int[] old,int[] next) {
        if(old==null)return true;
        for(int i=3;i<70;i++)if(old[i]!=next[i])return true;
        for(int i=76;i<80;i++)if(old[i]!=next[i])return true;
        return false;
    }
}
