package io.github.modao7067.luma.root;

import android.net.LocalSocket;
import android.system.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class DirectPty {
    private final LocalSocket lease;
    private final FileDescriptor fd;
    private final FileDescriptor[] wake;
    private final AtomicBoolean stopped=new AtomicBoolean(),disposed=new AtomicBoolean();
    final InputStream input=new InputStream(){
        @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int offset,int length)throws IOException{
            if(length==0)return 0;
            while(!stopped.get())try{
                int count=Os.read(fd,b,offset,length);
                return count==0?-1:count;
            }catch(ErrnoException e){
                if(e.errno==OsConstants.EINTR)continue;
                if(e.errno==OsConstants.EAGAIN){if(!await(OsConstants.POLLIN))return -1;continue;}
                if(e.errno==OsConstants.EIO)return -1;
                throw new IOException(e);
            }
            return -1;
        }
        @Override public void close(){stop();}
    };
    final OutputStream output=new OutputStream(){
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] b,int offset,int length)throws IOException{
            int end=offset+length;
            while(offset<end&&!stopped.get())try{offset+=Os.write(fd,b,offset,end-offset);}
            catch(ErrnoException e){
                if(e.errno==OsConstants.EINTR)continue;
                if(e.errno==OsConstants.EAGAIN){if(!await(OsConstants.POLLOUT))return;continue;}
                throw new IOException(e);
            }
        }
        @Override public void close(){stop();}
    };
    DirectPty(LocalSocket lease,FileDescriptor fd)throws IOException{
        this.lease=lease;this.fd=fd;
        try{
            Os.fcntlInt(fd,OsConstants.F_SETFL,Os.fcntlInt(fd,OsConstants.F_GETFL,0)|OsConstants.O_NONBLOCK);
            wake=Os.pipe();
        }catch(ErrnoException e){try{Os.close(fd);}catch(Exception ignored){}lease.close();throw new IOException(e);}
    }
    private boolean await(int events)throws IOException{
        StructPollfd pty=new StructPollfd();pty.fd=fd;pty.events=(short)events;
        StructPollfd cancel=new StructPollfd();cancel.fd=wake[0];cancel.events=(short)OsConstants.POLLIN;
        while(!stopped.get())try{
            Os.poll(new StructPollfd[]{pty,cancel},-1);
            return !stopped.get()&&cancel.revents==0;
        }catch(ErrnoException e){if(e.errno!=OsConstants.EINTR)throw new IOException(e);}
        return false;
    }
    void stop(){
        if(stopped.compareAndSet(false,true))try{Os.write(wake[1],new byte[]{1},0,1);}catch(Exception ignored){}
    }
    void release(){
        if(!disposed.compareAndSet(false,true))return;
        stop();
        for(FileDescriptor item:new FileDescriptor[]{fd,wake[0],wake[1]})try{Os.close(item);}catch(Exception ignored){}
        try{lease.close();}catch(IOException ignored){}
    }
}
