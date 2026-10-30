#!/usr/bin/env python3
"""Explicit, per-game Steam setup. No global runtime change; private verified backup."""
import argparse, hashlib, os, pathlib, re, stat, subprocess, tempfile

def game_block(text, appid):
    # Tokens retain offsets so unrelated config (including account data) is never rewritten.
    tokens=list(re.finditer(r'"(?:\\.|[^"\\])*"|[{}]',text))
    stack=[]; pending=None
    for token in tokens:
        value=token.group()
        if value=='{':
            stack.append(pending);pending=None
            if stack[-1]==str(appid) and any(str(k).lower()=='apps' for k in stack[:-1]):
                depth=1
                for end in tokens[tokens.index(token)+1:]:
                    if end.group()=='{': depth+=1
                    elif end.group()=='}': depth-=1
                    if depth==0:return token.end(),end.start()
                raise ValueError('Truncated Steam app configuration')
        elif value=='}':
            if stack:stack.pop()
            pending=None
        elif pending is None:pending=value[1:-1]
        else:pending=None
    return None

def edit(text,appid,options):
    block=game_block(text,appid)
    if block is None: raise ValueError('This Steam account has no configuration for the requested app')
    a,b=block
    current=re.search(r'"LaunchOptions"\s*"((?:\\.|[^"\\])*)"',text[a:b],re.I)
    escaped=options.replace('\\','\\\\').replace('"','\\"')
    if current:
        if current.group(1)==escaped:return text
        if current.group(1):raise ValueError('Existing launch options preserved; merge them manually before retrying')
        return text[:a+current.start()]+f'"LaunchOptions"\t\t"{escaped}"'+text[a+current.end():]
    return text[:b]+f'\t\t\t\t\t"LaunchOptions"\t\t"{escaped}"\n'+text[b:]

def apply(path,appid,options,backup_root):
    data=path.read_bytes()
    updated=edit(data.decode('utf-8'),appid,options).encode('utf-8')
    if updated==data:return False
    backup_root.mkdir(parents=True,exist_ok=True,mode=0o700)
    os.chmod(backup_root,0o700)
    digest=hashlib.sha256(data).hexdigest()
    backup=backup_root/(path.parent.parent.name+'-'+digest+'.vdf')
    if not backup.exists():
        fd=os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as out:out.write(data)
    if hashlib.sha256(backup.read_bytes()).hexdigest()!=digest:raise ValueError('Backup verification failed')
    fd,name=tempfile.mkstemp(prefix='.openuuyc-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:
            out.write(updated);out.flush();os.fsync(out.fileno())
        os.chmod(name,stat.S_IMODE(path.stat().st_mode)&0o600)
        if path.read_bytes()!=data:raise ValueError('Steam configuration changed concurrently; refusing overwrite')
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)
    return True

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--appid',type=int,required=True)
    parser.add_argument('--client',type=pathlib.Path,default=pathlib.Path('target/debug/OpenUUYC'))
    args=parser.parse_args()
    if not 0<args.appid<2**32:parser.error('invalid Steam app id')
    if subprocess.run(['pgrep','-x','steam'],stdout=subprocess.DEVNULL).returncode==0:
        parser.error('Close Steam first; do not edit configuration while the client is running')
    result=subprocess.check_output([str(args.client.resolve()),'pico-vr','runtime-status'],text=True)
    prefix='Steam OpenVR 启动选项：'
    options=next((line[len(prefix):] for line in result.splitlines() if line.startswith(prefix)),None)
    if not options or 'OPENUUYC_STEREO_CAPTURE=' not in options:parser.error('Selected runtime does not supply the real stereo export')
    home=pathlib.Path.home()
    candidates=[p for p in (home/'.local/share/Steam/userdata').glob('*/config/localconfig.vdf')
                if game_block(p.read_text(),args.appid)]
    if len(candidates)!=1:parser.error('Expected exactly one matching local Steam account; no files changed')
    changed=apply(candidates[0],args.appid,options,home/'.local/state/OpenUUYC/steam-config-backups')
    print('Per-game launch options updated with a verified private backup' if changed else 'Per-game launch options already match')
if __name__=='__main__':main()
