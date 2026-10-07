package com.mirrorlink.mobile;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline QR scanning; the camera is opened only on this user-invoked page. */
@SuppressWarnings("deprecation")
public class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView preview;private volatile Camera camera;private boolean resumed;private long lastFrame;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();private final AtomicBoolean decoding=new AtomicBoolean();
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(17,24,39));getWindow().setNavigationBarColor(Color.rgb(17,24,39));LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,20,20,24);root.setBackgroundColor(Color.rgb(17,24,39));
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-2,-2));TextView title=new TextView(this);title.setText("扫描电脑端连接二维码");title.setTextSize(20);title.setTextColor(Color.WHITE);title.setGravity(Gravity.CENTER);root.addView(title,new LinearLayout.LayoutParams(-1,80));preview=new SurfaceView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));preview.getHolder().addCallback(this);TextView note=new TextView(this);note.setText("仅用于扫码，相机画面不会上传");note.setTextColor(Color.LTGRAY);note.setGravity(Gravity.CENTER);root.addView(note,new LinearLayout.LayoutParams(-1,80));setContentView(root);}
    @Override protected void onResume(){super.onResume();resumed=true;if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},81);else open();}
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] result){super.onRequestPermissionsResult(request,p,result);if(request==81){if(result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED)open();else{Toast.makeText(this,"未开启相机，可手动输入电脑地址和配对码",Toast.LENGTH_LONG).show();finish();}}}
    private void open(){if(!resumed||camera!=null||!preview.getHolder().getSurface().isValid()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;try{
        int id=0;Camera.CameraInfo info=new Camera.CameraInfo();for(int n=0;n<Camera.getNumberOfCameras();n++){Camera.getCameraInfo(n,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=n;break;}}Camera.getCameraInfo(id,info);Camera local=Camera.open(id);camera=local;Camera.Parameters params=local.getParameters();Camera.Size best=params.getPreviewSize();for(Camera.Size size:params.getSupportedPreviewSizes())if(size.width>=640&&size.width<=1280&&(best.width>1280||Math.abs(size.width-640)<Math.abs(best.width-640)))best=size;params.setPreviewSize(best.width,best.height);params.setPreviewFormat(ImageFormat.NV21);if(params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);local.setParameters(params);
        int[] degrees={0,90,180,270};int rotation=degrees[getWindowManager().getDefaultDisplay().getRotation()];int orientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+rotation)%360)%360:(info.orientation-rotation+360)%360;local.setDisplayOrientation(orientation);local.setPreviewDisplay(preview.getHolder());final int w=best.width,h=best.height;local.addCallbackBuffer(new byte[w*h*3/2]);local.addCallbackBuffer(new byte[w*h*3/2]);local.setPreviewCallbackWithBuffer((bytes,source)->{
            if(System.currentTimeMillis()-lastFrame<180||!decoding.compareAndSet(false,true)){source.addCallbackBuffer(bytes);return;}lastFrame=System.currentTimeMillis();try{decoder.execute(()->{String result=null;try{PlanarYUVLuminanceSource luminance=new PlanarYUVLuminanceSource(bytes,w,h,0,0,w,h,false);Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(luminance)),hints).getText();PairLink.parse(result);}catch(Exception ignored){result=null;}final String found=result;runOnUiThread(()->{decoding.set(false);if(camera==source){if(found!=null){setResult(RESULT_OK,new Intent().putExtra("pairLink",found));finish();}else source.addCallbackBuffer(bytes);}});});}catch(RejectedExecutionException e){decoding.set(false);}});local.startPreview();
    }catch(Exception e){release();Toast.makeText(this,"相机不可用，请手动连接",Toast.LENGTH_LONG).show();finish();}}
    private void release(){Camera c=camera;camera=null;if(c!=null){try{c.setPreviewCallbackWithBuffer(null);c.stopPreview();}catch(Exception ignored){}c.release();}}
    @Override protected void onPause(){resumed=false;release();super.onPause();}
    @Override protected void onDestroy(){decoder.shutdownNow();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder h){open();}public void surfaceChanged(SurfaceHolder h,int format,int w,int height){}public void surfaceDestroyed(SurfaceHolder h){release();}
}
