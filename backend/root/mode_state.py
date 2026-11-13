# SPDX-License-Identifier: GPL-3.0-only
import mmap
import os
import re
import stat
import struct
import zlib

WORDS=80
SIZE=4096
SLOT=2048
MAGIC=0x4c4d5331
BITS={1:1,5:2,6:4,7:8,25:16,66:32,1000:64,1002:128,1004:256,1006:512,2004:1024,69:2048,1003:8192,1016:16384}
ANNEX_OFFSET=1024
ANNEX_MAGIC=0x4c4d4b58
ANNEX_VERSION=1
ANNEX_TOTAL=112
ANNEX_HEADER=24
KB_WORDS=21
SPECIAL=re.compile(b'[\x0e\x0f\x1b]')
STRING_END=re.compile(b'[\x07\x18\x1a\x1b\\\\]')
MODIFY_CURRENT={1036:1,1035:2,67:3}
MODIFY_SAVED={1036:4,1035:5,67:6}

def defaults(cols,rows):
    values=[0]*WORDS
    values[:15]=[1,cols,rows,24,24,0,0,0,0,rows,0,cols,0,0,1]
    for at in (16,25):values[at+2:at+9]=[256,257,0,24,0,0,1]
    values[38:70]=[-1]*32;values[72:74]=[256,257]
    return values

def valid_checkpoint(v):
    if len(v)!=WORDS or v[0]!=1 or not (1<=v[1]<=65535 and 1<=v[2]<=65535):return False
    if any(not 0<=v[i]<=0x7fff for i in (3,4,21,30)):return False
    if any(v[i] not in (0,1) for i in (5,6,12,13,14,15,22,23,24,31,32,33,37)):return False
    if not 0<=v[7]<=2 or not (0<=v[8]<v[9]<=v[2] and 0<=v[10]<v[11]<=v[1]):return False
    if any(not 0<=v[i]<=65535 for i in (16,17,25,26)):return False
    if not (0<=v[74]<v[1] and 0<=v[75]<v[2]):return False
    if any(not (0<=v[i]<=258 or -0x1000000<=v[i]<0) for i in (18,19,27,28,72,73)):return False
    if any(not 0<=v[i]<=0x1ff for i in (20,29,71)):return False
    if v[34] not in (0,1,2,3,4,*range(6,24)) or not 0<=v[35]<32:return False
    if any(not -1<=n<=99999 for n in v[38:70]) or not 0<=v[70]<=3:return False
    prefix=struct.pack('>4I',*(n&0xffffffff for n in v[76:80]))
    if v[34] in (10,11):return prefix[0]<=15
    if v[34]==0:return prefix[0] in (0,0x81,0x82,0x83)
    return True

def default_keyboard():
    words=[0]*KB_WORDS
    words[0]=1
    words[1]=(1<<1)|(1<<2)|(1<<4)|(1<<5)
    return words

