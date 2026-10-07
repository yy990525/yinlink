package com.mirrorlink.mobile;

import android.app.*;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    static final String STATE = "com.mirrorlink.mobile.STATE";
    static final String STOP = "com.mirrorlink.mobile.STOP";
    static final String PROFILE="com.mirrorlink.mobile.PROFILE", CLIPBOARD="com.mirrorlink.mobile.CLIPBOARD";
    static volatile boolean clipboardAllowed=false;static volatile String mode="balanced";static volatile int actualFps,actualWidth,actualHeight;
    private boolean realtime;private CaptureOptions requestedOptions;private AdaptiveQuality adaptive;private int contentWidth,contentHeight,profileRevision;private long lastSuccess=SystemClock.elapsedRealtime();
    static final String CONTROL = "com.mirrorlink.mobile.CONTROL", AUDIO = "com.mirrorlink.mobile.AUDIO";
    static volatile boolean starting=false;
    static volatile boolean active = false, controlAllowed = false, audioWanted = false, remoteEnabled = false;
    private VideoStreamer video;private boolean streamVideo;private PowerManager.WakeLock cpuWake;
    static volatile int targetFps=60;
    static volatile long controlEpoch = 0, startedAt = 0;
    static volatile String audioStatus="off", audioMessage="手机声音未开启";
    private static volatile CaptureService instance;
    private final ScheduledExecutorService commands = Executors.newSingleThreadScheduledExecutor();
    private AudioStreamer audio;
    private volatile long commandAfter=0;
    private volatile boolean wholeScreen=true;
    static void sendClipboard(String text){CaptureService service=instance;if(service==null||!active||!clipboardAllowed)return;if(text==null||text.isEmpty()||text.length()>1000){android.widget.Toast.makeText(service,"文字需为 1–1000 字",android.widget.Toast.LENGTH_SHORT).show();return;}try{service.network.execute(()->{String result;try{Api.json(service.base,"/api/clipboard",service.token,new JSONObject().put("text",text));result="已发送，可在电脑设置中取到剪贴板";}catch(Exception e){result=e.getMessage();}final String note=result;service.main.post(()->android.widget.Toast.makeText(service,note,android.widget.Toast.LENGTH_LONG).show());});}catch(RejectedExecutionException ignored){}}
    static void allowControl(boolean allowed) {
        controlEpoch++;controlAllowed=allowed&&ControlService.available();remoteEnabled=false;
        CaptureService service=instance;
        if(service!=null){service.sendCapabilitiesSoon();service.publish(message,active);}
    }
    static void setAudioState(String status,String detail){audioStatus=status;audioMessage=detail;}
    static void setAudioState(AudioStreamer owner,String status,String detail){CaptureService service=instance;if(service!=null&&service.audio==owner&&audioWanted)setAudioState(status,detail);}
    private void sendCapabilitiesSoon(){try{network.execute(this::sendCapabilities);}catch(RejectedExecutionException ignored){}}
    private void sendCapabilities(){if(closing.get()||!active)return;try{
        boolean granted=controlAllowed&&ControlService.available()&&wholeScreen;
        JSONObject result=Api.json(base,"/api/capabilities",token,new JSONObject().put("controlGranted",granted)
            .put("controlMessage",granted?ControlService.hint:wholeScreen?"手机已暂停控制或尚未授权":"控制需要共享整个屏幕")
            .put("videoMode",video!=null?"h264":"jpeg").put("effectiveFps",video!=null?video.effectiveFps:fps).put("audioStatus",audioStatus).put("audioMessage",audioMessage).put("captureWidth",width).put("captureHeight",height).put("targetFps",requestedOptions!=null?requestedOptions.fps:fps).put("jpegQuality",jpegQuality).put("longEdge",longEdge).put("mode",mode).put("clipboardAllowed",clipboardAllowed).put("profileRevision",profileRevision).put("bitrate",video!=null?video.bitrate:0).put("temperature",temperature()).put("thermalStatus",thermalStatus()));
        remoteEnabled=result.optBoolean("controlEnabled")&&granted;
    }catch(Exception e){failed(e);}}
    private void toggleAudio(boolean enabled){
        audioWanted=enabled;
        if(audio!=null){audio.stop();audio=null;}
        if(enabled&&projection!=null){audio=new AudioStreamer(base,token,realtime);audio.start(this,projection);}
        else setAudioState("off","手机声音已关闭");
        sendCapabilitiesSoon();publish(message,active);
    }
    static volatile String message = "● 等待连接";
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean sending = new AtomicBoolean(false), closing = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler capture;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private String base, token;
    private int maxFrameBytes=2*1024*1024;private int longEdge, fps, jpegQuality, failures, width, height;
    private CaptureOptions.Pacer pacer;
    private ScheduledFuture<?> heartbeat;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { finish("投屏已结束"); return START_NOT_STICKY; }
        if (CONTROL.equals(intent.getAction())) { if(active)allowControl(intent.getBooleanExtra("enabled",false));else stopSelf(); return START_NOT_STICKY; }
        if (AUDIO.equals(intent.getAction())) { if(active)toggleAudio(intent.getBooleanExtra("enabled",false));else stopSelf(); return START_NOT_STICKY; }

        if(PROFILE.equals(intent.getAction())){if(!active){stopSelf();return START_NOT_STICKY;}if(active&&capture!=null)capture.post(()->{try{applyProfile(new JSONObject().put("fps",intent.getIntExtra("fps",60)).put("longEdge",intent.getIntExtra("longEdge",1920)).put("quality",intent.getIntExtra("jpegQuality",90)).put("mode",intent.getStringExtra("mode")),0);}catch(Exception ignored){}});return START_NOT_STICKY;}
        if(CLIPBOARD.equals(intent.getAction())){if(!active){stopSelf();return START_NOT_STICKY;}clipboardAllowed=active&&intent.getBooleanExtra("enabled",false);sendCapabilitiesSoon();return START_NOT_STICKY;}
        if (active||starting) return START_NOT_STICKY;
        starting=true;
        instance=this;closing.set(false);wholeScreen=true;realtime=intent.getBooleanExtra("realtime",false);clipboardAllowed=false;mode=AdaptiveQuality.validMode(intent.getStringExtra("mode"));lastSuccess=SystemClock.elapsedRealtime();streamVideo=intent.getBooleanExtra("streamVideo",false);maxFrameBytes=Math.max(1024*1024,Math.min(8*1024*1024,intent.getIntExtra("maxFrameBytes",2*1024*1024)));
        controlEpoch++;controlAllowed=intent.getBooleanExtra("control",false)&&ControlService.available();
        audioWanted=intent.getBooleanExtra("audio",false);setAudioState("off","手机声音未开启");
        base = intent.getStringExtra("base"); token = intent.getStringExtra("token");
        CaptureOptions opts=CaptureOptions.safe(intent.getIntExtra("fps",60),intent.getIntExtra("longEdge",1920),intent.getIntExtra("jpegQuality",90));
        requestedOptions=opts;adaptive=new AdaptiveQuality(opts.fps,opts.longEdge,mode);longEdge=opts.longEdge;fps=targetFps=opts.fps;jpegQuality=opts.jpegQuality;pacer=new CaptureOptions.Pacer(fps);
        try {
            if (base == null || token == null) throw new IllegalArgumentException("缺少连接信息");
            showNotification();
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("projection", Intent.class) : intent.getParcelableExtra("projection");
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(intent.getIntExtra("result", Activity.RESULT_CANCELED), data);
            if (projection == null) throw new IllegalStateException("屏幕共享授权无效");
            captureThread = new HandlerThread("吟链-Capture"); captureThread.start(); capture = new Handler(captureThread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> finish("系统已结束屏幕共享")); }
                @Override public void onCapturedContentResize(int w, int h) {
                    int[] physical=screenSize();wholeScreen=Math.abs(w-physical[0])<=2&&Math.abs(h-physical[1])<=2;
                    if(!wholeScreen)allowControl(false);resize(w,h);
                }
            }, capture);
            int[] size = screenSize();contentWidth=size[0];contentHeight=size[1];setDimensions(size[0], size[1]);
            PowerManager pm=getSystemService(PowerManager.class);cpuWake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Yinlian:Streaming");cpuWake.setReferenceCounted(false);cpuWake.acquire();
            capture.post(()->{try{
                if(closing.get())return;
                android.view.Surface target;
                if(streamVideo){video=new VideoStreamer(capture,base,token,jpegQuality,e->main.post(()->finish("视频推流失败："+e.getMessage())),this::streamMessage);target=video.configure(width,height,fps);width=video.width;height=video.height;actualFps=video.effectiveFps;actualWidth=width;actualHeight=height;}
                else{reader=makeReader();target=reader.getSurface();}
                display=projection.createVirtualDisplay("吟链",width,height,getResources().getConfiguration().densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,target,null,capture);
                main.post(()->{if(!closing.get()){starting=false;active=true;startedAt=System.currentTimeMillis();publish("● 正在投屏 · 可返回桌面打开其他应用",true);}});
            }catch(Exception e){main.post(()->finish("启动失败："+e.getMessage()));}});
            if(audioWanted)toggleAudio(true);
            commands.scheduleWithFixedDelay(() -> {
                if(closing.get())return;
                try { long epoch=controlEpoch;long requested=SystemClock.elapsedRealtime();
                    JSONObject result=Api.get(base,"/api/commands?after="+commandAfter,token);
                    if(result.has("profile")&&capture!=null){JSONObject profile=result.optJSONObject("profile");int rev=result.optInt("profileRevision");if(profile!=null&&rev>profileRevision)capture.post(()->applyProfile(profile,rev));}
                    remoteEnabled=result.optBoolean("enabled")&&controlAllowed&&ControlService.available()&&wholeScreen;
                    org.json.JSONArray list=result.getJSONArray("commands");
                    for(int i=0;i<list.length();i++){
                        JSONObject command=list.getJSONObject(i);if(command.getLong("sequence")<=commandAfter)continue;commandAfter=command.getLong("sequence");
                        if(!remoteEnabled||epoch!=controlEpoch||command.optLong("ageMs")+SystemClock.elapsedRealtime()-requested>1500)continue;
                        ControlService.execute(command,epoch);
                    }
                    commandAfter=Math.max(commandAfter,result.optLong("nextSequence"));
                } catch(Exception e){if(!closing.get())failed(e);}
            },0,realtime?1000:40,TimeUnit.MILLISECONDS);
            heartbeat = network.scheduleWithFixedDelay(() -> {
                if (closing.get()) return;
                try { Api.json(base, "/api/heartbeat", token, new JSONObject()); sendCapabilities(); failures = 0;lastSuccess=SystemClock.elapsedRealtime(); }
                catch (Exception e) { failed(e); }
            }, 0, 1, TimeUnit.SECONDS);
        } catch (Exception e) { finish("启动失败：" + e.getMessage()); }
        return START_NOT_STICKY;
    }
    private double temperature(){Intent battery=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));return battery==null?0:battery.getIntExtra("temperature",0)/10.0;}
    private int thermalStatus(){return Build.VERSION.SDK_INT>=29?getSystemService(PowerManager.class).getCurrentThermalStatus():0;}
    private void streamMessage(JSONObject d){if(closing.get())return;switch(d.optString("type")){
        case "control":remoteEnabled=d.optBoolean("enabled")&&controlAllowed&&wholeScreen;break;
        case "input":try{long epoch=controlEpoch,at=SystemClock.elapsedRealtime();commands.execute(()->{try{long seq=d.optLong("sequence");if(seq<=commandAfter)return;commandAfter=seq;if(remoteEnabled&&controlAllowed&&wholeScreen&&epoch==controlEpoch&&SystemClock.elapsedRealtime()-at+d.optLong("ageMs")<1500){d.put("type",d.optString("inputType"));ControlService.execute(d,epoch);}}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}break;
        case "profile":applyProfile(d.optJSONObject("profile"),d.optInt("revision"));break;
        case "clipboard":if(clipboardAllowed){String text=d.optString("text");if(text.length()<=1000)getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("吟链",text));}break;
        case "feedback":if(adaptive==null||video==null)break;int decision=adaptive.decide(SystemClock.elapsedRealtime(),fps,longEdge,d.optDouble("delay"),d.optInt("drops"),d.optInt("queue"),thermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE);
            if(decision<0){int oldFps=fps,oldEdge=longEdge;if(mode.equals("quality")&&fps>30)fps=adaptive.lowerFps(fps);else if(longEdge>960)longEdge=adaptive.lowerEdge(longEdge);else fps=adaptive.lowerFps(fps);if(fps!=oldFps||longEdge!=oldEdge)reconfigure();}
            else if(decision>0){longEdge=Math.min(requestedOptions.longEdge,(int)(longEdge*1.2));fps=Math.min(requestedOptions.fps,Math.max(fps+1,(int)(fps*1.2)));reconfigure();}break;
    }}
    private void applyProfile(JSONObject d,int revision){if(d==null||closing.get()||video==null||revision!=0&&revision<=profileRevision)return;try{
        CaptureOptions opts=new CaptureOptions(d.getInt("fps"),d.getInt("longEdge"),d.getInt("quality"));String next=AdaptiveQuality.validMode(d.optString("mode"));requestedOptions=opts;mode=next;targetFps=opts.fps;fps=opts.fps;longEdge=opts.longEdge;jpegQuality=opts.jpegQuality;adaptive=new AdaptiveQuality(fps,longEdge,mode);video.setQuality(jpegQuality);reconfigure();if(revision>0)profileRevision=revision;sendCapabilitiesSoon();
    }catch(Exception e){main.post(()->publish("画质调整失败："+e.getMessage(),active));}}
    private void reconfigure(){if(display==null||video==null||closing.get())return;try{
        setDimensions(contentWidth,contentHeight);display.setSurface(null);android.view.Surface next=video.configure(width,height,fps);width=video.width;height=video.height;actualFps=video.effectiveFps;actualWidth=width;actualHeight=height;display.resize(width,height,getResources().getConfiguration().densityDpi);display.setSurface(next);sendCapabilitiesSoon();
    }catch(Exception e){main.post(()->finish("画质调整失败："+e.getMessage()));}}
    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("casting", "屏幕投屏", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, CaptureService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 2, new Intent(this,CaptureService.class).setAction(CONTROL).putExtra("enabled",false),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "casting").setSmallIcon(com.mirrorlink.mobile.R.drawable.ic_notification)
            .setContentTitle("吟链 正在共享屏幕").setContentText("手机画面正在发送到你的电脑").setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null, "暂停控制", pause).build()).addAction(new Notification.Action.Builder(null, "结束投屏", stop).build()).build();
        startForeground(1, notification);
    }
    private int[] screenSize() {
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) { Rect bounds = wm.getMaximumWindowMetrics().getBounds(); return new int[]{bounds.width(), bounds.height()}; }
        DisplayMetrics metrics = new DisplayMetrics(); wm.getDefaultDisplay().getRealMetrics(metrics); return new int[]{metrics.widthPixels, metrics.heightPixels};
    }
    private void setDimensions(int w, int h) {
        float scale = Math.min(1f, (float) longEdge / Math.max(w, h));
        width = Math.max(2, Math.round(w * scale) / 2 * 2); height = Math.max(2, Math.round(h * scale) / 2 * 2);
    }
    private ImageReader makeReader() {
        ImageReader next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        next.setOnImageAvailableListener(this::onImage, capture); return next;
    }
    private void resize(int w, int h) {
        if (closing.get() || display == null || w < 1 || h < 1) return;
        contentWidth=w;contentHeight=h;int oldW = width, oldH = height; setDimensions(w, h);
        if (width == oldW && height == oldH) return;
        if(video!=null){try{display.setSurface(null);android.view.Surface next=video.configure(width,height,fps);width=video.width;height=video.height;actualFps=video.effectiveFps;actualWidth=width;actualHeight=height;display.resize(width,height,getResources().getConfiguration().densityDpi);display.setSurface(next);sendCapabilitiesSoon();}catch(Exception e){main.post(()->finish("视频尺寸调整失败："+e.getMessage()));}return;}
        ImageReader previous = reader;
        reader = makeReader(); display.setSurface(null);
        display.resize(width, height, getResources().getConfiguration().densityDpi);
        display.setSurface(reader.getSurface()); previous.close();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (Build.VERSION.SDK_INT < 34 && capture != null) capture.post(() -> { int[] size = screenSize(); resize(size[0], size[1]); });
    }
    private void onImage(ImageReader source) {
        Image image = null; Bitmap padded = null, cropped = null;
        try {
            if(source!=reader||closing.get())return; image = source.acquireLatestImage();
            if (image == null || closing.get() || sending.get()) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (!pacer.take(now) || !sending.compareAndSet(false, true)) return;
            Image.Plane plane = image.getPlanes()[0];
            int w = image.getWidth(), h = image.getHeight();
            int strideWidth = plane.getRowStride() / plane.getPixelStride();
            padded = Bitmap.createBitmap(strideWidth, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(plane.getBuffer());
            cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
            byte[] bytes = out.toByteArray();
            int retryQuality=jpegQuality;while(bytes.length>maxFrameBytes&&retryQuality>40){retryQuality=Math.max(40,retryQuality-10);out.reset();cropped.compress(Bitmap.CompressFormat.JPEG,retryQuality,out);bytes=out.toByteArray();}
            if (bytes.length > maxFrameBytes) { sending.set(false); return; }
            final byte[] frameBytes=bytes;
            network.execute(() -> {
                try { if (!closing.get()) { Api.post(base, "/api/frame", token, frameBytes, "image/jpeg"); failures = 0; } }
                catch (Exception e) { failed(e); }
                finally { sending.set(false); }
            });
        } catch (Exception e) {
            sending.set(false);
            if (!closing.get()) main.post(() -> finish("画面采集失败：" + e.getMessage()));
        } finally {
            if (image != null) image.close();
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
        }
    }
    private void failed(Exception e) {
        if (closing.get()) return;
        if (e instanceof Api.Failure && (((Api.Failure) e).status == 401 || ((Api.Failure) e).status == 403) || (++failures >= 3&&SystemClock.elapsedRealtime()-lastSuccess>10000))
            main.post(() -> finish("连接已断开：" + e.getMessage() + "，请重新配对"));
    }
    private void publish(String value, boolean running) {
        message = value; active = running;
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()).putExtra("message", value).putExtra("active", running));
    }
    private void finish(String value) {
        if (!closing.compareAndSet(false, true)) return;
        publish(value, false); stopSelf();
    }
    @Override public void onDestroy() {
        closing.set(true);starting=false;if(cpuWake!=null){if(cpuWake.isHeld())cpuWake.release();cpuWake=null;} active = false;clipboardAllowed=false;controlAllowed=false;remoteEnabled=false;controlEpoch++;startedAt=0;instance=null;
        if(audio!=null){audio.stop();audio=null;}audioWanted=false;setAudioState("off","投屏已结束");commands.shutdownNow();
        if (heartbeat != null) heartbeat.cancel(false);
        if (capture != null) capture.post(() -> {
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if(video!=null){video.close();video=null;}
            if (projection != null) { projection.stop(); projection = null; }
            captureThread.quitSafely();
        });
        else if (projection != null) projection.stop();
        try { network.execute(() -> { try { if (base != null && token != null) Api.json(base, "/api/stop", token, new JSONObject()); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException ignored) { }
        network.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
