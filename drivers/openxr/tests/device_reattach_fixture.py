"""Synthetic device IPC, real persistent OpenXR session. Not headset acceptance."""
import argparse,json,mmap,os,pathlib,struct,subprocess,tempfile,threading,time
p=argparse.ArgumentParser()
p.add_argument('--runtime',required=True);p.add_argument('--smoke',required=True)
args=p.parse_args()
for incompatible in (False,True):
    with tempfile.TemporaryDirectory(prefix='openuuyc-reattach-fixture-') as temporary:
        root=pathlib.Path(temporary);directory=root/'openuuyc-pcvr/openxr';directory.mkdir(parents=True,mode=0o700)
        os.chmod(directory.parent,0o700)
        data=bytearray(4096);data[:4]=b'OUXD';struct.pack_into('<I',data,4,1)
        struct.pack_into('<Q',data,16,1);struct.pack_into('<IIf',data,32,320,256,72.)
        values=[]
        for x in (-.031,.031):values.extend([x,0.,0.,0.,0.,0.,1.,-.7,.9,.8,-.8])
        struct.pack_into('<22f',data,48,*values)
        data[136:140]=b'OUVR';struct.pack_into('<I',data,140,1);struct.pack_into('<Q',data,144,1)
        struct.pack_into('<I',data,168,3);struct.pack_into('<13f',data,176,1.,2.,3.,0.,0.,0.,1.,0.,0.,0.,0.,0.,0.)
        path=directory/'device.shm';marker=root/'replace'
        def create(path):
            fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600)
            os.ftruncate(fd,4096);result=mmap.mmap(fd,4096);os.close(fd);return result
        mapping=create(path);stop=threading.Event();failure=[]
        def writer():
            current=mapping;sequence=0;replaced=False
            try:
                while not stop.is_set():
                    if marker.exists() and not replaced:
                        struct.pack_into('<Q',data,16,2)
                        struct.pack_into('<3f',data,176,4.,5.,6.)
                        if incompatible:struct.pack_into('<I',data,32,640)
                        next_path=directory/'replacement.shm';new=create(next_path)
                        old=current;current=new;replaced=True
                    sequence+=2;struct.pack_into('<Q',current,8,sequence|1)
                    struct.pack_into('<q',data,24,time.monotonic_ns())
                    current[:8]=data[:8];current[16:360]=data[16:360];struct.pack_into('<Q',current,8,sequence)
                    if replaced and next_path.exists():
                        os.replace(next_path,path);old.close()
                    stop.wait(.01)
            except BaseException as e:failure.append(e)
            finally:current.close()
        worker=threading.Thread(target=writer);worker.start()
        env=os.environ.copy();env.update(XDG_RUNTIME_DIR=temporary,XDG_CONFIG_HOME=str(root/'config'),XR_RUNTIME_JSON=str(pathlib.Path(args.runtime).resolve()))
        try:
            result=subprocess.run([args.smoke,str(marker)]+(['reject'] if incompatible else []),env=env,text=True,capture_output=True,timeout=15)
            if result.returncode:raise RuntimeError(result.stderr+f'smoke exit={result.returncode}')
            if failure:raise failure[0]
            print(result.stdout,end='')
        finally:stop.set();worker.join()
print('PASS: a persistent real OpenXR session reattaches compatible publisher inodes and rejects changed optics')
