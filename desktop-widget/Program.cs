// Weekly Routine — desktop widget for Windows.
// Shows the web app's "?view=widget" page (지금 bar + the whole week) in a small borderless
// window pinned to the desktop. Controlled from the tray icon.

namespace WeeklyRoutineWidget;

static class Program
{
    public const string AppUrl = "https://ksdfs1.github.io/Weekly-Routine/";
    // `--url <page>` points the widget elsewhere, e.g. a local server while testing index.html changes
    public static string WidgetUrl { get; private set; } = AppUrl + "?view=widget";

    [STAThread]
    static void Main(string[] args)
    {
        int i = Array.IndexOf(args, "--url");
        if (i >= 0 && i + 1 < args.Length) WidgetUrl = args[i + 1];

        using var mutex = new Mutex(true, "WeeklyRoutineWidget.SingleInstance", out bool isFirst);
        if (!isFirst) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new WidgetContext());
    }
}

/// Owns the tray icon and the widget window. The window is owned by the desktop (Progman),
/// so it is destroyed when Explorer restarts; the context then simply opens a new one.
sealed class WidgetContext : ApplicationContext
{
    readonly NotifyIcon tray;
    readonly ContextMenuStrip menu;
    readonly ToolStripMenuItem adjustItem;
    readonly ToolStripMenuItem autostartItem;
    readonly Settings settings = Settings.Load();
    WidgetForm? form;
    bool exiting;

    public WidgetContext()
    {
        // start with Windows unless the user has turned it off; also keeps the registered path
        // pointing at this exe if it was moved
        try
        {
            if (!settings.AutostartSetUp) { Autostart.Set(true); settings.AutostartSetUp = true; settings.Save(); }
            else if (Autostart.IsEnabled()) Autostart.Set(true);
        }
        catch { /* registry blocked: the tray menu shows it as off */ }

        adjustItem = new ToolStripMenuItem("위치·크기 조정", null, (_, _) => ToggleAdjust());
        autostartItem = new ToolStripMenuItem("Windows 시작 시 자동 실행", null, (_, _) => ToggleAutostart())
        {
            Checked = Autostart.IsEnabled()
        };
        menu = new ContextMenuStrip();
        menu.Items.Add(adjustItem);
        menu.Items.Add("새로고침", null, (_, _) => form?.Reload());
        menu.Items.Add("앱 열기", null, (_, _) => AppLauncher.Open());
        menu.Items.Add("편집 토큰 설정…", null, (_, _) => form?.EditToken());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(autostartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("종료", null, (_, _) => Exit());

        tray = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "Weekly Routine 위젯",
            ContextMenuStrip = menu,
            Visible = true
        };
        tray.DoubleClick += (_, _) => form?.Reload();

        Microsoft.Win32.SystemEvents.PowerModeChanged += (_, e) =>
        {
            if (e.Mode == Microsoft.Win32.PowerModes.Resume) form?.Reload();
        };
        Microsoft.Win32.SystemEvents.DisplaySettingsChanged += (_, _) => form?.KeepOnScreen();

        OpenForm();
    }

    void OpenForm()
    {
        form = new WidgetForm(settings);
        form.MenuRequested += () => menu.Show(Cursor.Position);   // right-click on the widget
        form.FormClosed += (_, _) =>
        {
            adjustItem.Checked = false;
            if (exiting) return;
            // closed by something other than us (usually an Explorer restart): come back shortly
            var t = new System.Windows.Forms.Timer { Interval = 3000 };
            t.Tick += (_, _) => { t.Dispose(); if (!exiting) OpenForm(); };
            t.Start();
        };
        form.Show();
    }

    void ToggleAdjust()
    {
        if (form == null) return;
        adjustItem.Checked = !adjustItem.Checked;
        form.SetAdjustMode(adjustItem.Checked);
    }

    void ToggleAutostart()
    {
        bool on = !autostartItem.Checked;
        try
        {
            Autostart.Set(on);
            autostartItem.Checked = on;
        }
        catch (Exception ex)
        {
            MessageBox.Show("자동 실행 설정을 바꾸지 못했어요.\n" + ex.Message, "Weekly Routine 위젯");
        }
    }

    void Exit()
    {
        exiting = true;
        form?.Close();
        tray.Visible = false;
        tray.Dispose();
        ExitThread();
    }

    static Icon LoadIcon()
    {
        using var s = typeof(Program).Assembly.GetManifestResourceStream("icon.png");
        if (s == null) return SystemIcons.Application;
        using var bmp = new Bitmap(s);
        using var small = new Bitmap(bmp, new Size(32, 32));
        return Icon.FromHandle(small.GetHicon());
    }
}

static class Browser
{
    public static void Open(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
        catch { /* no default browser: nothing sensible to do */ }
    }
}

/// Opens the web app the way it's normally run on this PC: the installed app (PWA) if there is one,
/// otherwise the default browser.
static class AppLauncher
{
    const string AppName = "Weekly Routine";   // "name" in ../manifest.webmanifest

    public static void Open()
    {
        var shortcut = FindInstalledApp();
        if (shortcut != null)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(shortcut) { UseShellExecute = true });
                return;
            }
            catch { /* broken shortcut: fall back to the browser */ }
        }
        Browser.Open(Program.AppUrl);
    }

    /// true for links to the web app itself (e.g. the widget page's "앱 열기", href="./")
    public static bool IsAppUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return false;
        var page = u.GetLeftPart(UriPartial.Path);
        return page.Equals(Program.AppUrl, StringComparison.OrdinalIgnoreCase) ||
               page.Equals(new Uri(Program.WidgetUrl).GetLeftPart(UriPartial.Path), StringComparison.OrdinalIgnoreCase);
    }

    /// Installing the site as an app in Chrome or Edge puts a "Weekly Routine" shortcut in the Start
    /// menu (e.g. "Chrome 앱\Weekly Routine.lnk") that runs chrome_proxy.exe/msedge_proxy.exe --app-id=…
    static string? FindInstalledApp()
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null) return null;
        dynamic shell = Activator.CreateInstance(shellType)!;
        var opts = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true };
        foreach (var root in new[] {
            Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
            Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu),
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory) })
        {
            if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) continue;
            foreach (var lnk in Directory.EnumerateFiles(root, AppName + ".lnk", opts))
            {
                try
                {
                    dynamic s = shell.CreateShortcut(lnk);
                    string target = s.TargetPath, args = s.Arguments;
                    // an installed web app, and its browser is still there
                    if (args.Contains("--app-id=") && File.Exists(target)) return lnk;
                }
                catch { /* unreadable shortcut: keep looking */ }
            }
        }
        return null;
    }
}

static class Autostart
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string ValueName = "WeeklyRoutineWidget";

    public static bool IsEnabled()
    {
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(ValueName) != null;
    }

    public static void Set(bool on)
    {
        using var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) key.SetValue(ValueName, "\"" + Environment.ProcessPath + "\"");
        else key.DeleteValue(ValueName, false);
    }
}
