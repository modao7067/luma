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
    dprintf(fd,"%d\n",getpid());close(fd);
    execv(argv[2],argv+2);perror("execv");return errno==ENOENT?127:126;
}
