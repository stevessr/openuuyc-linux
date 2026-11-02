#!/usr/bin/env python3
"""Explicit synthetic IPC fixture -> real Monado/OpenXR -> poses. No production state."""
import argparse
import json
import mmap
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import threading
import time

p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--smoke',required=True);p.add_argument('--probe',required=True);args=p.parse_args()
with tempfile.TemporaryDirectory(prefix='ouxd-fixture-') as temporary:
    root=Path(temporary);os.chmod(root,0o700);directory=root/'openuuyc-pcvr/openxr';directory.mkdir(parents=True,mode=0o700)
    path=directory/'device.shm'
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600);os.ftruncate(fd,4096)
    mapping=mmap.mmap(fd,4096);os.close(fd)
    data=bytearray(360);data[:4]=b'OUXD'
    struct.pack_into('<I',data,4,1);struct.pack_into('<Q',data,16,1);struct.pack_into('<IIf',data,32,320,256,72.)
    values=[]
    for x in (-.031,.031):values.extend([x,0.,0.,0.,0.,0.,1.,-.7,.9,.8,-.8])
    struct.pack_into('<22f',data,48,*values)
    data[136:140]=b'OUVR';struct.pack_into('<I',data,140,1);struct.pack_into('<Q',data,144,1)
    struct.pack_into('<I',data,168,3);struct.pack_into('<13f',data,176,1.,2.,3.,0.,0.,0.,1.,0.,0.,0.,0.,0.,0.)
    stop=threading.Event()
    def writer():
        sequence=0
        while not stop.is_set():
            sequence+=2;struct.pack_into('<Q',mapping,8,sequence|1)
            struct.pack_into('<q',data,24,time.monotonic_ns())
            mapping[:8]=data[:8];mapping[16:360]=data[16:360];struct.pack_into('<Q',mapping,8,sequence)
            stop.wait(.01)
    worker=threading.Thread(target=writer);worker.start()
    env=os.environ.copy();env.update(XDG_RUNTIME_DIR=temporary,XDG_CONFIG_HOME=str(root/'config'),XR_RUNTIME_JSON=str(Path(args.runtime).resolve()))
    try:
        result=subprocess.run([args.smoke],env=env,text=True,capture_output=True,timeout=15)
        print(result.stdout,end='')
        if result.returncode:raise RuntimeError(result.stderr+f'\nsmoke exit={result.returncode}')
        stop.set();worker.join();time.sleep(.3)
        stale=subprocess.run([args.probe,'--device-only'],env=env,text=True,capture_output=True,timeout=10)
        state=json.loads(stale.stdout);assert state['instance'] and not state['system'],state
        print('PASS: real OpenXR creates a headless HMD session and reads the received head pose; stale source cannot create a device')
    finally:
        stop.set();worker.join();mapping.close()
