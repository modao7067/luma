package io.github.modao7067.luma.root;
import java.util.*;
public final class ColdStartRepaintCheck {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static final class Fake implements ColdStartRepaint.Host {
        long time;Map<Runnable,Long> tasks=new LinkedHashMap<>();List<String> sizes=new ArrayList<>();int inspected,redrawn;
        public long now(){return time;}
        public void later(Runnable r,long delay){tasks.put(r,time+delay);}
        public void remove(Runnable r){tasks.remove(r);}
        public void resize(int c,int r,int w,int h){sizes.add(c+":"+r+":"+w+":"+h);}
        public void inspect(){inspected++;}public void redraw(){redrawn++;}
        void advance(long target){while(true){Map.Entry<Runnable,Long> next=tasks.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);if(next==null||next.getValue()>target)break;time=next.getValue();Runnable r=next.getKey();tasks.remove(r);r.run();}time=target;}
    }
    public static void main(String[] args){
        Fake h=new Fake();ColdStartRepaint p=new ColdStartRepaint(h);p.start(80,24,12,24);
        check(h.sizes.equals(List.of("80:25:12:24"))&&h.inspected==1,"only a row changes, columns and cell size preserved");
        p.sizeConfirmed(80,24,12,24);p.output();h.advance(999);check(h.sizes.size()==1,"unrelated inspect/output does not acknowledge probe");
        p.sizeConfirmed(80,25,12,24);p.output();h.advance(1248);check(p.isActive(),"minimum hold starts at actual keeper acknowledgement");
        h.advance(1249);check(h.sizes.equals(List.of("80:25:12:24","80:24:12:24"))&&h.redrawn==1&&!p.isActive()&&h.tasks.isEmpty(),"output after acknowledged resize restores exact dimensions once");
        p.start(80,24,12,24);check(h.sizes.size()==2,"no repeated pulse on resume");
        h=new Fake();p=new ColdStartRepaint(h);p.start(80,24,12,24);h.advance(1000);check(h.sizes.size()==2&&!p.isActive(),"missing acknowledgement has bounded fallback");
        h=new Fake();p=new ColdStartRepaint(h);p.start(80,24,12,24);p.sizeConfirmed(80,25,12,24);h.advance(999);check(p.isActive(),"no output waits for bounded deadline");h.advance(1000);check(!p.isActive()&&h.sizes.size()==2,"silent TUI does not leave PTY at probe dimensions");
        h=new Fake();p=new ColdStartRepaint(h);p.start(80,24,12,24);p.sizeConfirmed(80,25,12,24);p.cancel();p.output();p.sizeConfirmed(80,25,12,24);h.advance(5000);check(h.sizes.size()==1&&h.tasks.isEmpty(),"real resize/detach cancels every timer and stale acknowledgement");
        for(int[] size:new int[][]{{80,1000,12,24},{80,16,12,4096},{80,2,12,24}}){h=new Fake();p=new ColdStartRepaint(h);p.start(size[0],size[1],size[2],size[3]);int expected=size[1]==2?3:size[1]-1;check(h.sizes.get(0).equals("80:"+expected+":12:"+size[3]),"probe respects row and pixel limits");}
        System.out.println("PASS "+checks+" cold repaint timing, acknowledgement, cancellation, once-only and size bounds");
    }
}
