'use strict';
// The same bounded PCM queue runs in the audio rendering thread and in DSP tests.
(function(root){
class PcmBuffer {
 constructor(rate=48000){this.rate=rate;this.data=new Float32Array(Math.ceil(rate*.3));this.stats={packets:0,duplicates:0,gaps:0,underruns:0,resets:0};this.reset();}
 reset(){this.head=0;this.size=0;this.startAt=null;this.seq=null;this.pts=null;this.tail=this.tail||0;this.fade=0;this.fadeOrigin=0;this.waiting=true;}
 push(samples,at,seq=null,pts=null){
  if(!samples.length||samples.length>this.rate*.2)return;
  let missing=0;
  if(seq!==null&&this.seq!==null){const delta=(seq-this.seq)|0;
   if(delta<=0){if(seq===1&&pts!==null&&pts>this.pts){this.reset();this.stats.resets++;}else{this.stats.duplicates++;return;}}
   else if(delta>1)missing=delta-1;
  }
  // Stale bursts never build up latency. A real discontinuity re-primes the queue.
  if(missing>5||this.size+samples.length*(missing+1)>this.data.length||
     (pts!==null&&this.pts!==null&&pts-this.pts>250000)){this.reset();this.stats.resets++;missing=0;}
  this.seq=seq;this.pts=pts;this.stats.packets++;
  if(this.startAt===null){this.startAt=Number.isFinite(at)?at:0;this.fade=0;}
  const put=value=>{this.data[(this.head+this.size)%this.data.length]=value;this.size++;};
  if(missing){this.stats.gaps+=missing;const gap=missing*samples.length;const previous=this.size?this.data[(this.head+this.size-1)%this.data.length]:this.tail;
   for(let i=0;i<gap;i++)put(i<64?previous*(1-(i+1)/64):0);
   for(let i=0;i<samples.length;i++)put(samples[i]*Math.min(1,(i+1)/64));
  }else for(const sample of samples)put(Number.isFinite(sample)?Math.max(-1,Math.min(1,sample)):0);
 }
 render(out,time){
  let consumed=0;
  for(let i=0;i<out.length;i++){
   if(this.startAt===null||time+i/this.rate<this.startAt){out[i]=this.tail*=.94;continue;}
   if(this.size){
    if(this.waiting){this.fade=0;this.fadeOrigin=this.tail;this.waiting=false;}
    this.fade=Math.min(1,this.fade+1/64);this.tail=this.data[this.head]*this.fade+this.fadeOrigin*(1-this.fade);
    this.head=(this.head+1)%this.data.length;this.size--;consumed++;out[i]=this.tail;
   }else{
    if(!this.waiting){this.stats.underruns++;this.waiting=true;}
    out[i]=this.tail*=.94;
    // Wait for the next bounded batch instead of emitting sharp zero/sample edges.
    this.startAt=null;
   }
  }
  return consumed;
 }
}
root.PcmBuffer=PcmBuffer;if(typeof module!=='undefined')module.exports={PcmBuffer};
})(globalThis);
