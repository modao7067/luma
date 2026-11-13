package io.github.modao7067.luma.root;

final class ColdStartRepaint {
    interface Host {
        long now();
        void later(Runnable task,long delay);
        void remove(Runnable task);
        void resize(int columns,int rows,int cellWidth,int cellHeight);
        void inspect();
        void redraw();
    }
    private final Host host;
    private boolean used,active,acknowledged,output;
    private int cols,rows,cw,ch,probe;
    private long earliest;
    private final Runnable minimum=this::afterMinimum,deadline=this::finish;
    ColdStartRepaint(Host host){this.host=host;}
    boolean isActive(){return active;}
    void start(int columns,int lines,int width,int height){
        if(used)return;used=true;
        cols=columns;rows=lines;cw=width;ch=height;
        probe=rows<1000&&(long)(rows+1)*ch<=65535 ? rows+1 : rows-1;
        if(probe<2){host.redraw();return;}
        active=true;
        host.resize(cols,probe,cw,ch);host.inspect();
        host.later(deadline,1000);
    }
    void sizeConfirmed(int columns,int lines,int width,int height){
        if(!active||acknowledged||columns!=cols||lines!=probe||width!=cw||height!=ch)return;
        acknowledged=true;earliest=host.now()+250;
        host.remove(deadline);host.later(deadline,1000);host.later(minimum,250);
    }
    void output(){
        if(!active||!acknowledged)return;
        output=true;if(host.now()>=earliest)finish();
    }
    private void afterMinimum(){if(output)finish();}
    private void finish(){
        if(!active)return;
        cancel();host.resize(cols,rows,cw,ch);host.redraw();
    }
    void cancel(){active=false;host.remove(minimum);host.remove(deadline);}
}
