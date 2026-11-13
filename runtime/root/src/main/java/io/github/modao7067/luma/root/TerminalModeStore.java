package io.github.modao7067.luma.root;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.zip.CRC32;
import android.system.Os;
import com.termux.terminal.TerminalEmulator;

final class TerminalModeStore implements AutoCloseable {
    static final int SIZE=4096,SLOT=2048,MAGIC=0x4c4d5331;
    static final int ANNEX_OFFSET=1024,ANNEX_MAGIC=0x4c4d4b58,ANNEX_VERSION=1;
    static final int ANNEX_TOTAL=112,ANNEX_HEADER=24,ANNEX_PAYLOAD=TerminalEmulator.KEYBOARD_STATE_WORDS*4;
    final File file;
    private final RandomAccessFile channel;
    private final MappedByteBuffer memory;
    private int[] last;
    private int[] lastKeyboard;
    private boolean lastConfident;
    private long sequence;
    private final java.util.concurrent.atomic.AtomicLong publishFence=new java.util.concurrent.atomic.AtomicLong();

    static final class Checkpoint {
        final int[] modes;
        final int[] keyboard;
        final boolean confident;
        final boolean orphan;
        final boolean empty;
        Checkpoint(int[] modes,int[] keyboard,boolean confident,boolean orphan,boolean empty){
            this.modes=modes;this.keyboard=keyboard;this.confident=confident;this.orphan=orphan;this.empty=empty;
        }
    }

    TerminalModeStore(File directory,String id)throws IOException{
        File folder=new File(directory,"terminal-modes");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建终端模式目录");
        file=new File(folder,id+".modes");channel=new RandomAccessFile(file,"rw");
        try{
            Os.chmod(folder.getAbsolutePath(),0700);Os.chmod(file.getAbsolutePath(),0600);
            if(channel.length()!=SIZE)channel.setLength(SIZE);
            memory=channel.getChannel().map(FileChannel.MapMode.READ_WRITE,0,SIZE);memory.order(ByteOrder.BIG_ENDIAN);
        }catch(Exception e){channel.close();throw new IOException(e);}
    }
    FileDescriptor descriptor()throws IOException{return channel.getFD();}

    private static ByteBuffer slotReader(MappedByteBuffer memory,int slot){
        ByteBuffer data=memory.duplicate().order(ByteOrder.BIG_ENDIAN);data.position(slot*SLOT);return data;
    }

    synchronized Checkpoint load(){
        int[] best=null;long newest=0;int bestSlot=-1;int bestCrc=0;
        for(int slot=0;slot<2;slot++){
            ByteBuffer data=slotReader(memory,slot);
            long serial=data.getLong();int magic=data.getInt(),length=data.getInt();
            if(serial<=0||serial<=newest||magic!=MAGIC||length!=TerminalEmulator.MODE_STATE_WORDS)continue;
            byte[] body=new byte[length*4];data.get(body);int crc=data.getInt();
            CRC32 check=new CRC32();check.update(body);
            if((int)check.getValue()!=crc||memory.getLong(slot*SLOT)!=serial)continue;
            int[] values=new int[length];ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(values);
            if(values[0]!=1)continue;
            newest=serial;best=values;bestSlot=slot;bestCrc=crc;
        }
        sequence=newest;last=best==null?null:best.clone();
        if(best==null){
            lastKeyboard=null;lastConfident=false;
            return new Checkpoint(null,null,false,hasAnyAnnex(),allZero());
        }
        boolean[] confident={false};
        int[] keyboard=readBoundAnnex(bestSlot,newest,bestCrc,confident);
        lastKeyboard=keyboard==null?null:keyboard.clone();lastConfident=keyboard!=null&&confident[0];
        boolean orphan=keyboard==null&&hasAnyAnnex();
        return new Checkpoint(best,keyboard,lastConfident,orphan,false);
    }

    private boolean allZero(){
        byte[] data=new byte[SIZE];
        ByteBuffer buffer=memory.duplicate();buffer.position(0);buffer.get(data);
        for(byte value:data)if(value!=0)return false;
        return true;
    }

    private byte[] annexBytes(int slot){
        byte[] annex=new byte[ANNEX_TOTAL];
        ByteBuffer data=memory.duplicate().order(ByteOrder.BIG_ENDIAN);
        data.position(slot*SLOT+ANNEX_OFFSET);data.get(annex);return annex;
    }

