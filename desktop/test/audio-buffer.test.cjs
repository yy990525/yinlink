const {test}=require('node:test');const assert=require('node:assert/strict');
const {PcmBuffer}=require('../ui/audio-buffer.js');
const sine=(start,n=960)=>Float32Array.from({length:n},(_,i)=>Math.sin((start+i)*Math.PI*2*440/48000)*.3);
test('continuous PCM survives packet timing and clock jitter without gaps or overlap',()=>{
 const q=new PcmBuffer(),out=[],batch=new Float32Array(128);let next=0;
 for(let block=0;block<200;block++){
  const time=block*128/48000;
  while(next<30&&next*.02+((next%3)-1)*.004<=time){q.push(sine(next*960),.06+next*.02+(next%2?-.012:.012),next+1,next*20000);next++;}
  q.render(batch,time);out.push(...batch);
 }
 // The initial anchor is .072; all following blocks must stay sample-contiguous.
 const begin=Math.ceil(.072*48000);for(let i=begin+65;i<out.length;i++)assert.ok(Math.abs(out[i]-sine(i-begin,1)[0])<1e-6,'sample phase changed at '+i);
 assert.equal(q.stats.underruns,0);assert.equal(q.stats.resets,0);
});
test('duplicate packets cannot double the volume or append the same samples',()=>{const q=new PcmBuffer();q.push(sine(0),0,12,20000);q.push(sine(0),0,12,20000);assert.equal(q.size,960);assert.equal(q.stats.duplicates,1);});
test('sequence gaps reserve missing duration instead of overlapping adjacent audio',()=>{const q=new PcmBuffer();q.push(sine(0),0,1,0);q.push(sine(1920),0,3,40000);assert.equal(q.size,2880);assert.equal(q.stats.gaps,1);const out=new Float32Array(2880);q.render(out,0);assert.equal(out[1100],0);assert.ok(Math.abs(out[2000]-sine(2000,1)[0])<1e-6);});
test('underflow fades to silence and new audio fades in without a sharp edge',()=>{const q=new PcmBuffer();q.push(new Float32Array(128).fill(.8),0,1,0);const a=new Float32Array(128),b=new Float32Array(128);q.render(a,0);q.render(b,128/48000);assert.ok(Math.abs(b[0]-a[127])<.05);assert.ok(Math.abs(b[127])<.001);q.push(new Float32Array(128).fill(-.8),256/48000,2,20000);q.render(a,256/48000);assert.ok(Math.abs(a[0]-b[127])<.015);assert.equal(q.stats.underruns,1);});
test('backlog is bounded and reconnect resets sequence without replaying stale audio',()=>{const q=new PcmBuffer();for(let i=0;i<25;i++)q.push(sine(i*960),0,i+1,i*20000);assert.ok(q.size<=14400);assert.ok(q.stats.resets>0);q.push(sine(0),.6,1,600000);assert.equal(q.size,960);assert.equal(q.seq,1);});
test('sequence wraparound remains continuous',()=>{const q=new PcmBuffer();q.push(sine(0),0,0xffffffff,0);q.push(sine(960),0,0,20000);assert.equal(q.size,1920);assert.equal(q.stats.duplicates,0);});
