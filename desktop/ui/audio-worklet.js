import './audio-buffer.js';
class YinlianAudio extends AudioWorkletProcessor {
 constructor(){super();this.pcm=new PcmBuffer(sampleRate);this.ticks=0;this.port.onmessage=({data})=>{if(data.type==='reset')this.pcm.reset();else if(data.type==='pcm')this.pcm.push(data.samples,Math.max(currentTime,Math.min(currentTime+.18,data.at)),data.seq,data.pts);};}
 process(inputs,outputs){const out=outputs[0]?.[0];if(out)this.pcm.render(out,currentTime);if(++this.ticks%375===0)this.port.postMessage({...this.pcm.stats,queued:this.pcm.size/sampleRate});return true;}
}
registerProcessor('yinlian-audio',YinlianAudio);
