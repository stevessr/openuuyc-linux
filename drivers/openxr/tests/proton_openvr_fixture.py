"""Isolated synthetic HMD + Windows OpenVR API probe, not VRChat acceptance.
The dedicated Proton prefix is preserved; never point --prefix at a game prefix.
"""
import argparse,fcntl,json,mmap,os,pathlib,struct,subprocess,tempfile,threading,time
p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--xrizer',required=True);p.add_argument('--probe',required=True);p.add_argument('--api-dll',required=True);p.add_argument('--proton',required=True);p.add_argument('--steam',required=True);p.add_argument('--prefix',required=True);p.add_argument('--timeout',type=int,default=120);p.add_argument('--render',action='store_true');p.add_argument('--container');p.add_argument('--without-stage',action='store_true');p.add_argument('--expect-init-error',action='store_true');a=p.parse_args()
prefix=pathlib.Path(a.prefix).resolve();marker=prefix/'.openuuyc-diagnostic-prefix'
if prefix.exists() and (not marker.is_file() or marker.read_text()!='isolated OpenUUYC diagnostic; not game data\n'):raise SystemExit('Refusing a non-diagnostic Proton prefix')
prefix.mkdir(mode=0o700,parents=True,exist_ok=True);os.chmod(prefix,0o700);marker.write_text('isolated OpenUUYC diagnostic; not game data\n');os.chmod(marker,0o600)
lock=(prefix/'.probe.lock').open('ab');os.chmod(prefix/'.probe.lock',0o600)
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
wineserver=pathlib.Path(a.proton).resolve().parent/'files/bin/wineserver'
server_env=os.environ.copy();server_env['WINEPREFIX']=str(prefix/'pfx')
# A failed Windows probe can leave its own Wine registry thread alive. Never
# touch another prefix; the marker and exclusive lock above are mandatory.
subprocess.run([str(wineserver),'-k'],env=server_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=5)
subprocess.run([str(wineserver),'-w'],env=server_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
with tempfile.TemporaryDirectory(prefix='openuuyc-proton-openvr-') as tmp:
 root=pathlib.Path(tmp);d=root/'openuuyc-pcvr/openxr';d.mkdir(parents=True,mode=0o700);os.chmod(d.parent,0o700)
 fd=os.open(d/'device.shm',os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);os.ftruncate(fd,4096);mapping=mmap.mmap(fd,4096);os.close(fd)
 capture=None;observed=[];exported=[]
 if a.render:
  fd=os.open(d/'stereo.shm',os.O_RDWR|os.O_CREAT|os.O_EXCL,0o600);os.ftruncate(fd,256+2*1024*1024*4);capture=mmap.mmap(fd,256+2*1024*1024*4);os.close(fd)
 data=bytearray(568);data[:4]=b'OUXD';struct.pack_into('<I',data,4,2);struct.pack_into('<Q',data,16,1);struct.pack_into('<IIf',data,32,320,256,72.)
 views=[]
 for x in (-.031,.031):views.extend([x,0.,0.,0.,0.,0.,1.,-.8,.8,.8,-.8])
 struct.pack_into('<22f',data,48,*views);data[136:140]=b'OUVR';struct.pack_into('<I',data,140,2);struct.pack_into('<Q',data,144,1);struct.pack_into('<I',data,168,51);struct.pack_into('<f',data,200,1.)
 if not a.without_stage:
  struct.pack_into('<I',data,44,1);struct.pack_into('<7f',data,536,0.,-1.6,0.,0.,0.,0.,1.)
 config=root/'config/openvr';config.mkdir(parents=True)
 (config/'openvrpaths.vrpath').write_text(json.dumps(dict(version=1,jsonid='vrpathreg',runtime=[str(pathlib.Path(a.xrizer).resolve())])))
 stop=threading.Event();failures=[]
 def writer():
  sequence=0
  try:
   while not stop.is_set():
    if capture is not None:
     guard=struct.unpack_from('<Q',capture)[0]
     if guard>0 and not guard&1:
      magic,version,frame,timestamp,w,h,fmt,origin=struct.unpack_from('<4sIQQIIII',capture,8)
      if magic==b'OUST' and version==2 and fmt==1 and origin==0 and 64<=w<=1024 and 64<=h<=1024:
       colors=[list(capture[256+eye*1024*1024*4+((h//2)*w+w//2)*4:256+eye*1024*1024*4+((h//2)*w+w//2)*4+4]) for eye in range(2)]
       if struct.unpack_from('<Q',capture)[0]==guard:exported[:]=[dict(sequence=frame,eye_width=w,eye_height=h,center_rgba=colors)]
       if struct.unpack_from('<Q',capture)[0]==guard and colors[0][0]>180 and colors[0][1]<80 and colors[1][1]>180 and colors[1][0]<80:observed[:]=[dict(sequence=frame,eye_width=w,eye_height=h,center_rgba=colors)]
    sequence+=2;struct.pack_into('<Q',mapping,8,sequence|1);struct.pack_into('<q',data,24,time.monotonic_ns());mapping[:8]=data[:8];mapping[16:568]=data[16:568];struct.pack_into('<Q',mapping,8,sequence);stop.wait(.005)
  except BaseException as e:failures.append(e)
 worker=threading.Thread(target=writer);worker.start()
 env=os.environ.copy();env.update(XDG_RUNTIME_DIR=tmp,XDG_CONFIG_HOME=str(root/'config'),XDG_STATE_HOME=str(root/'state'),XR_RUNTIME_JSON=str(pathlib.Path(a.runtime).resolve()),VR_OVERRIDE=str(pathlib.Path(a.xrizer).resolve()),STEAM_COMPAT_DATA_PATH=str(prefix),STEAM_COMPAT_CLIENT_INSTALL_PATH=str(pathlib.Path(a.steam).resolve()),SteamAppId='0',SteamGameId='0',DISPLAY=':0',XRT_COMPOSITOR_FORCE_XCB='1',WINEDEBUG='-all')
 env.pop('OPENUUYC_STEREO_CAPTURE',None)
 if a.render:env['OPENUUYC_STEREO_CAPTURE']=str(d/'stereo.shm')
 dll='Z:'+str(pathlib.Path(a.api_dll).resolve()).replace('/','\\')
 output=root/'result.json'
 report='Z:'+str(output).replace('/','\\')
 command=[str(pathlib.Path(a.proton).resolve()),'run',str(pathlib.Path(a.probe).resolve()),dll,report]
 if a.container:
  env['PRESSURE_VESSEL_FILESYSTEMS_RO']=':'.join([str(pathlib.Path(a.runtime).resolve().parent.parent),str(pathlib.Path(a.xrizer).resolve()),str(pathlib.Path(a.probe).resolve())])
  env['PRESSURE_VESSEL_FILESYSTEMS_RW']=':'.join([tmp,str(prefix)])
  command=[str(pathlib.Path(a.container).resolve()),'--','env','XDG_RUNTIME_DIR='+tmp,'XDG_CONFIG_HOME='+str(root/'config'),'XDG_STATE_HOME='+str(root/'state')]+command
 try:
  try:result=subprocess.run(command,env=env,text=True,capture_output=True,timeout=max(10,min(a.timeout,120)))
  except subprocess.TimeoutExpired:
   if output.exists():print(output.read_text(),end='')
   raise
  (prefix/'last-probe.stderr').write_text(result.stderr);os.chmod(prefix/'last-probe.stderr',0o600)
  trace=root/'state/xrizer/xrizer.txt'
  if trace.exists():(prefix/'last-xrizer.log').write_text(trace.read_text());os.chmod(prefix/'last-xrizer.log',0o600)
  payload=output.read_text() if output.exists() else ''
  result.stdout=payload
  # Only fixture stdout and bounded diagnostics; no account config/environment dump.
  print(result.stdout[-8000:],end='')
  if a.expect_init_error:
   records=[json.loads(line) for line in payload.splitlines() if line.startswith('{')]
   assert any(r.get('dll_loaded') is True and r.get('background_init_error',0)>0 for r in records),payload
   print(json.dumps(dict(missing_stage_rejected_without_panic=True,game_started=False)))
   raise SystemExit(0)
  if result.returncode:raise RuntimeError(result.stderr[-8000:]+f'\nprobe exit={result.returncode}')
  if failures:raise failures[0]
  success_marker='"scene_init_error":0' if a.render else '"background_init_error":0'
  if success_marker not in result.stdout:
   log=root/'state/xrizer/xrizer.txt'
   raise RuntimeError(result.stderr[-10000:]+(log.read_text()[-8000:] if log.exists() else '')+'\nmissing successful initialization marker')
  assert '"shutdown_complete":true' in result.stdout, 'initialization passed but cleanup did not complete'
  interfaces=[json.loads(line) for line in payload.splitlines() if line.startswith('{') and '"interface"' in line]
  assert len(interfaces)==5 and all(r.get('available') is True and r.get('error')==0 for r in interfaces),interfaces
  if a.render:
   records=[json.loads(line) for line in payload.splitlines() if line.startswith('{')]
   assert any(r.get('d3d11_stereo_frames_submitted')==90 and r.get('diagnostic_error')==0 for r in records),payload
   assert observed,'D3D submissions returned success, but no matching red/green compositor export was observed: '+json.dumps(exported)
   print(json.dumps(dict(proton_d3d11_stereo_export_verified=True,**observed[0],game_started=False,headset_verified=False)))
  else:print(json.dumps(dict(proton_openvr_background_initialized=True,vrchat_sdk_interfaces_verified=True,game_started=False,headset_verified=False)))
 finally:
  subprocess.run([str(wineserver),'-k'],env=server_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=5)
  stop.set();worker.join();mapping.close()
  if capture is not None:capture.close()
