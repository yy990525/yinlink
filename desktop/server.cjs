'use strict';
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {validatePairSettings,loadSettings,saveSettings,windowsStartup}=require('./settings.cjs');

const {startDiscovery}=require('./discovery.cjs');
const {attachVideoHub}=require('./video-hub.cjs');
const MAX_FRAME = 8 * 1024 * 1024;
const MAX_AUDIO = 19200;
const KEYS = new Set(['Enter','Backspace','Delete','Tab','ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End','PageUp','PageDown','a','c','x','v']);
function validateInput(data) {
  if (!data || typeof data !== 'object') throw new Error('输入指令格式错误');
  const point = v => typeof v === 'number' && Number.isFinite(v) && v >= 0 && v <= 1;
  if (data.type === 'tap' && point(data.x) && point(data.y)) return { type: 'tap', x:data.x, y:data.y, duration:Math.max(40,Math.min(800,Number(data.duration)||50)) };
  if (data.type === 'swipe' && [data.x,data.y,data.x2,data.y2].every(point)) return { type:'swipe',x:data.x,y:data.y,x2:data.x2,y2:data.y2,duration:Math.max(80,Math.min(800,Number(data.duration)||220)) };
  if (data.type === 'text' && typeof data.text === 'string' && data.text.length > 0 && data.text.length <= 1000) return { type:'text',text:data.text };
  if (data.type === 'key' && KEYS.has(data.key)) return { type:'key',key:data.key,modifiers:(Number(data.modifiers)||0)&7 };
  if (data.type === 'global' && ['back','home','recents'].includes(data.action)) return { type:'global',action:data.action };
  throw new Error('不支持的输入或无效坐标');
}
function validateProfile(d){if(!d||typeof d!=='object')throw Error('画质设置无效');for(const [key,min,max] of [['fps',1,120],['longEdge',640,3840],['quality',40,95]])if(!Number.isInteger(d[key])||d[key]<min||d[key]>max)throw Error(key==='fps'?'FPS 需为 1–120':'画质参数超出范围');if(!['flow','balanced','quality'].includes(d.mode))throw Error('请选择流畅、均衡或高清');return {fps:d.fps,longEdge:d.longEdge,quality:d.quality,mode:d.mode};}
const IDLE_MS = 12000;
const PIN_MS = 5 * 60 * 1000;
const isLoopback = ip => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(ip);
const safeEqual = (a, b) => typeof a === 'string' && typeof b === 'string' &&
  Buffer.byteLength(a) === Buffer.byteLength(b) &&
  crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));

