package io.github.modao7067.luma;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class TaskRestartCheck {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    private static final class Child extends Process {
        private final CountDownLatch exited=new CountDownLatch(1);
        private volatile int code;
        void exit(int value){code=value;exited.countDown();}
        public boolean isAlive(){return exited.getCount()!=0;}
        public int waitFor()throws InterruptedException{exited.await();return code;}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return exited.await(timeout,unit);}
        public int exitValue(){if(isAlive())throw new IllegalThreadStateException();return code;}
        public void destroy(){exit(143);}
        public Process destroyForcibly(){exit(137);return this;}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
    }
    private static final class Processes implements TaskRegistry.ProcessOps {
        final List<Child> children=new CopyOnWriteArrayList<>();
        public Process start(ProcessBuilder builder)throws IOException{
            Files.writeString(Path.of(builder.command().get(1)),"-1");Child child=new Child();children.add(child);return child;
        }
        public void kill(int pid,String token,long grace,boolean linux){}
    }
    private static JSONObject request(String id){
        return new JSONObject().put("id",id).put("args",new JSONArray().put("/bin/true"))
            .put("probeType","none").put("restartPolicy","on-failure").put("maxRestarts",3).put("gracePeriodMs",200);
    }
    private static JSONObject await(TaskRegistry registry,String id,String phase,int restarts)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        JSONObject status;
        do{
            status=registry.getStatus(id);
            if(status.getString("phase").equals(phase)&&status.getInt("restartCount")==restarts)return status;
            Thread.sleep(5);
        }while(System.nanoTime()<until);
        throw new AssertionError("state not reached: "+phase+"/"+restarts+" actual="+status);
    }
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]);Files.createDirectories(base);
        Context context=new Context(base.resolve("stop").toFile(),base.resolve("assets").toFile());
        Processes processes=new Processes();
        try(TaskRegistry registry=new TaskRegistry(context,null,processes)){
            registry.startTask(request("svc"));await(registry,"svc","running",0);processes.children.get(0).exit(1);
            await(registry,"svc","starting",1);
            long start=System.nanoTime();JSONObject stopped=registry.stopTask("svc",0,false);
            check(stopped.getString("phase").equals("exited")&&stopped.getJSONObject("exit").getString("reason").equals("user_stop"),"stop during restart backoff settles at exited/user_stop");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<450,"stop wakes immediately rather than waiting for backoff");
            check(registry.waitTask("svc","exited",100).getBoolean("waitSatisfied"),"exit wait is satisfied after stopping during backoff");
            Path record=context.getCacheDir().toPath().resolve("services/svc.json");
            check(new JSONObject(Files.readString(record)).getString("phase").equals("exited"),"persisted record leaves stopping");
            registry.startTask(request("svc"));await(registry,"svc","running",0);Thread.sleep(650);
            check(processes.children.size()==2&&processes.children.get(1).isAlive(),"same ID restarts and the cancelled waiter never launches another process");
            processes.children.get(1).exit(2);await(registry,"svc","running",1);
            check(processes.children.size()==3,"on-failure still restarts normally when no stop was requested");
            processes.children.get(2).exit(3);await(registry,"svc","starting",2);
            stopped=registry.stopTask("svc",0,false);
            check(stopped.getString("phase").equals("exited"),"stop also settles during the longer second backoff");
            check(registry.stopTask("svc",0,false).getString("phase").equals("exited"),"repeated stop remains idempotent");
        }
        Context closing=new Context(base.resolve("close").toFile(),base.resolve("assets").toFile());
        Processes closeProcesses=new Processes();TaskRegistry closingRegistry=new TaskRegistry(closing,null,closeProcesses);
        try{
            closingRegistry.startTask(request("closing"));await(closingRegistry,"closing","running",0);
            closeProcesses.children.get(0).exit(1);await(closingRegistry,"closing","starting",1);closingRegistry.close();
            JSONObject status=closingRegistry.getStatus("closing");
            check(status.getString("phase").equals("exited")&&status.getJSONObject("exit").getString("reason").equals("app_task_removed"),"registry shutdown cancels backoff and records app_task_removed");
            Thread.sleep(650);check(closeProcesses.children.size()==1,"shutdown never spawns a replacement process");
        }finally{closingRegistry.close();}
        System.out.println("ALL PASS "+checks+" supervisor restart/stop checks");
    }
}
