using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using System.Management;

[assembly: System.Reflection.AssemblyTitle("吟链")]
[assembly: System.Reflection.AssemblyProduct("吟链")]
[assembly: System.Reflection.AssemblyVersion("0.8.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("0.8.0.0")]
namespace MirrorLink {
    internal static class Launcher {
        [STAThread]
        static void Main(string[] args) {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            bool created;
            using (Mutex mutex = new Mutex(true, "Local\\MirrorLink-Receiver", out created)) {
                if (!created) { MessageBox.Show("吟链 已在运行。请从系统托盘打开接收界面。", "吟链"); return; }
                try { Application.Run(new ReceiverContext(Array.IndexOf(args,"--autostart")>=0)); }
                catch (Exception error) { MessageBox.Show(error.Message, "吟链 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error); }
            }
        }
    }
    internal static class NativeWindow {
        delegate bool EnumCallback(IntPtr window,IntPtr data);
        [DllImport("user32.dll")]static extern bool EnumWindows(EnumCallback callback,IntPtr data);
        [DllImport("user32.dll",CharSet=CharSet.Unicode)]static extern int GetWindowText(IntPtr window,StringBuilder text,int count);
        [DllImport("user32.dll")]static extern uint GetWindowThreadProcessId(IntPtr window,out uint processId);
        [DllImport("user32.dll")]internal static extern bool IsWindow(IntPtr window);
        [DllImport("user32.dll")]internal static extern bool IsIconic(IntPtr window);
        [DllImport("user32.dll")]internal static extern bool ShowWindow(IntPtr window,int command);
        [DllImport("user32.dll")]internal static extern bool SetForegroundWindow(IntPtr window);
        [DllImport("user32.dll")]internal static extern bool PostMessage(IntPtr window,uint message,IntPtr wParam,IntPtr lParam);
        [StructLayout(LayoutKind.Sequential)]internal struct WindowRect{public int Left,Top,Right,Bottom;}
        [DllImport("user32.dll")]internal static extern bool GetWindowRect(IntPtr window,out WindowRect rect);
        [DllImport("user32.dll")]internal static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
        internal static IntPtr FindViewer(string profile){IntPtr found=IntPtr.Zero;EnumWindows(delegate(IntPtr window,IntPtr data){
            StringBuilder text=new StringBuilder(512);GetWindowText(window,text,512);string title=text.ToString();
            if(title!="吟链 · 投屏"&&title!="吟链 · 投屏 - Microsoft Edge")return true;
            uint id;GetWindowThreadProcessId(window,out id);try{using(Process process=Process.GetProcessById((int)id)){if(process.ProcessName!="msedge")return true;using(ManagementObject info=new ManagementObject("Win32_Process.Handle='"+id+"'")){string command=Convert.ToString(info["CommandLine"]);if(command.IndexOf("--app=",StringComparison.OrdinalIgnoreCase)<0||command.IndexOf("--user-data-dir=\""+profile+"\"",StringComparison.OrdinalIgnoreCase)<0)return true;}}}catch{return true;}
            found=window;return false;
        },IntPtr.Zero);return found;}
    }
    internal sealed class ReceiverContext : ApplicationContext {
        readonly Control dispatcher = new Control();
        readonly NotifyIcon tray = new NotifyIcon();
        readonly Process receiver = new Process();
        readonly System.Windows.Forms.Timer windowTimer = new System.Windows.Forms.Timer();
        IntPtr viewerWindow=IntPtr.Zero;bool viewerOpened,promptBusy,pinned,mini;NativeWindow.WindowRect normalRect;
        readonly string promptFile=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),"Yinlian","tray-prompt.txt");
        readonly System.Windows.Forms.Timer startup = new System.Windows.Forms.Timer();
        string url;
        bool closing;
        readonly bool quiet;

        public ReceiverContext(bool startQuiet) {
            quiet=startQuiet;
            IntPtr handle = dispatcher.Handle;
            string root = AppDomain.CurrentDomain.BaseDirectory;
            string node = Path.Combine(root, "runtime", "node.exe");
            string server = Path.Combine(root, "desktop", "server.cjs");
            if (!File.Exists(node) || !File.Exists(server)) throw new FileNotFoundException("请先完整解压 Windows 压缩包，再运行 吟链.exe。");
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("打开投屏界面", null, delegate { OpenViewer(); });
            menu.Items.Add("恢复托盘提示", null, delegate { ResetPrompt(); });
            menu.Items.Add("退出 吟链", null, delegate { Shutdown(); });
            string iconFile=Path.Combine(root,"branding","yinlian.ico");tray.Icon = File.Exists(iconFile)?new Icon(iconFile):SystemIcons.Application; tray.Text = "吟链 · 手机投屏"; tray.ContextMenuStrip = menu; tray.Visible = true;
            tray.DoubleClick += delegate { OpenViewer(); };
            receiver.StartInfo = new ProcessStartInfo(node, "\"" + server + "\"") {
                WorkingDirectory = root, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
            };
            receiver.StartInfo.EnvironmentVariables["MIRRORLINK_WINDOW_CONTROL"]=File.Exists(EdgePath())?"1":"0";
            receiver.StartInfo.EnvironmentVariables["MIRRORLINK_LAUNCHER"]=Process.GetCurrentProcess().MainModule.FileName;
            receiver.EnableRaisingEvents = true;
            receiver.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) {
                if(e.Data!=null&&Regex.IsMatch(e.Data,@"^YINLIAN_WINDOW (minimize|resetPrompt|pinOn|pinOff|miniOn|miniOff)$")){
                    dispatcher.BeginInvoke((MethodInvoker)delegate{if(closing)return;string action=e.Data.Substring(15);if(action=="resetPrompt")ResetPrompt();else if(action=="minimize")MinimizeToTray();else ChangeWindow(action);});return;
                }
                if (e.Data != null && Regex.IsMatch(e.Data, @"^http://127\.0\.0\.1:52345/#[a-f0-9]{64}$")) {
                    dispatcher.BeginInvoke((MethodInvoker)delegate {
                        if (closing) return;
                        url = e.Data; startup.Stop(); if(!quiet)OpenViewer();
                        if(!quiet)tray.ShowBalloonTip(3000, "吟链 已就绪", "等待手机连接。", ToolTipIcon.Info);
                    });
                }
            };
            receiver.ErrorDataReceived += delegate { };
            receiver.Exited += delegate {
                if (!closing && !dispatcher.IsDisposed) dispatcher.BeginInvoke((MethodInvoker)delegate {
                    if (closing) return;
                    MessageBox.Show("接收服务已退出。请确认端口 52345 没有被其他接收端占用，然后重新打开。", "吟链", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    Shutdown();
                });
            };
            startup.Interval = 12000;
            startup.Tick += delegate {
                startup.Stop();
                if (url == null && !closing) { MessageBox.Show("接收端启动超时，请退出后重试。", "吟链"); Shutdown(); }
            };
            receiver.Start(); receiver.BeginOutputReadLine(); receiver.BeginErrorReadLine(); startup.Start();
            windowTimer.Interval=300;windowTimer.Tick+=delegate{
                if(closing||promptBusy||!viewerOpened)return;
                if(!NativeWindow.IsWindow(viewerWindow))viewerWindow=NativeWindow.FindViewer(ViewerProfile());
                if(viewerWindow!=IntPtr.Zero&&NativeWindow.IsIconic(viewerWindow))MinimizeToTray();
            };windowTimer.Start();
        }
        static string ViewerProfile(){return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Yinlian","Viewer");}
        static string EdgePath(){return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),"Microsoft","Edge","Application","msedge.exe");}
        void OpenViewer() {
            if (closing || url == null) return;
            if(NativeWindow.IsWindow(viewerWindow)){NativeWindow.ShowWindow(viewerWindow,9);NativeWindow.SetForegroundWindow(viewerWindow);return;}
            string edge=EdgePath();
            try {
                if (File.Exists(edge)) {
                    string profile=ViewerProfile();
                    Process.Start(new ProcessStartInfo(edge,"--user-data-dir=\""+profile+"\" --app=\""+url+"\" --no-first-run --window-size=1280,820") { UseShellExecute = true });viewerOpened=true;
                }else Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            } catch (Exception error) { MessageBox.Show("无法打开浏览器界面：" + error.Message, "吟链"); }
        }
        void ChangeWindow(string action){
            if(!NativeWindow.IsWindow(viewerWindow))viewerWindow=NativeWindow.FindViewer(ViewerProfile());if(viewerWindow==IntPtr.Zero)return;
            if(action.StartsWith("pin")){pinned=action=="pinOn";NativeWindow.SetWindowPos(viewerWindow,new IntPtr(pinned?-1:-2),0,0,0,0,0x13);return;}
            if(action=="miniOn"&&!mini){NativeWindow.GetWindowRect(viewerWindow,out normalRect);Rectangle area=Screen.FromHandle(viewerWindow).WorkingArea;int height=Math.Min(740,area.Height-40),width=Math.Min(420,area.Width-40);NativeWindow.ShowWindow(viewerWindow,9);NativeWindow.SetWindowPos(viewerWindow,new IntPtr(pinned?-1:-2),area.Right-width-20,area.Bottom-height-20,width,height,0x10);mini=true;}
            else if(action=="miniOff"&&mini){NativeWindow.SetWindowPos(viewerWindow,new IntPtr(pinned?-1:-2),normalRect.Left,normalRect.Top,normalRect.Right-normalRect.Left,normalRect.Bottom-normalRect.Top,0x10);mini=false;}
        }
        void ResetPrompt(){try{if(File.Exists(promptFile))File.Delete(promptFile);}catch(Exception error){MessageBox.Show("无法保存托盘提示设置："+error.Message,"吟链");}}
        void MinimizeToTray(){
            if(promptBusy||closing)return;
            if(!NativeWindow.IsWindow(viewerWindow))viewerWindow=NativeWindow.FindViewer(ViewerProfile());
            if(viewerWindow==IntPtr.Zero){tray.ShowBalloonTip(2500,"吟链","请先打开投屏窗口。",ToolTipIcon.Info);return;}
            promptBusy=true;try{
                bool skip=File.Exists(promptFile)&&File.ReadAllText(promptFile)=="off";
                if(!skip){
                    if(NativeWindow.IsIconic(viewerWindow))NativeWindow.ShowWindow(viewerWindow,9);
                    using(Form dialog=new Form()){
                        dialog.Text="最小化到托盘";dialog.ClientSize=new Size(380,180);dialog.Font=new Font("Microsoft YaHei",9F);dialog.FormBorderStyle=FormBorderStyle.FixedDialog;dialog.MaximizeBox=false;dialog.MinimizeBox=false;dialog.StartPosition=FormStartPosition.CenterScreen;dialog.TopMost=true;
                        Label detail=new Label{Text="投屏会继续运行。双击托盘图标可恢复窗口。",AutoSize=false,Location=new Point(22,22),Size=new Size(340,46)};
                        CheckBox remember=new CheckBox{Text="不再提示",Location=new Point(22,78),AutoSize=true};
                        Button yes=new Button{Text="驻留托盘",DialogResult=DialogResult.OK,Location=new Point(178,126),Size=new Size(90,32)};
                        Button cancel=new Button{Text="取消",DialogResult=DialogResult.Cancel,Location=new Point(278,126),Size=new Size(80,32)};
                        dialog.Controls.AddRange(new Control[]{detail,remember,yes,cancel});dialog.AcceptButton=yes;dialog.CancelButton=cancel;
                        if(dialog.ShowDialog()!=DialogResult.OK)return;
                        if(remember.Checked){Directory.CreateDirectory(Path.GetDirectoryName(promptFile));File.WriteAllText(promptFile,"off");}
                    }
                }
                NativeWindow.ShowWindow(viewerWindow,0);tray.ShowBalloonTip(2500,"吟链 已驻留托盘","双击图标恢复，右键可退出。",ToolTipIcon.Info);
            }catch(Exception error){MessageBox.Show("托盘操作失败："+error.Message,"吟链");}finally{promptBusy=false;}
        }
        void Shutdown() {
            if (closing) return;
            closing = true; startup.Stop();windowTimer.Stop(); tray.Visible = false;
            if(NativeWindow.IsWindow(viewerWindow))NativeWindow.PostMessage(viewerWindow,0x0010,IntPtr.Zero,IntPtr.Zero);
            try {
                if (!receiver.HasExited) {
                    receiver.StandardInput.WriteLine("quit"); receiver.StandardInput.Flush();
                    if (!receiver.WaitForExit(8000)) receiver.Kill();
                }
            } catch (InvalidOperationException) { }
            tray.Dispose();windowTimer.Dispose(); startup.Dispose(); receiver.Dispose(); dispatcher.Dispose(); ExitThread();
        }
        protected override void ExitThreadCore() { if (!closing) { Shutdown(); return; } base.ExitThreadCore(); }
    }
}
