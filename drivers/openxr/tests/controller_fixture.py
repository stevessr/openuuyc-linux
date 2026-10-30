"""Synthetic v2 controller IPC through real headless OpenXR actions."""
import argparse,json,mmap,os,pathlib,socket,struct,subprocess,tempfile,threading,time
p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--smoke',required=True);p.add_argument('--with-stage',action='store_true');a=p.parse_args()
with tempfile.TemporaryDirectory(prefix='openuuyc-controller-fixture-') as tmp:
 root=pathlib.Path(tmp);directory=root/'openuuyc-pcvr/openxr';directory.mkdir(parents=True,mode=0o700);os.chmod(directory.parent,0o700)
 path=directory/'device.shm';fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);os.ftruncate(fd,4096);mapping=mmap.mmap(fd,4096);os.close(fd)
 data=bytearray(568);data[:4]=b'OUXD';struct.pack_into('<I',data,4,2);struct.pack_into('<Q',data,16,1);struct.pack_into('<IIf',data,32,320,256,72.)
 views=[]
 for x in (-.031,.031):views.extend([x,0.,0.,0.,0.,0.,1.,-.8,.8,.8,-.8])
 struct.pack_into('<22f',data,48,*views);data[136:140]=b'OUVR';struct.pack_into('<I',data,140,2);struct.pack_into('<Q',data,144,1)
 for i in range(5):
  offset=136+32+i*64;struct.pack_into('<I',data,offset,51);struct.pack_into('<13f',data,offset+8,float(i),0.,0.,0.,0.,0.,1.,0.,0.,0.,0.,0.,0.)
 for hand in range(2):struct.pack_into('<II4f',data,136+352+hand*24,127,11,.7,.4,-.25,.75)
 feedback=socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM);feedback.bind(str(directory/'haptics.sock'));feedback.setblocking(False);os.chmod(directory/'haptics.sock',0o600)
 pulses=[]
 marker=root/'release';stop=threading.Event();failures=[]
 if a.with_stage:struct.pack_into('<I',data,44,1);struct.pack_into('<7f',data,536,0.,-1.6,0.,0.,0.,0.,1.)
 def writer():
  sequence=0
  try:
   while not stop.is_set():
    while True:
     try:
      packet=feedback.recv(128)
      if len(packet)==40:pulses.append(struct.unpack('<4sIQQIffI',packet))
     except BlockingIOError:break
    if marker.exists():
     for i in range(1,5):struct.pack_into('<I',data,136+32+i*64,0)
    sequence+=2;struct.pack_into('<Q',mapping,8,sequence|1);struct.pack_into('<q',data,24,time.monotonic_ns());mapping[:8]=data[:8];mapping[16:568]=data[16:568];struct.pack_into('<Q',mapping,8,sequence);stop.wait(.005)
  except BaseException as e:failures.append(e)
 worker=threading.Thread(target=writer);worker.start()
 env=os.environ.copy();env.update(XDG_RUNTIME_DIR=tmp,XDG_CONFIG_HOME=str(root/'config'),XR_RUNTIME_JSON=str(pathlib.Path(a.runtime).resolve()))
 try:
  result=subprocess.run([a.smoke,str(marker)]+(['require-stage'] if a.with_stage else []),env=env,text=True,capture_output=True,timeout=15)
  if result.returncode:raise RuntimeError(result.stdout+result.stderr[-16000:]+f'\nfixture exit={result.returncode}')
  if failures:raise failures[0]
  for hand in range(2):
   records=[r for r in pulses if r[4]==hand]
   assert any(r[0]==b'OUHP' and r[1]==1 and r[2]==1 and r[3]>0 and r[5]==.5 and r[6]==120 and r[7]==1000 for r in records),pulses
   assert any(r[5]==0 and r[7]==0 for r in records),pulses
   assert any(r[5]==.25 and r[7]==1 for r in records),pulses
  print(result.stdout.strip());print(json.dumps(dict(real_openxr_haptic_output_to_private_socket=True,bounded_duration_and_explicit_stop=True,headset_verified=False)))
 finally:stop.set();worker.join();mapping.close();feedback.close()