    private static int[] validPayload(byte[] annex){
        if(annex.length<ANNEX_TOTAL)return null;
        ByteBuffer data=ByteBuffer.wrap(annex).order(ByteOrder.BIG_ENDIAN);
        if(data.getInt(0)!=ANNEX_MAGIC||(data.getShort(4)&0xffff)!=ANNEX_VERSION||
                (data.getShort(6)&0xffff)!=ANNEX_TOTAL)return null;
        CRC32 check=new CRC32();check.update(annex,0,ANNEX_TOTAL-4);
        if((int)check.getValue()!=data.getInt(ANNEX_TOTAL-4))return null;
        int[] words=new int[TerminalEmulator.KEYBOARD_STATE_WORDS];
        for(int i=0;i<words.length;i++)words[i]=data.getInt(ANNEX_HEADER+i*4);
        if(words[0]!=1)return null;
        if(words[2]<0||words[2]>7||words[11]<0||words[11]>7)return null;
        for(int i=0;i<8;i++)if(words[3+i]<0||words[3+i]>31||words[12+i]<0||words[12+i]>31)return null;
        return words;
    }

    private int[] readBoundAnnex(int slot,long serial,int bodyCrc,boolean[] confidentOut){
        byte[] annex=annexBytes(slot);
        ByteBuffer data=ByteBuffer.wrap(annex).order(ByteOrder.BIG_ENDIAN);
        if(data.getInt(0)!=ANNEX_MAGIC||(data.getShort(4)&0xffff)!=ANNEX_VERSION||
                (data.getShort(6)&0xffff)!=ANNEX_TOTAL)return null;
        if(data.getLong(8)!=serial||data.getInt(16)!=bodyCrc)return null;
        if(memory.getLong(slot*SLOT)!=serial)return null;
        int[] words=validPayload(annex);
        if(words==null)return null;
        confidentOut[0]=(data.getInt(20)&1)!=0;
        return words;
    }

    private boolean hasAnyAnnex(){
        for(int slot=0;slot<2;slot++)if(validPayload(annexBytes(slot))!=null)return true;
        return false;
    }

    synchronized void save(int[] values){
        int[] keyboard=new int[TerminalEmulator.KEYBOARD_STATE_WORDS];
        keyboard[0]=1;keyboard[1]=(1<<1)|(1<<2)|(1<<4)|(1<<5);
        save(values,keyboard,false);
    }

    synchronized void save(int[] values,int[] keyboard,boolean confident){
        if(Arrays.equals(last,values)&&Arrays.equals(lastKeyboard,keyboard)&&lastConfident==confident)return;
        if(values.length!=TerminalEmulator.MODE_STATE_WORDS)throw new IllegalArgumentException("Invalid mode metadata");
        if(keyboard==null||keyboard.length!=TerminalEmulator.KEYBOARD_STATE_WORDS)throw new IllegalArgumentException("Invalid keyboard metadata");
        byte[] body=new byte[values.length*4];ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).asIntBuffer().put(values);
        CRC32 check=new CRC32();check.update(body);long serial=sequence+1;int offset=(int)(serial&1)*SLOT;
        memory.putLong(offset,0);
        ByteBuffer data=memory.duplicate().order(ByteOrder.BIG_ENDIAN);data.position(offset+8);
        data.putInt(MAGIC).putInt(values.length).put(body).putInt((int)check.getValue());
        byte[] annex=new byte[ANNEX_TOTAL];ByteBuffer framed=ByteBuffer.wrap(annex).order(ByteOrder.BIG_ENDIAN);
        framed.putInt(ANNEX_MAGIC).putShort((short)ANNEX_VERSION).putShort((short)ANNEX_TOTAL)
              .putLong(serial).putInt((int)check.getValue()).putInt(confident?1:0);
        for(int word:keyboard)framed.putInt(word);
        CRC32 annexCheck=new CRC32();annexCheck.update(annex,0,ANNEX_TOTAL-4);
        framed.putInt((int)annexCheck.getValue());
        ByteBuffer annexOut=memory.duplicate().order(ByteOrder.BIG_ENDIAN);
        annexOut.position(offset+ANNEX_OFFSET);annexOut.put(annex);
        publishFence.getAndSet(serial);memory.putLong(offset,serial);
        sequence=serial;last=values.clone();lastKeyboard=keyboard.clone();lastConfident=confident;
    }
    @Override public void close(){try{channel.close();}catch(IOException ignored){}}
}
