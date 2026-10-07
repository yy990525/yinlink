'use strict';
const { app, BrowserWindow, dialog, Menu } = require('electron');
const { createReceiver } = require('./server.cjs');
let receiver, window;
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
  app.whenReady().then(async () => {
    try {
      receiver = await createReceiver();
      Menu.setApplicationMenu(null);
      window = new BrowserWindow({
        width: 1280, height: 850, minWidth: 360, minHeight: 480,
        title: '吟链', icon: require('node:path').join(__dirname,'ui','icon.png'), backgroundColor: '#f4f7fc',
        webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true }
      });
      window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      window.webContents.on('will-navigate', (event, url) => {
        if (new URL(url).origin !== new URL(receiver.url).origin) event.preventDefault();
      });
      await window.loadURL(receiver.url);
    } catch (err) {
      dialog.showErrorBox('吟链 启动失败', err.code === 'EADDRINUSE' ? '端口 52345 已被占用。请关闭其他接收端后重试。' : err.message);
      app.quit();
    }
  });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => { if (receiver) { receiver.close(); receiver = null; } });
}