class ModeState:
    def __init__(self,fd,uid,size):
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size!=SIZE or info.st_uid not in (0,uid):
            os.close(fd);raise ValueError('Invalid mode metadata FD')
        self.fd=fd
        try:self.memory=mmap.mmap(fd,SIZE)
        except BaseException:os.close(fd);raise
        self.sequence=0;self.values=defaults(*size[:2]);self.osc=bytearray()
        self.keyboard=default_keyboard();self.kb_confident=False;self.kb_available=False
        self.kb_orphan=False;self.kb_partial=0
        self.load()

    def load(self):
        latest=None;serial=0;slot_index=-1;body_crc=0
        for slot in range(2):
            offset=slot*SLOT
            seq,magic,count=struct.unpack_from('>QII',self.memory,offset)
            if seq<=0 or seq<=serial or magic!=MAGIC or count!=WORDS:continue
            body=self.memory[offset+16:offset+16+WORDS*4]
            checksum=struct.unpack_from('>I',self.memory,offset+16+WORDS*4)[0]
            if zlib.crc32(body)!=checksum or struct.unpack_from('>Q',self.memory,offset)[0]!=seq:continue
            values=list(struct.unpack('>'+str(WORDS)+'i',body))
            if not valid_checkpoint(values):continue
            latest,serial,slot_index,body_crc=values,seq,slot,checksum
        if latest is not None:self.values,self.sequence=latest,serial
        prefix=struct.pack('>4I',*(n&0xffffffff for n in self.values[76:80]))
        self.osc=bytearray(prefix[1:1+prefix[0]]) if self.values[34] in (10,11) else bytearray()
        self.keyboard=default_keyboard();self.kb_confident=False;self.kb_available=False;self.kb_partial=0
        self.kb_orphan=False
        if latest is not None:
            found=self.load_annex(slot_index,serial,body_crc)
            if found is not None:
                self.keyboard,self.kb_confident=found
                self.kb_available=True
                self.kb_partial=self.keyboard[20]&0xff
            else:
                self.kb_orphan=self.any_annex()
        self._last=list(self.values);self._last_kb=list(self.keyboard)
        self._last_confident=self.kb_confident

    def _annex_bytes(self,slot):
        offset=slot*SLOT+ANNEX_OFFSET
        return self.memory[offset:offset+ANNEX_TOTAL]

    def _valid_payload(self,annex):
        if len(annex)!=ANNEX_TOTAL:return None
        magic,version,total=struct.unpack_from('>IHH',annex,0)
        if magic!=ANNEX_MAGIC or version!=ANNEX_VERSION or total!=ANNEX_TOTAL:return None
        checksum=struct.unpack_from('>I',annex,ANNEX_TOTAL-4)[0]
        if zlib.crc32(annex[:ANNEX_TOTAL-4])!=checksum:return None
        words=list(struct.unpack_from('>'+str(KB_WORDS)+'i',annex,ANNEX_HEADER))
        if words[0]!=1 or not 0<=words[1]<=255 or words[20] not in (0,60,61,62,63):return None
        if not 0<=words[2]<=7 or not 0<=words[11]<=7:return None
        for i in range(8):
            if not 0<=words[3+i]<=31 or not 0<=words[12+i]<=31:return None
        return words

    def load_annex(self,slot,serial,body_crc):
        annex=self._annex_bytes(slot)
        magic,version,total,annex_serial,annex_crc,flags=struct.unpack_from('>IHHQII',annex,0)
        if magic!=ANNEX_MAGIC or version!=ANNEX_VERSION or total!=ANNEX_TOTAL:return None
        if annex_serial!=serial or annex_crc!=body_crc:return None
        if struct.unpack_from('>Q',self.memory,slot*SLOT)[0]!=serial:return None
        words=self._valid_payload(annex)
        if words is None:return None
        if struct.unpack_from('>Q',self.memory,slot*SLOT)[0]!=serial:return None
        return words,bool(flags&1)

    def any_annex(self):
        return any(self._valid_payload(self._annex_bytes(slot)) is not None for slot in (0,1))

    def save(self):
        if self.values[34]==0:
            self.values[35:38]=[0]*3;self.values[38:70]=[-1]*32
        self.values[76:80]=[0]*4
        if self.values[34] in (10,11) and len(self.osc)<=15 and (b'133;'.startswith(self.osc) or self.osc.startswith(b'133;')):
            packed=bytes([len(self.osc)])+bytes(self.osc)+bytes(15-len(self.osc))
            self.values[76:80]=struct.unpack('>4i',packed)
        self.keyboard[20]=self.kb_partial&0xff
        if (self.values==self._last and self.keyboard==self._last_kb
                and self.kb_confident==self._last_confident):return
        body=struct.pack('>'+str(WORDS)+'i',*self.values)
        checksum=zlib.crc32(body)
        self.sequence+=1;offset=(self.sequence&1)*SLOT;serial=self.sequence
        struct.pack_into('>Q',self.memory,offset,0)
        self.memory[offset+8:offset+20+len(body)]=struct.pack('>II',MAGIC,WORDS)+body+struct.pack('>I',checksum)
        annex=bytearray(ANNEX_TOTAL)
        struct.pack_into('>IHHQII',annex,0,ANNEX_MAGIC,ANNEX_VERSION,ANNEX_TOTAL,serial,checksum,
                         1 if self.kb_confident else 0)
        struct.pack_into('>'+str(KB_WORDS)+'i',annex,ANNEX_HEADER,*self.keyboard)
        struct.pack_into('>I',annex,ANNEX_TOTAL-4,zlib.crc32(annex[:ANNEX_TOTAL-4]))
        self.memory[offset+ANNEX_OFFSET:offset+ANNEX_OFFSET+ANNEX_TOTAL]=annex
        struct.pack_into('>Q',self.memory,offset,serial)
        self._last=list(self.values);self._last_kb=list(self.keyboard)
        self._last_confident=self.kb_confident

    def close(self):
        self.memory.close();os.close(self.fd)

    def saved(self,restore):
        v=self.values;at=25 if v[5] else 16
        if restore:
            v[75],v[74]=v[at],v[at+1];v[72],v[73],v[71]=v[at+2:at+5]
            v[3]=(v[3]&~12)|(v[at+5]&12);v[12:15]=v[at+6:at+9]
        else:v[at:at+9]=[v[75],v[74],v[72],v[73],v[71],v[3],*v[12:15]]

    def kb_bit(self,bit):return (self.keyboard[1]>>bit)&1
    def kb_put(self,bit,value):
        if value:self.keyboard[1]|=1<<bit
        else:self.keyboard[1]&=~(1<<bit)
    def stack_words(self,screen=None):
        alt=self.values[5] if screen is None else int(screen)
        return 11 if alt else 2,12 if alt else 3
    def push_flags(self,flags):
        pos,base=self.stack_words();next_idx=(self.keyboard[pos]+1)%8
        self.keyboard[pos]=next_idx;self.keyboard[base+next_idx]=flags
    def pop_flags(self,count):
        idx,base=self.stack_words()
        if count>=8:
            self.keyboard[idx]=0;self.keyboard[base:base+8]=[0]*8;return
        for _ in range(count):
            self.keyboard[base+self.keyboard[idx]]=0
            self.keyboard[idx]=(self.keyboard[idx]-1)%8
    def set_flags(self,flags,mode):
        idx,base=self.stack_words();at=base+self.keyboard[idx]
        if mode==1:self.keyboard[at]=flags
        elif mode==2:self.keyboard[at]|=flags
        elif mode==3:self.keyboard[at]&=~flags

    def dec(self,mode,setting):
        v=self.values;bit=BITS.get(mode,0)
        if setting and bit in (64,128,8192):v[3]&=~(64|128|8192)
        v[3]=(v[3]|bit) if setting else (v[3]&~bit)
        if mode in MODIFY_CURRENT:self.kb_put(MODIFY_CURRENT[mode],setting)
        if mode in (47,1047,1049) and bool(v[5])!=setting:
            if setting:self.saved(False)
            v[5]=int(setting)
            if not setting:self.saved(True)
        elif mode==69 and not setting:v[10:12]=[0,v[1]]
        elif mode==1048:self.saved(not setting)
        elif mode==3:
            v[8:12]=[0,v[2],0,v[1]];v[3]&=~2048

    def soft_reset(self):
        v=self.values
        v[3]=(v[3]&~(BITS[1]|BITS[6]|BITS[66]|BITS[69]))|BITS[25]|BITS[7]
        v[6]=0;v[7]=0;v[8:12]=[0,v[2],0,v[1]]
        v[12:15]=[0,0,1];v[71:74]=[0,256,257]
        at=25 if v[5] else 16
        v[at:at+9]=[0,0,256,257,0,24,0,0,1]
        self.kb_put(0,False)

    def csi(self,final,state):
        v=self.values;args=v[38:39+v[35]]
        arg=lambda n,default:(args[n] if n<len(args) and args[n]>0 else default)
        count=v[35]+1 if v[38]>=0 or v[35]>0 else 0
        if state==7:
            for mode in args:
                if mode<0:continue
                if final in (104,108):self.dec(mode,final==104)
                elif final==115:
                    bit=BITS.get(mode,0);v[4]=(v[4]&~bit)|(v[3]&bit)
                    if mode in MODIFY_SAVED:self.kb_put(MODIFY_SAVED[mode],self.kb_bit(MODIFY_CURRENT[mode]))
                elif final==114:
                    self.dec(mode,bool(v[4]&BITS.get(mode,0)))
                    if mode in MODIFY_CURRENT:self.kb_put(MODIFY_CURRENT[mode],self.kb_bit(MODIFY_SAVED[mode]))
        elif state==6:
            if final in (104,108) and arg(0,0)==4:v[6]=int(final==104)
            elif final==114:
                v[8]=max(0,min(arg(0,1)-1,v[2]-2));v[9]=max(v[8]+2,min(arg(1,v[2]),v[2]))
            elif final==115:
                if v[3]&2048:
                    v[10]=max(0,min(arg(0,1)-1,v[1]-2));v[11]=max(v[10]+1,min(arg(1,v[1]),v[1]))
                else:self.saved(False)
            elif final==117:self.saved(True)
        elif state==19 and final==112:
            self.soft_reset()
        elif state==15 and final==113:
            style=arg(0,0);v[7]=0 if style<=2 else 1 if style<=4 else 2
        elif state==12 and final==117:
            if count==1 and 0<=args[0]<=31:self.push_flags(args[0])
            elif count!=1:self.push_flags(0)
        elif state==12 and final==109:
            if count<=2 and (count==0 or args[0] in (0,1,2,4)):
                self.kb_put(0,count==2 and args[0]==4 and args[1]==2)
        elif state==22 and final==117:
            if self.kb_partial==61:
                if count==0:
                    self.set_flags(0,1)
                elif 0<=args[0]<=31:
                    mode=args[1] if count>=2 else 1
                    if mode in (1,2,3):self.set_flags(args[0],mode)
            elif self.kb_partial==60:
                n=args[0] if count==1 and args[0]>=0 else 1
                self.pop_flags(n)

    def feed(self,data):
        v=self.values;at=0
        while at<len(data):
            state=v[34]
            if state==0:
                found=SPECIAL.search(data,at)
                if found is None:break
                at=found.start()
            elif state in (10,11,13,20,21) and (state not in (10,11) or len(self.osc)>=16):
                found=STRING_END.search(data,at)
                if found is None:break
                at=found.start()
            byte=data[at];at+=1
            if state in (10,11,13,20,21):
                escaped=state in (11,21) or (state==13 and v[37]!=0)
                if byte in (24,26) or (state in (10,11) and byte==7) or (escaped and byte==92):
                    if bytes(self.osc)==b'133;C':v[70]=1
                    elif self.osc.startswith(b'133;D;') and v[70]==1:
                        try:v[70]=2 if int(self.osc[6:])==0 else 3
                        except ValueError:pass
                    v[34]=v[37]=0;self.osc.clear()
                else:
                    if state in (10,11) and len(self.osc)<16 and byte!=27:self.osc.append(byte)
                    if state in (10,11):v[34]=11 if byte==27 else 10
                    elif state in (20,21):v[34]=21 if byte==27 else 20
                    else:v[37]=int(byte==27)
                continue
            if byte in (24,26):v[34]=0;self.kb_partial=0;continue
            if byte==27:v[34]=1;self.kb_partial=0;continue
            if byte in (14,15):v[14]=int(byte==15);continue
            if state==1:
                v[34]={91:6,93:10,80:13,95:20,40:3,41:4,35:2,37:9}.get(byte,0)
                if byte==91:v[35]=v[36]=0;v[38:70]=[-1]*32
                elif byte==93:self.osc.clear()
                elif byte in (61,62):self.dec(66,byte==61)
                elif byte in (55,56):self.saved(byte==56)
                elif byte==99:
                    cols,rows=v[1:3];v[:]=defaults(cols,rows)
                    self.keyboard=default_keyboard();self.kb_partial=0;self.osc.clear()
            elif state in (3,4):
                v[12 if state==3 else 13]=int(byte==48);v[34]=0
            elif state in (2,9):v[34]=0
            elif state in (6,7,8,12,14,15,16,17,18,19,22,23):
                if 48<=byte<=57:
                    index=38+v[35];v[index]=min(9999,max(0,v[index]))*10+byte-48
                elif byte in (59,58):v[35]=min(31,v[35]+1)
                elif byte==63 and state==6:v[34]=7;self.kb_partial=63
                elif byte==32:v[34]=15
                elif byte in (62,60,61,36,33,34,39,42):
                    v[34]={62:12,36:14 if state==7 else 8,33:19,34:17,39:18,42:16}.get(byte,22)
                    if byte in (62,60,61):self.kb_partial=byte
                elif 64<=byte<=126:
                    self.csi(byte,state);v[34]=0;self.kb_partial=0
        self.save()
