#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc,char **argv) {
    if(argc<3)return 64;
    if(setsid()<0){perror("setsid");return 1;}
    if(prctl(PR_SET_PDEATHSIG,SIGKILL)<0){perror("prctl");return 1;}
    if(getppid()==1)return 1;
    int fd=open(argv[1],O_CREAT|O_WRONLY|O_TRUNC|O_CLOEXEC,0600);
    if(fd<0){perror("pid file");return 1;}
    char buf[32];
    int len=snprintf(buf,sizeof(buf),"%d\n",(int)getpid());
    if(len<=0 || (size_t)len>=sizeof(buf)){close(fd);return 1;}
    size_t off=0;
    while(off<(size_t)len){
        ssize_t n=write(fd,buf+off,(size_t)len-off);
        if(n<0){
            if(errno==EINTR)continue;
            perror("pid write");close(fd);return 1;
        }
        off+=(size_t)n;
    }
    if(close(fd)<0){perror("pid close");return 1;}
    execv(argv[2],argv+2);perror("execv");return errno==ENOENT?127:126;
}
