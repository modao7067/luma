import array
import json
import os
import select
import socket
import time

class Connection:
    def __init__(self,address,token,role='control'):
        self.socket=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.socket.settimeout(5)
        self.socket.connect('\0'+address)
        self.rights=[]
        self.send(op='hello',token=token,role=role);self.initial=self.read()
        assert self.initial['event']=='list' and self.initial['version']==3,self.initial
    def send(self,**value):self.socket.sendall(json.dumps(value).encode()+b'\n')
    def read(self):
        data=bytearray()
        while True:
            chunk,ancillary,flags,_=self.socket.recvmsg(1,socket.CMSG_SPACE(4*8))
            assert chunk,'Unexpected disconnect'
            for level,kind,value in ancillary:
                if level==socket.SOL_SOCKET and kind==socket.SCM_RIGHTS:
                    values=array.array('i');values.frombytes(value[:len(value)//values.itemsize*values.itemsize]);self.rights.extend(values)
            if chunk==b'\n':return json.loads(data)
            data.extend(chunk)
    def until(self,kind,ident=None):
        for _ in range(100):
            event=self.read()
            assert event['event']!='error',event
            if event['event']==kind and (ident is None or event.get('id')==ident):return event
        raise AssertionError(kind+' not received')
    def close(self):
        for fd in self.rights:os.close(fd)
        self.rights.clear();self.socket.close()

class Lease(Connection):
    def __init__(self,address,token,ident):
        super().__init__(address,token,'lease');self.send(op='lease',id=ident);self.packet=self.until('leased',ident)
        assert self.packet['hasFd'] and len(self.rights)==1,self.packet
        self.fd=self.rights[0]
    def input(self,data):
        if isinstance(data,str):data=data.encode()
        while data:
            try:count=os.write(self.fd,data);data=data[count:]
            except BlockingIOError:assert select.select([],[self.fd],[],5)[1]
    def output_until(self,marker,timeout=5):
        data=bytearray();deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            assert select.select([self.fd],[],[],max(0,deadline-time.monotonic()))[0],repr(data[-200:])
            try:chunk=os.read(self.fd,65536)
            except BlockingIOError:continue
            assert chunk,'PTY closed before expected output'
            data.extend(chunk)
            if marker in data:return bytes(data)
        raise AssertionError('Missing '+repr(marker))
