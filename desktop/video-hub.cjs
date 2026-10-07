'use strict';
const crypto=require('node:crypto');
const MAX_MESSAGE=8*1024*1024+24;
function frame(opcode,data){data=Buffer.isBuffer(data)?data:Buffer.from(data);let h;if(data.length<126){h=Buffer.from([0x80|opcode,data.length]);}else if(data.length<=65535){h=Buffer.alloc(4);h[0]=0x80|opcode;h[1]=126;h.writeUInt16BE(data.length,2);}else{h=Buffer.alloc(10);h[0]=0x80|opcode;h[1]=127;h.writeBigUInt64BE(BigInt(data.length),2);}return Buffer.concat([h,data]);}
function upgrade(req,socket,head,onMessage,onClose){
 const key=req.headers['sec-websocket-key'];if(typeof key!=='string'||!/^[A-Za-z0-9+/]{22}==$/.test(key)||req.headers['sec-websocket-version']!=='13'||req.headers.upgrade?.toLowerCase()!=='websocket'){socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');return null;}
 const accept=crypto.createHash('sha1').update(key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
 socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\nSec-WebSocket-Protocol: yinlian-v1\r\n\r\n');socket.setNoDelay(true);socket.setTimeout(15000,()=>socket.destroy());
 let buffered=Buffer.alloc(0),closed=false,fragmentOpcode=0,fragmentBytes=0,fragments=[];const peer={socket,send:(opcode,data)=>{if(!closed&&!socket.destroyed)socket.write(frame(opcode,data));},close:reason=>{peer.failureReason=reason||peer.failureReason;socket.destroy();}};
 function done(){if(closed)return;closed=true;onClose(peer);}socket.on('close',done);socket.on('error',()=>{});
 function parse(chunk){if(closed)return;buffered=buffered.length?Buffer.concat([buffered,chunk]):chunk;
  while(buffered.length>=2){const first=buffered[0],second=buffered[1],op=first&15;let length=second&127,offset=2;
   if((first&112)||!(second&128)||![0,1,2,8,9,10].includes(op)||op>=8&&!(first&128))return peer.close('Invalid WebSocket flags');
   if(length===126){if(buffered.length<4)return;length=buffered.readUInt16BE(2);offset=4;}else if(length===127){if(buffered.length<10)return;const big=buffered.readBigUInt64BE(2);if(big>BigInt(MAX_MESSAGE))return peer.close();length=Number(big);offset=10;}
   if(length>MAX_MESSAGE||(op>=8&&length>125))return peer.close();if(buffered.length<offset+4+length)return;
   const mask=buffered.subarray(offset,offset+4),data=Buffer.from(buffered.subarray(offset+4,offset+4+length));for(let i=0;i<data.length;i++)data[i]^=mask[i%4];buffered=buffered.subarray(offset+4+length);
   if(op===8){peer.send(8,data);socket.end();return;}if(op===9){peer.send(10,data);continue;}if(op===10)continue;
   if(op===0){if(!fragmentOpcode)return peer.close('Unexpected continuation');fragmentBytes+=data.length;if(fragmentBytes>MAX_MESSAGE)return peer.close('Fragmented message too large');fragments.push(data);if(!(first&128))continue;const message=Buffer.concat(fragments,fragmentBytes),type=fragmentOpcode;fragmentOpcode=fragmentBytes=0;fragments=[];try{onMessage(peer,type,message);}catch(e){return peer.close(e.message);}continue;}
   if(fragmentOpcode)return peer.close('Interleaved fragmented messages');if(!(first&128)){fragmentOpcode=op;fragmentBytes=data.length;fragments=[data];continue;}
   try{onMessage(peer,op,data);}catch(e){return peer.close(e.message);}
  }
 }
 socket.on('data',parse);if(head.length)queueMicrotask(()=>parse(head));return peer;
}
function validateConfig(data){if(!data||data.type!=='config'||!/^avc1\.[0-9a-fA-F]{6}$/.test(data.codec))throw Error('Invalid AVC config');for(const k of ['width','height'])if(!Number.isInteger(data[k])||data[k]<2||data[k]>4096)throw Error('Invalid size');if(!Number.isInteger(data.epoch)||data.epoch<1||data.epoch>0xffffffff)throw Error('Invalid epoch');if(!Number.isInteger(data.fps)||data.fps<1||data.fps>120)throw Error('Invalid fps');return {type:'config',codec:data.codec,width:data.width,height:data.height,epoch:data.epoch,fps:data.fps,bitrate:Math.max(0,Math.min(100000000,Number(data.bitrate)||0)),encoder:typeof data.encoder==='string'?data.encoder.slice(0,80):''};}
function validatePacket(bytes,config){if(!config||bytes.length<29||bytes.toString('ascii',0,4)!=='YLV1'||bytes[4]>1||bytes[5]||bytes[6]||bytes[7]||bytes.readUInt32BE(8)!==config.epoch||bytes.readBigUInt64BE(12)>BigInt(Number.MAX_SAFE_INTEGER))throw Error('Invalid video packet');const nal=bytes.subarray(24);if(!(nal[0]===0&&nal[1]===0&&(nal[2]===1||nal[2]===0&&nal[3]===1)))throw Error('Expected Annex B');return {key:!!bytes[4],sequence:bytes.readUInt32BE(20)};}
function validateAudio(bytes){
 if(bytes.length<26||bytes.length>19424||bytes.toString('ascii',0,4)!=='YLA1'||bytes[4]!==1||bytes[5]!==0||bytes.readUInt16BE(6)!==48000||bytes.readBigUInt64BE(8)>BigInt(Number.MAX_SAFE_INTEGER)||bytes.readUInt32BE(20)!==bytes.length-24||(bytes.length-24)%2)throw Error('Invalid PCM packet');
 return {pts:Number(bytes.readBigUInt64BE(8)),sequence:bytes.readUInt32BE(16),bytes:bytes.length-24};
}
function attachVideoHub(server,{authorize,onConfig,onPacket,onFailure,onAudio=()=>true,onFeedback=()=>{}}){
 let sender=null,audioSender=null,config=null,lastSequence=0;const viewers=new Set();let requestAt=0,clockSequence=0;const clocks=new Map();
 const requestKey=()=>{const now=Date.now();if(sender&&now-requestAt>300){requestAt=now;sender.send(1,JSON.stringify({type:'keyframe'}));}};
 const broadcastReset=()=>{for(const v of viewers){v.needsKey=true;v.send(1,JSON.stringify({type:'reset'}));}};
 const ping=setInterval(()=>{for(const [id,c] of clocks)if(Date.now()-c.at>5000)clocks.delete(id);for(const p of [...viewers,...(sender?[sender]:[]),...(audioSender?[audioSender]:[])])p.send(9,Buffer.alloc(0));},4000);ping.unref();
 server.on('upgrade',(req,socket,head)=>{let role;try{role=authorize(req);}catch{}if(!role){socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');return;}if(role==='sender'&&sender||role==='audio'&&audioSender||role==='viewer'&&viewers.size>=4){socket.end('HTTP/1.1 409 Conflict\r\nConnection: close\r\n\r\n');return;}
  const peer=upgrade(req,socket,head,(p,op,bytes)=>{
   if(role==='audio'){if(op!==2)throw Error('Expected audio');const a=validateAudio(bytes);if(a.sequence<=p.lastAudio)throw Error('Audio out of order');p.lastAudio=a.sequence;if(onAudio(a)!==false)for(const v of viewers)if(v.socket.writableLength<256*1024)v.send(2,bytes);return;}
   if(role==='sender'){
    if(op===1){if(bytes.length>4096)throw Error('Config too large');const d=JSON.parse(bytes);
     if(d.type==='clock'){const c=clocks.get(d.id);clocks.delete(d.id);if(c&&viewers.has(c.peer)&&Number.isSafeInteger(d.phoneUs)&&d.phoneUs>0)c.peer.send(1,JSON.stringify({type:'clock',id:c.clientId,phoneUs:d.phoneUs}));return;}
     config=validateConfig(d);lastSequence=0;onConfig(config);for(const v of viewers){v.needsKey=true;v.send(1,JSON.stringify(config));}}
    else{const packet=validatePacket(bytes,config);if(packet.sequence<=lastSequence)throw Error('Out of order frame');lastSequence=packet.sequence;onPacket(bytes.length,packet);for(const v of viewers){if(v.socket.writableLength>512*1024){v.needsKey=true;requestKey();continue;}if(v.needsKey&&!packet.key)continue;v.needsKey=false;v.send(2,bytes);}}
   }else{if(op!==1||bytes.length>512)throw Error('Viewer command invalid');const d=JSON.parse(bytes);
    if(d.type==='config'||d.type==='keyframe'){p.needsKey=true;if(d.type==='config'&&config)p.send(1,JSON.stringify(config));requestKey();}
    else if(d.type==='clock'&&sender&&Number.isSafeInteger(d.id)&&d.id>=0){if(clocks.size>=32||Date.now()-(p.clockAt||0)<900)return;p.clockAt=Date.now();const id=++clockSequence;clocks.set(id,{peer:p,clientId:d.id,at:Date.now()});sender.send(1,JSON.stringify({type:'clock',id}));}
    else if(d.type==='feedback'&&sender){if(Date.now()-(p.feedbackAt||0)<900)return;p.feedbackAt=Date.now();const bounded=(v,max)=>Math.max(0,Math.min(max,Number(v)||0));const f={type:'feedback',delay:bounded(d.delay,5000),rtt:bounded(d.rtt,5000),fps:bounded(d.fps,240),drops:bounded(d.drops,240),queue:bounded(d.queue,64)};onFeedback(f);sender.send(1,JSON.stringify(f));}
   }
  },p=>{if(role==='sender'&&sender===p){sender=null;config=null;broadcastReset();onFailure(p.failureReason);}if(audioSender===p)audioSender=null;viewers.delete(p);for(const [id,c] of clocks)if(c.peer===p)clocks.delete(id);});
  if(!peer)return;if(role==='sender'){sender=peer;config=null;requestAt=0;requestKey();}else if(role==='audio'){audioSender=peer;peer.lastAudio=0;}else{peer.needsKey=true;viewers.add(peer);if(config)peer.send(1,JSON.stringify(config));requestAt=0;requestKey();}
 });
 return {notice(data){if(sender){sender.send(1,JSON.stringify(data));return true;}return false;},reset(){const p=sender,a=audioSender;sender=audioSender=null;config=null;clocks.clear();if(p)p.close();if(a)a.close();broadcastReset();},close(){clearInterval(ping);this.reset();for(const p of viewers)p.close();viewers.clear();}};
}
module.exports={attachVideoHub,validateConfig,validatePacket,validateAudio,frame};
