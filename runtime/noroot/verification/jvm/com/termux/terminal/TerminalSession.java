package com.termux.terminal;
public class TerminalSession {
    public interface ProcessTerminator {void terminate(int pid);}
    public final String executable;
    public final String[] args,environment;
    public TerminalSession(String executable,String cwd,String[] args,String[] environment,int rows,TerminalSessionClient client){this.executable=executable;this.args=args;this.environment=environment;}
    public TerminalSession(String executable,String cwd,String[] args,String[] environment,int rows,TerminalSessionClient client,ProcessTerminator terminator,TerminalEmulatorFactory factory){this(executable,cwd,args,environment,rows,client);}
}
