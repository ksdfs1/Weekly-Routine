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
    readonly ToolStripMenuItem adjustItem;
    readonly ToolStripMenuItem autostartItem;
    readonly Settings settings = Settings.Load();
    WidgetForm? form;
    bool exiting;

    public WidgetContext()
    {
        adjustItem = new ToolStripMenuItem("위치·크기 조정", null, (_, _) => ToggleAdjust());
        autostartItem = new ToolStripMenuItem("Windows 시작 시 자동 실행", null, (_, _) => ToggleAutostart())
        {
            Checked = Autostart.IsEnabled()
        };
        var menu = new ContextMenuStrip();
        menu.Items.Add(adjustItem);
        menu.Items.Add("새로고침", null, (_, _) => form?.Reload());
        menu.Items.Add("브라우저에서 앱 열기", null, (_, _) => Browser.Open(Program.AppUrl));
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