async function readBody(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) { const err = new Error('请求过大'); err.status = 413; throw err; }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function createReceiver({ port = 52345, host = '0.0.0.0', configPath=null, startup=windowsStartup(), windowControl={supported:false,request:async()=>{}} } = {}) {
  let preferences=await loadSettings(configPath),settingsBusy=false,pairSettingsBusy=false,startupEnabled=await startup.get();
  const localToken = crypto.randomBytes(32).toString('hex');
  let pairCode, pairExpires, session = null, frame = null, sequence = 0;
  let actualPort = port, lastStartedAt=0;
  const attempts = new Map();
  const rotateCode = () => {
    pairCode = preferences.pairMode==='fixed'?preferences.fixedCode:crypto.randomInt(0,1000000).toString().padStart(6,'0');
    pairExpires = preferences.pairMode==='fixed'?null:Date.now()+PIN_MS;
  };
  let videoHub;
  const disconnect = () => { videoHub?.reset(); session = null; frame = null; sequence++; rotateCode(); };
  rotateCode();

  function state() {
    const addresses = Object.values(os.networkInterfaces()).flat().filter(n => n && !n.internal && n.family === 'IPv4').map(n => n.address);
    return {
      version: '0.8.0',computerName:os.hostname(),realtime:!!session?.realtime,mode:session?.mode||'balanced',effectiveFps:session?.effectiveFps||session?.videoConfig?.fps||0,bitrate:session?.bitrate||session?.videoConfig?.bitrate||0,temperature:session?.temperature||0,thermalStatus:session?.thermalStatus||0,feedback:session?.feedback||null,clipboardAllowed:!!session?.clipboardAllowed,profileRevision:session?.profileRevision||0, port: actualPort, addresses: [...new Set(addresses)],
      pairCode: session ? null : pairCode, pairExpires, pairMode:preferences.pairMode, startupEnabled, startupSupported:startup.supported,windowSupported:windowControl.supported,
      videoMode:session?.videoMode||'jpeg',videoConfig:session?.videoConfig||null,videoError:session?.videoError||'',
      connected: !!session, device: session?.name || null,
      receivedFrames: session?.frames || 0, receivedBytes: session?.bytes || 0,
      startedAt: session?.startedAt || null, lastFrameAt: session?.lastFrameAt || null,
      sequence, hasFrame: !!frame||!!session?.videoLastFrameAt,
      controlGranted:!!session?.controlGranted, controlEnabled:!!session?.controlEnabled,
      controlMessage:session?.controlMessage || '请先在手机上授权',
      audioStatus:session?.audioStatus || 'off', audioMessage:session?.audioMessage || '',
      audioSequence:session?.audioSequence || 0, audioBytes:session?.audioBytes || 0,
      captureWidth:session?.captureWidth || 0, captureHeight:session?.captureHeight || 0,targetFps:session?.targetFps||0,jpegQuality:session?.jpegQuality||0,longEdge:session?.longEdge||0
    };
  }

  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    const json = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(data)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      const route = url.pathname;
      const credential = req.headers.authorization?.replace(/^Bearer /, '');
      const local = isLoopback(req.socket.remoteAddress) && safeEqual(credential, localToken);
      const sender = session && safeEqual(credential, session.token);

      if (req.method === 'POST' && route === '/api/pair') {
        if(pairSettingsBusy)return json(409,{error:'配对设置正在保存，请稍后'});
        if (session) return json(409, { error: '已有手机连接，请先在电脑上断开' });
        const ip = req.socket.remoteAddress;
        const now = Date.now();
        let attempt = attempts.get(ip);
        if (!attempt || now > attempt.reset) attempt = { count: 0, reset: now + 60000 };
        if (attempts.size >= 256 && !attempts.has(ip)) return json(429, { error: '请求过多，请稍后重试' });
        attempts.set(ip, attempt);
        if (++attempt.count > 10) return json(429, { error: '尝试过多，请一分钟后重试' });
        let data;
        try { data = JSON.parse((await readBody(req, 2048)).toString()); }
        catch (e) { return json(e.status || 400, { error: '配对请求格式错误' }); }
        if (session) return json(409, { error: '已有手机连接，请先在电脑上断开' });
        if(pairSettingsBusy)return json(409,{error:'配对设置正在保存，请稍后'});
        if (!data || typeof data !== 'object') return json(400, { error: '配对请求格式错误' });
        if (pairExpires && Date.now() > pairExpires) rotateCode();
        if (!safeEqual(data.code, pairCode)) return json(401, { error: '配对码错误或已过期' });
        session = {
          realtime:data.realtime===true,profileRevision:0,mode:'balanced',clipboardAllowed:false,clipboard:null,
          ip:req.socket.remoteAddress?.replace(/^::ffff:/,''),videoMode:'jpeg',videoConfig:null,videoLastFrameAt:null,
          token: crypto.randomBytes(32).toString('hex'),
          name: typeof data.name === 'string' ? data.name.replace(/[\x00-\x1f]/g, '').slice(0, 64) : 'Android 手机',
          startedAt: (lastStartedAt=Math.max(Date.now(),lastStartedAt+1)), lastSeen: Date.now(), lastFrameAt: null, frames: 0, bytes: 0,
          controlGranted:false,controlEnabled:false,controlMessage:'请先在手机上授权',
          commands:[],commandSequence:0,audio:[],audioSequence:0,audioBytes:0,audioStatus:'off',audioMessage:''
        };
        return json(200, { token: session.token,pairMode:preferences.pairMode, maxFrameBytes: MAX_FRAME, heartbeatMs: 2000, videoTransport:'h264-ws-v1',audioTransport:'pcm-ws-v1',realtime:true,computerName:os.hostname() });
      }
      if (req.method === 'POST' && route === '/api/frame') {
        if (!sender) return json(401, { error: '连接已结束，请重新配对' });
        if (req.headers['content-type'] !== 'image/jpeg') return json(415, { error: '只接受 JPEG 画面' });
        const currentSession = session;
        const bytes = await readBody(req, MAX_FRAME);
        if (session !== currentSession) return json(401, { error: '连接已结束' });
        if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9)
          return json(400, { error: 'JPEG 数据不完整' });
        if(session.videoMode==='h264'){videoHub?.reset();session.videoMode='jpeg';session.videoConfig=null;session.videoLastFrameAt=null;}frame = bytes; sequence++;
        session.lastSeen = session.lastFrameAt = Date.now(); session.frames++; session.bytes += bytes.length;
        return json(200, { sequence });
      }
      if (req.method === 'POST' && route === '/api/capabilities') {
        if (!sender) return json(401,{error:'连接已结束'});
        const active = session;
        let data; try { data=JSON.parse((await readBody(req,4096)).toString()); } catch(e) { return json(e.status||400,{error:'设备状态格式错误'}); }
        if(session!==active) return json(401,{error:'连接已结束'});
        if(!data || typeof data!=='object') return json(400,{error:'设备状态格式错误'});
        session.controlGranted=data.controlGranted===true;session.clipboardAllowed=data.clipboardAllowed===true;if(!session.clipboardAllowed)session.clipboard=null;
        if(['flow','balanced','quality'].includes(data.mode))session.mode=data.mode;
        for(const [k,max] of [['effectiveFps',120],['bitrate',100000000],['temperature',80],['thermalStatus',6]])if(typeof data[k]==='number'&&Number.isFinite(data[k])&&data[k]>=0&&data[k]<=max)session[k]=data[k];
        if(!session.controlGranted){session.controlEnabled=false;session.commands=[];videoHub.notice({type:'control',enabled:false});}
        session.controlMessage=typeof data.controlMessage==='string'?data.controlMessage.slice(0,160):'';
        const validStatus=['off','capturing','silent','unsupported','denied','error'];
        if(validStatus.includes(data.audioStatus)) session.audioStatus=data.audioStatus;
        if(!['capturing','silent'].includes(session.audioStatus)) session.audio=[];
        session.audioMessage=typeof data.audioMessage==='string'?data.audioMessage.slice(0,160):'';
        for(const k of ['captureWidth','captureHeight']) if(Number.isInteger(data[k])&&data[k]>0&&data[k]<=10000) session[k]=data[k];
        for(const [key,min,max] of [['targetFps',1,120],['jpegQuality',40,95],['longEdge',640,3840]])if(Number.isInteger(data[key])&&data[key]>=min&&data[key]<=max)session[key]=data[key];
        if(Number.isSafeInteger(data.profileRevision)&&data.profileRevision>=session.profileRevision)session.profile=null;session.lastSeen=Date.now();return json(200,{ok:true,controlEnabled:session.controlEnabled});
      }
      if(req.method==='POST' && route==='/api/audio') {
        if(!sender) return json(401,{error:'连接已结束'});
        if(!['capturing','silent'].includes(session.audioStatus)) return json(409,{error:'手机尚未开启声音'});
        if(req.headers['content-type']!=='audio/pcm') return json(415,{error:'需要 PCM 16-bit 单声道 48000 Hz'});
        const active=session, bytes=await readBody(req,MAX_AUDIO);
        if(session!==active) return json(401,{error:'连接已结束'});
        if(!['capturing','silent'].includes(session.audioStatus)) return json(409,{error:'声音已关闭'});
        if(!bytes.length || bytes.length%2) return json(400,{error:'PCM 长度错误'});
        session.audio.push({sequence:++session.audioSequence,at:Date.now(),data:bytes.toString('base64')});
        if(session.audio.length>30)session.audio.shift();
        session.audioBytes+=bytes.length;session.lastSeen=Date.now();return json(200,{sequence:session.audioSequence});
      }
      if(req.method==='GET' && route==='/api/commands') {
        if(!sender) return json(401,{error:'连接已结束'});
        const after=Number(url.searchParams.get('after')||0);
        if(!Number.isSafeInteger(after)||after<0) return json(400,{error:'无效指令序号'});
        session.commands=session.commands.filter(c=>c.sequence>after&&Date.now()-c.at<2000);
        const commands=session.controlEnabled&&session.controlGranted ? session.commands.slice(0,8).map(c=>({...c,ageMs:Date.now()-c.at})) : [];
        return json(200,{enabled:session.controlEnabled&&session.controlGranted,commands,profile:session.profile||null,profileRevision:session.profileRevision,nextSequence:commands.at(-1)?.sequence||Math.max(after,session.commandSequence-session.commands.length)});
      }
      if (req.method === 'POST' && route === '/api/heartbeat') {
        if (!sender) return json(401, { error: '连接已结束' });
        session.lastSeen = Date.now(); return json(200, { ok: true });
      }
      if (req.method === 'POST' && route === '/api/stop') {
        if (!sender) return json(401, { error: '连接已结束' });
        disconnect(); return json(200, { ok: true });
      }
      if(route==='/api/clipboard'&&req.method==='POST'&&sender){
        const active=session;if(!active.clipboardAllowed)return json(409,{error:'请在手机开启剪贴板互传'});let d;try{d=JSON.parse((await readBody(req,8192)).toString());}catch(e){return json(e.status||400,{error:'剪贴板内容无效'});}if(active!==session||!session.clipboardAllowed)return json(409,{error:'授权已结束'});if(typeof d?.text!=='string'||!d.text||d.text.length>1000)return json(400,{error:'文字需为 1–1000 字'});session.clipboard={text:d.text,sequence:(session.clipboard?.sequence||0)+1};return json(200,{ok:true});
      }
      if (route.startsWith('/api/')) {
        if (!local) return json(403, { error: '只能从本机接收端访问' });
        if (req.method === 'GET' && route === '/api/state') return json(200, state());
        if(req.method==='POST'&&route==='/api/window'){
          if(!windowControl.supported)return json(409,{error:'请在 Windows 客户端使用托盘功能'});
          let data;try{data=JSON.parse((await readBody(req,1024)).toString());}catch(e){return json(e.status||400,{error:'窗口操作无效'});}
          if(!data||!['minimize','resetPrompt','pinOn','pinOff','miniOn','miniOff'].includes(data.action))return json(400,{error:'窗口操作无效'});
          await windowControl.request(data.action);return json(200,{ok:true,requested:true});
        }
        if(route==='/api/profile'&&req.method==='POST'){
          if(!session?.realtime||session.videoMode!=='h264')return json(409,{error:'请更新两端并开始 H.264 投屏'});const active=session;let profile;try{profile=validateProfile(JSON.parse((await readBody(req,2048)).toString()));}catch(e){return json(e.status||400,{error:e.message});}if(active!==session)return json(409,{error:'连接已变化'});session.profile=profile;session.profileRevision++;videoHub.notice({type:'profile',profile,revision:session.profileRevision});return json(200,{ok:true,revision:session.profileRevision});
        }
        if(route==='/api/clipboard'){
          if(!session?.clipboardAllowed)return json(409,{error:'请在手机开启剪贴板互传'});
          if(req.method==='GET')return json(200,session.clipboard||{text:'',sequence:0});
          if(req.method==='POST'){const active=session;let d;try{d=JSON.parse((await readBody(req,8192)).toString());}catch(e){return json(e.status||400,{error:'剪贴板内容无效'});}if(active!==session||!session.clipboardAllowed)return json(409,{error:'授权已结束'});if(typeof d?.text!=='string'||!d.text||d.text.length>1000)return json(400,{error:'文字需为 1–1000 字'});if(!videoHub.notice({type:'clipboard',text:d.text}))return json(409,{error:'手机正在恢复连接，请稍后重试'});return json(200,{ok:true});}
        }
        if(req.method==='GET'&&route==='/api/settings'){
          startupEnabled=await startup.get();return json(200,{pairMode:preferences.pairMode,fixedCode:preferences.fixedCode,startupEnabled,startupSupported:startup.supported,windowSupported:windowControl.supported,connected:!!session,warning:preferences.warning||''});
        }
        if(req.method==='POST'&&route==='/api/settings'){
          if(settingsBusy)return json(409,{error:'设置正在保存'});
          settingsBusy=true;
          try{
            let data;try{data=JSON.parse((await readBody(req,2048)).toString());}catch(e){return json(e.status||400,{error:'设置格式错误'});}
            if(!data||typeof data!=='object')return json(400,{error:'设置格式错误'});
            const changePair='pairMode' in data||'fixedCode' in data,changeStartup='startupEnabled' in data;
            if(!changePair&&!changeStartup)return json(400,{error:'没有可保存的设置'});
            if(changePair&&session)return json(409,{error:'请先断开设备再修改配对方式'});
            if(changePair)pairSettingsBusy=true;
            if(changeStartup&&typeof data.startupEnabled!=='boolean')return json(400,{error:'开机启动开关无效'});
            let next;try{next=validatePairSettings(data,preferences);}catch(e){return json(400,{error:e.message});}
            if(changeStartup&&!startup.supported)return json(409,{error:'请在 Windows 客户端设置开机启动'});
            if(changePair){delete next.warning;await saveSettings(configPath,next);preferences=next;rotateCode();}
            if(changeStartup)startupEnabled=await startup.set(data.startupEnabled);
            return json(200,{ok:true,...state()});
          }finally{settingsBusy=false;pairSettingsBusy=false;}
        }
        if(req.method==='GET'&&route==='/api/audio') {
          const after=Number(url.searchParams.get('after')||0);
          if(!Number.isSafeInteger(after)||after<0)return json(400,{error:'无效声音序号'});
          const chunks=(session?.audio||[]).filter(c=>c.sequence>after&&Date.now()-c.at<1000).slice(-8);
          return json(200,{sampleRate:48000,channels:1,chunks,startedAt:session?.startedAt||null,sequence:session?.audioSequence||0});
        }
        if(req.method==='POST'&&route==='/api/control') {
          if(!session)return json(409,{error:'手机未连接'});
          const active=session;
          let data;try{data=JSON.parse((await readBody(req,2048)).toString());}catch(e){return json(e.status||400,{error:'控制请求格式错误'});}
          if(session!==active)return json(409,{error:'连接已变化'});
          if(!data||typeof data.enabled!=='boolean')return json(400,{error:'需要明确开关状态'});
          if(data.keepAlive&&!session.controlEnabled)return json(409,{error:'控制已释放，请重新启用'});
          if(data.enabled&&!session.controlGranted)return json(409,{error:'请先在手机上授权电脑控制'});
          if(!data.keepAlive || session.controlEnabled!==data.enabled)session.commands=[];session.controlEnabled=data.enabled;session.lastControlActivity=Date.now();videoHub.notice({type:'control',enabled:session.controlEnabled});return json(200,state());
        }
        if(req.method==='POST'&&route==='/api/input') {
          if(!session?.controlEnabled||!session.controlGranted)return json(409,{error:'电脑控制未开启或手机已撤销授权'});
          const active=session;
          let command;try{command=validateInput(JSON.parse((await readBody(req,8192)).toString()));}catch(e){return json(e.status||400,{error:e.message});}
          if(session!==active||!session.controlEnabled||!session.controlGranted)return json(409,{error:'控制已结束'});
          session.commands=session.commands.filter(c=>Date.now()-c.at<2000);
          if(session.commands.length>=64)return json(429,{error:'手机正在处理输入，请稍后重试'});
          session.lastControlActivity=Date.now();session.commands.push({...command,sequence:++session.commandSequence,at:Date.now()});if(session.realtime)videoHub.notice({...command,type:'input',inputType:command.type,sequence:session.commandSequence,ageMs:0});
          return json(200,{sequence:session.commandSequence});
        }
        if (req.method === 'POST' && route === '/api/disconnect') { disconnect(); return json(200, { ok: true }); }
        if (req.method === 'POST' && route === '/api/regenerate') {
          if (session) return json(409, { error: '请先断开手机' });
          rotateCode(); return json(200, state());
        }
        if (req.method === 'GET' && route === '/api/snapshot') {
          if (!frame) return json(404, { error: '尚未收到画面' });
          const etag = '"' + sequence + '"';
          if (req.headers['if-none-match'] === etag) { res.writeHead(304); return res.end(); }
          res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Content-Length': frame.length, ETag: etag }); return res.end(frame);
        }
        return json(404, { error: '接口不存在' });
      }
      if (!isLoopback(req.socket.remoteAddress)) return json(403, { error: '请在电脑本机打开接收端' });
      const staticFiles = { '/': ['index.html', 'text/html'], '/app.js': ['app.js', 'text/javascript'], '/gallery.js':['gallery.js','text/javascript'], '/audio-worklet.js':['audio-worklet.js','text/javascript'], '/audio-buffer.js':['audio-buffer.js','text/javascript'], '/video-player.js':['video-player.js','text/javascript'], '/live-tools.js':['live-tools.js','text/javascript'], '/qrcodegen.js':['../vendor/qrcodegen.js','text/javascript'], '/geometry.js':['geometry.js','text/javascript'], '/device-art.svg':['device-art.svg','image/svg+xml'], '/icon.svg':['icon.svg','image/svg+xml'], '/icon.png':['icon.png','image/png'], '/style.css': ['style.css', 'text/css'] };
      if (req.method !== 'GET' || !staticFiles[route]) return json(404, { error: '页面不存在' });
      const [file, type] = staticFiles[route];
      const bytes = await fs.readFile(path.join(__dirname, 'ui', file));
      res.setHeader('Content-Security-Policy', `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self' ws://127.0.0.1:${actualPort} ws://localhost:${actualPort}; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`);
      res.writeHead(200, { 'Content-Type': type + '; charset=utf-8' }); res.end(bytes);
    } catch (err) {
      if (!res.headersSent && !res.destroyed) json(err.status || 500, { error: err.status ? err.message : '接收端处理失败' });
    }
  });
  videoHub=attachVideoHub(server,{
    authorize(req){const url=new URL(req.url,'http://localhost');if(!['/api/video','/api/audio-stream'].includes(url.pathname))return null;const protocols=(req.headers['sec-websocket-protocol']||'').split(',').map(s=>s.trim());if(!protocols.includes('yinlian-v1'))return null;const credential=req.headers.authorization?.replace(/^Bearer /,'')||protocols.find(p=>p.startsWith('auth.'))?.slice(5);if(session&&safeEqual(credential,session.token))return url.pathname==='/api/audio-stream'?'audio':'sender';if(url.pathname!='/api/video'||!isLoopback(req.socket.remoteAddress)||!safeEqual(credential,localToken))return null;const origin=req.headers.origin;if(origin&&origin!=='http://'+req.headers.host)return null;return 'viewer';},
    onConfig(c){if(!session)throw Error('Session ended');session.videoMode='h264';session.videoConfig=c;session.videoError='';session.videoLastFrameAt=null;session.captureWidth=c.width;session.captureHeight=c.height;frame=null;session.lastSeen=Date.now();},
    onPacket(bytes){if(!session)throw Error('Session ended');session.videoLastFrameAt=session.lastFrameAt=session.lastSeen=Date.now();session.frames++;session.bytes+=bytes;sequence++;},
    onFeedback(f){if(session)session.feedback=f;},
    onAudio(a){if(!session||!['capturing','silent'].includes(session.audioStatus))return false;session.audioBytes+=a.bytes;session.audioSequence++;session.lastSeen=Date.now();return true;},
    onFailure(reason){if(process.env.YINLIAN_DEBUG==='1')console.error('VIDEO_ERROR',reason||'Socket closed');if(session?.videoMode==='h264'){session.videoLastFrameAt=null;session.videoError='视频连接中断，正在恢复…';}}
  });
  server.requestTimeout = 10000; server.headersTimeout = 10000;
  const timer = setInterval(() => {
    const now = Date.now();
    if(session?.controlEnabled && now-(session.lastControlActivity||0)>8000){session.controlEnabled=false;session.commands=[];videoHub.notice({type:'control',enabled:false});}
    if (session && now - session.lastSeen > IDLE_MS) disconnect();
    if (!session && pairExpires && now > pairExpires) rotateCode();
    for (const [ip, attempt] of attempts) if (now > attempt.reset) attempts.delete(ip);
  }, 1000);
  timer.unref();
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  } catch (err) { clearInterval(timer); throw err; }
  actualPort = server.address().port;
  const discovery=startDiscovery(actualPort,os.hostname(),!['127.0.0.1','::1'].includes(host));
  return {
    server, state,
    url: `http://127.0.0.1:${actualPort}/#${localToken}`,
    localToken,
    close: async () => {
      clearInterval(timer);discovery.close();disconnect();videoHub.close();
      await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
    }
  };
}
module.exports = { createReceiver, MAX_FRAME, MAX_AUDIO, validateInput,validateProfile };
if (require.main === module) {
  createReceiver({port:Number(process.env.MIRRORLINK_PORT||52345),configPath:process.env.MIRRORLINK_CONFIG_PATH||path.join(process.env.APPDATA||os.homedir(),'Yinlian','settings.json'),windowControl:{supported:process.env.MIRRORLINK_WINDOW_CONTROL==='1',request:async action=>{console.log('YINLIAN_WINDOW '+action);}}}).then(receiver => {
    console.log('吟链已启动。请在此电脑浏览器打开：\n' + receiver.url);
    for (const ip of receiver.state().addresses) console.log('手机连接地址：' + ip + ':' + receiver.state().port);
    const stop = async () => { await receiver.close(); process.exit(0); };
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', text => { if (text.trim() === 'quit') stop(); });
  }).catch(err => { console.error('无法启动接收端：', err.message); process.exitCode = 1; });
}
