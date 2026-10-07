package com.mirrorlink.mobile;

import android.accessibilityservice.*;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.*;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.*;
import android.view.inputmethod.EditorInfo;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ControlService extends AccessibilityService {
    static volatile ControlService instance;
    static volatile String hint = "请开启无障碍服务，并允许本次控制";
    private final Handler main = new Handler(Looper.getMainLooper());
    static boolean available() { return instance != null; }
    @Override protected void onServiceConnected() { instance=this; hint="服务已启用，请允许本次控制"; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { CaptureService.allowControl(false); }
    @Override public void onDestroy() { instance=null; CaptureService.allowControl(false); super.onDestroy(); }
    static void execute(JSONObject command, long grantEpoch) throws InterruptedException {
        ControlService service=instance; if(service==null)return;
        CountDownLatch done=new CountDownLatch(1);
        service.main.post(() -> {
            if(!CaptureService.active || !CaptureService.controlAllowed || CaptureService.controlEpoch!=grantEpoch) { done.countDown(); return; }
            try { service.perform(command, done); } catch(Exception e) { hint="此操作不受当前应用支持"; done.countDown(); }
        });
        done.await(1200, TimeUnit.MILLISECONDS);
    }
    private void perform(JSONObject c, CountDownLatch done) throws Exception {
        String type=c.getString("type");
        if(type.equals("tap") || type.equals("swipe")) {
            int[] size=screenSize(); Path path=new Path();
            path.moveTo(coord(c.getDouble("x"),size[0]),coord(c.getDouble("y"),size[1]));
            if(type.equals("swipe"))path.lineTo(coord(c.getDouble("x2"),size[0]),coord(c.getDouble("y2"),size[1]));
            GestureDescription gesture=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,Math.max(40,Math.min(800,c.optLong("duration",100))))).build();
            boolean accepted=dispatchGesture(gesture,new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription g){done.countDown();}
                @Override public void onCancelled(GestureDescription g){done.countDown();}
            },main);
            if(!accepted){hint="系统未接受触控，请检查无障碍权限";done.countDown();}return;
        }
        if(type.equals("global")){String action=c.getString("action");performGlobalAction(action.equals("back")?GLOBAL_ACTION_BACK:action.equals("home")?GLOBAL_ACTION_HOME:GLOBAL_ACTION_RECENTS);}
        else if(type.equals("text")) insertText(c.getString("text"));
        else if(type.equals("key")) pressKey(c.getString("key"),c.optInt("modifiers"));
        done.countDown();
    }
    private int[] screenSize(){WindowManager wm=getSystemService(WindowManager.class);if(Build.VERSION.SDK_INT>=30){Rect r=wm.getMaximumWindowMetrics().getBounds();return new int[]{r.width(),r.height()};}android.util.DisplayMetrics d=new android.util.DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(d);return new int[]{d.widthPixels,d.heightPixels};}
    private float coord(double v,int size){return (float)Math.max(0,Math.min(size-1,v*size));}
    private InputMethod.AccessibilityInputConnection connection(){if(Build.VERSION.SDK_INT<33 || getInputMethod()==null)return null;return getInputMethod().getCurrentInputConnection();}
    private AccessibilityNodeInfo focused(){AccessibilityNodeInfo root=getRootInActiveWindow();return root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);}
    private void insertText(String text){
        AccessibilityNodeInfo target=focused();if(target!=null&&target.isPassword()){hint="密码框请在手机输入";return;}
        if(Build.VERSION.SDK_INT>=33){InputMethod.AccessibilityInputConnection ic=connection();if(ic!=null){ic.commitText(text,1,null);hint="键鼠已授权 · 正在控制";return;}}
        AccessibilityNodeInfo node=focused();if(node==null || !node.isEditable() || node.isPassword()){hint="先选中可编辑输入框；密码框请在手机输入";return;}
        String old=node.getText()==null?"":node.getText().toString();int a=node.getTextSelectionStart(),b=node.getTextSelectionEnd();if(a<0||b<0)a=b=old.length();a=Math.min(a,old.length());b=Math.min(b,old.length());int left=Math.min(a,b),right=Math.max(a,b);
        setText(node,old.substring(0,left)+text+old.substring(right),left+text.length());
    }
    private void setText(AccessibilityNodeInfo node,String text,int caret){Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);if(!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){hint="此应用不支持远程文字输入";return;}Bundle selection=new Bundle();selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,caret);selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,caret);node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection);}
    private int keyCode(String key){switch(key){case "Enter":return KeyEvent.KEYCODE_ENTER;case "Backspace":return KeyEvent.KEYCODE_DEL;case "Delete":return KeyEvent.KEYCODE_FORWARD_DEL;case "Tab":return KeyEvent.KEYCODE_TAB;case "ArrowLeft":return KeyEvent.KEYCODE_DPAD_LEFT;case "ArrowRight":return KeyEvent.KEYCODE_DPAD_RIGHT;case "ArrowUp":return KeyEvent.KEYCODE_DPAD_UP;case "ArrowDown":return KeyEvent.KEYCODE_DPAD_DOWN;case "Home":return KeyEvent.KEYCODE_MOVE_HOME;case "End":return KeyEvent.KEYCODE_MOVE_END;case "PageUp":return KeyEvent.KEYCODE_PAGE_UP;case "PageDown":return KeyEvent.KEYCODE_PAGE_DOWN;case "a":return KeyEvent.KEYCODE_A;case "c":return KeyEvent.KEYCODE_C;case "x":return KeyEvent.KEYCODE_X;case "v":return KeyEvent.KEYCODE_V;default:return KeyEvent.KEYCODE_UNKNOWN;}}
    private void pressKey(String key,int modifiers){
        if(Build.VERSION.SDK_INT>=33){InputMethod.AccessibilityInputConnection ic=connection();if(ic!=null){
            if(key.equals("Enter")&&getInputMethod().getCurrentInputEditorInfo()!=null){EditorInfo info=getInputMethod().getCurrentInputEditorInfo();int action=info.imeOptions&EditorInfo.IME_MASK_ACTION;if(action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED){ic.performEditorAction(action);return;}}
            int meta=((modifiers&1)!=0?KeyEvent.META_SHIFT_ON:0)|((modifiers&2)!=0?KeyEvent.META_CTRL_ON:0)|((modifiers&4)!=0?KeyEvent.META_ALT_ON:0),code=keyCode(key);long now=SystemClock.uptimeMillis();
            ic.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta,-1,0,KeyEvent.FLAG_SOFT_KEYBOARD));ic.sendKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0,meta,-1,0,KeyEvent.FLAG_SOFT_KEYBOARD));return;
        }}
        AccessibilityNodeInfo node=focused();if(node==null||!node.isEditable()||node.isPassword()){hint="此应用的键盘操作需要 Android 13 或可编辑输入框";return;}
        String old=node.getText()==null?"":node.getText().toString();int a=Math.max(0,Math.min(old.length(),node.getTextSelectionStart())),b=Math.max(0,Math.min(old.length(),node.getTextSelectionEnd()));int left=Math.min(a,b),right=Math.max(a,b);
        if(key.equals("Backspace")||key.equals("Delete")){if(left==right){if(key.equals("Backspace")&&left>0)left=old.offsetByCodePoints(left,-1);else if(key.equals("Delete")&&right<old.length())right=old.offsetByCodePoints(right,1);}setText(node,old.substring(0,left)+old.substring(right),left);return;}
        if(key.equals("Enter")){if(Build.VERSION.SDK_INT>=30)node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());else insertText("\n");return;}
        int start=left,end=right;if(key.equals("a")&&(modifiers&2)!=0){start=0;end=old.length();}else if(key.equals("ArrowLeft")){start=end=left>0?old.offsetByCodePoints(left,-1):0;}else if(key.equals("ArrowRight")){start=end=right<old.length()?old.offsetByCodePoints(right,1):old.length();}else if(key.equals("Home"))start=end=0;else if(key.equals("End"))start=end=old.length();else if(key.equals("c")&&(modifiers&2)!=0){node.performAction(AccessibilityNodeInfo.ACTION_COPY);return;}else if(key.equals("x")&&(modifiers&2)!=0){node.performAction(AccessibilityNodeInfo.ACTION_CUT);return;}else{hint="此键在当前系统或应用中不受支持";return;}
        Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args);
    }
}
