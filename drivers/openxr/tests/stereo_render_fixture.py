"""Real graphical compositor test with synthetic poses/colors, never game acceptance."""
import argparse,json,mmap,os,pathlib,struct,subprocess,tempfile,threading,time
p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--smoke',required=True);p.add_argument('--gdb',action='store_true');a=p.parse_args()
with tempfile.TemporaryDirectory(prefix='openuuyc-stereo-render-fixture-') as tmp:
    root=pathlib.Path(tmp);d=root/'openuuyc-pcvr/openxr';d.mkdir(parents=True,mode=0o700);os.chmod(d.parent,0o700)
    def mapping(path,size):
        fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);os.ftruncate(fd,size)
        result=mmap.mmap(fd,size);os.close(fd);return result
    device=mapping(d/'device.shm',4096);capture=mapping(d/'stereo.shm',256+2*1024*1024*4)
    data=bytearray(4096);data[:4]=b'OUXD';struct.pack_into('<I',data,4,1);struct.pack_into('<Q',data,16,1);struct.pack_into('<IIf',data,32,320,256,72.)
    views=[]
    for x in (-.031,.031):views.extend([x,0.,0.,0.,0.,0.,1.,-.8,.8,.8,-.8])
    struct.pack_into('<22f',data,48,*views);data[136:140]=b'OUVR';struct.pack_into('<I',data,140,1);struct.pack_into('<Q',data,144,1);struct.pack_into('<I',data,168,3);struct.pack_into('<f',data,200,1.)
    stop=threading.Event();failures=[]
    def writer():
        sequence=0
        try:
            while not stop.is_set():
                sequence+=2;struct.pack_into('<Q',device,8,sequence|1);struct.pack_into('<q',data,24,time.monotonic_ns());device[:8]=data[:8];device[16:360]=data[16:360];struct.pack_into('<Q',device,8,sequence);stop.wait(.005)
        except BaseException as e:failures.append(e)
    worker=threading.Thread(target=writer);worker.start()
    env=os.environ.copy();env.update(XDG_RUNTIME_DIR=tmp,XDG_CONFIG_HOME=str(root/'config'),XR_RUNTIME_JSON=str(pathlib.Path(a.runtime).resolve()),OPENUUYC_STEREO_CAPTURE=str(d/'stereo.shm'),XRT_COMPOSITOR_FORCE_XCB='1',XRT_COMPOSITOR_XCB_FULLSCREEN='0')
    try:
        command=[a.smoke]
        if a.gdb:command=['gdb','-batch','-ex','run','-ex','thread apply all bt','--args']+command
        result=subprocess.run(command,env=env,text=True,capture_output=True,timeout=35)
        if result.returncode:raise RuntimeError(result.stderr[-16000:]+result.stdout+f'\nfixture exit={result.returncode}')
        if failures:raise failures[0]
        guard=struct.unpack_from('<Q',capture)[0]
        if not guard or guard&1:raise RuntimeError('no complete compositor stereo export\n'+result.stdout[-22000:]+result.stderr[-12000:])
        magic,version,sequence,timestamp,w,h,fmt,origin=struct.unpack_from('<4sIQQIIII',capture,8)
        assert (magic,version,fmt,origin)==(b'OUST',2,1,0),(magic,version,fmt,origin)
        assert 64<=w<=1024 and 64<=h<=1024 and sequence>0 and timestamp>0
        colors=[]
        for eye in range(2):
            offset=256+eye*1024*1024*4+((h//2)*w+w//2)*4
            colors.append(list(capture[offset:offset+4]))
        assert colors[0][0]>180 and colors[0][1]<80,colors
        assert colors[1][1]>180 and colors[1][0]<80,colors
        print(result.stdout.strip());print(json.dumps(dict(real_compositor_stereo_export=True,sequence=sequence,eye_width=w,eye_height=h,center_rgba=colors,headset_verified=False)))
    finally:
        stop.set();worker.join();device.close();capture.close()
