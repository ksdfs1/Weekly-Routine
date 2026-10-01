using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace WeeklyRoutineWidget;

/// Borderless window showing the widget page. Normally it sits at the very bottom of the z-order,
/// owned by the desktop window so "show desktop" (Win+D) doesn't hide it. The page has a hover
/// move handle and resize grip that message us (see HOST in index.html); adjust mode also grows
/// an orange frame (drag the edges to resize) and a title strip (drag to move).
sealed class WidgetForm : Form
{
    const int AdjustPad = 8;
    static readonly Color Navy = Color.FromArgb(0x08, 0x0d, 0x1a);
    static readonly Color Accent = Color.FromArgb(0xf5, 0xb9, 0x5c);

    readonly Settings settings;
    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    readonly Label dragStrip;
    bool adjusting;

    /// the page was right-clicked: show the tray menu there
    public event Action? MenuRequested;

    public WidgetForm(Settings settings)
    {
        this.settings = settings;
        Text = "Weekly Routine 위젯";
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        BackColor = Navy;
        MinimumSize = new Size(360, 140);
        Bounds = InitialBounds();

        web.DefaultBackgroundColor = Navy;   // no white flash while the page loads
        dragStrip = new Label
        {
            Dock = DockStyle.Top,
            Height = 26,
            Visible = false,
            BackColor = Accent,
            ForeColor = Navy,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            Text = "여기를 끌어서 이동 · 테두리를 끌어서 크기 조절 · 끝나면 트레이 메뉴에서 '위치·크기 조정' 해제",
            Cursor = Cursors.SizeAll
        };
        dragStrip.MouseDown += (_, e) =>
        {
            if (e.Button != MouseButtons.Left) return;
            Native.ReleaseCapture();
            Native.SendMessage(Handle, Native.WM_NCLBUTTONDOWN, Native.HTCAPTION, 0);
        };
        Controls.Add(web);
        Controls.Add(dragStrip);
        ResizeEnd += (_, _) => SaveBounds();   // after any move/resize, however it was started
    }

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= Native.WS_EX_TOOLWINDOW;   // keep it out of Alt+Tab
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int round = Native.DWMWCP_ROUND;
        Native.DwmSetWindowAttribute(Handle, Native.DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int));
        // owned by the desktop: stays visible through Win+D
        var progman = Native.FindWindow("Progman", null);
        if (progman != IntPtr.Zero) Native.SetWindowLongPtr(Handle, Native.GWLP_HWNDPARENT, progman);
    }

    protected override async void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WeeklyRoutineWidget", "WebView2"));
            await web.EnsureCoreWebView2Async(env);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MessageBox.Show("Microsoft Edge WebView2 런타임이 필요해요.\nhttps://developer.microsoft.com/microsoft-edge/webview2/ 에서 설치해주세요.",
                "Weekly Routine 위젯");
            return;
        }
        var core = web.CoreWebView2;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsZoomControlEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        // anything that tries to leave the widget page opens in the normal browser instead
        core.NewWindowRequested += (_, a) => { a.Handled = true; Browser.Open(a.Uri); };
        core.NavigationStarting += (_, a) =>
        {
            var widgetOrigin = new Uri(Program.WidgetUrl).GetLeftPart(UriPartial.Path);
            if (!a.Uri.StartsWith(widgetOrigin, StringComparison.OrdinalIgnoreCase)) { a.Cancel = true; Browser.Open(a.Uri); }
        };
        core.WebMessageReceived += (_, a) =>
        {
            string msg;
            try { msg = a.TryGetWebMessageAsString(); } catch (ArgumentException) { return; }
            // the mouse button is still down: hand the drag over to Windows' own move/size loop
            if (msg == "move") BeginSystemDrag(Native.HTCAPTION);
            else if (msg == "resize") BeginSystemDrag(Native.HTBOTTOMRIGHT_I);
            else if (msg == "menu") MenuRequested?.Invoke();
        };
        core.Navigate(Program.WidgetUrl);
    }

    void BeginSystemDrag(int hit)
    {
        Native.ReleaseCapture();
        Native.SendMessage(Handle, Native.WM_NCLBUTTONDOWN, hit, 0);
    }

    public void Reload() => web.CoreWebView2?.Reload();

    /// the page keeps the edit token in its localStorage, like the web app; with it, cases picked
    /// in the widget are saved to the server
    public async void EditToken()
    {
        var core = web.CoreWebView2;
        if (core == null) return;
        string current = "";
        try
        {
            var json = await core.ExecuteScriptAsync("localStorage.getItem('" + TokenKey + "')");
            current = System.Text.Json.JsonSerializer.Deserialize<string?>(json) ?? "";
        }
        catch { /* page not loaded yet: start empty */ }
        using var dlg = new TokenDialog(current);
        if (dlg.ShowDialog() != DialogResult.OK) return;
        var token = dlg.Token;
        var script = token.Length == 0
            ? "localStorage.removeItem('" + TokenKey + "')"
            : "localStorage.setItem('" + TokenKey + "', " + System.Text.Json.JsonSerializer.Serialize(token) + ")";
        await core.ExecuteScriptAsync("try{" + script + "}catch(e){}; location.reload();");
    }

    const string TokenKey = "weekly-routine-write-token";   // TOKEN_KEY in index.html

    public void SetAdjustMode(bool on)
    {
        adjusting = on;
        dragStrip.Visible = on;
        Padding = on ? new Padding(AdjustPad) : Padding.Empty;
        BackColor = on ? Accent : Navy;
        if (on)
        {
            Native.SetWindowPos(Handle, Native.HWND_TOP, 0, 0, 0, 0, Native.SWP_NOMOVE | Native.SWP_NOSIZE);
            Activate();
        }
        else
        {
            SaveBounds();
            Native.SetWindowPos(Handle, Native.HWND_BOTTOM, 0, 0, 0, 0, Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE);
        }
    }

    /// after monitors change, pull the widget back if it ended up off-screen
    public void KeepOnScreen()
    {
        if (!IsVisibleOnSomeScreen(Bounds)) { Bounds = DefaultBounds(Size); SaveBounds(); }
    }

    void SaveBounds()
    {
        settings.X = Left; settings.Y = Top; settings.Width = Width; settings.Height = Height;
        settings.Save();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (adjusting) SaveBounds();
        base.OnFormClosing(e);
    }

    Rectangle InitialBounds()
    {
        var size = new Size(Math.Max(settings.Width, 360), Math.Max(settings.Height, 140));
        if (settings.X == int.MinValue) return DefaultBounds(size);
        var r = new Rectangle(new Point(settings.X, settings.Y), size);
        return IsVisibleOnSomeScreen(r) ? r : DefaultBounds(size);
    }

    static Rectangle DefaultBounds(Size size)
    {
        // bottom-right corner of the primary screen, above the taskbar
        var wa = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
        size = new Size(Math.Min(size.Width, wa.Width - 48), Math.Min(size.Height, wa.Height - 48));
        return new Rectangle(wa.Right - size.Width - 24, wa.Bottom - size.Height - 24, size.Width, size.Height);
    }

    static bool IsVisibleOnSomeScreen(Rectangle r) =>
        Screen.AllScreens.Any(s =>
        {
            var hit = Rectangle.Intersect(s.WorkingArea, r);
            return hit.Width >= 120 && hit.Height >= 60;
        });

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.WM_WINDOWPOSCHANGING && !adjusting)
        {
            // stay underneath every other window, even when clicked
            var pos = Marshal.PtrToStructure<Native.WINDOWPOS>(m.LParam);
            pos.hwndInsertAfter = Native.HWND_BOTTOM;
            pos.flags &= ~Native.SWP_NOZORDER;
            Marshal.StructureToPtr(pos, m.LParam, false);
        }
        base.WndProc(ref m);
        if (m.Msg == Native.WM_NCHITTEST && adjusting)
        {
            // the orange frame (the form's own padding) acts as resize handles
            var p = PointToClient(new Point(unchecked((short)(long)m.LParam), unchecked((short)((long)m.LParam >> 16))));
            bool l = p.X < AdjustPad, r = p.X >= ClientSize.Width - AdjustPad;
            bool t = p.Y < AdjustPad, b = p.Y >= ClientSize.Height - AdjustPad;
            if (t && l) m.Result = Native.HTTOPLEFT;
            else if (t && r) m.Result = Native.HTTOPRIGHT;
            else if (b && l) m.Result = Native.HTBOTTOMLEFT;
            else if (b && r) m.Result = Native.HTBOTTOMRIGHT;
            else if (l) m.Result = Native.HTLEFT;
            else if (r) m.Result = Native.HTRIGHT;
            else if (t) m.Result = Native.HTTOP;
            else if (b) m.Result = Native.HTBOTTOM;
        }
    }
}

