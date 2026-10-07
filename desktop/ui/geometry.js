(function(root){
  'use strict';
  function mapPoint(box,w,h,x,y,cover=false){
    if(!w||!h||!box.width||!box.height)return null;
    const scale=(cover?Math.max:Math.min)(box.width/w,box.height/h);
    const left=box.left+(box.width-w*scale)/2,top=box.top+(box.height-h*scale)/2;
    if(x<box.left||x>box.left+box.width||y<box.top||y>box.top+box.height)return null;
    const px=(x-left)/(w*scale),py=(y-top)/(h*scale);
    return px<0||px>1||py<0||py>1?null:{x:px,y:py};
  }
  function decodePCM(base64){
    const bytes=typeof atob==='function'?Uint8Array.from(atob(base64),c=>c.charCodeAt(0)):Uint8Array.from(Buffer.from(base64,'base64'));
    if(bytes.length%2)throw new Error('PCM length');
    const view=new DataView(bytes.buffer),out=new Float32Array(bytes.length/2);
    for(let i=0;i<out.length;i++)out[i]=view.getInt16(i*2,true)/32768;
    return out;
  }
  const api={mapPoint,decodePCM};if(typeof module==='object')module.exports=api;else root.MirrorMath=api;
})(typeof globalThis==='object'?globalThis:this);
