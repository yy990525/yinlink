 'use strict';
class LiveClock{
 constructor(){this.reset();}
 reset(){this.valid=false;this.offset=0;this.rtt=Infinity;this.buffer=60;}
 update(sent,received,phoneUs){const rtt=received-sent;if(rtt<0||rtt>400||!Number.isSafeInteger(phoneUs))return false;const offset=(sent+received)/2-phoneUs/1000;if(!this.valid||rtt<this.rtt+10){this.offset=this.valid&&Math.abs(offset-this.offset)<80?this.offset*.8+offset*.2:offset;this.valid=true;this.rtt=rtt;return true;}return false;}
 time(pts){return pts/1000+this.offset+this.buffer;}
}
class LiveVideoPlayer{
 constructor({token,canvas,backdrop,onFrame,onError,onAudio,onStats}){Object.assign(this,{token,canvas,backdrop,onFrame,onError,onAudio,onStats});this.ctx=canvas.getContext('2d',{alpha:false,desynchronized:true});this.bg=backdrop.getContext('2d',{alpha:false,desynchronized:true});this.clock=new LiveClock();this.mode='jpeg';this.generation=0;this.frames=0;this.dropped=0;this.networkDrops=0;this.lastBg=0;this.lastKeyRequest=0;this.clockRequests=new Map();this.queue=[];this.delay=0;this.connect();this.statsAt=performance.now();this.statsFrames=0;this.statsDrops=0;this.timer=setInterval(()=>this.tick(),1000);}
 connect(){if(this.closed)return;this.ws=new WebSocket('ws://'+location.host+'/api/video',['yinlian-v1','auth.'+this.token]);this.ws.binaryType='arraybuffer';this.ws.onopen=()=>this.askClock();this.ws.onmessage=e=>{if(typeof e.data==='string'){try{const d=JSON.parse(e.data);if(d.type==='config')this.configure(d);else if(d.type==='reset')this.reset();else if(d.type==='clock'){const sent=this.clockRequests.get(d.id);this.clockRequests.delete(d.id);if(sent!==undefined)this.clock.update(sent,performance.now(),d.phoneUs);}}catch{this.fail('视频配置无效');}}else{const b=new DataView(e.data);if(b.byteLength>=24&&b.getUint32(0)===0x594c4131){if(this.clock.valid)this.onAudio?.(e.data);return;}this.decode(e.data);}};this.ws.onclose=()=>{this.reset();if(!this.closed)this.retry=setTimeout(()=>this.connect(),600);};this.ws.onerror=()=>{};}
 askClock(){if(this.ws?.readyState!==WebSocket.OPEN)return;const id=Math.round(performance.now()*1000);this.clockRequests.set(id,performance.now());for(const [k,v] of this.clockRequests)if(performance.now()-v>5000)this.clockRequests.delete(k);this.ws.send(JSON.stringify({type:'clock',id}));}
 tick(){this.askClock();const now=performance.now(),fps=(this.frames-this.statsFrames)*1000/(now-this.statsAt),drops=this.networkDrops-this.statsDrops;this.statsAt=now;this.statsFrames=this.frames;this.statsDrops=this.networkDrops;const stats={type:'feedback',fps,drops,delay:this.delay,rtt:this.clock.valid?this.clock.rtt:0,queue:this.decoder?.decodeQueueSize||0};this.onStats?.({...stats,totalDrops:this.dropped,clockValid:this.clock.valid});if(this.mode==='h264'&&this.ws?.readyState===WebSocket.OPEN)this.ws.send(JSON.stringify(stats));}
 setSync(mode){this.clock.buffer=mode==='flow'?40:mode==='quality'?90:60;}
 resync(){this.reset();this.clock.reset();this.clockRequests.clear();this.delay=0;if(this.ws?.readyState===WebSocket.OPEN)this.ws.send(JSON.stringify({type:'config'}));}
 requestKey(){const now=performance.now();if(now-this.lastKeyRequest<350)return;this.lastKeyRequest=now;if(this.ws?.readyState===WebSocket.OPEN)this.ws.send(JSON.stringify({type:'keyframe'}));}
 async configure(c){this.reset();this.mode='h264';this.configuring=true;const generation=this.generation;this.epoch=c.epoch;this.pendingPackets=[];this.askClock();if(!globalThis.VideoDecoder)return this.fail('请使用新版 Edge 或 Chrome 播放 H.264');
  try{let config={codec:c.codec,codedWidth:c.width,codedHeight:c.height,optimizeForLatency:true,hardwareAcceleration:'prefer-hardware'};let support=await VideoDecoder.isConfigSupported(config);if(!support.supported){config.hardwareAcceleration='no-preference';support=await VideoDecoder.isConfigSupported(config);}if(generation!==this.generation)return;if(!support.supported)throw Error('电脑不支持此 H.264 配置');
   this.decoder=new VideoDecoder({output:frame=>this.output(frame,generation),error:()=>{if(generation===this.generation)this.fail('视频解码失败，请重新开始投屏');}});this.decoder.configure(config);this.configuring=false;const pending=this.pendingPackets;this.pendingPackets=[];for(const p of pending)this.decode(p);this.requestKey();
  }catch(e){if(generation===this.generation)this.fail(e.message);}
 }
 decode(buffer){if(this.mode!=='h264')return;if(this.configuring){if(this.pendingPackets.length<3)this.pendingPackets.push(buffer);else{this.pendingPackets=[];this.requestKey();}return;}if(!this.decoder||this.decoder.state!=='configured')return;
  const bytes=new Uint8Array(buffer),view=new DataView(buffer);if(bytes.length<29||view.getUint32(0)!==0x594c5631||view.getUint32(8)!==this.epoch)return;const key=!!bytes[4];
  if(this.decoder.decodeQueueSize>=3){this.waitKey=true;this.dropped++;this.networkDrops++;this.requestKey();return;}if(this.waitKey&&!key){this.dropped++;this.networkDrops++;return;}if(key)this.waitKey=false;
  try{this.decoder.decode(new EncodedVideoChunk({type:key?'key':'delta',timestamp:Number(view.getBigUint64(12)),data:bytes.subarray(24)}));}catch{this.waitKey=true;this.requestKey();}
 }
 output(frame,generation){if(generation!==this.generation||this.closed){frame.close();return;}this.queue.push(frame);if(this.queue.length>18){this.queue.shift().close();this.dropped++;}if(!this.raf)this.raf=requestAnimationFrame(now=>this.draw(now));}
 draw(now){this.raf=0;let next=null;while(this.queue.length&&(!this.clock.valid||this.clock.time(this.queue[0].timestamp)<=now)){if(next){next.close();this.dropped++;}next=this.queue.shift();}
  if(next){try{const w=next.displayWidth,h=next.displayHeight;if(this.canvas.width!==w||this.canvas.height!==h){this.canvas.width=w;this.canvas.height=h;}this.ctx.drawImage(next,0,0,w,h);if(this.clock.valid)this.delay=Math.max(0,Math.min(5000,now-(next.timestamp/1000+this.clock.offset)));if(now-this.lastBg>=100){this.lastBg=now;const scale=Math.min(1,192/Math.max(w,h)),bw=Math.round(w*scale),bh=Math.round(h*scale);if(this.backdrop.width!==bw||this.backdrop.height!==bh){this.backdrop.width=bw;this.backdrop.height=bh;}this.bg.drawImage(next,0,0,bw,bh);}this.canvas.hidden=this.backdrop.hidden=false;this.frames++;this.onFrame(w,h);}finally{next.close();}}
  if(this.queue.length)this.raf=requestAnimationFrame(t=>this.draw(t));
 }
 fail(message){this.reset();this.mode='h264';this.onError(message);}
 setMode(mode){if(mode==='jpeg'&&this.mode==='h264'){this.reset();this.mode='jpeg';}}
 reset(){this.generation++;this.configuring=false;this.waitKey=true;this.pendingPackets=[];if(this.raf)cancelAnimationFrame(this.raf);this.raf=0;for(const f of this.queue)f.close();this.queue=[];if(this.decoder){try{this.decoder.close();}catch{}this.decoder=null;}}
 close(){this.closed=true;clearTimeout(this.retry);clearInterval(this.timer);this.reset();this.ws?.close();}
}