static class Native
{
    public const int WM_NCHITTEST = 0x0084, WM_NCLBUTTONDOWN = 0x00A1, WM_WINDOWPOSCHANGING = 0x0046;
    public const int HTCAPTION = 2, HTBOTTOMRIGHT_I = 17;
    public static readonly IntPtr HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13, HTTOPRIGHT = 14,
        HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;
    public const int WS_EX_TOOLWINDOW = 0x00000080;
    public const int GWLP_HWNDPARENT = -8;
    public static readonly IntPtr HWND_TOP = 0, HWND_BOTTOM = 1;
    public const uint SWP_NOSIZE = 0x0001, SWP_NOMOVE = 0x0002, SWP_NOZORDER = 0x0004, SWP_NOACTIVATE = 0x0010;
    public const int DWMWA_WINDOW_CORNER_PREFERENCE = 33, DWMWCP_ROUND = 2;

    [StructLayout(LayoutKind.Sequential)]
    public struct WINDOWPOS
    {
        public IntPtr hwnd, hwndInsertAfter;
        public int x, y, cx, cy;
        public uint flags;
    }

    [DllImport("user32.dll")] public static extern bool ReleaseCapture();
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr hWnd, int msg, int wParam, int lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string? title);
    [DllImport("user32.dll")] public static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int index, IntPtr value);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("dwmapi.dll")] public static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
}

/// small "편집 토큰" prompt for the tray menu
sealed class TokenDialog : Form
{
    readonly TextBox box;
    public string Token => box.Text.Trim();

    public TokenDialog(string current)
    {
        Text = "편집 토큰 설정";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true;
        AutoScaleMode = AutoScaleMode.Font;
        Font = new Font("Segoe UI", 9.5f);
        ClientSize = new Size(420, 150);
        Padding = new Padding(14);

        var hint = new Label
        {
            Dock = DockStyle.Top, Height = 54,
            Text = "웹앱의 편집 토큰을 넣으면 위젯에서 바꾼 케이스가 서버에 저장돼 모든 기기에 반영돼요.\n비우고 확인을 누르면 토큰을 지우고, 케이스는 이 PC에만 적용돼요."
        };
        box = new TextBox { Dock = DockStyle.Top, Text = current, UseSystemPasswordChar = true };
        var ok = new Button { Text = "확인", DialogResult = DialogResult.OK, Width = 88 };
        var cancel = new Button { Text = "취소", DialogResult = DialogResult.Cancel, Width = 88 };
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, FlowDirection = FlowDirection.RightToLeft, Height = 36 };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(ok);
        Controls.Add(box);
        Controls.Add(hint);
        Controls.Add(buttons);
        AcceptButton = ok;
        CancelButton = cancel;
    }
}
